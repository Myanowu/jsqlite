package com.myano.jsqlite;

import com.myano.jsqlite.format.Btree;
import com.myano.jsqlite.format.Pager;
import com.myano.jsqlite.format.SqliteReader;
import com.myano.jsqlite.wal.Checkpoint;
import com.myano.jsqlite.wal.LockFile;
import com.myano.jsqlite.wal.WalReader;
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
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.store.TableInfo;
import com.myano.jsqlite.vm.Program;
import com.myano.jsqlite.vm.VirtualMachine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class Database implements AutoCloseable {
  private final Path path;
  private final MemoryCatalog catalog = new MemoryCatalog();
  private final Parser parser = new Parser();
  private final Compiler compiler = new Compiler();
  private final VirtualMachine vm = new VirtualMachine();
  private boolean closed;
  private boolean readOnly;
  private Pager pager;
  private LockFile connectionLock;
  private boolean autoCommit = true;
  private boolean foreignKeys;
  private long busyTimeout;
  private String journalMode = "memory";
  private long derivedSeq;

  private Database(Path path) {
    this.path = path;
  }

  /** 路徑是庫名。若檔案是 sqlite3 主檔，載入後可寫；有 -wal 時套用已提交幀。否則是記憶體庫。 */
  public static Database open(Path path) {
    Objects.requireNonNull(path, "path");
    Database database = new Database(path);
    if (SqliteReader.isDatabase(path)) {
      database.pager = Pager.open(path);
      byte[][] pages = WalReader.apply(path, database.pager.copyPages());
      database.pager.replace(pages);
      SqliteReader.loadBytes(Pager.flatten(pages), database.catalog);
      database.journalMode = Files.isRegularFile(WalReader.pathFor(path)) ? "wal" : "delete";
      database.connectionLock = LockFile.register(path);
    }
    return database;
  }

  /** JDBC 新建的空庫。檔案已在磁碟上，可寫，日誌模式從 delete 開始。 */
  public static Database openFile(Path path) {
    return open(path);
  }

  public void setAutoCommit(boolean autoCommit) {
    ensureOpen();
    if (this.autoCommit == autoCommit) {
      return;
    }
    if (autoCommit) {
      catalog.commitTransaction();
      persist(true);
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
    catalog.commitTransaction();
    persist(true);
  }

  public void rollback() {
    ensureOpen();
    if (autoCommit) {
      throw new SqlException("rollback while auto-commit is on");
    }
    catalog.rollbackTransaction();
  }

  public String journalMode() {
    return journalMode;
  }

  public long busyTimeout() {
    return busyTimeout;
  }

  public boolean foreignKeys() {
    return foreignKeys;
  }

  /** JDBC 入口。已有的 sqlite 主檔拒絕寫入。新建的庫在語句成功後把頁寫回檔案。 */
  public Outcome run(String sql, Object... bindings) {
    ensureOpen();
    Statement statement = parser.parse(sql);
    Object[] values = normalize(sql, bindings);
    if (Parameters.count(statement) != values.length) {
      throw new SqlException("binding count mismatch: " + SqlText.snippet(sql));
    }
    if (statement instanceof Select select) {
      ensureTransaction();
      return selectOutcome(select, sql, values);
    }
    if (statement instanceof Pragma pragma) {
      return pragma(pragma, sql);
    }
    if (readOnly) {
      throw new SqlException("sqlite file is read-only: " + path);
    }
    ensureTransaction();
    catalog.clearGeneratedKey();
    catalog.resetChanges();
    catalog.beginStatement();
    try {
      apply(statement, values, sql);
      catalog.endStatement();
      wrote();
    } catch (RuntimeException ex) {
      catalog.rollbackStatement();
      catalog.clearGeneratedKey();
      throw ex;
    }
    return Outcome.update(catalog.changes(), catalog.generatedKey());
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
    ensureTransaction();
    catalog.beginStatement();
    try {
      apply(statement, values, sql);
      catalog.endStatement();
      wrote();
    } catch (RuntimeException ex) {
      catalog.rollbackStatement();
      throw ex;
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
    ensureTransaction();
    return queryRows(select, sql, values);
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
    return catalog.tables();
  }

  public List<IndexDef> indexes() {
    ensureOpen();
    return catalog.indexes();
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
    ensureTransaction();
    catalog.beginStatement();
    try {
      catalog.redefineTable(tableName, newName, columns, source, primaryKey, sql);
      catalog.endStatement();
      wrote();
    } catch (RuntimeException ex) {
      catalog.rollbackStatement();
      throw ex;
    }
  }

  public void updateRow(String tableName, List<Object> original, List<Object> values) {
    ensureOpen();
    ensureTransaction();
    catalog.beginStatement();
    try {
      catalog.replaceFirst(tableName, original, values, "UPDATE");
      catalog.endStatement();
      wrote();
    } catch (RuntimeException ex) {
      catalog.rollbackStatement();
      throw ex;
    }
  }

  public void deleteRows(String tableName, List<List<Object>> rows) {
    ensureOpen();
    ensureTransaction();
    catalog.beginStatement();
    try {
      for (List<Object> row : rows) {
        catalog.deleteFirst(tableName, row, "DELETE");
      }
      catalog.endStatement();
      wrote();
    } catch (RuntimeException ex) {
      catalog.rollbackStatement();
      throw ex;
    }
  }

  private void wrote() {
    persist(autoCommit);
  }

  private void persist(boolean durable) {
    if (pager == null || !durable) {
      return;
    }
    catalog.syncSequences();
    byte[][] image = Btree.image(catalog);
    if ("wal".equals(journalMode)) {
      try (LockFile write = LockFile.acquireWrite(path, busyTimeout)) {
        WalWriter.commit(path, image);
        pager.replace(image);
      }
    } else {
      pager.commit(image);
    }
  }

  /** 測試用：下一次提交寫主庫時，在第 n 次寫頁拋錯。 */
  public void failOnWrite(int n) {
    ensureOpen();
    if (pager == null) {
      throw new SqlException("write failure injection needs a file database");
    }
    pager.failOnWrite(n);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (pager != null && "wal".equals(journalMode) && LockFile.onlyConnection(path)) {
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
  }

  @Override
  public String toString() {
    return "Database[" + path + "]";
  }

  private List<Row> queryRows(Select select, String sql, Object[] values) {
    return selectOutcome(select, sql, values).rows();
  }

  private Outcome selectOutcome(Select select, String sql, Object[] values) {
    List<String> temps = new ArrayList<>();
    try {
      Select flat = flattenDerived(select, sql, values, temps);
      Program program = compiler.compile(flat, catalog, sql);
      List<Row> rows = distinctRows(flat, vm.execute(program, catalog, values));
      List<String> columns = rows.isEmpty() ? program.resultColumns() : rows.get(0).columns();
      return Outcome.rows(columns, rows);
    } finally {
      for (String temp : temps) {
        catalog.dropTable(temp);
      }
    }
  }

  private Select flattenDerived(Select select, String sql, Object[] values, List<String> temps) {
    if (select.fromSelect() == null) {
      return select;
    }
    Select inner = flattenDerived(select.fromSelect(), sql, values, temps);
    Program program = compiler.compile(inner, catalog, sql);
    List<Row> rows = distinctRows(inner, vm.execute(program, catalog, values));
    List<String> columns = rows.isEmpty() ? program.resultColumns() : rows.get(0).columns();
    String tempName = "__derived_" + (++derivedSeq);
    temps.add(tempName);
    List<Object[]> data = new ArrayList<>(rows.size());
    for (Row row : rows) {
      data.add(row.values().toArray());
    }
    catalog.loadDerived(tempName, columns, data, sql);
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
        select.offset());
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
      case CreateTable create -> catalog.createTable(
          create.name(), create.columns(), create.constraints(), create.ifNotExists(), sql);
      case CreateIndex index -> catalog.createIndex(
          index.name(),
          index.unique(),
          index.ifNotExists(),
          index.table(),
          index.columns(),
          index.partialColumn(),
          sql);
      case AlterTable alter -> catalog.addColumn(alter.table(), alter.column(), sql);
      case Pragma ignored -> throw new SqlException("PRAGMA is handled by the driver: " + SqlText.snippet(sql));
      default -> {
        Program program = compiler.compile(statement, catalog, sql);
        vm.execute(program, catalog, values);
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
        if (pager == null) {
          throw new SqlException("WAL needs a file database: " + SqlText.snippet(sql));
        }
        WalWriter.ensure(path);
      }
      journalMode = mode;
    }
    return oneColumn("journal_mode", journalMode);
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
      busyTimeout = millis;
    }
    return oneColumn("busy_timeout", busyTimeout);
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
      foreignKeys = on;
      catalog.setEnforceForeignKeys(on);
    }
    return oneColumn("foreign_keys", foreignKeys ? 1L : 0L);
  }

  private Outcome tableInfo(Pragma pragma, String sql) {
    if (pragma.value() != null || pragma.argument() == null) {
      throw new SqlException("table_info needs a table name: " + SqlText.snippet(sql));
    }
    Table table = catalog.find(pragma.argument());
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
      values.add(column.type() == ColumnType.INTEGER ? "INTEGER" : "TEXT");
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
      catalog.beginTransaction();
    }
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
