package com.myano.jsqlite.admin;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.Row;
import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.SqliteReader;
import com.myano.jsqlite.sql.Ast.Join;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.SelectItem;
import com.myano.jsqlite.sql.Ast.Star;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Labels;
import com.myano.jsqlite.sql.Parser;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AdminSession implements AutoCloseable {
  private final Parser parser = new Parser();
  private final Map<Path, Database> kept = new LinkedHashMap<>();
  private Database database;

  public void create(Path path) {
    Objects.requireNonNull(path, "path");
    if (kept.containsKey(path)) {
      throw new SqlException("資料庫已經存在：" + path);
    }
    if (SqliteReader.isDatabase(path)) {
      throw new SqlException("這個 sqlite3 檔已經存在，請用「打開資料庫」：" + path);
    }
    Database created = Database.open(path);
    kept.put(path, created);
    database = created;
  }

  public void open(Path path) {
    Objects.requireNonNull(path, "path");
    if (SqliteReader.isDatabase(path)) {
      Database opened = Database.open(path);
      Database previous = kept.put(path, opened);
      if (previous != null) {
        previous.close();
      }
      database = opened;
      return;
    }
    Database existing = kept.get(path);
    if (existing == null) {
      throw new SqlException("找不到資料庫 " + path + "。這個階段的資料只在這次執行的記憶體裡，還不能從檔案讀回來。");
    }
    database = existing;
  }

  public boolean readOnly() {
    return database != null && database.readOnly();
  }

  public List<Path> databases() {
    return List.copyOf(kept.keySet());
  }

  public boolean isOpen() {
    return database != null;
  }

  public Path path() {
    return require().path();
  }

  public List<TableInfo> tables() {
    return require().tables();
  }

  public List<IndexDef> indexes() {
    return require().indexes();
  }

  public String createTable(String name, List<Column> columns) {
    String sql = SqlForms.createTable(name, columns);
    run(sql);
    return sql;
  }

  public String insertRow(String table, List<Object> values) {
    String sql = SqlForms.insert(table, values);
    run(sql);
    return sql;
  }

  public void redefineTable(
      String table,
      String newName,
      List<Column> columns,
      int[] source,
      List<String> primaryKey) {
    String sql = SqlForms.create(new TableInfo(newName, columns, 0, null, primaryKey));
    require().redefineTable(table, newName, columns, source, primaryKey, sql);
  }

  public void updateRow(String table, List<Object> original, List<Object> values) {
    require().updateRow(table, original, values);
  }

  public void deleteRows(String table, List<List<Object>> rows) {
    require().deleteRows(table, rows);
  }

  public ScriptResult run(String sql) {
    Database current = require();
    if (sql == null || sql.isBlank()) {
      throw new SqlException("empty SQL");
    }
    Statement statement = parser.parse(sql);
    if (statement instanceof Select select) {
      List<Row> rows = current.query(sql);
      List<String> columns = rows.isEmpty() ? outputColumns(current, select) : rows.get(0).columns();
      return ScriptResult.query(columns, rows);
    }
    current.execute(sql);
    return ScriptResult.executed();
  }

  public void closeDatabase() {
    database = null;
  }

  @Override
  public void close() {
    database = null;
    for (Database stored : kept.values()) {
      stored.close();
    }
    kept.clear();
  }

  private Database require() {
    if (database == null) {
      throw new IllegalStateException("尚未開啟資料庫");
    }
    return database;
  }

  private List<String> outputColumns(Database current, Select select) {
    if (select.items().size() == 1 && select.items().get(0) instanceof Star) {
      List<String> names = new ArrayList<>();
      addStar(current, names, select.table());
      for (Join join : select.joins()) {
        addStar(current, names, join.table());
      }
      return names;
    }
    List<String> names = new ArrayList<>();
    for (SelectItem item : select.items()) {
      names.add(Labels.item(item));
    }
    return names;
  }

  private void addStar(Database current, List<String> names, String tableName) {
    if (tableName == null) {
      return;
    }
    for (TableInfo table : current.tables()) {
      if (table.name().equalsIgnoreCase(tableName)) {
        for (Column column : table.columns()) {
          names.add(column.name());
        }
        return;
      }
    }
  }
}
