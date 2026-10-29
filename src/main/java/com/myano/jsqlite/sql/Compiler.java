package com.myano.jsqlite.sql;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Ast.AbsExpr;
import com.myano.jsqlite.sql.Ast.And;
import com.myano.jsqlite.sql.Ast.Assign;
import com.myano.jsqlite.sql.Ast.Bool;
import com.myano.jsqlite.sql.Ast.CaseArm;
import com.myano.jsqlite.sql.Ast.CaseExpr;
import com.myano.jsqlite.sql.Ast.CastExpr;
import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Compare;
import com.myano.jsqlite.sql.Ast.CompareOp;
import com.myano.jsqlite.sql.Ast.Concat;
import com.myano.jsqlite.sql.Ast.CountItem;
import com.myano.jsqlite.sql.Ast.CountStar;
import com.myano.jsqlite.sql.Ast.Delete;
import com.myano.jsqlite.sql.Ast.Exists;
import com.myano.jsqlite.sql.Ast.Expr;
import com.myano.jsqlite.sql.Ast.ExpressionItem;
import com.myano.jsqlite.sql.Ast.IfNullExpr;
import com.myano.jsqlite.sql.Ast.InSubquery;
import com.myano.jsqlite.sql.Ast.Insert;
import com.myano.jsqlite.sql.Ast.IsNull;
import com.myano.jsqlite.sql.Ast.Join;
import com.myano.jsqlite.sql.Ast.Like;
import com.myano.jsqlite.sql.Ast.Literal;
import com.myano.jsqlite.sql.Ast.LiteralExpr;
import com.myano.jsqlite.sql.Ast.MaxItem;
import com.myano.jsqlite.sql.Ast.Not;
import com.myano.jsqlite.sql.Ast.Now;
import com.myano.jsqlite.sql.Ast.NullLiteral;
import com.myano.jsqlite.sql.Ast.NumberLiteral;
import com.myano.jsqlite.sql.Ast.Or;
import com.myano.jsqlite.sql.Ast.Parameter;
import com.myano.jsqlite.sql.Ast.Pred;
import com.myano.jsqlite.sql.Ast.Predicate;
import com.myano.jsqlite.sql.Ast.ScalarSubquery;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.SelectItem;
import com.myano.jsqlite.sql.Ast.Star;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Ast.StringLiteral;
import com.myano.jsqlite.sql.Ast.SumExpr;
import com.myano.jsqlite.sql.Ast.Update;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.vm.CastSpec;
import com.myano.jsqlite.vm.InsertMode;
import com.myano.jsqlite.vm.Instruction;
import com.myano.jsqlite.vm.Opcode;
import com.myano.jsqlite.vm.OrderKey;
import com.myano.jsqlite.vm.Program;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Compiler {
  private static final int CURSOR = 0;

  /** Set for the duration of {@link #compile}; used by nested IN-subquery emission. */
  private MemoryCatalog activeCatalog;
  /** Next cursor above those already reserved by this statement, including EXISTS scans. */
  private int cursorHigh;

  public Program compile(Statement statement, MemoryCatalog catalog, String sql) {
    MemoryCatalog previous = activeCatalog;
    int previousCursor = cursorHigh;
    activeCatalog = catalog;
    cursorHigh = 0;
    try {
      return switch (statement) {
        case Ast.CreateTable ignored ->
            throw new IllegalStateException("CREATE TABLE does not compile to bytecode");
        case Ast.CreateIndex ignored ->
            throw new IllegalStateException("CREATE INDEX does not compile to bytecode");
        case Ast.AlterTable ignored ->
            throw new IllegalStateException("ALTER TABLE does not compile to bytecode");
        case Ast.DropTable ignored ->
            throw new IllegalStateException("DROP TABLE does not compile to bytecode");
        case Ast.Shutdown ignored ->
            throw new IllegalStateException("SHUTDOWN does not compile to bytecode");
        case Ast.Compound ignored ->
            throw new IllegalStateException("UNION is executed by the engine");
        case Insert insert -> compileInsert(insert, catalog, sql);
        case Select select -> compileSelect(select, catalog, sql);
        case Update update -> compileUpdate(update, catalog, sql);
        case Delete delete -> compileDelete(delete, catalog, sql);
        case Ast.Pragma ignored ->
            throw new IllegalStateException("PRAGMA does not compile to bytecode");
      };
    } finally {
      activeCatalog = previous;
      cursorHigh = previousCursor;
    }
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
    Env env = new Env(List.of(new Source(CURSOR, table, null)), null, 0, excludedBase, table, sql);
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
      if (!select.groupBy().isEmpty()) {
        throw new SqlException("GROUP BY needs a table: " + SqlText.snippet(sql));
      }
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
    Env env = new Env(List.of(), null, 0, -1, null, sql);
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
        case CountItem ignored -> asm.emit(Opcode.Integer, start + i, 0, 0);
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
    prepareGroupRegisters(asm, select);
    Env env = envFor(table, select.alias(), sql);
    Access access = seekAccess(table, select.where(), env);
    if (access != null) {
      return compileAccess(
          asm, select, table, env, access.columns(), access.keys(), access.primary(), orderRegs);
    }
    boolean epilogue = hasEpilogue(select);
    Scan scan = emitScan(asm, Opcode.OpenRead, table, select.where(), env, epilogue);
    int width = width(select, env.sources, sql);
    int start = asm.allocRange(width);
    writeProjection(asm, select, env, start, sql);
    writeGroupInto(asm, select, env);
    writeOrderInto(asm, select, env, orderRegs);
    asm.emit(Opcode.ResultRow, start, width, 0);
    finishScan(asm, scan);
    return asm.build(outputNames(select, env.sources, sql));
  }

  private Program compileAccess(
      Assembler asm,
      Select select,
      Table table,
      Env env,
      int[] columns,
      Expr[] keys,
      boolean primary,
      List<Integer> orderRegs) {
    if (!primary) {
      table.ensureIndex(columns);
    }
    int keyStart = asm.allocRange(keys.length);
    for (int i = 0; i < keys.length; i++) {
      emitExprInto(asm, keyStart + i, keys[i], env);
    }
    asm.emit(Opcode.OpenRead, CURSOR, asm.constant(table.name()), 0);
    int seek;
    int colsConst = -1;
    if (primary) {
      seek = asm.emit(Opcode.SeekPk, CURSOR, -1, keyStart);
    } else {
      colsConst = asm.constant(boxColumns(columns));
      seek = asm.emit(Opcode.SeekIdx, CURSOR, -1, keyStart, colsConst);
    }
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
    int width = width(select, env.sources, env.sql);
    int start = asm.allocRange(width);
    writeProjection(asm, select, env, start, env.sql);
    writeGroupInto(asm, select, env);
    writeOrderInto(asm, select, env, orderRegs);
    asm.emit(Opcode.ResultRow, start, width, 0);
    if (!primary) {
      asm.emit(Opcode.NextIdx, CURSOR, loop, 0);
    }
    emitEpilogue(asm);
    int halt = asm.emit(Opcode.Halt, 0, 0, 0);
    asm.patch(seek, halt);
    for (int jump : fails) {
      asm.patch(jump, halt);
    }
    return asm.build(outputNames(select, env.sources, env.sql));
  }

  private static List<Integer> boxColumns(int[] columns) {
    List<Integer> boxed = new ArrayList<>(columns.length);
    for (int column : columns) {
      boxed.add(column);
    }
    return List.copyOf(boxed);
  }

  private Program compileAggregate(Select select, MemoryCatalog catalog, String sql) {
    if (!select.orderBy().isEmpty() && select.groupBy().isEmpty()) {
      throw new SqlException("ORDER BY is not supported on aggregates: " + SqlText.snippet(sql));
    }
    Table table = requireTable(catalog, select.table(), sql);
    requireAggregated(select, sql);
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
        case CountItem count -> initCount(asm, acc, i, count.distinct());
        case MaxItem ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Null, acc[i], 0, 0);
        }
        default -> {
        }
      }
    }
    bindSumAccumulators(asm, select);
    boolean grouped = !select.groupBy().isEmpty();
    if (grouped) {
      prepareGroupRegisters(asm, select);
      emitGroupTemplate(asm, acc);
    }
    Env env = envFor(table, select.alias(), sql);
    Access access = seekAccess(table, select.where(), env);
    asm.emit(Opcode.OpenRead, CURSOR, asm.constant(table.name()), 0);
    int rewind = -1;
    int seekMiss = -1;
    int loop;
    List<Integer> fails = new ArrayList<>();
    if (access != null) {
      if (!access.primary()) {
        table.ensureIndex(access.columns());
      }
      int keyStart = asm.allocRange(access.keys().length);
      for (int i = 0; i < access.keys().length; i++) {
        emitExprInto(asm, keyStart + i, access.keys()[i], env);
      }
      if (access.primary()) {
        seekMiss = asm.emit(Opcode.SeekPk, CURSOR, -1, keyStart);
      } else {
        seekMiss =
            asm.emit(
                Opcode.SeekIdx,
                CURSOR,
                -1,
                keyStart,
                asm.constant(boxColumns(access.columns())));
      }
      loop = asm.here();
      if (select.where() != null) {
        List<Integer> trues = new ArrayList<>();
        emitBool(asm, select.where(), env, trues, fails);
        int body = asm.here();
        for (int jump : trues) {
          asm.patch(jump, body);
        }
      }
    } else {
      rewind = asm.emit(Opcode.Rewind, CURSOR, -1, 0);
      loop = asm.here();
      if (select.where() != null) {
        List<Integer> trues = new ArrayList<>();
        emitBool(asm, select.where(), env, trues, fails);
        int body = asm.here();
        for (int jump : trues) {
          asm.patch(jump, body);
        }
      }
    }
    if (grouped) {
      writeGroupInto(asm, select, env);
      asm.emit(Opcode.GroupLoad, 0, 0, 0);
    }
    for (int i = 0; i < select.items().size(); i++) {
      switch (select.items().get(i)) {
        case CountStar ignored -> asm.emit(Opcode.AggCount, acc[i], 0, 0);
        case CountItem count -> emitCount(asm, acc[i], count, env);
        case MaxItem max -> {
          int value = emitExpr(asm, new ColumnRef(max.table(), max.column()), env);
          asm.emit(Opcode.AggMax, acc[i], value, 0);
        }
        default -> {
        }
      }
    }
    emitSumAccumulators(asm, env);
    if (grouped) {
      asm.emit(Opcode.GroupStore, 0, 0, 0);
    }
    int next;
    if (access != null && !access.primary()) {
      next = asm.emit(Opcode.NextIdx, CURSOR, loop, 0);
    } else if (access != null) {
      next = asm.emit(Opcode.Goto, 0, -1, 0);
    } else {
      next = asm.emit(Opcode.Next, CURSOR, loop, 0);
    }
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int output = asm.here();
    if (access != null && access.primary()) {
      asm.patch(next, output);
    }
    if (rewind >= 0) {
      asm.patch(rewind, output);
    }
    if (seekMiss >= 0) {
      asm.patch(seekMiss, output);
    }
    if (grouped) {
      emitGroupedTail(asm, select, acc);
      return asm.build(outputNames(select, env.sources, sql));
    }
    List<Integer> havingFails = new ArrayList<>();
    if (select.having() != null) {
      List<Integer> havingTrues = new ArrayList<>();
      emitBool(asm, select.having(), env, havingTrues, havingFails);
      int body = asm.here();
      for (int jump : havingTrues) {
        asm.patch(jump, body);
      }
    }
    int start = asm.allocRange(select.items().size());
    for (int i = 0; i < select.items().size(); i++) {
      SelectItem item = select.items().get(i);
      storeAggregate(asm, item, acc[i], start + i);
      if (acc[i] < 0 && item instanceof ExpressionItem expression) {
        emitExprInto(asm, start + i, expression.expr(), env);
      }
    }
    asm.emit(Opcode.ResultRow, start, select.items().size(), 0);
    emitEpilogue(asm);
    int halt = asm.emit(Opcode.Halt, 0, 0, 0);
    for (int jump : havingFails) {
      asm.patch(jump, halt);
    }
    return asm.build(outputNames(select, env.sources, sql));
  }

  private Program compileJoin(Select select, MemoryCatalog catalog, String sql) {
    if (isAggregate(select)) {
      return compileJoinAggregate(select, catalog, sql);
    }
    Select planned = planInnerJoins(select, catalog, sql);
    Assembler asm = new Assembler();
    List<Source> sources = sourcesOf(planned, catalog, sql, asm);
    prepareLimit(asm, planned, sql);
    List<Integer> orderRegs = prepareOrderRegisters(asm, planned);
    prepareGroupRegisters(asm, planned);
    int zero = -1;
    for (Join join : planned.joins()) {
      if (join.left()) {
        zero = asm.alloc();
        asm.emit(Opcode.Integer, zero, 0, 0);
        break;
      }
    }
    Env env = new Env(sources, new boolean[sources.size()], 0, -1, null, sql);
    emitLevel(asm, planned, env, 0, zero, orderRegs);
    return asm.build(outputNames(planned, sources, sql));
  }

  private Program compileJoinAggregate(Select select, MemoryCatalog catalog, String sql) {
    if (!select.orderBy().isEmpty() && select.groupBy().isEmpty()) {
      throw new SqlException("ORDER BY is not supported on aggregates: " + SqlText.snippet(sql));
    }
    requireAggregated(select, sql);
    Select planned = planInnerJoins(select, catalog, sql);
    List<Source> sources = sourcesOf(planned, catalog, sql, null);
    Assembler asm = new Assembler();
    prepareLimit(asm, planned, sql);
    int[] acc = new int[planned.items().size()];
    Arrays.fill(acc, -1);
    for (int i = 0; i < planned.items().size(); i++) {
      switch (planned.items().get(i)) {
        case CountStar ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Integer, acc[i], 0, 0);
        }
        case CountItem count -> initCount(asm, acc, i, count.distinct());
        case MaxItem ignored -> {
          acc[i] = asm.alloc();
          asm.emit(Opcode.Null, acc[i], 0, 0);
        }
        default -> {
        }
      }
    }
    bindSumAccumulators(asm, planned);
    boolean grouped = !planned.groupBy().isEmpty();
    if (grouped) {
      prepareGroupRegisters(asm, planned);
      emitGroupTemplate(asm, acc);
    }
    int zero = -1;
    for (Join join : planned.joins()) {
      if (join.left()) {
        zero = asm.alloc();
        asm.emit(Opcode.Integer, zero, 0, 0);
        break;
      }
    }
    Env env = new Env(sources, new boolean[sources.size()], 0, -1, null, sql);
    emitAggregateLevel(asm, planned, env, 0, zero, acc);
    if (grouped) {
      emitGroupedTail(asm, planned, acc);
      return asm.build(outputNames(planned, sources, sql));
    }
    List<Integer> havingFails = new ArrayList<>();
    if (planned.having() != null) {
      List<Integer> havingTrues = new ArrayList<>();
      emitBool(asm, planned.having(), env, havingTrues, havingFails);
      int body = asm.here();
      for (int jump : havingTrues) {
        asm.patch(jump, body);
      }
    }
    int start = asm.allocRange(planned.items().size());
    for (int i = 0; i < planned.items().size(); i++) {
      SelectItem item = planned.items().get(i);
      storeAggregate(asm, item, acc[i], start + i);
      if (acc[i] < 0 && item instanceof ExpressionItem expression) {
        emitExprInto(asm, start + i, expression.expr(), env);
      }
    }
    asm.emit(Opcode.ResultRow, start, planned.items().size(), 0);
    emitEpilogue(asm);
    int halt = asm.emit(Opcode.Halt, 0, 0, 0);
    for (int jump : havingFails) {
      asm.patch(jump, halt);
    }
    return asm.build(outputNames(planned, sources, sql));
  }

  private List<Source> sourcesOf(Select select, MemoryCatalog catalog, String sql, Assembler asm) {
    List<Source> sources = new ArrayList<>();
    sources.add(new Source(0, requireTable(catalog, select.table(), sql), select.alias()));
    for (int i = 0; i < select.joins().size(); i++) {
      Join join = select.joins().get(i);
      if (join.apply() != null) {
        if (asm == null) {
          throw new SqlException("APPLY is not supported with aggregates: " + SqlText.snippet(sql));
        }
        String[] names = new String[join.apply().items().size()];
        for (int c = 0; c < names.length; c++) {
          names[c] = Labels.item(join.apply().items().get(c));
        }
        int[] regs = new int[names.length];
        for (int c = 0; c < regs.length; c++) {
          regs[c] = asm.alloc();
        }
        if (join.apply().table() == null) {
          throw new SqlException("APPLY subquery needs a table: " + SqlText.snippet(sql));
        }
        sources.add(
            new Source(
                i + 1,
                requireTable(catalog, join.apply().table(), sql),
                join.alias(),
                names,
                regs));
      } else {
        sources.add(new Source(i + 1, requireTable(catalog, join.table(), sql), join.alias()));
      }
    }
    return sources;
  }

  /**
   * Flatten INNER/CROSS ON into WHERE, then put the most selective table first so later
   * tables can SeekPk / SeekIdx instead of nested full scans. LEFT JOIN is left alone.
   */
  private Select planInnerJoins(Select select, MemoryCatalog catalog, String sql) {
    if (select.joins().isEmpty()) {
      return select;
    }
    for (Join join : select.joins()) {
      if (join.left() || join.apply() != null) {
        return select;
      }
    }
    Bool where = select.where();
    List<Join> crosses = new ArrayList<>(select.joins().size());
    for (Join join : select.joins()) {
      if (join.on() != null) {
        where = combineAnd(where, join.on());
      }
      crosses.add(new Join(false, join.table(), join.alias(), null));
    }
    Select flat =
        new Select(
            select.distinct(),
            select.items(),
            select.table(),
            select.fromSelect(),
            select.alias(),
            crosses,
            where,
            select.orderBy(),
            select.limit(),
            select.offset(),
            select.groupBy());
    return reorderCrossJoin(flat, catalog, sql);
  }

  private Select reorderCrossJoin(Select select, MemoryCatalog catalog, String sql) {
    List<Source> sources = sourcesOf(select, catalog, sql, null);
    if (sources.size() < 2 || select.where() == null || !isAndChain(select.where())) {
      return select;
    }
    Env env = new Env(sources, null, 0, -1, null, sql);
    int[] scores = new int[sources.size()];
    for (Predicate predicate : andPredicates(select.where())) {
      if (!(predicate instanceof Compare compare) || compare.op() != CompareOp.EQ) {
        continue;
      }
      int left = exprMaxSource(compare.left(), env);
      int right = exprMaxSource(compare.right(), env);
      if (left >= 0 && right < 0) {
        scores[left]++;
      } else if (right >= 0 && left < 0) {
        scores[right]++;
      }
    }
    int best = 0;
    for (int i = 1; i < scores.length; i++) {
      if (scores[i] > scores[best]) {
        best = i;
      } else if (scores[i] == scores[best]
          && sources.get(i).table().rowCount() < sources.get(best).table().rowCount()) {
        best = i;
      }
    }
    if (best == 0) {
      return select;
    }
    List<Source> ordered = new ArrayList<>(sources.size());
    ordered.add(sources.get(best));
    for (int i = 0; i < sources.size(); i++) {
      if (i != best) {
        ordered.add(sources.get(i));
      }
    }
    Source root = ordered.get(0);
    List<Join> joins = new ArrayList<>(ordered.size() - 1);
    for (int i = 1; i < ordered.size(); i++) {
      Source source = ordered.get(i);
      joins.add(new Join(false, source.table().name(), source.alias(), null));
    }
    return new Select(
        select.distinct(),
        select.items(),
        root.table().name(),
        select.fromSelect(),
        root.alias(),
        joins,
        select.where(),
        select.orderBy(),
        select.limit(),
        select.offset(),
        select.groupBy());
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
    if (level > 0 && select.joins().get(level - 1).apply() != null) {
      throw new SqlException("APPLY is not supported with aggregates: " + SqlText.snippet(env.sql));
    }
    asm.emit(Opcode.OpenRead, source.cursor(), asm.constant(source.table().name()), 0);
    if (level == 0) {
      if (emitOuterSeek(asm, select, env, source, zero, null, acc, false)) {
        return;
      }
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      List<Integer> filterFails = new ArrayList<>();
      emitReadyFilters(asm, select.where(), env, 0, filterFails);
      emitAggregateLevel(asm, select, env, 1, zero, acc);
      int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
      for (int jump : filterFails) {
        asm.patch(jump, next);
      }
      int after = asm.here();
      asm.patch(rewind, after);
      return;
    }
    Join join = select.joins().get(level - 1);
    if (!join.left()) {
      if (emitInnerJoinSeek(asm, select, env, level, zero, null, acc, source, join)) {
        return;
      }
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      List<Integer> filterFails = new ArrayList<>();
      if (join.on() == null) {
        emitReadyFilters(asm, select.where(), env, level, filterFails);
        emitAggregateLevel(asm, select, env, level + 1, zero, acc);
        int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
        for (int jump : filterFails) {
          asm.patch(jump, next);
        }
        asm.patch(rewind, asm.here());
        return;
      }
      List<Integer> trues = new ArrayList<>();
      List<Integer> fails = new ArrayList<>();
      emitJoinCondition(asm, join.on(), env, trues, fails);
      int matched = asm.here();
      for (int jump : trues) {
        asm.patch(jump, matched);
      }
      emitReadyFilters(asm, select.where(), env, level, filterFails);
      emitAggregateLevel(asm, select, env, level + 1, zero, acc);
      int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
      for (int jump : fails) {
        asm.patch(jump, next);
      }
      for (int jump : filterFails) {
        asm.patch(jump, next);
      }
      asm.patch(rewind, asm.here());
      return;
    }
    if (emitLeftJoinSeek(asm, select, env, level, zero, null, acc, source, join)) {
      return;
    }
    int flag = asm.alloc();
    asm.emit(Opcode.Integer, flag, 0, 0);
    int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
    int loop = asm.here();
    List<Integer> trues = new ArrayList<>();
    List<Integer> fails = new ArrayList<>();
    emitJoinCondition(asm, join.on(), env, trues, fails);
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
    boolean grouped = !select.groupBy().isEmpty();
    if (grouped) {
      writeGroupInto(asm, select, env);
      asm.emit(Opcode.GroupLoad, 0, 0, 0);
    }
    for (int i = 0; i < select.items().size(); i++) {
      switch (select.items().get(i)) {
        case CountStar ignored -> asm.emit(Opcode.AggCount, acc[i], 0, 0);
        case CountItem count -> emitCount(asm, acc[i], count, env);
        case MaxItem max -> {
          int value = emitExpr(asm, new ColumnRef(max.table(), max.column()), env);
          asm.emit(Opcode.AggMax, acc[i], value, 0);
        }
        default -> {
        }
      }
    }
    emitSumAccumulators(asm, env);
    if (grouped) {
      asm.emit(Opcode.GroupStore, 0, 0, 0);
    }
    int skip = asm.here();
    for (int jump : fails) {
      asm.patch(jump, skip);
    }
  }

  private void emitApply(
      Assembler asm,
      Select select,
      Env env,
      int level,
      int zero,
      List<Integer> orderRegs,
      Join join) {
    Select sub = join.apply();
    if (sub == null || sub.table() == null || !sub.joins().isEmpty() || sub.fromSelect() != null) {
      throw new SqlException("APPLY subquery must read one table: " + SqlText.snippet(env.sql));
    }
    if (!sub.groupBy().isEmpty() || isAggregate(sub)) {
      throw new SqlException("APPLY subquery cannot aggregate: " + SqlText.snippet(env.sql));
    }
    for (SelectItem item : sub.items()) {
      if (!(item instanceof ExpressionItem)) {
        throw new SqlException("APPLY subquery needs named columns: " + SqlText.snippet(env.sql));
      }
    }
    boolean topOne =
        sub.limit() instanceof LiteralExpr literal
            && literal.literal() instanceof NumberLiteral number
            && number.value() == 1;
    if (sub.limit() != null && !topOne) {
      throw new SqlException(
          "APPLY supports TOP 1 or an unlimited subquery: " + SqlText.snippet(env.sql));
    }
    Source slot = env.sources.get(level);
    int[] regs = slot.applyRegs();
    for (int reg : regs) {
      asm.emit(Opcode.Null, reg, 0, 0);
    }
    int matched = asm.alloc();
    asm.emit(Opcode.Integer, matched, 0, 0);
    List<Source> innerSources = new ArrayList<>(env.sources.subList(0, level));
    innerSources.add(new Source(slot.cursor(), slot.table(), sub.alias()));
    Env inner = new Env(innerSources, null, level, env.excludedBase, env.excludedTable, env.sql);
    if (topOne) {
      emitApplyTop1(asm, sub, inner, slot.cursor(), regs, matched);
      finishApply(asm, select, env, level, zero, orderRegs, join, matched);
      return;
    }
    emitApplyAll(asm, select, env, level, zero, orderRegs, join, sub, inner, slot, regs, matched);
  }

  private void emitApplyTop1(
      Assembler asm, Select sub, Env inner, int cursor, int[] regs, int matched) {
    Table table = inner.sources.get(inner.sources.size() - 1).table();
    asm.emit(Opcode.OpenRead, cursor, asm.constant(table.name()), 0);
    int skipZero = emitSkipWhenLimitZero(asm, sub.limit(), inner);
    if (sub.orderBy().isEmpty()) {
      int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
      int loop = asm.here();
      List<Integer> fails = new ArrayList<>();
      if (sub.where() != null) {
        List<Integer> trues = new ArrayList<>();
        emitBool(asm, sub.where(), inner, trues, fails);
        int body = asm.here();
        for (int jump : trues) {
          asm.patch(jump, body);
        }
      }
      projectApply(asm, sub, inner, regs);
      asm.emit(Opcode.Integer, matched, 1, 0);
      int done = asm.emit(Opcode.Goto, 0, -1, 0);
      int next = asm.emit(Opcode.Next, cursor, loop, 0);
      for (int jump : fails) {
        asm.patch(jump, next);
      }
      int after = asm.here();
      asm.patch(rewind, after);
      asm.patch(done, after);
      if (skipZero >= 0) {
        asm.patch(skipZero, after);
      }
      return;
    }
    int seen = asm.alloc();
    asm.emit(Opcode.Null, seen, 0, 0);
    int keyCount = sub.orderBy().size();
    int[] best = new int[keyCount];
    int[] current = new int[keyCount];
    for (int i = 0; i < keyCount; i++) {
      best[i] = asm.alloc();
      current[i] = asm.alloc();
    }
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (sub.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, sub.where(), inner, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    for (int i = 0; i < keyCount; i++) {
      emitExprInto(asm, current[i], sub.orderBy().get(i).expr(), inner);
    }
    List<Integer> takeJumps = new ArrayList<>();
    List<Integer> skipJumps = new ArrayList<>();
    takeJumps.add(asm.emit(Opcode.IfNull, seen, -1, 0));
    for (int i = 0; i < keyCount; i++) {
      boolean descending = sub.orderBy().get(i).descending();
      int cur = current[i];
      int old = best[i];
      int curIsNull = asm.emit(Opcode.IfNull, cur, -1, 0);
      int oldIsNull = asm.emit(Opcode.IfNull, old, -1, 0);
      if (descending) {
        takeJumps.add(asm.emit(Opcode.Gt, cur, -1, old));
        skipJumps.add(asm.emit(Opcode.Lt, cur, -1, old));
      } else {
        takeJumps.add(asm.emit(Opcode.Lt, cur, -1, old));
        skipJumps.add(asm.emit(Opcode.Gt, cur, -1, old));
      }
      int equalKeys = asm.emit(Opcode.Goto, 0, -1, 0);
      asm.patch(curIsNull, asm.here());
      int bothNull = asm.emit(Opcode.IfNull, old, -1, 0);
      if (descending) {
        skipJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      } else {
        takeJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      }
      asm.patch(oldIsNull, asm.here());
      if (descending) {
        takeJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      } else {
        skipJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      }
      int keyDone = asm.here();
      asm.patch(equalKeys, keyDone);
      asm.patch(bothNull, keyDone);
    }
    int keep = asm.emit(Opcode.Goto, 0, -1, 0);
    int take = asm.here();
    for (int jump : takeJumps) {
      asm.patch(jump, take);
    }
    asm.emit(Opcode.Integer, seen, 1, 0);
    for (int i = 0; i < keyCount; i++) {
      asm.emit(Opcode.IfNullValue, current[i], current[i], best[i]);
    }
    projectApply(asm, sub, inner, regs);
    asm.emit(Opcode.Integer, matched, 1, 0);
    int stored = asm.emit(Opcode.Goto, 0, -1, 0);
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    asm.patch(keep, next);
    asm.patch(stored, next);
    for (int jump : skipJumps) {
      asm.patch(jump, next);
    }
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int after = asm.here();
    asm.patch(rewind, after);
    if (skipZero >= 0) {
      asm.patch(skipZero, after);
    }
  }

  private void emitApplyAll(
      Assembler asm,
      Select select,
      Env env,
      int level,
      int zero,
      List<Integer> orderRegs,
      Join join,
      Select sub,
      Env inner,
      Source slot,
      int[] regs,
      int matched) {
    int cursor = slot.cursor();
    asm.emit(Opcode.OpenRead, cursor, asm.constant(slot.table().name()), 0);
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (sub.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, sub.where(), inner, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    projectApply(asm, sub, inner, regs);
    asm.emit(Opcode.Integer, matched, 1, 0);
    emitLevel(asm, select, env, level + 1, zero, orderRegs);
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    asm.patch(rewind, asm.here());
    if (!join.left()) {
      return;
    }
    int zeroReg = zeroRegister(asm, zero);
    int miss = asm.emit(Opcode.Eq, matched, -1, zeroReg);
    int done = asm.emit(Opcode.Goto, 0, -1, 0);
    asm.patch(miss, asm.here());
    emitLevel(asm, select, env.withNulled(pad(env, level)), level + 1, zero, orderRegs);
    asm.patch(done, asm.here());
  }

  private void finishApply(
      Assembler asm,
      Select select,
      Env env,
      int level,
      int zero,
      List<Integer> orderRegs,
      Join join,
      int matched) {
    int miss = asm.emit(Opcode.Eq, matched, -1, zeroRegister(asm, zero));
    emitLevel(asm, select, env, level + 1, zero, orderRegs);
    int done = asm.emit(Opcode.Goto, 0, -1, 0);
    asm.patch(miss, asm.here());
    if (join.left()) {
      emitLevel(asm, select, env.withNulled(pad(env, level)), level + 1, zero, orderRegs);
    }
    asm.patch(done, asm.here());
  }

  private int zeroRegister(Assembler asm, int zero) {
    if (zero >= 0) {
      return zero;
    }
    int allocated = asm.alloc();
    asm.emit(Opcode.Integer, allocated, 0, 0);
    return allocated;
  }

  private boolean[] pad(Env env, int level) {
    boolean[] nulled = env.nulled == null ? new boolean[env.sources.size()] : env.nulled.clone();
    nulled[level] = true;
    return nulled;
  }

  private void projectApply(Assembler asm, Select sub, Env inner, int[] regs) {
    for (int i = 0; i < regs.length; i++) {
      ExpressionItem item = (ExpressionItem) sub.items().get(i);
      emitExprInto(asm, regs[i], item.expr(), inner);
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
    if (level > 0) {
      Join join = select.joins().get(level - 1);
      if (join.apply() != null) {
        emitApply(asm, select, env, level, zero, orderRegs, join);
        return;
      }
    }
    asm.emit(Opcode.OpenRead, source.cursor(), asm.constant(source.table().name()), 0);
    if (level == 0) {
      if (emitOuterSeek(
          asm, select, env, source, zero, orderRegs, null, true)) {
        return;
      }
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      List<Integer> filterFails = new ArrayList<>();
      emitReadyFilters(asm, select.where(), env, 0, filterFails);
      emitLevel(asm, select, env, 1, zero, orderRegs);
      int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
      for (int jump : filterFails) {
        asm.patch(jump, next);
      }
      emitEpilogue(asm);
      int halt = asm.emit(Opcode.Halt, 0, 0, 0);
      asm.patch(rewind, halt);
      return;
    }
    Join join = select.joins().get(level - 1);
    if (!join.left()) {
      if (emitInnerJoinSeek(asm, select, env, level, zero, orderRegs, null, source, join)) {
        return;
      }
      int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
      int loop = asm.here();
      List<Integer> filterFails = new ArrayList<>();
      if (join.on() == null) {
        emitReadyFilters(asm, select.where(), env, level, filterFails);
        emitLevel(asm, select, env, level + 1, zero, orderRegs);
        int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
        for (int jump : filterFails) {
          asm.patch(jump, next);
        }
        asm.patch(rewind, asm.here());
        return;
      }
      List<Integer> trues = new ArrayList<>();
      List<Integer> fails = new ArrayList<>();
      emitJoinCondition(asm, join.on(), env, trues, fails);
      int matched = asm.here();
      for (int jump : trues) {
        asm.patch(jump, matched);
      }
      emitReadyFilters(asm, select.where(), env, level, filterFails);
      emitLevel(asm, select, env, level + 1, zero, orderRegs);
      int next = asm.emit(Opcode.Next, source.cursor(), loop, 0);
      for (int jump : fails) {
        asm.patch(jump, next);
      }
      for (int jump : filterFails) {
        asm.patch(jump, next);
      }
      asm.patch(rewind, asm.here());
      return;
    }
    if (emitLeftJoinSeek(asm, select, env, level, zero, orderRegs, null, source, join)) {
      return;
    }
    int flag = asm.alloc();
    asm.emit(Opcode.Integer, flag, 0, 0);
    int rewind = asm.emit(Opcode.Rewind, source.cursor(), -1, 0);
    int loop = asm.here();
    List<Integer> trues = new ArrayList<>();
    List<Integer> fails = new ArrayList<>();
    emitJoinCondition(asm, join.on(), env, trues, fails);
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
    writeGroupInto(asm, select, env);
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
      if (!epilogue && isSimpleAndChain(where)) {
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
    if (!asm.groupRegisters.isEmpty()) {
      asm.emit(Opcode.Group, 0, 0, 0);
    }
    if (!asm.orderKeys.isEmpty()) {
      asm.emit(Opcode.Sort, 0, 0, 0);
    }
    if (asm.limitRegister >= 0 || asm.offsetRegister >= 0) {
      asm.emit(Opcode.Limit, 0, 0, 0);
    }
  }

  private void emitJoinCondition(
      Assembler asm, Bool on, Env env, List<Integer> onTrue, List<Integer> onFalse) {
    if (on == null) {
      onTrue.add(asm.emit(Opcode.Goto, 0, -1, 0));
      return;
    }
    emitBool(asm, on, env, onTrue, onFalse);
  }

  /**
   * When the inner table's primary key or secondary index is fully bound by equality
   * predicates from earlier sources / parameters, seek instead of scanning.
   */
  private boolean emitInnerJoinSeek(
      Assembler asm,
      Select select,
      Env env,
      int level,
      int zero,
      List<Integer> orderRegs,
      int[] acc,
      Source source,
      Join join) {
    Access access = joinSeekAccess(source, level, join.on(), select.where(), env);
    if (access == null) {
      return false;
    }
    prepareSeekIndex(source, access);
    int keyStart = asm.allocRange(access.keys().length);
    for (int i = 0; i < access.keys().length; i++) {
      emitExprInto(asm, keyStart + i, access.keys()[i], env);
    }
    int miss;
    if (access.primary()) {
      miss = asm.emit(Opcode.SeekPk, source.cursor(), -1, keyStart);
    } else {
      miss =
          asm.emit(
              Opcode.SeekIdx,
              source.cursor(),
              -1,
              keyStart,
              asm.constant(boxColumns(access.columns())));
    }
    int loop = asm.here();
    List<Integer> filterFails = new ArrayList<>();
    emitReadyFilters(asm, select.where(), env, level, filterFails);
    if (join.on() != null) {
      List<Integer> trues = new ArrayList<>();
      List<Integer> onFails = new ArrayList<>();
      emitJoinCondition(asm, join.on(), env, trues, onFails);
      int matched = asm.here();
      for (int jump : trues) {
        asm.patch(jump, matched);
      }
      filterFails.addAll(onFails);
    }
    if (acc != null) {
      emitAggregateLevel(asm, select, env, level + 1, zero, acc);
    } else {
      emitLevel(asm, select, env, level + 1, zero, orderRegs);
    }
    if (!access.primary()) {
      asm.emit(Opcode.NextIdx, source.cursor(), loop, 0);
    }
    int after = asm.here();
    asm.patch(miss, after);
    for (int jump : filterFails) {
      asm.patch(jump, after);
    }
    return true;
  }

  /**
   * LEFT JOIN seek: probe ON equalities via PK/index (auto-index if needed); on miss emit one
   * null-padded row.
   */
  private boolean emitLeftJoinSeek(
      Assembler asm,
      Select select,
      Env env,
      int level,
      int zero,
      List<Integer> orderRegs,
      int[] acc,
      Source source,
      Join join) {
    // Only ON predicates define the join probe; WHERE applies after the join.
    Access access = joinSeekAccess(source, level, join.on(), null, env);
    if (access == null) {
      return false;
    }
    prepareSeekIndex(source, access);
    int flag = asm.alloc();
    asm.emit(Opcode.Integer, flag, 0, 0);
    int keyStart = asm.allocRange(access.keys().length);
    for (int i = 0; i < access.keys().length; i++) {
      emitExprInto(asm, keyStart + i, access.keys()[i], env);
    }
    int miss;
    if (access.primary()) {
      miss = asm.emit(Opcode.SeekPk, source.cursor(), -1, keyStart);
    } else {
      miss =
          asm.emit(
              Opcode.SeekIdx,
              source.cursor(),
              -1,
              keyStart,
              asm.constant(boxColumns(access.columns())));
    }
    int loop = asm.here();
    List<Integer> onFails = new ArrayList<>();
    if (join.on() != null) {
      List<Integer> trues = new ArrayList<>();
      emitJoinCondition(asm, join.on(), env, trues, onFails);
      int matched = asm.here();
      for (int jump : trues) {
        asm.patch(jump, matched);
      }
    }
    asm.emit(Opcode.Integer, flag, 1, 0);
    if (acc != null) {
      emitAggregateLevel(asm, select, env, level + 1, zero, acc);
    } else {
      emitLevel(asm, select, env, level + 1, zero, orderRegs);
    }
    int advance;
    if (!access.primary()) {
      advance = asm.emit(Opcode.NextIdx, source.cursor(), loop, 0);
    } else {
      advance = asm.here();
    }
    for (int jump : onFails) {
      asm.patch(jump, advance);
    }
    int check = asm.emit(Opcode.Eq, flag, -1, zero);
    int skip = asm.emit(Opcode.Goto, 0, -1, 0);
    asm.patch(miss, check);
    asm.patch(check, asm.here());
    boolean[] pad = env.nulled.clone();
    pad[level] = true;
    if (acc != null) {
      emitAggregateLevel(asm, select, env.withNulled(pad), level + 1, zero, acc);
    } else {
      emitLevel(asm, select, env.withNulled(pad), level + 1, zero, orderRegs);
    }
    asm.patch(skip, asm.here());
    return true;
  }

  private void prepareSeekIndex(Source source, Access access) {
    if (!access.primary()) {
      source.table().ensureIndex(access.columns());
    }
  }

  /** Emit WHERE conjuncts that only reference sources {@code 0..maxLevel}. */
  private void emitReadyFilters(
      Assembler asm, Bool where, Env env, int maxLevel, List<Integer> fails) {
    if (where == null || !isAndChain(where)) {
      return;
    }
    for (Predicate predicate : andPredicates(where)) {
      if (predicateMaxSource(predicate, env) > maxLevel) {
        continue;
      }
      List<Integer> trues = new ArrayList<>();
      List<Integer> localFails = new ArrayList<>();
      emitBool(asm, new Pred(predicate), env, trues, localFails);
      int cont = asm.here();
      for (int jump : trues) {
        asm.patch(jump, cont);
      }
      fails.addAll(localFails);
    }
  }

  private Access joinSeekAccess(Source inner, int innerIndex, Bool on, Bool where, Env env) {
    Bool combined = combineAnd(on, where);
    if (combined == null || !isAndChain(combined)) {
      return null;
    }
    Map<Integer, Expr> bound = new HashMap<>();
    for (Predicate predicate : andPredicates(combined)) {
      if (!(predicate instanceof Compare compare) || compare.op() != CompareOp.EQ) {
        continue;
      }
      ColumnRef column = null;
      Expr value = null;
      if (compare.left() instanceof ColumnRef left && columnSource(left, env) == innerIndex) {
        column = left;
        value = compare.right();
      } else if (compare.right() instanceof ColumnRef right && columnSource(right, env) == innerIndex) {
        column = right;
        value = compare.left();
      }
      if (column == null || exprMaxSource(value, env) >= innerIndex) {
        continue;
      }
      int index = inner.table().columnIndex(column.name());
      if (index < 0 || bound.containsKey(index)) {
        continue;
      }
      bound.put(index, value);
    }
    int[] primaryKey = inner.table().primaryKey();
    Expr[] primary = bindColumns(primaryKey, bound);
    if (primary != null) {
      return new Access(primaryKey, primary, true);
    }
    Access bestUnique = bestCoveringAccess(inner.table().uniqueIndexColumns(), bound, false);
    if (bestUnique != null && bestUnique.columns().length >= bound.size()) {
      return bestUnique;
    }
    Access bestSecondary = bestCoveringAccess(inner.table().secondaryIndexColumns(), bound, false);
    if (bestSecondary != null && bestSecondary.columns().length >= bound.size()) {
      return bestSecondary;
    }
    if (bound.size() >= 2) {
      int[] columns = bound.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
      Expr[] keys = new Expr[columns.length];
      for (int i = 0; i < columns.length; i++) {
        keys[i] = bound.get(columns[i]);
      }
      return new Access(columns, keys, false);
    }
    if (!bound.isEmpty()) {
      com.myano.jsqlite.store.RatioAutoIndex.ensure(inner.table());
      bestSecondary = bestCoveringAccess(inner.table().secondaryIndexColumns(), bound, false);
      if (bestSecondary != null) {
        return bestSecondary;
      }
      int only = bound.keySet().iterator().next();
      if (inner.table().isRatioWinner(only)) {
        return new Access(new int[] {only}, new Expr[] {bound.get(only)}, false);
      }
      int[] columns = bound.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
      Expr[] keys = new Expr[columns.length];
      for (int i = 0; i < columns.length; i++) {
        keys[i] = bound.get(columns[i]);
      }
      return new Access(columns, keys, false);
    }
    if (bestUnique != null) {
      return bestUnique;
    }
    if (bestSecondary != null) {
      return bestSecondary;
    }
    return null;
  }

  private Bool combineAnd(Bool left, Bool right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return new And(left, right);
  }

  private int columnSource(ColumnRef column, Env env) {
    return resolve(column, env).source;
  }

  private int exprMaxSource(Expr expr, Env env) {
    if (expr == null) {
      return -1;
    }
    return switch (expr) {
      case ColumnRef column -> columnSource(column, env);
      case Concat concat -> Math.max(exprMaxSource(concat.left(), env), exprMaxSource(concat.right(), env));
      case IfNullExpr ifNull ->
          Math.max(exprMaxSource(ifNull.value(), env), exprMaxSource(ifNull.fallback(), env));
      case SumExpr sum -> exprMaxSource(sum.expr(), env);
      case CaseExpr cse -> {
        int max = exprMaxSource(cse.operand(), env);
        max = Math.max(max, exprMaxSource(cse.elseExpr(), env));
        for (CaseArm arm : cse.arms()) {
          if (arm.whenCond() != null) {
            max = Math.max(max, boolMaxSource(arm.whenCond(), env));
          }
          max = Math.max(max, exprMaxSource(arm.whenValue(), env));
          max = Math.max(max, exprMaxSource(arm.thenExpr(), env));
        }
        // Evaluate after outer sources are open (may contain scalar subqueries).
        if (!env.sources.isEmpty()) {
          max = Math.max(max, env.sources.size() - 1);
        }
        yield max;
      }
      case ScalarSubquery ignored -> {
        // Correlate after all current FROM sources are open.
        yield env.sources.isEmpty() ? -1 : env.sources.size() - 1;
      }
      case CastExpr cast -> exprMaxSource(cast.value(), env);
      case AbsExpr abs -> exprMaxSource(abs.value(), env);
      default -> -1;
    };
  }

  private int boolMaxSource(Bool condition, Env env) {
    if (condition == null) {
      return -1;
    }
    return switch (condition) {
      case And and -> Math.max(boolMaxSource(and.left(), env), boolMaxSource(and.right(), env));
      case Or or -> Math.max(boolMaxSource(or.left(), env), boolMaxSource(or.right(), env));
      case Not not -> boolMaxSource(not.condition(), env);
      case Pred pred -> predicateMaxSource(pred.predicate(), env);
    };
  }

  private int predicateMaxSource(Predicate predicate, Env env) {
    return switch (predicate) {
      case Compare compare -> Math.max(exprMaxSource(compare.left(), env), exprMaxSource(compare.right(), env));
      case IsNull isNull -> exprMaxSource(isNull.expr(), env);
      case Like like -> Math.max(exprMaxSource(like.left(), env), exprMaxSource(like.right(), env));
      case InSubquery in -> {
        // Defer until all outer sources of the current query are open.
        int max = exprMaxSource(in.left(), env);
        if (!env.sources.isEmpty()) {
          max = Math.max(max, env.sources.size() - 1);
        }
        yield max;
      }
      case Exists ignored -> env.sources.isEmpty() ? -1 : env.sources.size() - 1;
    };
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
      case Not not -> emitBool(asm, not.condition(), env, onFalse, onTrue);
      case Pred pred -> {
        if (pred.predicate() instanceof InSubquery in) {
          emitInSubquery(asm, in, env, onTrue, onFalse);
        } else if (pred.predicate() instanceof Exists exists) {
          emitExists(asm, exists, env, onTrue, onFalse);
        } else {
          onTrue.add(emitPredicateJump(asm, pred.predicate(), env));
          onFalse.add(asm.emit(Opcode.Goto, 0, -1, 0));
        }
      }
    }
  }

  /**
   * Compile {@code left [NOT] IN (SELECT col FROM t [WHERE ...])} as a nested scan.
   * Uses a free cursor so the outer scan can stay open (supports correlation).
   */
  private void emitInSubquery(
      Assembler asm, InSubquery in, Env env, List<Integer> onTrue, List<Integer> onFalse) {
    Select sub = in.query();
    if (sub.table() == null || sub.fromSelect() != null) {
      throw new SqlException("IN subquery needs a base table: " + SqlText.snippet(env.sql));
    }
    if (!sub.joins().isEmpty()) {
      throw new SqlException("IN subquery joins are not supported yet: " + SqlText.snippet(env.sql));
    }
    if (sub.items().size() != 1 || !(sub.items().get(0) instanceof ExpressionItem projection)) {
      throw new SqlException("IN subquery must select one expression: " + SqlText.snippet(env.sql));
    }
    if (isAggregate(sub)) {
      throw new SqlException("IN subquery aggregates are not supported: " + SqlText.snippet(env.sql));
    }

    int left = emitExpr(asm, in.left(), env);
    Table table = requireTable(catalogFor(env), sub.table(), env.sql);
    int cursor = nextCursor(env);
    int scopeStart = env.sources.size();
    List<Source> sources = new ArrayList<>(env.sources);
    sources.add(new Source(cursor, table, sub.alias()));
    boolean[] nulled = null;
    if (env.nulled != null) {
      nulled = Arrays.copyOf(env.nulled, sources.size());
    }
    Env innerEnv = new Env(sources, nulled, scopeStart, env.excludedBase, env.excludedTable, env.sql);

    asm.emit(Opcode.OpenRead, cursor, asm.constant(table.name()), 0);
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (sub.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, sub.where(), innerEnv, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    int value = emitExpr(asm, projection.expr(), innerEnv);
    int match = asm.emit(Opcode.Eq, left, -1, value);
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }

    if (in.not()) {
      // No match → true; match → false.
      int noMatch = asm.emit(Opcode.Goto, 0, -1, 0);
      onTrue.add(noMatch);
      onFalse.add(match);
      asm.patch(rewind, noMatch);
    } else {
      // Match → true; no match → false.
      int noMatch = asm.emit(Opcode.Goto, 0, -1, 0);
      onTrue.add(match);
      onFalse.add(noMatch);
      asm.patch(rewind, noMatch);
    }
  }

  private void emitExists(
      Assembler asm, Exists exists, Env env, List<Integer> onTrue, List<Integer> onFalse) {
    Select sub = exists.query();
    if (sub.table() == null || sub.fromSelect() != null || !sub.joins().isEmpty()) {
      throw new SqlException("EXISTS subquery must read one table: " + SqlText.snippet(env.sql));
    }
    if (isAggregate(sub) || !sub.groupBy().isEmpty()) {
      throw new SqlException("EXISTS subquery cannot aggregate: " + SqlText.snippet(env.sql));
    }
    Table table = requireTable(catalogFor(env), sub.table(), env.sql);
    int cursor = nextCursor(env);
    int scopeStart = env.sources.size();
    List<Source> sources = new ArrayList<>(env.sources);
    sources.add(new Source(cursor, table, sub.alias()));
    boolean[] nulled = env.nulled == null ? null : Arrays.copyOf(env.nulled, sources.size());
    Env inner = new Env(sources, nulled, scopeStart, env.excludedBase, env.excludedTable, env.sql);
    asm.emit(Opcode.OpenRead, cursor, asm.constant(table.name()), 0);
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (sub.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, sub.where(), inner, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    onTrue.add(asm.emit(Opcode.Goto, 0, -1, 0));
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int done = asm.here();
    asm.patch(rewind, done);
    onFalse.add(asm.emit(Opcode.Goto, 0, -1, 0));
  }

  private int nextCursor(Env env) {
    int max = cursorHigh - 1;
    for (Source source : env.sources) {
      if (source.cursor() > max) {
        max = source.cursor();
      }
    }
    int cursor = max + 1;
    if (cursor >= 32) {
      throw new SqlException("too many open cursors: " + SqlText.snippet(env.sql));
    }
    cursorHigh = cursor + 1;
    return cursor;
  }

  /** Catalog is not on Env; resolve table via a side channel stored during compile. */
  private MemoryCatalog catalogFor(Env env) {
    if (activeCatalog == null) {
      throw new IllegalStateException("compiler catalog is not set");
    }
    return activeCatalog;
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
        int pattern = like.escape() == 0 ? right : (like.escape() << 16) | (right & 0xFFFF);
        yield asm.emit(Opcode.Like, left, -1, pattern);
      }
      case InSubquery ignored ->
          throw new IllegalStateException("InSubquery must be emitted via emitInSubquery");
      case Exists ignored ->
          throw new IllegalStateException("EXISTS must be emitted via emitExists");
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
      case SumExpr sum -> {
        Integer reg = asm.sumAcc.get(sum);
        if (reg == null) {
          throw new SqlException("SUM used outside aggregate context: " + SqlText.snippet(env.sql));
        }
        asm.emit(Opcode.IfNullValue, reg, reg, dest);
      }
      case CaseExpr cse -> emitCase(asm, dest, cse, env);
      case ScalarSubquery sub -> emitScalarSubquery(asm, dest, sub, env);
      case CastExpr cast -> {
        int value = emitExpr(asm, cast.value(), env);
        int spec = asm.constant(new CastSpec(cast.type(), cast.length(), cast.scale()));
        asm.emit(Opcode.Cast, dest, value, spec);
      }
      case AbsExpr abs -> {
        int value = emitExpr(asm, abs.value(), env);
        asm.emit(Opcode.Abs, dest, value, 0);
      }
    }
  }

  private void emitCase(Assembler asm, int dest, CaseExpr cse, Env env) {
    int operand = cse.operand() == null ? -1 : emitExpr(asm, cse.operand(), env);
    List<Integer> endJumps = new ArrayList<>();
    for (CaseArm arm : cse.arms()) {
      List<Integer> matchTrue = new ArrayList<>();
      List<Integer> matchFalse = new ArrayList<>();
      if (operand >= 0) {
        int whenVal = emitExpr(asm, arm.whenValue(), env);
        int eq = asm.emit(Opcode.Eq, operand, -1, whenVal);
        matchTrue.add(eq);
        matchFalse.add(asm.emit(Opcode.Goto, 0, -1, 0));
      } else {
        emitBool(asm, arm.whenCond(), env, matchTrue, matchFalse);
      }
      int thenPc = asm.here();
      for (int j : matchTrue) {
        asm.patch(j, thenPc);
      }
      emitExprInto(asm, dest, arm.thenExpr(), env);
      endJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      int nextPc = asm.here();
      for (int j : matchFalse) {
        asm.patch(j, nextPc);
      }
    }
    if (cse.elseExpr() != null) {
      emitExprInto(asm, dest, cse.elseExpr(), env);
    } else {
      asm.emit(Opcode.Null, dest, 0, 0);
    }
    int end = asm.here();
    for (int j : endJumps) {
      asm.patch(j, end);
    }
  }

  /**
   * Scalar {@code (SELECT …)}: one projection column. Aggregates scan all matching rows.
   * {@code TOP n} / {@code LIMIT} with {@code ORDER BY} keeps the first row in that order
   * (NULL sorts lowest). Without {@code ORDER BY}, the first matching row is used.
   */
  private void emitScalarSubquery(Assembler asm, int dest, ScalarSubquery sub, Env env) {
    Select query = sub.query();
    if (query.table() == null || query.fromSelect() != null) {
      throw new SqlException("scalar subquery needs a base table: " + SqlText.snippet(env.sql));
    }
    if (!query.joins().isEmpty()) {
      throw new SqlException("scalar subquery joins are not supported: " + SqlText.snippet(env.sql));
    }
    if (query.items().size() != 1) {
      throw new SqlException("scalar subquery must select one expression: " + SqlText.snippet(env.sql));
    }
    if (query.offset() != null || !query.groupBy().isEmpty()) {
      throw new SqlException(
          "scalar subquery GROUP BY / OFFSET not supported: " + SqlText.snippet(env.sql));
    }
    SelectItem item = query.items().get(0);
    Table table = requireTable(catalogFor(env), query.table(), env.sql);
    int cursor = nextCursor(env);
    int scopeStart = env.sources.size();
    List<Source> sources = new ArrayList<>(env.sources);
    sources.add(new Source(cursor, table, query.alias()));
    boolean[] nulled = null;
    if (env.nulled != null) {
      nulled = Arrays.copyOf(env.nulled, sources.size());
    }
    Env innerEnv = new Env(sources, nulled, scopeStart, env.excludedBase, env.excludedTable, env.sql);

    asm.emit(Opcode.OpenRead, cursor, asm.constant(table.name()), 0);

    if (isAggregate(query)) {
      emitScalarAggregate(asm, dest, item, query.where(), cursor, innerEnv);
      return;
    }
    if (!(item instanceof ExpressionItem projection)) {
      throw new SqlException(
          "scalar subquery projection not supported: " + SqlText.snippet(env.sql));
    }
    if (!query.orderBy().isEmpty()) {
      emitScalarOrdered(asm, dest, projection, query, cursor, innerEnv);
      return;
    }
    // Default NULL; overwrite on first match. TOP without ORDER BY is the first row.
    asm.emit(Opcode.Null, dest, 0, 0);
    int skipZero = emitSkipWhenLimitZero(asm, query.limit(), innerEnv);
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (query.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, query.where(), innerEnv, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    emitExprInto(asm, dest, projection.expr(), innerEnv);
    int done = asm.emit(Opcode.Goto, 0, -1, 0);
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int after = asm.here();
    asm.patch(rewind, after);
    asm.patch(done, after);
    if (skipZero >= 0) {
      asm.patch(skipZero, after);
    }
  }

  /**
   * Scalar subquery that must honor ORDER BY, including {@code SELECT TOP 1 … ORDER BY}.
   * NULL keys sort lowest, the same order as {@link Opcode#Sort}.
   */
  private void emitScalarOrdered(
      Assembler asm, int dest, ExpressionItem projection, Select query, int cursor, Env env) {
    int seen = asm.alloc();
    asm.emit(Opcode.Null, seen, 0, 0);
    int keyCount = query.orderBy().size();
    int[] best = new int[keyCount];
    int[] current = new int[keyCount];
    for (int i = 0; i < keyCount; i++) {
      best[i] = asm.alloc();
      current[i] = asm.alloc();
    }
    asm.emit(Opcode.Null, dest, 0, 0);
    int skipZero = emitSkipWhenLimitZero(asm, query.limit(), env);
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (query.where() != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, query.where(), env, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    for (int i = 0; i < keyCount; i++) {
      emitExprInto(asm, current[i], query.orderBy().get(i).expr(), env);
    }
    List<Integer> takeJumps = new ArrayList<>();
    List<Integer> skipJumps = new ArrayList<>();
    takeJumps.add(asm.emit(Opcode.IfNull, seen, -1, 0));
    for (int i = 0; i < keyCount; i++) {
      boolean descending = query.orderBy().get(i).descending();
      int cur = current[i];
      int old = best[i];
      int curIsNull = asm.emit(Opcode.IfNull, cur, -1, 0);
      int oldIsNull = asm.emit(Opcode.IfNull, old, -1, 0);
      if (descending) {
        takeJumps.add(asm.emit(Opcode.Gt, cur, -1, old));
        skipJumps.add(asm.emit(Opcode.Lt, cur, -1, old));
      } else {
        takeJumps.add(asm.emit(Opcode.Lt, cur, -1, old));
        skipJumps.add(asm.emit(Opcode.Gt, cur, -1, old));
      }
      int equalKeys = asm.emit(Opcode.Goto, 0, -1, 0);
      int curNull = asm.here();
      asm.patch(curIsNull, curNull);
      int bothNull = asm.emit(Opcode.IfNull, old, -1, 0);
      if (descending) {
        skipJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      } else {
        takeJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      }
      int oldNull = asm.here();
      asm.patch(oldIsNull, oldNull);
      if (descending) {
        takeJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      } else {
        skipJumps.add(asm.emit(Opcode.Goto, 0, -1, 0));
      }
      int keyDone = asm.here();
      asm.patch(equalKeys, keyDone);
      asm.patch(bothNull, keyDone);
    }
    int keep = asm.emit(Opcode.Goto, 0, -1, 0);
    int take = asm.here();
    for (int jump : takeJumps) {
      asm.patch(jump, take);
    }
    for (int i = 0; i < keyCount; i++) {
      emitExprInto(asm, best[i], query.orderBy().get(i).expr(), env);
    }
    emitExprInto(asm, dest, projection.expr(), env);
    asm.emit(Opcode.Integer, seen, 1, 0);
    int stored = asm.emit(Opcode.Goto, 0, -1, 0);
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    asm.patch(keep, next);
    asm.patch(stored, next);
    for (int jump : skipJumps) {
      asm.patch(jump, next);
    }
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int after = asm.here();
    asm.patch(rewind, after);
    if (skipZero >= 0) {
      asm.patch(skipZero, after);
    }
  }

  /** Jump past the scan when TOP / LIMIT is zero. Returns -1 when there is no limit. */
  private int emitSkipWhenLimitZero(Assembler asm, Expr limit, Env env) {
    if (limit == null) {
      return -1;
    }
    int limitReg = asm.alloc();
    int zero = asm.alloc();
    asm.emit(Opcode.Integer, zero, 0, 0);
    emitExprInto(asm, limitReg, limit, env);
    return asm.emit(Opcode.Eq, limitReg, -1, zero);
  }

  private void emitScalarAggregate(
      Assembler asm, int dest, SelectItem item, Bool where, int cursor, Env env) {
    if (item instanceof ExpressionItem expression && containsSum(expression.expr())) {
      bindSumAccumulators(asm, List.of(item));
      int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
      int loop = asm.here();
      List<Integer> fails = new ArrayList<>();
      if (where != null) {
        List<Integer> trues = new ArrayList<>();
        emitBool(asm, where, env, trues, fails);
        int body = asm.here();
        for (int jump : trues) {
          asm.patch(jump, body);
        }
      }
      emitSumAccumulators(asm, env);
      int next = asm.emit(Opcode.Next, cursor, loop, 0);
      for (int jump : fails) {
        asm.patch(jump, next);
      }
      int output = asm.here();
      asm.patch(rewind, output);
      emitExprInto(asm, dest, expression.expr(), env);
      return;
    }
    int acc = asm.alloc();
    switch (item) {
      case CountStar ignored -> asm.emit(Opcode.Integer, acc, 0, 0);
      case CountItem count -> {
        if (count.distinct()) {
          asm.emit(Opcode.Null, acc, 0, 0);
        } else {
          asm.emit(Opcode.Integer, acc, 0, 0);
        }
      }
      case MaxItem ignored -> asm.emit(Opcode.Null, acc, 0, 0);
      default -> throw new SqlException(
          "scalar aggregate not supported: " + SqlText.snippet(env.sql));
    }
    int rewind = asm.emit(Opcode.Rewind, cursor, -1, 0);
    int loop = asm.here();
    List<Integer> fails = new ArrayList<>();
    if (where != null) {
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, where, env, trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    switch (item) {
      case CountStar ignored -> asm.emit(Opcode.AggCount, acc, 0, 0);
      case CountItem count -> emitCount(asm, acc, count, env);
      case MaxItem max -> {
        int value = emitExpr(asm, new ColumnRef(max.table(), max.column()), env);
        asm.emit(Opcode.AggMax, acc, value, 0);
      }
      default -> {
      }
    }
    int next = asm.emit(Opcode.Next, cursor, loop, 0);
    for (int jump : fails) {
      asm.patch(jump, next);
    }
    int output = asm.here();
    asm.patch(rewind, output);
    storeAggregate(asm, item, acc, dest);
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
    Source source = env.sources.get(resolved.source);
    if (env.nulled != null && env.nulled[resolved.source]) {
      asm.emit(Opcode.Null, dest, 0, 0);
      return;
    }
    if (source.applyRegs() != null) {
      int reg = source.applyRegs()[resolved.index];
      asm.emit(Opcode.IfNullValue, reg, reg, dest);
      return;
    }
    asm.emit(Opcode.Column, resolved.cursor, resolved.index, dest);
  }

  private Resolved resolve(ColumnRef column, Env env) {
    // Qualified names may bind to any open source (including outer correlation).
    if (column.table() != null) {
      return resolveInRange(column, env, 0, env.sources.size(), true);
    }
    // Unqualified: SQLite-style — prefer the current SELECT's FROM (innermost scope).
    // This keeps MAX(id) / project_id inside a scalar subquery on the subquery table,
    // not ambiguous with outer JOIN tables that also have id / shared column names.
    int scope = Math.max(0, Math.min(env.scopeStart, env.sources.size()));
    List<Resolved> local = collectHits(column, env, scope, env.sources.size());
    if (local.size() == 1) {
      return local.get(0);
    }
    if (local.size() > 1) {
      throw new SqlException("ambiguous column " + column.name() + ": " + SqlText.snippet(env.sql));
    }
    if (scope > 0) {
      return resolveInRange(column, env, 0, scope, true);
    }
    String label = column.name();
    throw new SqlException("unknown column " + label + ": " + SqlText.snippet(env.sql));
  }

  private Resolved resolveInRange(
      ColumnRef column, Env env, int from, int to, boolean requireOne) {
    List<Resolved> hits = collectHits(column, env, from, to);
    if (hits.isEmpty()) {
      String label = column.table() == null ? column.name() : column.table() + "." + column.name();
      throw new SqlException("unknown column " + label + ": " + SqlText.snippet(env.sql));
    }
    if (requireOne && hits.size() > 1) {
      throw new SqlException("ambiguous column " + column.name() + ": " + SqlText.snippet(env.sql));
    }
    return hits.get(0);
  }

  private List<Resolved> collectHits(ColumnRef column, Env env, int from, int to) {
    List<Resolved> hits = new ArrayList<>();
    for (int i = from; i < to; i++) {
      Source source = env.sources.get(i);
      if (column.table() != null && !qualifierMatches(source, column.table())) {
        continue;
      }
      if (source.applyNames() != null) {
        for (int columnIndex = 0; columnIndex < source.applyNames().length; columnIndex++) {
          if (same(source.applyNames()[columnIndex], column.name())) {
            hits.add(new Resolved(i, source.cursor, columnIndex));
          }
        }
        continue;
      }
      int index = source.table.columnIndex(column.name());
      if (index >= 0) {
        hits.add(new Resolved(i, source.cursor, index));
      }
    }
    return hits;
  }

  private void writeProjection(Assembler asm, Select select, Env env, int start, String sql) {
    if (select.items().size() == 1 && select.items().get(0) instanceof Star) {
      int slot = 0;
      for (int source = 0; source < env.sources.size(); source++) {
        if (env.sources.get(source).applyNames() != null) {
          throw new SqlException("SELECT * cannot expand APPLY: " + SqlText.snippet(sql));
        }
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

  private void prepareGroupRegisters(Assembler asm, Select select) {
    for (int i = 0; i < select.groupBy().size(); i++) {
      asm.groupRegisters.add(asm.alloc());
    }
  }

  private void writeGroupInto(Assembler asm, Select select, Env env) {
    for (int i = 0; i < select.groupBy().size(); i++) {
      emitExprInto(asm, asm.groupRegisters.get(i), select.groupBy().get(i), env);
    }
  }

  private void initCount(Assembler asm, int[] acc, int index, boolean distinct) {
    acc[index] = asm.alloc();
    if (distinct) {
      asm.emit(Opcode.Null, acc[index], 0, 0);
    } else {
      asm.emit(Opcode.Integer, acc[index], 0, 0);
    }
  }

  private void emitCount(Assembler asm, int acc, CountItem count, Env env) {
    int value = emitExpr(asm, count.expr(), env);
    if (count.distinct()) {
      asm.emit(Opcode.AggCountDistinct, acc, value, 0);
    } else {
      asm.emit(Opcode.AggCount, acc, value, 1);
    }
  }

  private void storeAggregate(Assembler asm, SelectItem item, int acc, int dest) {
    if (item instanceof CountItem count && count.distinct()) {
      asm.emit(Opcode.AggCard, acc, 0, dest);
      return;
    }
    if (acc >= 0) {
      asm.emit(Opcode.IfNullValue, acc, acc, dest);
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
    Env env = new Env(List.of(), null, 0, -1, null, sql);
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

  /**
   * Prefer primary key, then unique indexes, then non-unique secondary indexes. Extra AND
   * predicates stay as residual filters after the point lookup. Large tables may gain single-column
   * ratio indexes via {@link com.myano.jsqlite.store.RatioAutoIndex}. When nothing matches, build an
   * auto-index on every equality-bound column if at least two are bound (same as join probes).
   */
  private Access seekAccess(Table table, Bool where, Env env) {
    if (where == null || !isAndChain(where)) {
      return null;
    }
    Map<Integer, Expr> bound = equalityBindings(where, env, 0);
    if (bound.isEmpty()) {
      return null;
    }
    int[] primaryKey = table.primaryKey();
    Expr[] primary = bindColumns(primaryKey, bound);
    if (primary != null) {
      return new Access(primaryKey, primary, true);
    }
    Access bestUnique = bestCoveringAccess(table.uniqueIndexColumns(), bound, false);
    if (bestUnique != null && bestUnique.columns().length >= bound.size()) {
      return bestUnique;
    }
    Access bestSecondary = bestCoveringAccess(table.secondaryIndexColumns(), bound, false);
    if (bestSecondary != null && bestSecondary.columns().length >= bound.size()) {
      return bestSecondary;
    }
    // Multiple equalities: composite auto-index (e.g. Company+GroupID). Do NOT run full-table
    // ratio analyze here — that scan is for single-column discovery and was dominating latency.
    if (bound.size() >= 2) {
      int[] columns = bound.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
      Expr[] keys = new Expr[columns.length];
      for (int i = 0; i < columns.length; i++) {
        keys[i] = bound.get(columns[i]);
      }
      return new Access(columns, keys, false);
    }
    // Single equality: discover/use high-ratio column postings.
    com.myano.jsqlite.store.RatioAutoIndex.ensure(table);
    bestSecondary = bestCoveringAccess(table.secondaryIndexColumns(), bound, false);
    if (bestSecondary != null) {
      return bestSecondary;
    }
    if (bestUnique != null) {
      return bestUnique;
    }
    int only = bound.keySet().iterator().next();
    if (table.isRatioWinner(only)) {
      int[] columns = {only};
      return new Access(columns, new Expr[] {bound.get(only)}, false);
    }
    return null;
  }

  /** Pick the bound index that covers the most equality columns (longest key wins). */
  private static Access bestCoveringAccess(
      List<int[]> indexes, Map<Integer, Expr> bound, boolean primary) {
    Access best = null;
    int bestCover = 0;
    for (int[] columns : indexes) {
      Expr[] keys = bindColumns(columns, bound);
      if (keys == null) {
        continue;
      }
      if (columns.length > bestCover) {
        best = new Access(columns, keys, primary);
        bestCover = columns.length;
      }
    }
    return best;
  }

  /**
   * Drive the outermost FROM table by SeekPk / SeekIdx when WHERE equalities allow it; residual
   * predicates stay as filters. Used for both join and join-aggregate plans.
   */
  private boolean emitOuterSeek(
      Assembler asm,
      Select select,
      Env env,
      Source source,
      int zero,
      List<Integer> orderRegs,
      int[] acc,
      boolean withEpilogue) {
    Access access = seekAccess(source.table(), select.where(), env);
    if (access == null) {
      return false;
    }
    prepareSeekIndex(source, access);
    int keyStart = asm.allocRange(access.keys().length);
    for (int i = 0; i < access.keys().length; i++) {
      emitExprInto(asm, keyStart + i, access.keys()[i], env);
    }
    int miss;
    if (access.primary()) {
      miss = asm.emit(Opcode.SeekPk, source.cursor(), -1, keyStart);
    } else {
      miss =
          asm.emit(
              Opcode.SeekIdx,
              source.cursor(),
              -1,
              keyStart,
              asm.constant(boxColumns(access.columns())));
    }
    int loop = asm.here();
    List<Integer> filterFails = new ArrayList<>();
    emitReadyFilters(asm, select.where(), env, 0, filterFails);
    if (acc != null) {
      emitAggregateLevel(asm, select, env, 1, zero, acc);
    } else {
      emitLevel(asm, select, env, 1, zero, orderRegs);
    }
    int afterAdvance;
    if (!access.primary()) {
      afterAdvance = asm.emit(Opcode.NextIdx, source.cursor(), loop, 0);
    } else {
      afterAdvance = asm.here();
    }
    for (int jump : filterFails) {
      asm.patch(jump, afterAdvance);
    }
    if (withEpilogue) {
      emitEpilogue(asm);
      int halt = asm.emit(Opcode.Halt, 0, 0, 0);
      asm.patch(miss, halt);
      if (access.primary()) {
        // primary hit already advanced past body; fall through to halt
      }
    } else {
      int after = asm.here();
      asm.patch(miss, after);
    }
    return true;
  }

  private Map<Integer, Expr> equalityBindings(Bool where, Env env, int sourceIndex) {
    Map<Integer, Expr> bound = new HashMap<>();
    for (Predicate predicate : andPredicates(where)) {
      if (!(predicate instanceof Compare compare) || compare.op() != CompareOp.EQ) {
        continue;
      }
      ColumnRef column = null;
      Expr value = null;
      if (compare.left() instanceof ColumnRef left
          && columnSource(left, env) == sourceIndex
          && !references(compare.right())) {
        column = left;
        value = compare.right();
      } else if (compare.right() instanceof ColumnRef right
          && columnSource(right, env) == sourceIndex
          && !references(compare.left())) {
        column = right;
        value = compare.left();
      }
      if (column == null) {
        continue;
      }
      int index = env.sources.get(sourceIndex).table().columnIndex(column.name());
      if (index < 0 || bound.containsKey(index)) {
        continue;
      }
      bound.put(index, value);
    }
    return bound;
  }

  private static Expr[] bindColumns(int[] columns, Map<Integer, Expr> bound) {
    if (columns.length == 0) {
      return null;
    }
    Expr[] keys = new Expr[columns.length];
    for (int i = 0; i < columns.length; i++) {
      Expr value = bound.get(columns[i]);
      if (value == null) {
        return null;
      }
      keys[i] = value;
    }
    return keys;
  }

  private boolean references(Expr expr) {
    if (expr == null) {
      return false;
    }
    return switch (expr) {
      case ColumnRef ignored -> true;
      case Concat concat -> references(concat.left()) || references(concat.right());
      case IfNullExpr ifNull -> references(ifNull.value()) || references(ifNull.fallback());
      case SumExpr ignored -> false; // argument is aggregated, not a bare column
      case CaseExpr cse -> {
        if (references(cse.operand()) || references(cse.elseExpr())) {
          yield true;
        }
        for (CaseArm arm : cse.arms()) {
          if (references(arm.whenValue()) || references(arm.thenExpr())) {
            yield true;
          }
          if (arm.whenCond() != null && boolReferences(arm.whenCond())) {
            yield true;
          }
        }
        yield false;
      }
      case ScalarSubquery ignored -> true;
      case CastExpr cast -> references(cast.value());
      case AbsExpr abs -> references(abs.value());
      default -> false;
    };
  }

  private boolean boolReferences(Bool condition) {
    return switch (condition) {
      case And and -> boolReferences(and.left()) || boolReferences(and.right());
      case Or or -> boolReferences(or.left()) || boolReferences(or.right());
      case Not not -> boolReferences(not.condition());
      case Pred pred -> switch (pred.predicate()) {
        case Compare compare -> references(compare.left()) || references(compare.right());
        case IsNull isNull -> references(isNull.expr());
        case Like like -> references(like.left()) || references(like.right());
        case InSubquery in -> references(in.left());
        case Exists ignored -> true;
      };
    };
  }

  private boolean containsColumn(Expr expr) {
    return references(expr);
  }

  private boolean isAggregate(Select select) {
    for (SelectItem item : select.items()) {
      if (item instanceof CountStar || item instanceof CountItem || item instanceof MaxItem) {
        return true;
      }
      if (item instanceof ExpressionItem expression && containsSum(expression.expr())) {
        return true;
      }
    }
    return false;
  }

  private boolean containsSum(Expr expr) {
    if (expr == null) {
      return false;
    }
    return switch (expr) {
      case SumExpr ignored -> true;
      case IfNullExpr ifNull -> containsSum(ifNull.value()) || containsSum(ifNull.fallback());
      case Concat concat -> containsSum(concat.left()) || containsSum(concat.right());
      case CastExpr cast -> containsSum(cast.value());
      case AbsExpr abs -> containsSum(abs.value());
      case CaseExpr cse -> {
        if (containsSum(cse.operand()) || containsSum(cse.elseExpr())) {
          yield true;
        }
        for (CaseArm arm : cse.arms()) {
          if (containsSum(arm.whenValue()) || containsSum(arm.thenExpr())) {
            yield true;
          }
        }
        yield false;
      }
      default -> false;
    };
  }

  private void collectSums(Expr expr, List<SumExpr> out) {
    if (expr == null) {
      return;
    }
    switch (expr) {
      case SumExpr sum -> out.add(sum);
      case IfNullExpr ifNull -> {
        collectSums(ifNull.value(), out);
        collectSums(ifNull.fallback(), out);
      }
      case Concat concat -> {
        collectSums(concat.left(), out);
        collectSums(concat.right(), out);
      }
      case CastExpr cast -> collectSums(cast.value(), out);
      case AbsExpr abs -> collectSums(abs.value(), out);
      case CaseExpr cse -> {
        collectSums(cse.operand(), out);
        collectSums(cse.elseExpr(), out);
        for (CaseArm arm : cse.arms()) {
          collectSums(arm.whenValue(), out);
          collectSums(arm.thenExpr(), out);
        }
      }
      default -> {
      }
    }
  }

  private void bindSumAccumulators(Assembler asm, Select select) {
    bindSumAccumulators(asm, select.items(), select.having());
  }

  private void bindSumAccumulators(Assembler asm, List<SelectItem> items) {
    bindSumAccumulators(asm, items, null);
  }

  private void bindSumAccumulators(Assembler asm, List<SelectItem> items, Bool having) {
    List<SumExpr> sums = new ArrayList<>();
    for (SelectItem item : items) {
      if (item instanceof ExpressionItem expression) {
        collectSums(expression.expr(), sums);
      }
    }
    collectSums(having, sums);
    if (sums.isEmpty()) {
      return;
    }
    Map<SumExpr, Integer> sumAcc = new IdentityHashMap<>();
    for (SumExpr sum : sums) {
      if (sumAcc.containsKey(sum)) {
        continue;
      }
      Integer shared = null;
      for (Map.Entry<SumExpr, Integer> existing : sumAcc.entrySet()) {
        if (sameExpr(existing.getKey().expr(), sum.expr())) {
          shared = existing.getValue();
          break;
        }
      }
      if (shared != null) {
        sumAcc.put(sum, shared);
        continue;
      }
      int reg = asm.alloc();
      asm.emit(Opcode.Null, reg, 0, 0);
      sumAcc.put(sum, reg);
    }
    asm.sumAcc = sumAcc;
  }

  private void emitSumAccumulators(Assembler asm, Env env) {
    Set<Integer> seen = new HashSet<>();
    for (Map.Entry<SumExpr, Integer> entry : asm.sumAcc.entrySet()) {
      if (!seen.add(entry.getValue())) {
        continue;
      }
      int value = emitExpr(asm, entry.getKey().expr(), env);
      asm.emit(Opcode.AggSum, entry.getValue(), value, 0);
    }
  }

  private void emitGroupTemplate(Assembler asm, int[] acc) {
    List<Integer> registers = new ArrayList<>();
    for (int reg : acc) {
      if (reg >= 0) {
        registers.add(reg);
      }
    }
    for (int reg : new HashSet<>(asm.sumAcc.values())) {
      registers.add(reg);
    }
    asm.emit(Opcode.GroupTemplate, 0, asm.constant(List.copyOf(registers)), 0);
  }

  private void emitGroupedTail(Assembler asm, Select select, int[] acc) {
    int loop = asm.here();
    int done = asm.emit(Opcode.GroupNext, 0, -1, 0);
    List<Integer> fails = new ArrayList<>();
    if (select.having() != null) {
      if (boolReferences(select.having())) {
        throw new SqlException("HAVING column must be aggregated");
      }
      List<Integer> trues = new ArrayList<>();
      emitBool(asm, select.having(), groupedEnv(select), trues, fails);
      int body = asm.here();
      for (int jump : trues) {
        asm.patch(jump, body);
      }
    }
    int start = asm.allocRange(select.items().size());
    for (int i = 0; i < select.items().size(); i++) {
      emitGroupedValue(asm, start + i, select.items().get(i), select, acc[i]);
    }
    emitGroupedOrder(asm, select);
    asm.emit(Opcode.ResultRow, start, select.items().size(), 0);
    asm.emit(Opcode.Goto, 0, loop, 0);
    int after = asm.here();
    asm.patch(done, after);
    for (int jump : fails) {
      asm.patch(jump, loop);
    }
    emitEpilogue(asm);
    asm.emit(Opcode.Halt, 0, 0, 0);
  }

  private Env groupedEnv(Select select) {
    return new Env(List.of(), null, 0, -1, null, "");
  }

  private void emitGroupedValue(Assembler asm, int dest, SelectItem item, Select select, int acc) {
    if (acc >= 0) {
      storeAggregate(asm, item, acc, dest);
      return;
    }
    if (!(item instanceof ExpressionItem expression)) {
      throw new SqlException("aggregate cannot be mixed into this select");
    }
    if (containsSum(expression.expr())) {
      emitExprInto(asm, dest, expression.expr(), groupedEnv(select));
      return;
    }
    int group = groupIndex(expression.expr(), select);
    if (group < 0) {
      throw new SqlException("column must be aggregated or grouped");
    }
    int reg = asm.groupRegisters.get(group);
    asm.emit(Opcode.IfNullValue, reg, reg, dest);
  }

  private void emitGroupedOrder(Assembler asm, Select select) {
    for (int i = 0; i < select.orderBy().size(); i++) {
      Expr expr = select.orderBy().get(i).expr();
      int group = groupIndex(expr, select);
      if (group < 0) {
        throw new SqlException("ORDER BY must use a grouped column");
      }
      int register = asm.alloc();
      asm.orderKeys.add(new OrderKey(register, select.orderBy().get(i).descending()));
      int source = asm.groupRegisters.get(group);
      asm.emit(Opcode.IfNullValue, source, source, register);
    }
  }

  private int groupIndex(Expr expr, Select select) {
    for (int i = 0; i < select.groupBy().size(); i++) {
      if (sameExpr(expr, select.groupBy().get(i))) {
        return i;
      }
    }
    return -1;
  }

  private void requireAggregated(Select select, String sql) {
    for (SelectItem item : select.items()) {
      if (item instanceof Star) {
        throw new SqlException("aggregate cannot include *: " + SqlText.snippet(sql));
      }
      if (item instanceof ExpressionItem expression
          && containsColumn(expression.expr())
          && !containsSum(expression.expr())
          && groupIndex(expression.expr(), select) < 0) {
        throw new SqlException("column must be aggregated or grouped: " + SqlText.snippet(sql));
      }
    }
  }

  private boolean sameExpr(Expr left, Expr right) {
    if (left == right) {
      return true;
    }
    if (left == null || right == null) {
      return false;
    }
    if (left instanceof ColumnRef a && right instanceof ColumnRef b) {
      return same(a.name(), b.name()) && sameNullable(a.table(), b.table());
    }
    if (left instanceof AbsExpr a && right instanceof AbsExpr b) {
      return sameExpr(a.value(), b.value());
    }
    if (left instanceof IfNullExpr a && right instanceof IfNullExpr b) {
      return sameExpr(a.value(), b.value()) && sameExpr(a.fallback(), b.fallback());
    }
    if (left instanceof LiteralExpr a && right instanceof LiteralExpr b) {
      return a.literal().equals(b.literal());
    }
    if (left instanceof com.myano.jsqlite.sql.Ast.Parameter a
        && right instanceof com.myano.jsqlite.sql.Ast.Parameter b) {
      return a.index() == b.index();
    }
    return false;
  }

  private static boolean sameNullable(String left, String right) {
    if (left == null || right == null) {
      return left == null && right == null;
    }
    return left.equalsIgnoreCase(right);
  }

  private void collectSums(Bool condition, List<SumExpr> out) {
    if (condition == null) {
      return;
    }
    switch (condition) {
      case And and -> {
        collectSums(and.left(), out);
        collectSums(and.right(), out);
      }
      case Or or -> {
        collectSums(or.left(), out);
        collectSums(or.right(), out);
      }
      case Not not -> collectSums(not.condition(), out);
      case Pred pred -> {
        switch (pred.predicate()) {
          case Compare compare -> {
            collectSums(compare.left(), out);
            collectSums(compare.right(), out);
          }
          case IsNull isNull -> collectSums(isNull.expr(), out);
          case Like like -> {
            collectSums(like.left(), out);
            collectSums(like.right(), out);
          }
          case InSubquery in -> collectSums(in.left(), out);
          case Exists ignored -> {
          }
        }
      }
    }
  }

  private boolean hasEpilogue(Select select) {
    return select.limit() != null
        || select.offset() != null
        || !select.orderBy().isEmpty()
        || !select.groupBy().isEmpty();
  }

  private boolean isAndChain(Bool condition) {
    return switch (condition) {
      case And and -> isAndChain(and.left()) && isAndChain(and.right());
      case Or ignored -> false;
      case Not ignored -> false;
      case Pred ignored -> true;
    };
  }

  /** AND of simple predicates (no IN-subquery) — eligible for the emitScan fast path. */
  private boolean isSimpleAndChain(Bool condition) {
    return switch (condition) {
      case And and -> isSimpleAndChain(and.left()) && isSimpleAndChain(and.right());
      case Or ignored -> false;
      case Not ignored -> false;
      case Pred pred -> !(pred.predicate() instanceof InSubquery);
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
      case Not ignored -> throw new IllegalStateException("NOT is not an AND chain");
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
    if ((column.type() == ColumnType.INTEGER || column.type() == ColumnType.REAL)
        && !(literal instanceof NumberLiteral)) {
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
    return new Env(List.of(new Source(CURSOR, table, alias)), null, 0, -1, null, sql);
  }

  private static boolean same(String left, String right) {
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }

  private record Scan(int rewind, int loopHead, List<Integer> fails) {}

  private record Access(int[] columns, Expr[] keys, boolean primary) {}

  private record Source(int cursor, Table table, String alias, String[] applyNames, int[] applyRegs) {
    Source(int cursor, Table table, String alias) {
      this(cursor, table, alias, null, null);
    }
  }

  private record Resolved(int source, int cursor, int index) {}

  /**
   * @param scopeStart index of the first source belonging to the current SELECT's FROM.
   *     Sources before this are outer (correlation) tables; unqualified names bind locally first.
   */
  private record Env(
      List<Source> sources,
      boolean[] nulled,
      int scopeStart,
      int excludedBase,
      Table excludedTable,
      String sql) {
    private Env withNulled(boolean[] nulled) {
      return new Env(sources, nulled, scopeStart, excludedBase, excludedTable, sql);
    }
  }

  private static final class Assembler {
    private final List<Instruction> code = new ArrayList<>();
    private final List<Object> constants = new ArrayList<>();
    private final List<OrderKey> orderKeys = new ArrayList<>();
    private final List<Integer> groupRegisters = new ArrayList<>();
    private Map<SumExpr, Integer> sumAcc = Map.of();
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
      return emit(op, p1, p2, p3, 0);
    }

    int emit(Opcode op, int p1, int p2, int p3, int p4) {
      code.add(new Instruction(op, p1, p2, p3, p4));
      return code.size() - 1;
    }

    int here() {
      return code.size();
    }

    void patch(int index, int target) {
      Instruction instruction = code.get(index);
      code.set(
          index,
          new Instruction(instruction.op(), instruction.p1(), target, instruction.p3(), instruction.p4()));
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
      int[] groups = new int[groupRegisters.size()];
      for (int i = 0; i < groups.length; i++) {
        groups[i] = groupRegisters.get(i);
      }
      return new Program(
          code,
          constants,
          resultColumns,
          registers,
          orderKeys,
          groups,
          limitRegister,
          offsetRegister,
          insertMode,
          conflictColumns,
          insertDone);
    }
  }
}
