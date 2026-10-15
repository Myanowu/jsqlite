package com.myano.jsqlite;

import com.myano.jsqlite.format.Btree;
import com.myano.jsqlite.format.SqliteReader;
import com.myano.jsqlite.wal.Checkpoint;
import com.myano.jsqlite.wal.LockFile;
import com.myano.jsqlite.wal.WalWriter;
import com.myano.jsqlite.sql.Ast.AlterTable;
import com.myano.jsqlite.sql.Ast.CreateIndex;
import com.myano.jsqlite.sql.Ast.CreateTable;
import com.myano.jsqlite.sql.Ast.Delete;
import com.myano.jsqlite.sql.Ast.Insert;
import com.myano.jsqlite.sql.Ast.Pragma;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Ast.Update;
import com.myano.jsqlite.sql.Compiler;
import com.myano.jsqlite.sql.Parameters;
import com.myano.jsqlite.sql.Parser;
import com.myano.jsqlite.sql.SqlText;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.store.TableInfo;
import com.myano.jsqlite.vm.Program;
import com.myano.jsqlite.vm.VirtualMachine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class Database implements AutoCloseable {
  private final Engine engine;
  private final Path path;
  private final Parser parser = new Parser();
  private final Compiler compiler = new Compiler();
  private final VirtualMachine vm = new VirtualMachine();
  private boolean closed;
  private boolean readOnly;
  private LockFile connectionLock;
  private boolean autoCommit = true;
  private boolean gateHeld;
  /**
   * Exclusive gate is held across statements only while this connection has uncommitted writes.
   * Read-only Hibernate transactions must release after each statement or connection pools deadlock.
   */
  private boolean uncommittedWrite;
  private long derivedSeq;

  private Database(Engine engine) {
    this.engine = engine;
    this.path = engine.path;
  }

  /** 路徑是庫名。若檔案是 sqlite3 主檔，載入後可寫；有 -wal 時套用已提交幀。否則是記憶體庫。 */
  public static Database open(Path path) {
    Objects.requireNonNull(path, "path");
    if (SqliteReader.isDatabase(path)) {
      Database database = new Database(Engine.openShared(path));
      database.connectionLock = LockFile.register(path);
      return database;
    }
    return new Database(Engine.memory(path));
  }

  /** JDBC 新建的空庫。檔案已在磁碟上，可寫，日誌模式從 delete 開始。 */
  public static Database openFile(Path path) {
    Objects.requireNonNull(path, "path");
    Database database = new Database(Engine.openShared(path));
    database.connectionLock = LockFile.register(path);
    return database;
  }

  public void setAutoCommit(boolean autoCommit) {
    ensureOpen();
    if (this.autoCommit == autoCommit) {
      return;
    }
    if (autoCommit) {
      enter();
      try {
        engine.catalog.commitTransaction();
        persist(true);
      } finally {
        uncommittedWrite = false;
        leave();
      }
    }
    this.autoCommit = autoCommit;
  }

  public boolean autoCommit() {
    return autoCommit;
  }

  public void commit() {
    ensureOpen();
    if (autoCommit) {
      throw new SqlException("commit while auto-commit is on");
    }
    enter();
    try {
      engine.catalog.commitTransaction();
      persist(true);
    } finally {
      uncommittedWrite = false;
      leave();
    }
  }

  public void rollback() {
    ensureOpen();
    if (autoCommit) {
      throw new SqlException("rollback while auto-commit is on");
    }
    enter();
    try {
      engine.catalog.rollbackTransaction();
    } finally {
      uncommittedWrite = false;
      leave();
    }
  }

  public String journalMode() {
    return engine.journalMode;
  }

  public long busyTimeout() {
    return engine.busyTimeout;
  }

  public boolean foreignKeys() {
    return engine.foreignKeys;
  }

  /** JDBC 入口。已有的 sqlite 主檔拒絕寫入。新建的庫在語句成功後把頁寫回檔案。 */
  public Outcome run(String sql, Object... bindings) {
    ensureOpen();
    Statement statement = parser.parse(sql);
    Object[] values = normalize(sql, bindings);
    if (Parameters.count(statement) != values.length) {
      throw new SqlException("binding count mismatch: " + SqlText.snippet(sql));
    }
    enter();
    try {
      if (statement instanceof Select select) {
        ensureTransaction();
        return selectOutcome(select, sql, values);
      }
      if (statement instanceof Pragma pragma) {
        return pragma(pragma, sql);
      }
      if (statement instanceof com.myano.jsqlite.sql.Ast.Shutdown) {
        persist(true);
        return Outcome.update(0, null);
      }
      if (readOnly) {
        throw new SqlException("sqlite file is read-only: " + path);
      }
      ensureTransaction();
      engine.catalog.clearGeneratedKey();
      engine.catalog.resetChanges();
      engine.catalog.beginStatement();
      try {
        apply(statement, values, sql);
        engine.catalog.endStatement();
        wrote();
        markUncommittedWrite();
      } catch (RuntimeException ex) {
        engine.catalog.rollbackStatement();
        engine.catalog.clearGeneratedKey();
        throw ex;
      }
      return Outcome.update(engine.catalog.changes(), engine.catalog.generatedKey());
    } finally {
      releaseGateAfterStatement();
    }
  }

  public boolean readOnly() {
    return readOnly;
  }

  public void execute(String sql, Object... bindings) {
    ensureOpen();
    Statement statement = parser.parse(sql);
    if (statement instanceof Select) {
      throw new SqlException("SELECT must be run with query: " + SqlText.snippet(sql));
    }
    if (readOnly && !(statement instanceof Insert || statement instanceof Update || statement instanceof Delete)) {
      throw new SqlException("sqlite file is read-only: " + path);
    }
    Object[] values = normalize(sql, bindings);
    if (Parameters.count(statement) != values.length) {
      throw new SqlException("binding count mismatch: " + SqlText.snippet(sql));
    }
    enter();
    try {
      if (statement instanceof com.myano.jsqlite.sql.Ast.Shutdown) {
        persist(true);
        return;
      }
      ensureTransaction();
      engine.catalog.beginStatement();
      try {
        apply(statement, values, sql);
        engine.catalog.endStatement();
        wrote();
        markUncommittedWrite();
      } catch (RuntimeException ex) {
        engine.catalog.rollbackStatement();
        throw ex;
      }
    } finally {
      releaseGateAfterStatement();
    }
  }

  public List<Row> query(String sql, Object... bindings) {
    ensureOpen();
    Statement statement = parser.parse(sql);
    if (!(statement instanceof Select select)) {
      throw new SqlException("query only accepts SELECT: " + SqlText.snippet(sql));
    }
    Object[] values = normalize(sql, bindings);
    if (Parameters.count(statement) != values.length) {
      throw new SqlException("binding count mismatch: " + SqlText.snippet(sql));
    }
    enter();
    try {
      ensureTransaction();
      return queryRows(select, sql, values);
    } finally {
      releaseGateAfterStatement();
    }
  }

  private static Object[] normalize(String sql, Object[] bindings) {
    if (bindings == null) {
      throw new SqlException("binding count mismatch: " + SqlText.snippet(sql));
    }
    Object[] values = new Object[bindings.length];
    for (int i = 0; i < bindings.length; i++) {
      try {
        values[i] = Bindings.coerce(bindings[i]);
      } catch (SqlException ex) {
        throw new SqlException(ex.getMessage() + ": " + SqlText.snippet(sql));
      }
    }
    return values;
  }

  public Path path() {
    return path;
  }

  /** 建表順序。呼叫當下的列數，之後的寫入不會改這份清單。 */
  public List<TableInfo> tables() {
    ensureOpen();
    enter();
    try {
      return engine.catalog.tables();
    } finally {
      releaseGateAfterStatement();
    }
  }

  public List<IndexDef> indexes() {
    ensureOpen();
    enter();
    try {
      return engine.catalog.indexes();
    } finally {
      releaseGateAfterStatement();
    }
  }

  public void redefineTable(
      String tableName,
      String newName,
      List<Column> columns,
      int[] source,
      List<String> primaryKey,
      String sql) {
    ensureOpen();
    if (readOnly) {
      throw new SqlException("sqlite file is read-only: " + path);
    }
    enter();
    try {
      ensureTransaction();
      engine.catalog.beginStatement();
      try {
        engine.catalog.redefineTable(tableName, newName, columns, source, primaryKey, sql);
        engine.catalog.endStatement();
        wrote();
        markUncommittedWrite();
      } catch (RuntimeException ex) {
        engine.catalog.rollbackStatement();
        throw ex;
      }
    } finally {
      releaseGateAfterStatement();
    }
  }

  public void updateRow(String tableName, List<Object> original, List<Object> values) {
    ensureOpen();
    enter();
    try {
      ensureTransaction();
      engine.catalog.beginStatement();
      try {
        engine.catalog.replaceFirst(tableName, original, values, "UPDATE");
        engine.catalog.endStatement();
        wrote();
        markUncommittedWrite();
      } catch (RuntimeException ex) {
        engine.catalog.rollbackStatement();
        throw ex;
      }
    } finally {
      releaseGateAfterStatement();
    }
  }

  public void deleteRows(String tableName, List<List<Object>> rows) {
    ensureOpen();
    enter();
    try {
      ensureTransaction();
      engine.catalog.beginStatement();
      try {
        for (List<Object> row : rows) {
          engine.catalog.deleteFirst(tableName, row, "DELETE");
        }
        engine.catalog.endStatement();
        wrote();
        markUncommittedWrite();
      } catch (RuntimeException ex) {
        engine.catalog.rollbackStatement();
        throw ex;
      }
    } finally {
      releaseGateAfterStatement();
    }
  }

  private void wrote() {
    persist(autoCommit);
  }

  private void persist(boolean durable) {
    if (engine.pager == null || !durable) {
      return;
    }
    engine.catalog.syncSequences();
    byte[][] image = Btree.image(engine.catalog);
    if ("wal".equals(engine.journalMode)) {
      try (LockFile write = LockFile.acquireWrite(path, engine.busyTimeout)) {
        WalWriter.commit(path, image);
        engine.pager.replace(image);
      }
    } else {
      engine.pager.commit(image);
    }
  }

  /** 測試用：下一次提交寫主庫時，在第 n 次寫頁拋錯。 */
  public void failOnWrite(int n) {
    ensureOpen();
    if (engine.pager == null) {
      throw new SqlException("write failure injection needs a file database");
    }
    engine.pager.failOnWrite(n);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (!autoCommit) {
      try {
        engine.catalog.rollbackTransaction();
      } catch (RuntimeException ignored) {
        // 未提交的變更隨連線丟棄
      }
      uncommittedWrite = false;
    }
    leave();
    if (engine.pager != null && "wal".equals(engine.journalMode) && LockFile.onlyConnection(path)) {
      try {
        Checkpoint.run(path);
      } catch (RuntimeException ignored) {
        // 失敗不刪 -wal
      }
    }
    if (connectionLock != null) {
      connectionLock.close();
      connectionLock = null;
    }
    engine.release();
  }

  @Override
  public String toString() {
    return "Database[" + path + "]";
  }

  private List<Row> queryRows(Select select, String sql, Object[] values) {
    return selectOutcome(select, sql, values).rows();
  }

  private Outcome selectOutcome(Select select, String sql, Object[] values) {
    if (select.table() != null && select.table().regionMatches(true, 0, "information_schema.", 0, "information_schema.".length())) {
      return informationSchema(select);
    }
    List<String> temps = new ArrayList<>();
    try {
      Select flat = flattenDerived(select, sql, values, temps);
      Program program = compiler.compile(flat, engine.catalog, sql);
      List<Row> rows = distinctRows(flat, vm.execute(program, engine.catalog, values));
      List<String> columns = rows.isEmpty() ? program.resultColumns() : rows.get(0).columns();
      return Outcome.rows(columns, rows);
    } finally {
      for (String temp : temps) {
        engine.catalog.dropTable(temp);
      }
    }
  }

  private Select flattenDerived(Select select, String sql, Object[] values, List<String> temps) {
    if (select.fromSelect() == null) {
      return select;
    }
    Select inner = flattenDerived(select.fromSelect(), sql, values, temps);
    Program program = compiler.compile(inner, engine.catalog, sql);
    List<Row> rows = distinctRows(inner, vm.execute(program, engine.catalog, values));
    List<String> columns = rows.isEmpty() ? program.resultColumns() : rows.get(0).columns();
    String tempName = "__derived_" + (++derivedSeq);
    temps.add(tempName);
    List<Object[]> data = new ArrayList<>(rows.size());
    for (Row row : rows) {
      data.add(row.values().toArray());
    }
    engine.catalog.loadDerived(tempName, columns, data, sql);
    return new Select(
        select.distinct(),
        select.items(),
        tempName,
        null,
        select.alias(),
        select.joins(),
        select.where(),
        select.orderBy(),
        select.limit(),
        select.offset(),
        select.groupBy());
  }

  private static List<Row> distinctRows(Select select, List<Row> rows) {
    if (!select.distinct() || rows.size() < 2) {
      return rows;
    }
    List<Row> unique = new ArrayList<>();
    for (Row row : rows) {
      boolean seen = false;
      for (Row existing : unique) {
        if (existing.values().equals(row.values())) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        unique.add(row);
      }
    }
    return unique;
  }

  private void apply(Statement statement, Object[] values, String sql) {
    switch (statement) {
      case CreateTable create -> engine.catalog.createTable(
          create.name(), create.columns(), create.constraints(), create.ifNotExists(), sql);
      case CreateIndex index -> engine.catalog.createIndex(
          index.name(),
          index.unique(),
          index.ifNotExists(),
          index.table(),
          index.columns(),
          index.partialColumn(),
          sql);
      case AlterTable alter -> engine.catalog.addColumn(alter.table(), alter.column(), sql);
      case com.myano.jsqlite.sql.Ast.DropTable drop -> engine.catalog.dropTable(drop.name(), drop.ifExists(), sql);
      case Pragma ignored -> throw new SqlException("PRAGMA is handled by the driver: " + SqlText.snippet(sql));
      default -> {
        Program program = compiler.compile(statement, engine.catalog, sql);
        vm.execute(program, engine.catalog, values);
      }
    }
  }

  private Outcome pragma(Pragma pragma, String sql) {
    if (pragma.argument() != null && pragma.value() != null) {
      throw new SqlException("unsupported pragma: " + SqlText.snippet(sql));
    }
    return switch (pragma.name().toLowerCase(Locale.ROOT)) {
      case "journal_mode" -> journalMode(pragma, sql);
      case "busy_timeout" -> busyTimeout(pragma, sql);
      case "foreign_keys" -> foreignKeys(pragma, sql);
      case "table_info" -> tableInfo(pragma, sql);
      default -> throw new SqlException("unsupported pragma: " + SqlText.snippet(sql));
    };
  }

  private Outcome journalMode(Pragma pragma, String sql) {
    if (pragma.argument() != null) {
      throw new SqlException("unsupported pragma: " + SqlText.snippet(sql));
    }
    if (pragma.value() != null) {
      String mode = pragma.value().toLowerCase(Locale.ROOT);
      if (!mode.equals("wal") && !mode.equals("delete")) {
        throw new SqlException("unsupported journal mode " + pragma.value() + ": " + SqlText.snippet(sql));
      }
      if (mode.equals("wal")) {
        if (engine.pager == null) {
          throw new SqlException("WAL needs a file database: " + SqlText.snippet(sql));
        }
        WalWriter.ensure(path);
      }
      engine.journalMode = mode;
    }
    return oneColumn("journal_mode", engine.journalMode);
  }

  private Outcome busyTimeout(Pragma pragma, String sql) {
    if (pragma.argument() != null) {
      throw new SqlException("unsupported pragma: " + SqlText.snippet(sql));
    }
    if (pragma.value() != null) {
      long millis;
      try {
        millis = Long.parseLong(pragma.value());
      } catch (NumberFormatException ex) {
        throw new SqlException("busy_timeout needs a non-negative integer: " + SqlText.snippet(sql));
      }
      if (millis < 0) {
        throw new SqlException("busy_timeout needs a non-negative integer: " + SqlText.snippet(sql));
      }
      engine.busyTimeout = millis;
    }
    return oneColumn("busy_timeout", engine.busyTimeout);
  }

  private Outcome foreignKeys(Pragma pragma, String sql) {
    if (pragma.argument() != null) {
      throw new SqlException("unsupported pragma: " + SqlText.snippet(sql));
    }
    if (pragma.value() != null) {
      boolean on = switch (pragma.value().toLowerCase(Locale.ROOT)) {
        case "on", "1" -> true;
        case "off", "0" -> false;
        default -> throw new SqlException("foreign_keys needs ON or OFF: " + SqlText.snippet(sql));
      };
      engine.foreignKeys = on;
      engine.catalog.setEnforceForeignKeys(on);
    }
    return oneColumn("foreign_keys", engine.foreignKeys ? 1L : 0L);
  }

  private Outcome tableInfo(Pragma pragma, String sql) {
    if (pragma.value() != null || pragma.argument() == null) {
      throw new SqlException("table_info needs a table name: " + SqlText.snippet(sql));
    }
    Table table = engine.catalog.find(pragma.argument());
    if (table == null) {
      throw new SqlException("unknown table " + pragma.argument() + ": " + SqlText.snippet(sql));
    }
    List<String> columns = List.of("cid", "name", "type", "notnull", "dflt_value", "pk");
    List<Row> rows = new ArrayList<>();
    List<Column> defs = table.columns();
    int[] primary = table.primaryKey();
    for (int i = 0; i < defs.size(); i++) {
      Column column = defs.get(i);
      int keySeq = 0;
      for (int key = 0; key < primary.length; key++) {
        if (primary[key] == i) {
          keySeq = key + 1;
        }
      }
      List<Object> values = new ArrayList<>();
      values.add((long) i);
      values.add(column.name());
      values.add(column.type().name());
      values.add(column.notNull() || keySeq > 0 ? 1L : 0L);
      values.add(defaultText(column));
      values.add((long) keySeq);
      rows.add(new Row(columns, values));
    }
    return Outcome.rows(columns, rows);
  }

  private static Outcome oneColumn(String name, Object value) {
    return Outcome.rows(List.of(name), List.of(new Row(List.of(name), List.of(value))));
  }

  private static Object defaultText(Column column) {
    if (!column.hasDefault()) {
      return null;
    }
    if (column.defaultNow()) {
      return "datetime('now')";
    }
    Object value = column.defaultValue();
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Long number) {
      return Long.toString(number);
    }
    if (value instanceof String text) {
      return "'" + text.replace("'", "''") + "'";
    }
    return null;
  }

  private void ensureTransaction() {
    if (!autoCommit) {
      engine.catalog.beginTransaction();
    }
  }

  private void markUncommittedWrite() {
    if (!autoCommit) {
      uncommittedWrite = true;
    }
  }

  /** Release unless this connection holds an open write transaction (SQLite-like write lock). */
  private void releaseGateAfterStatement() {
    if (autoCommit || !uncommittedWrite) {
      leave();
    }
  }

  private void enter() {
    if (gateHeld) {
      return;
    }
    if (!engine.tryEnter(engine.busyTimeout)) {
      throw new SqlException("database is locked");
    }
    gateHeld = true;
  }

  private void leave() {
    if (!gateHeld) {
      return;
    }
    gateHeld = false;
    engine.exit();
  }

  private static Outcome informationSchema(Select select) {
    List<String> columns = new ArrayList<>();
    for (com.myano.jsqlite.sql.Ast.SelectItem item : select.items()) {
      if (item instanceof com.myano.jsqlite.sql.Ast.ExpressionItem expression
          && expression.expr() instanceof com.myano.jsqlite.sql.Ast.ColumnRef column) {
        columns.add(expression.alias() != null ? expression.alias() : column.name());
      } else if (item instanceof com.myano.jsqlite.sql.Ast.Star) {
        columns.add("sequence_name");
      } else {
        columns.add("column" + (columns.size() + 1));
      }
    }
    if (columns.isEmpty()) {
      columns.add("sequence_name");
    }
    return Outcome.rows(columns, List.of());
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("database is closed");
    }
  }

  public record Outcome(List<String> columns, List<Row> rows, int updateCount, Long generatedKey) {
    public static Outcome rows(List<String> columns, List<Row> rows) {
      return new Outcome(List.copyOf(columns), List.copyOf(rows), -1, null);
    }

    public static Outcome update(int count, Long generatedKey) {
      return new Outcome(List.of(), null, count, generatedKey);
    }

    public boolean hasResultSet() {
      return rows != null;
    }
  }
}
