package com.myano.jsqlite.sql;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Ast.And;
import com.myano.jsqlite.sql.Ast.Assign;
import com.myano.jsqlite.sql.Ast.Bool;
import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Compare;
import com.myano.jsqlite.sql.Ast.CompareOp;
import com.myano.jsqlite.sql.Ast.Concat;
import com.myano.jsqlite.sql.Ast.CountStar;
import com.myano.jsqlite.sql.Ast.Delete;
import com.myano.jsqlite.sql.Ast.Expr;
import com.myano.jsqlite.sql.Ast.ExpressionItem;
import com.myano.jsqlite.sql.Ast.IfNullExpr;
import com.myano.jsqlite.sql.Ast.Insert;
import com.myano.jsqlite.sql.Ast.IsNull;
import com.myano.jsqlite.sql.Ast.Join;
import com.myano.jsqlite.sql.Ast.Like;
import com.myano.jsqlite.sql.Ast.Literal;
import com.myano.jsqlite.sql.Ast.LiteralExpr;
import com.myano.jsqlite.sql.Ast.MaxItem;
import com.myano.jsqlite.sql.Ast.Now;
import com.myano.jsqlite.sql.Ast.NullLiteral;
import com.myano.jsqlite.sql.Ast.NumberLiteral;
import com.myano.jsqlite.sql.Ast.Or;
import com.myano.jsqlite.sql.Ast.Parameter;
import com.myano.jsqlite.sql.Ast.Pred;
import com.myano.jsqlite.sql.Ast.Predicate;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.SelectItem;
import com.myano.jsqlite.sql.Ast.Star;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Ast.StringLiteral;
import com.myano.jsqlite.sql.Ast.Update;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.vm.InsertMode;
import com.myano.jsqlite.vm.Instruction;
import com.myano.jsqlite.vm.Opcode;
import com.myano.jsqlite.vm.OrderKey;
import com.myano.jsqlite.vm.Program;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Compiler {
  private static final int CURSOR = 0;

  public Program compile(Statement statement, MemoryCatalog catalog, String sql) {
    return switch (statement) {
      case Ast.CreateTable ignored ->
          throw new IllegalStateException("CREATE TABLE does not compile to bytecode");
      case Ast.CreateIndex ignored ->
          throw new IllegalStateException("CREATE INDEX does not compile to bytecode");
      case Ast.AlterTable ignored ->
          throw new IllegalStateException("ALTER TABLE does not compile to bytecode");
      case Insert insert -> compileInsert(insert, catalog, sql);
      case Select select -> compileSelect(select, catalog, sql);
      case Update update -> compileUpdate(update, catalog, sql);
      case Delete delete -> compileDelete(delete, catalog, sql);
      case Ast.Pragma ignored -> throw new IllegalStateException("PRAGMA does not compile to bytecode");
    };
  }

  private Program compileInsert(Insert insert, MemoryCatalog catalog, String sql) {
    Table table = requireTable(catalog, insert.table(), sql);
    Expr[] values = align(insert, table, sql);
    Assembler asm = new Assembler();
    int start = asm.allocRange(values.length);
    Env env = envFor(table, sql);
    for (int i = 0; i < values.length; i++) {
      emitExprInto(asm, start + i, values[i], env);
    }
    int record = asm.alloc();
    asm.emit(Opcode.MakeRecord, start, values.length, record);
    asm.emit(Opcode.OpenWrite, CURSOR, asm.constant(table.name()), 0);
    if (insert.upsert() != null) {
      int[] conflict = conflictColumns(insert, table, sql);
      if (!table.hasUnique(conflict)) {
        throw new SqlException("ON CONFLICT clause does not match a unique constraint: " + SqlText.snippet(sql));
      }
      asm.insertMode = InsertMode.UPSERT;
      asm.conflictColumns = conflict;
      asm.emit(Opcode.Insert, CURSOR, record, 0);
      emitUpsertUpdate(asm, insert, table, start, sql);
      asm.insertDone = asm.emit(Opcode.Halt, 0, 0, 0);
    } else {
      if (insert.orIgnore()) {
        asm.insertMode = InsertMode.IGNORE;
      }
      asm.emit(Opcode.Insert, CURSOR, record, 0);
      asm.emit(Opcode.Halt, 0, 0, 0);
    }
    return asm.build(List.of());
  }

  private void emitUpsertUpdate(Assembler asm, Insert insert, Table table, int excludedBase, String sql) {
    int width = table.columns().size();
    int rowStart = asm.allocRange(width);
    for (int i = 0; i < width; i++) {
      asm.emit(Opcode.Column, CURSOR, i, rowStart + i);
    }
    Env env = new Env(List.of(new Source(CURSOR, table, null)), null, excludedBase, table, sql);
    int[] targets = assignmentTargets(insert.upsert().assignments(), table, sql);
    for (int i = 0; i < targets.length; i++) {
      emitExprInto(asm, rowStart + targets[i], insert.upsert().assignments().get(i).value(), env);
    }
    int record = asm.alloc();
    asm.emit(Opcode.MakeRecord, rowStart, width, record);
    asm.emit(Opcode.Update, CURSOR, record, 0);
  }

  private Program compileSelect(Select select, MemoryCatalog catalog, String sql) {
    if (select.fromSelect() != null) {
      throw new SqlException("derived table was not materialized: " + SqlText.snippet(sql));
    }
    if (select.table() == null) {
      return compileScalar(select, sql);
    }
    if (!select.joins().isEmpty()) {
      return compileJoin(select, catalog, sql);
    }
    if (isAggregate(select)) {
      return compileAggregate(select, catalog, sql);
    }
    return compileSimple(select, catalog, sql);
  }

  private Program compileScalar(Select select, String sql) {
    if (select.items().size() == 1 && select.items().get(0) instanceof Star) {
      throw new SqlException("SELECT * needs a table: " + SqlText.snippet(sql));
    }
    Assembler asm = new Assembler();
    prepareLimit(asm, select, sql);
    Env env = new Env(List.of(), null, -1, null, sql);
    List<Integer> fails = new ArrayList<>();
    if (select.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, select.where(), env, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    List<String> names = new ArrayList<>();
    int start = asm.allocRange(select.items().size());
    for (int i = 0; i < select.items().size(); i++) {
      SelectItem item = select.items().get(i);
      names.add(Labels.item(item));
      switch (item) {
        case Star ignored -> throw new SqlException("SELECT * needs a table: " + SqlText.snippet(sql));
        case CountStar ignored -> asm.emit(Opcode.Integer, start + i, 0, 0);
        case MaxItem ignored -> asm.emit(Opcode.Null, start + i, 0, 0);
        case ExpressionItem expression -> {
          if (containsColumn(expression.expr())) {
            throw new SqlException("unknown column: " + SqlText.snippet(sql));
          }
          emitExprInto(asm, start + i, expression.expr(), env);
        }
      }
    }
    writeOrder(asm, select, env);
    asm.emit(Opcode.ResultRow, start, select.items().size(), 0);
    emitEpilogue(asm);
    int halt = asm.emit(Opcode.Halt, 0, 0, 0);
    for (int jump : fails) {
      asm.patch(jump, halt);
    }
    return asm.build(names);
  }

  private Program compileSimple(Select select, MemoryCatalog catalog, String sql) {
    Table table = requireTable(catalog, select.table(), sql);
    Assembler asm = new Assembler();
    prepareLimit(asm, select, sql);
    List<Integer> orderRegs = prepareOrderRegisters(asm, select);
    Env env = envFor(table, select.alias(), sql);
    Expr[] keys = seekKeys(table, select.where(), env);
    if (keys != null) {
      return compileSeek(asm, select, table, env, keys, orderRegs);
    }
    boolean epilogue = hasEpilogue(select);
    Scan scan = emitScan(asm, Opcode.OpenRead, table, select.where(), env, epilogue);
    int width = width(select, env.sources, sql);
    int start = asm.allocRange(width);
    writeProjection(asm, select, env, start, sql);
    writeOrderInto(asm, select, env, orderRegs);
    asm.emit(Opcode.ResultRow, start, width, 0);
    finishScan(asm, scan);
    return asm.build(outputNames(select, env.sources, sql));
  }

  private Program compileSeek(
      Assembler asm, Select select, Table table, Env env, Expr[] keys, List<Integer> orderRegs) {
    int keyStart = asm.allocRange(keys.length);
    for (int i = 0; i < keys.length; i++) {
      emitExprInto(asm, keyStart + i, keys[i], env);
    }
    asm.emit(Opcode.OpenRead, CURSOR, asm.constant(table.name()), 0);
    int seek = asm.emit(Opcode.SeekPk, CURSOR, -1, keyStart);
    int width = width(select, env.sources, env.sql);
    int start = asm.allocRange(width);
    writeProjection(asm, select, env, start, env.sql);
    writeOrderInto(asm, select, env, orderRegs);
    asm.emit(Opcode.ResultRow, start, width, 0);
    emitEpilogue(asm);
    int halt = asm.emit(Opcode.Halt, 0, 0, 0);
    asm.patch(seek, halt);
    return asm.build(outputNames(select, env.sources, env.sql));
  }

  private Program compileAggregate(Select select, MemoryCatalog catalog, String sql) {
    if (!select.orderBy().isEmpty()) {
      throw new SqlException("ORDER BY is not supported on aggregates: " + SqlText.snippet(sql));
    }
    Table table = requireTable(catalog, select.table(), sql);
    for (SelectItem item : select.items()) {
      if (item instanceof Star) {
        throw new SqlException("aggregate cannot include *: " + SqlText.snippet(sql));
      }
      if (item instanceof ExpressionItem expression && containsColumn(expression.expr())) {
        throw new SqlException("column must be aggregated: " + SqlText.snippet(sql));
      }
    }
    Assembler asm = new Assembler();
    prepareLimit(asm, select, sql);
    int[] acc = new int[select.items().size()];
    Arrays.fill(acc, -1);
    for (int i = 0; i < select.items().size(); i++) {
      switch (select.items().get(i)) {
        case CountStar ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Integer, acc[i], 0, 0);
        }
        case MaxItem ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Null, acc[i], 0, 0);
        }
        default -> {
        }
      }
    }
    Env env = envFor(table, select.alias(), sql);
    asm.emit(Opcode.OpenRead, CURSOR, asm.constant(table.name()), 0);
    int rewind = asm.emit(Opcode.Rewind, CURSOR, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (select.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, select.where(), env, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    for (int i = 0; i < select.items().size(); i++) {
      switch (select.items().get(i)) {
        case CountStar ignored -> asm.emit(Opcode.AggCount, acc[i], 0, 0);
        case MaxItem max -> {
          int value = emitExpr(asm, new ColumnRef(null, max.column()), env);
          asm.emit(Opcode.AggMax, acc[i], value, 0);
        }
        default -> {
        }
      }
    }
    int next = asm.emit(Opcode.Next, CURSOR, loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int output = asm.here();
    asm.patch(rewind, output);
    int start = asm.allocRange(select.items().size());
    for (int i = 0; i < select.items().size(); i++) {
      SelectItem item = select.items().get(i);
      if (acc[i] >= 0) {
        asm.emit(Opcode.IfNullValue, acc[i], acc[i], start + i);
      } else if (item instanceof ExpressionItem expression) {
        emitExprInto(asm, start + i, expression.expr(), env);
      }
    }
    asm.emit(Opcode.ResultRow, start, select.items().size(), 0);
    emitEpilogue(asm);
    asm.emit(Opcode.Halt, 0, 0, 0);
    List<String> names = new ArrayList<>();
    for (SelectItem item : select.items()) {
      names.add(Labels.item(item));
    }
    return asm.build(names);
  }

  private Program compileJoin(Select select, MemoryCatalog catalog, String sql) {
    if (isAggregate(select)) {
      return compileJoinAggregate(select, catalog, sql);
    }
    Table root = requireTable(catalog, select.table(), sql);
    List<Source> sources = new ArrayList<>();
    sources.add(new Source(0, root, select.alias()));
    for (int i = 0; i < select.joins().size(); i++) {
      Join join = select.joins().get(i);
      sources.add(new Source(i + 1, requireTable(catalog, join.table(), sql), join.alias()));
    }
    Assembler asm = new Assembler();
    prepareLimit(asm, select, sql);
    List<Integer> orderRegs = prepareOrderRegisters(asm, select);
    int zero = -1;
    for (Join join : select.joins()) {
      if (join.left()) {
        zero = asm.alloc();
        asm.emit(Opcode.Integer, zero, 0, 0);
        break;
      }
    }
    Env env = new Env(sources, new boolean[sources.size()], -1, null, sql);
    emitLevel(asm, select, env, 0, zero, orderRegs);
    return asm.build(outputNames(select, sources, sql));
  }

  private Program compileJoinAggregate(Select select, MemoryCatalog catalog, String sql) {
    if (!select.orderBy().isEmpty()) {
      throw new SqlException("ORDER BY is not supported on aggregates: " + SqlText.snippet(sql));
    }
    for (SelectItem item : select.items()) {
      if (item instanceof Star) {
        throw new SqlException("aggregate cannot include *: " + SqlText.snippet(sql));
      }
      if (item instanceof ExpressionItem expression && containsColumn(expression.expr())) {
        throw new SqlException("column must be aggregated: " + SqlText.snippet(sql));
      }
    }
    Table root = requireTable(catalog, select.table(), sql);
    List<Source> sources = new ArrayList<>();
    sources.add(new Source(0, root, select.alias()));
    for (int i = 0; i < select.joins().size(); i++) {
      Join join = select.joins().get(i);
      sources.add(new Source(i + 1, requireTable(catalog, join.table(), sql), join.alias()));
    }
    Assembler asm = new Assembler();
    prepareLimit(asm, select, sql);
    int[] acc = new int[select.items().size()];
    Arrays.fill(acc, -1);
    for (int i = 0; i < select.items().size(); i++) {
      switch (select.items().get(i)) {
        case CountStar ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Integer, acc[i], 0, 0);
        }
        case MaxItem ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Null, acc[i], 0, 0);
        }
        default -> {
        }
      }
    }
    int zero = -1;
    for (Join join : select.joins()) {
      if (join.left()) {
        zero = asm.alloc();
        asm.emit(Opcode.Integer, zero, 0, 0);
        break;
      }
    }
    Env env = new Env(sources, new boolean[sources.size()], -1, null, sql);
    emitAggregateLevel(asm, select, env, 0, zero, acc);
    int start = asm.allocRange(select.items().size());
    for (int i = 0; i < select.items().size(); i++) {
      SelectItem item = select.items().get(i);
      if (acc[i] >= 0) {
        asm.emit(Opcode.IfNullValue, acc[i], acc[i], start + i);
      } else if (item instanceof ExpressionItem expression) {
        emitExprInto(asm, start + i, expression.expr(), env);
      }
    }
    asm.emit(Opcode.ResultRow, start, select.items().size(), 0);
    emitEpilogue(asm);
    asm.emit(Opcode.Halt, 0, 0, 0);
    List<String> names = new ArrayList<>();
    for (SelectItem item : select.items()) {
      names.add(Labels.item(item));
    }
    return asm.build(names);
  }

  private void emitAggregateLevel(
      Assembler asm, Select select, Env env, int level, int zero, int[] acc) {
    if (level == env.sources.size()) {
      emitAggregateProject(asm, select, env, acc);
      return;
    }
    if (env.nulled != null && env.nulled[level]) {
      emitAggregateLevel(asm, select, env, level + 1, zero, acc);
      return;
    }
    Source source = env.sources.get(level);
    asm.emit(Opcode.OpenRead, source.cursor(), asm.constant(source.table().name()), 0);
    if (level == 0) {
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      emitAggregateLevel(asm, select, env, 1, zero, acc);
      asm.emit(Opcode.Next, source.cursor(), loop, 0);
      int after = asm.here();
      asm.patch(rewind, after);
      return;
    }
    Join join = select.joins().get(level - 1);
    if (!join.left()) {
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      List<Integer> trues = new ArrayList<>();
      List<Integer> fails = new ArrayList<>();
      emitBool(asm, join.on(), env, trues, fails);
      int matched = asm.here();
      for (int jump : trues) {
        asm.patch(jump, matched);
      }
      emitAggregateLevel(asm, select, env, level + 1, zero, acc);
      int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
      for (int jump : fails) {
        asm.patch(jump, next);
      }
      asm.patch(rewind, asm.here());
      return;
    }
    int flag = asm.alloc();
    asm.emit(Opcode.Integer, flag, 0, 0);
    int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
    int loop = asm.here();
    List<Integer> trues = new ArrayList<>();
    List<Integer> fails = new ArrayList<>();
    emitBool(asm, join.on(), env, trues, fails);
    int matched = asm.here();
    for (int jump : trues) {
      asm.patch(jump, matched);
    }
    asm.emit(Opcode.Integer, flag, 1, 0);
    emitAggregateLevel(asm, select, env, level + 1, zero, acc);
    int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int check = asm.emit(Opcode.Eq, flag, -1, zero);
    int skip = asm.emit(Opcode.Goto, 0, -1, 0);
    asm.patch(check, asm.here());
    boolean[] pad = env.nulled.clone();
    pad[level] = true;
    emitAggregateLevel(asm, select, env.withNulled(pad), level + 1, zero, acc);
    asm.patch(skip, asm.here());
    asm.patch(rewind, check);
  }

  private void emitAggregateProject(Assembler asm, Select select, Env env, int[] acc) {
    List<Integer> fails = new ArrayList<>();
    if (select.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, select.where(), env, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    for (int i = 0; i < select.items().size(); i++) {
      switch (select.items().get(i)) {
        case CountStar ignored -> asm.emit(Opcode.AggCount, acc[i], 0, 0);
        case MaxItem max -> {
          int value = emitExpr(asm, new ColumnRef(null, max.column()), env);
          asm.emit(Opcode.AggMax, acc[i], value, 0);
        }
        default -> {
        }
      }
    }
    int skip = asm.here();
    for (int jump : fails) {
      asm.patch(jump, skip);
    }
  }

  private void emitLevel(
      Assembler asm, Select select, Env env, int level, int zero, List<Integer> orderRegs) {
    if (level == env.sources.size()) {
      emitProject(asm, select, env, orderRegs);
      return;
    }
    if (env.nulled != null && env.nulled[level]) {
      emitLevel(asm, select, env, level + 1, zero, orderRegs);
      return;
    }
    Source source = env.sources.get(level);
    asm.emit(Opcode.OpenRead, source.cursor(), asm.constant(source.table().name()), 0);
    if (level == 0) {
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      emitLevel(asm, select, env, 1, zero, orderRegs);
      asm.emit(Opcode.Next, source.cursor(), loop, 0);
      emitEpilogue(asm);
      int halt = asm.emit(Opcode.Halt, 0, 0, 0);
      asm.patch(rewind, halt);
      return;
    }
    Join join = select.joins().get(level - 1);
    if (!join.left()) {
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      List<Integer> trues = new ArrayList<>();
      List<Integer> fails = new ArrayList<>();
      emitBool(asm, join.on(), env, trues, fails);
      int matched = asm.here();
      for (int jump : trues) {
        asm.patch(jump, matched);
      }
      emitLevel(asm, select, env, level + 1, zero, orderRegs);
      int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
      for (int jump : fails) {
        asm.patch(jump, next);
      }
      asm.patch(rewind, asm.here());
      return;
    }
    int flag = asm.alloc();
    asm.emit(Opcode.Integer, flag, 0, 0);
    int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
    int loop = asm.here();
    List<Integer> trues = new ArrayList<>();
    List<Integer> fails = new ArrayList<>();
    emitBool(asm, join.on(), env, trues, fails);
    int matched = asm.here();
    for (int jump : trues) {
      asm.patch(jump, matched);
    }
    asm.emit(Opcode.Integer, flag, 1, 0);
    emitLevel(asm, select, env, level + 1, zero, orderRegs);
    int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int check = asm.emit(Opcode.Eq, flag, -1, zero);
    int skip = asm.emit(Opcode.Goto, 0, -1, 0);
    asm.patch(check, asm.here());
    boolean[] pad = env.nulled.clone();
    pad[level] = true;
    emitLevel(asm, select, env.withNulled(pad), level + 1, zero, orderRegs);
    asm.patch(skip, asm.here());
    asm.patch(rewind, check);
  }

  private void emitProject(Assembler asm, Select select, Env env, List<Integer> orderRegs) {
    List<Integer> fails = new ArrayList<>();
    if (select.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, select.where(), env, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    int width = width(select, env.sources, env.sql);
    int start = asm.allocRange(width);
    writeProjection(asm, select, env, start, env.sql);
    writeOrderInto(asm, select, env, orderRegs);
    asm.emit(Opcode.ResultRow, start, width, 0);
    int skip = asm.here();
    for (int jump : fails) {
      asm.patch(jump, skip);
    }
  }

  private Program compileUpdate(Update update, MemoryCatalog catalog, String sql) {
    Table table = requireTable(catalog, update.table(), sql);
    int[] targets = assignmentTargets(update.assignments(), table, sql);
    Assembler asm = new Assembler();
    Env env = envFor(table, sql);
    Scan scan = emitScan(asm, Opcode.OpenWrite, table, update.where(), env, false);
    int width = table.columns().size();
    int rowStart = asm.allocRange(width);
    for (int i = 0; i < width; i++) {
      asm.emit(Opcode.Column, CURSOR, i, rowStart + i);
    }
    for (int i = 0; i < targets.length; i++) {
      emitExprInto(asm, rowStart + targets[i], update.assignments().get(i).value(), env);
    }
    int record = asm.alloc();
    asm.emit(Opcode.MakeRecord, rowStart, width, record);
    asm.emit(Opcode.Update, CURSOR, record, 0);
    finishScan(asm, scan);
    return asm.build(List.of());
  }

  private Program compileDelete(Delete delete, MemoryCatalog catalog, String sql) {
    Table table = requireTable(catalog, delete.table(), sql);
    Assembler asm = new Assembler();
    Scan scan = emitScan(asm, Opcode.OpenWrite, table, delete.where(), envFor(table, sql), false);
    asm.emit(Opcode.Delete, CURSOR, 0, 0);
    finishScan(asm, scan);
    return asm.build(List.of());
  }

  private Scan emitScan(
      Assembler asm, Opcode open, Table table, Bool where, Env env, boolean epilogue) {
    asm.emit(open, CURSOR, asm.constant(table.name()), 0);
    int rewind = asm.emit(Opcode.Rewind, CURSOR, -1, 0);
    int loopHead = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (where != null) {
      if (!epilogue && isAndChain(where)) {
        for (Predicate predicate : andPredicates(where)) {
          int jump = emitPredicateJump(asm, predicate, env);
          asm.emit(Opcode.Next, CURSOR, loopHead, 0);
          asm.emit(Opcode.Halt, 0, 0, 0);
          asm.patch(jump, asm.here());
        }
      } else {
        List<Integer> trues = new ArrayList<>();
        emitBool(asm, where, env, trues, fails);
        int body = asm.here();
        for (int jump : trues) {
          asm.patch(jump, body);
        }
      }
    }
    return new Scan(rewind, loopHead, fails);
  }

  private void finishScan(Assembler asm, Scan scan) {
    int next = asm.emit(Opcode.Next, CURSOR, scan.loopHead, 0);
    for (int jump : scan.fails) {
      asm.patch(jump, next);
    }
    emitEpilogue(asm);
    int halt = asm.emit(Opcode.Halt, 0, 0, 0);
    asm.patch(scan.rewind, halt);
  }

  private void emitEpilogue(Assembler asm) {
    if (!asm.orderKeys.isEmpty()) {
      asm.emit(Opcode.Sort, 0, 0, 0);
    }
    if (asm.limitRegister >= 0 || asm.offsetRegister >= 0) {
      asm.emit(Opcode.Limit, 0, 0, 0);
    }
  }

  private void emitBool(Assembler asm, Bool condition, Env env, List<Integer> onTrue, List<Integer> onFalse) {
    switch (condition) {
      case Or or -> {
        List<Integer> leftFalse = new ArrayList<>();
        emitBool(asm, or.left(), env, onTrue, leftFalse);
        int right = asm.here();
        for (int jump : leftFalse) {
          asm.patch(jump, right);
        }
        emitBool(asm, or.right(), env, onTrue, onFalse);
      }
      case And and -> {
        List<Integer> leftTrue = new ArrayList<>();
        emitBool(asm, and.left(), env, leftTrue, onFalse);
        int right = asm.here();
        for (int jump : leftTrue) {
          asm.patch(jump, right);
        }
        emitBool(asm, and.right(), env, onTrue, onFalse);
      }
      case Pred pred -> {
        onTrue.add(emitPredicateJump(asm, pred.predicate(), env));
        onFalse.add(asm.emit(Opcode.Goto, 0, -1, 0));
      }
    }
  }

  private int emitPredicateJump(Assembler asm, Predicate predicate, Env env) {
    return switch (predicate) {
      case Compare compare -> {
        int left = emitExpr(asm, compare.left(), env);
        int right = emitExpr(asm, compare.right(), env);
        yield asm.emit(compareOpcode(compare.op()), left, -1, right);
      }
      case IsNull isNull -> {
        int value = emitExpr(asm, isNull.expr(), env);
        yield asm.emit(isNull.not() ? Opcode.NotNull : Opcode.IfNull, value, -1, 0);
      }
      case Like like -> {
        int left = emitExpr(asm, like.left(), env);
        int right = emitExpr(asm, like.right(), env);
        yield asm.emit(Opcode.Like, left, -1, right);
      }
    };
  }

  private int emitExpr(Assembler asm, Expr expr, Env env) {
    int dest = asm.alloc();
    emitExprInto(asm, dest, expr, env);
    return dest;
  }

  private void emitExprInto(Assembler asm, int dest, Expr expr, Env env) {
    switch (expr) {
      case LiteralExpr literal -> asm.emitLiteralInto(dest, literal.literal());
      case Parameter parameter -> asm.emit(Opcode.Variable, dest, parameter.index(), 0);
      case Now ignored -> asm.emit(Opcode.Now, dest, 0, 0);
      case ColumnRef column -> emitColumnRef(asm, dest, column, env);
      case Concat concat -> {
        int left = emitExpr(asm, concat.left(), env);
        int right = emitExpr(asm, concat.right(), env);
        asm.emit(Opcode.Concat, left, right, dest);
      }
      case IfNullExpr ifNull -> {
        int value = emitExpr(asm, ifNull.value(), env);
        int fallback = emitExpr(asm, ifNull.fallback(), env);
        asm.emit(Opcode.IfNullValue, value, fallback, dest);
      }
    }
  }

  private void emitColumnRef(Assembler asm, int dest, ColumnRef column, Env env) {
    if (column.table() != null && same(column.table(), "excluded")) {
      if (env.excludedBase < 0 || env.excludedTable == null) {
        throw new SqlException("unknown column excluded." + column.name() + ": " + SqlText.snippet(env.sql));
      }
      int index = requireColumn(env.excludedTable, column.name(), env.sql);
      asm.emit(Opcode.IfNullValue, env.excludedBase + index, env.excludedBase + index, dest);
      return;
    }
    Resolved resolved = resolve(column, env);
    if (env.nulled != null && env.nulled[resolved.source]) {
      asm.emit(Opcode.Null, dest, 0, 0);
      return;
    }
    asm.emit(Opcode.Column, resolved.cursor, resolved.index, dest);
  }

  private Resolved resolve(ColumnRef column, Env env) {
    List<Resolved> hits = new ArrayList<>();
    for (int i = 0; i < env.sources.size(); i++) {
      Source source = env.sources.get(i);
      if (column.table() != null && !qualifierMatches(source, column.table())) {
        continue;
      }
      int index = source.table.columnIndex(column.name());
      if (index >= 0) {
        hits.add(new Resolved(i, source.cursor, index));
      }
    }
    if (hits.isEmpty()) {
      String label = column.table() == null ? column.name() : column.table() + "." + column.name();
      throw new SqlException("unknown column " + label + ": " + SqlText.snippet(env.sql));
    }
    if (hits.size() > 1) {
      throw new SqlException("ambiguous column " + column.name() + ": " + SqlText.snippet(env.sql));
    }
    return hits.get(0);
  }

  private void writeProjection(Assembler asm, Select select, Env env, int start, String sql) {
    if (select.items().size() == 1 && select.items().get(0) instanceof Star) {
      int slot = 0;
      for (int source = 0; source < env.sources.size(); source++) {
        Table table = env.sources.get(source).table();
        for (int column = 0; column < table.columns().size(); column++) {
          if (env.nulled != null && env.nulled[source]) {
            asm.emit(Opcode.Null, start + slot, 0, 0);
          } else {
            asm.emit(Opcode.Column, env.sources.get(source).cursor(), column, start + slot);
          }
          slot++;
        }
      }
      return;
    }
    for (int i = 0; i < select.items().size(); i++) {
      if (!(select.items().get(i) instanceof ExpressionItem expression)) {
        throw new SqlException("aggregate cannot be mixed into this select: " + SqlText.snippet(sql));
      }
      emitExprInto(asm, start + i, expression.expr(), env);
    }
  }

  private void writeOrder(Assembler asm, Select select, Env env) {
    for (int i = 0; i < select.orderBy().size(); i++) {
      int register = asm.alloc();
      asm.orderKeys.add(new OrderKey(register, select.orderBy().get(i).descending()));
      emitExprInto(asm, register, select.orderBy().get(i).expr(), env);
    }
  }

  private void writeOrderInto(Assembler asm, Select select, Env env, List<Integer> orderRegs) {
    for (int i = 0; i < orderRegs.size(); i++) {
      emitExprInto(asm, orderRegs.get(i), select.orderBy().get(i).expr(), env);
    }
  }

  private void prepareLimit(Assembler asm, Select select, String sql) {
    Env env = new Env(List.of(), null, -1, null, sql);
    if (select.limit() != null) {
      asm.limitRegister = asm.alloc();
      emitExprInto(asm, asm.limitRegister, select.limit(), env);
    }
    if (select.offset() != null) {
      asm.offsetRegister = asm.alloc();
      emitExprInto(asm, asm.offsetRegister, select.offset(), env);
    }
  }

  private List<Integer> prepareOrderRegisters(Assembler asm, Select select) {
    List<Integer> registers = new ArrayList<>();
    for (int i = 0; i < select.orderBy().size(); i++) {
      int register = asm.alloc();
      registers.add(register);
      asm.orderKeys.add(new OrderKey(register, select.orderBy().get(i).descending()));
    }
    return registers;
  }

  private Expr[] align(Insert insert, Table table, String sql) {
    int width = table.columns().size();
    Expr[] aligned = new Expr[width];
    Expr nullValue = new LiteralExpr(new NullLiteral());
    Arrays.fill(aligned, nullValue);
    boolean[] provided = new boolean[width];
    if (insert.columns() == null) {
      if (insert.values().size() != width) {
        throw new SqlException("value count mismatch for " + table.name() + ": " + SqlText.snippet(sql));
      }
      for (int i = 0; i < width; i++) {
        aligned[i] = insert.values().get(i);
        provided[i] = true;
      }
    } else {
      if (insert.columns().size() != insert.values().size()) {
        throw new SqlException("value count mismatch for " + table.name() + ": " + SqlText.snippet(sql));
      }
      for (int i = 0; i < insert.columns().size(); i++) {
        int index = requireColumn(table, insert.columns().get(i), sql);
        if (provided[index]) {
          throw new SqlException("duplicate column " + insert.columns().get(i) + ": " + SqlText.snippet(sql));
        }
        provided[index] = true;
        aligned[index] = insert.values().get(i);
      }
    }
    for (int i = 0; i < width; i++) {
      if (!provided[i] && table.columns().get(i).defaultNow()) {
        aligned[i] = new Now();
      } else if (!provided[i] && table.columns().get(i).hasDefault()) {
        aligned[i] = new LiteralExpr(defaultLiteral(table.columns().get(i)));
      }
      if (aligned[i] instanceof LiteralExpr literal) {
        checkAssignable(table, i, literal.literal(), sql);
      }
    }
    return aligned;
  }

  private Literal defaultLiteral(Column column) {
    Object value = column.defaultValue();
    if (value == null) {
      return new NullLiteral();
    }
    if (value instanceof Long number) {
      return new NumberLiteral(number);
    }
    if (value instanceof String text) {
      return new StringLiteral(text);
    }
    throw new SqlException("type mismatch for " + column.name());
  }

  private int[] assignmentTargets(List<Assign> assignments, Table table, String sql) {
    int[] targets = new int[assignments.size()];
    boolean[] seen = new boolean[table.columns().size()];
    for (int i = 0; i < assignments.size(); i++) {
      Assign assign = assignments.get(i);
      int index = requireColumn(table, assign.column(), sql);
      if (seen[index]) {
        throw new SqlException("duplicate column " + assign.column() + ": " + SqlText.snippet(sql));
      }
      seen[index] = true;
      if (assign.value() instanceof LiteralExpr literal) {
        checkAssignable(table, index, literal.literal(), sql);
      }
      targets[i] = index;
    }
    return targets;
  }

  private int[] conflictColumns(Insert insert, Table table, String sql) {
    List<String> names = insert.upsert().conflictColumns();
    int[] columns = new int[names.size()];
    boolean[] seen = new boolean[table.columns().size()];
    for (int i = 0; i < names.size(); i++) {
      int index = requireColumn(table, names.get(i), sql);
      if (seen[index]) {
        throw new SqlException("duplicate column " + names.get(i) + ": " + SqlText.snippet(sql));
      }
      seen[index] = true;
      columns[i] = index;
    }
    return columns;
  }

  private Expr[] seekKeys(Table table, Bool where, Env env) {
    int[] primaryKey = table.primaryKey();
    if (primaryKey.length == 0 || where == null || !isAndChain(where)) {
      return null;
    }
    List<Predicate> predicates = andPredicates(where);
    if (predicates.size() != primaryKey.length) {
      return null;
    }
    Expr[] keys = new Expr[primaryKey.length];
    for (Predicate predicate : predicates) {
      if (!(predicate instanceof Compare compare) || compare.op() != CompareOp.EQ) {
        return null;
      }
      if (!(compare.left() instanceof ColumnRef column) || references(compare.right())) {
        return null;
      }
      if (column.table() != null && !same(column.table(), table.name())) {
        return null;
      }
      int index = table.columnIndex(column.name());
      int position = -1;
      for (int i = 0; i < primaryKey.length; i++) {
        if (primaryKey[i] == index) {
          position = i;
        }
      }
      if (index < 0 || position < 0 || keys[position] != null) {
        return null;
      }
      keys[position] = compare.right();
    }
    return keys;
  }

  private boolean references(Expr expr) {
    return switch (expr) {
      case ColumnRef ignored -> true;
      case Concat concat -> references(concat.left()) || references(concat.right());
      case IfNullExpr ifNull -> references(ifNull.value()) || references(ifNull.fallback());
      default -> false;
    };
  }

  private boolean containsColumn(Expr expr) {
    return references(expr);
  }

  private boolean isAggregate(Select select) {
    for (SelectItem item : select.items()) {
      if (item instanceof CountStar || item instanceof MaxItem) {
        return true;
      }
    }
    return false;
  }

  private boolean hasEpilogue(Select select) {
    return select.limit() != null || select.offset() != null || !select.orderBy().isEmpty();
  }

  private boolean isAndChain(Bool condition) {
    return switch (condition) {
      case And and -> isAndChain(and.left()) && isAndChain(and.right());
      case Or ignored -> false;
      case Pred ignored -> true;
    };
  }

  private List<Predicate> andPredicates(Bool condition) {
    List<Predicate> predicates = new ArrayList<>();
    collectAnd(condition, predicates);
    return predicates;
  }

  private void collectAnd(Bool condition, List<Predicate> predicates) {
    switch (condition) {
      case And and -> {
        collectAnd(and.left(), predicates);
        collectAnd(and.right(), predicates);
      }
      case Pred pred -> predicates.add(pred.predicate());
      case Or ignored -> throw new IllegalStateException("OR is not an AND chain");
    }
  }

  private int width(Select select, List<Source> sources, String sql) {
    if (select.items().size() == 1 && select.items().get(0) instanceof Star) {
      int width = 0;
      for (Source source : sources) {
        width += source.table.columns().size();
      }
      return width;
    }
    for (SelectItem item : select.items()) {
      if (item instanceof Star) {
        throw new SqlException("star must be the only select item: " + SqlText.snippet(sql));
      }
    }
    return select.items().size();
  }

  private List<String> outputNames(Select select, List<Source> sources, String sql) {
    if (select.items().size() == 1 && select.items().get(0) instanceof Star) {
      List<String> names = new ArrayList<>();
      for (Source source : sources) {
        for (Column column : source.table.columns()) {
          names.add(column.name());
        }
      }
      return names;
    }
    List<String> names = new ArrayList<>();
    for (SelectItem item : select.items()) {
      if (item instanceof Star) {
        throw new SqlException("star must be the only select item: " + SqlText.snippet(sql));
      }
      names.add(Labels.item(item));
    }
    return names;
  }

  private Table requireTable(MemoryCatalog catalog, String name, String sql) {
    Table table = catalog.find(name);
    if (table == null) {
      throw new SqlException("unknown table " + name + ": " + SqlText.snippet(sql));
    }
    return table;
  }

  private int requireColumn(Table table, String name, String sql) {
    int index = table.columnIndex(name);
    if (index < 0) {
      throw new SqlException("unknown column " + name + ": " + SqlText.snippet(sql));
    }
    return index;
  }

  private void checkAssignable(Table table, int index, Literal literal, String sql) {
    Column column = table.columns().get(index);
    if (literal instanceof NullLiteral) {
      if (column.notNull() && !table.assignsIdentity(index)) {
        throw new SqlException("NOT NULL constraint failed: " + column.name() + ": " + SqlText.snippet(sql));
      }
      return;
    }
    if (column.type() == ColumnType.INTEGER && !(literal instanceof NumberLiteral)) {
      throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
    }
    if (column.type() == ColumnType.TEXT && !(literal instanceof StringLiteral)) {
      throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
    }
  }

  private Opcode compareOpcode(CompareOp op) {
    return switch (op) {
      case EQ -> Opcode.Eq;
      case LT -> Opcode.Lt;
      case GT -> Opcode.Gt;
      case LE -> Opcode.Le;
      case GE -> Opcode.Ge;
      case NE -> Opcode.Ne;
    };
  }

  private boolean qualifierMatches(Source source, String qualifier) {
    String visible = source.alias != null ? source.alias : source.table.name();
    return same(visible, qualifier);
  }

  private Env envFor(Table table, String sql) {
    return envFor(table, null, sql);
  }

  private Env envFor(Table table, String alias, String sql) {
    return new Env(List.of(new Source(CURSOR, table, alias)), null, -1, null, sql);
  }

  private static boolean same(String left, String right) {
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }

  private record Scan(int rewind, int loopHead, List<Integer> fails) {}

  private record Source(int cursor, Table table, String alias) {}

  private record Resolved(int source, int cursor, int index) {}

  private record Env(
      List<Source> sources, boolean[] nulled, int excludedBase, Table excludedTable, String sql) {
    private Env withNulled(boolean[] nulled) {
      return new Env(sources, nulled, excludedBase, excludedTable, sql);
    }
  }

  private static final class Assembler {
    private final List<Instruction> code = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private final List<OrderKey> orderKeys = new ArrayList<>();
    private int registers;
    private int limitRegister = -1;
    private int offsetRegister = -1;
    private InsertMode insertMode = InsertMode.APPEND;
    private int[] conflictColumns = new int[0];
    private int insertDone = -1;

    int alloc() {
      return registers++;
    }

    int allocRange(int count) {
      int start = registers;
      registers += count;
      return start;
    }

    int constant(Object value) {
      int index = constants.indexOf(value);
      if (index >= 0) {
        return index;
      }
      constants.add(value);
      return constants.size() - 1;
    }

    int emit(Opcode op, int p1, int p2, int p3) {
      code.add(new Instruction(op, p1, p2, p3));
      return code.size() - 1;
    }

    int here() {
      return code.size();
    }

    void patch(int index, int target) {
      Instruction instruction = code.get(index);
      code.set(index, new Instruction(instruction.op(), instruction.p1(), target, instruction.p3()));
    }

    void emitNumber(int register, long value) {
      if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
        emit(Opcode.Integer, register, (int) value, 0);
      } else {
        emit(Opcode.Int64, register, constant(value), 0);
      }
    }

    void emitLiteralInto(int register, Literal literal) {
      switch (literal) {
        case NumberLiteral number -> emitNumber(register, number.value());
        case StringLiteral string -> emit(Opcode.String, register, constant(string.value()), 0);
        case NullLiteral ignored -> emit(Opcode.Null, register, 0, 0);
      }
    }

    Program build(List<String> resultColumns) {
      return new Program(
          code,
          constants,
          resultColumns,
          registers,
          orderKeys,
          limitRegister,
          offsetRegister,
          insertMode,
          conflictColumns,
          insertDone);
    }
  }
}
