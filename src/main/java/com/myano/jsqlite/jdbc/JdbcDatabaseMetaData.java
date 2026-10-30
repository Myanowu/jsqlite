package com.myano.jsqlite.jdbc;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.Row;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;

import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class JdbcDatabaseMetaData {
  private static final List<String> TABLE_COLUMNS =
      List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS");
  private static final List<String> COLUMN_COLUMNS = List.of(
      "TABLE_CAT",
      "TABLE_SCHEM",
      "TABLE_NAME",
      "COLUMN_NAME",
      "DATA_TYPE",
      "TYPE_NAME",
      "COLUMN_SIZE",
      "DECIMAL_DIGITS",
      "NULLABLE",
      "REMARKS",
      "COLUMN_DEF",
      "ORDINAL_POSITION",
      "IS_NULLABLE",
      "IS_AUTOINCREMENT");
  private static final List<String> KEY_COLUMNS =
      List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");

  private final Database database;
  private final String url;

  private JdbcDatabaseMetaData(Database database, String url) {
    this.database = database;
    this.url = url;
  }

  static DatabaseMetaData create(Database database, String url) {
    JdbcDatabaseMetaData meta = new JdbcDatabaseMetaData(database, url);
    return JdbcProxy.create(DatabaseMetaData.class, meta::call);
  }

  private Object call(Method method, Object[] args) throws SQLException {
    return switch (method.getName()) {
      case "getURL" -> {
        if (args.length != 0) {
          throw JdbcProxy.unsupported(method);
        }
        yield url;
      }
      case "getTables" -> tables(method, args);
      case "getColumns" -> columns(method, args);
      case "getPrimaryKeys" -> primaryKeys(method, args);
      case "getIndexInfo" -> indexInfo(method, args);
      case "getImportedKeys", "getExportedKeys", "getCrossReference" -> emptyKeys();
      case "getDatabaseProductName" -> "SQLite";
      case "getDatabaseProductVersion" -> "3";
      case "getDriverName" -> "JSQLite";
      case "getDriverVersion" -> "0.1";
      case "getIdentifierQuoteString" -> "\"";
      case "getExtraNameCharacters" -> "";
      case "getSQLKeywords" -> "";
      default -> metadataDefault(method);
    };
  }

  private ResultSet tables(Method method, Object[] args) throws SQLException {
    if (args.length != 4) {
      throw JdbcProxy.unsupported(method);
    }
    String pattern = args[2] instanceof String text ? text : null;
    if (!wantsTables(args[3])) {
      return JdbcResultSet.create(database, TABLE_COLUMNS, List.of());
    }
    List<Row> rows = new ArrayList<>();
    for (TableInfo table : database.tables()) {
      if (!matches(pattern, table.name())) {
        continue;
      }
      rows.add(row(TABLE_COLUMNS, null, null, table.name(), "TABLE", null));
    }
    return JdbcResultSet.create(database, TABLE_COLUMNS, rows);
  }

  private ResultSet columns(Method method, Object[] args) throws SQLException {
    if (args.length != 4) {
      throw JdbcProxy.unsupported(method);
    }
    String tablePattern = args[2] instanceof String text ? text : null;
    String columnPattern = args[3] instanceof String text ? text : null;
    List<Row> rows = new ArrayList<>();
    for (TableInfo table : database.tables()) {
      if (!matches(tablePattern, table.name())) {
        continue;
      }
      List<Column> columns = table.columns();
      for (int i = 0; i < columns.size(); i++) {
        Column column = columns.get(i);
        if (!matches(columnPattern, column.name())) {
          continue;
        }
        boolean required = column.notNull() || keySeq(table, i) > 0;
        int jdbcType = jdbcType(column.type());
        rows.add(row(
            COLUMN_COLUMNS,
            null,
            null,
            table.name(),
            column.name(),
            (long) jdbcType,
            column.type().name(),
            column.type() == ColumnType.TEXT || column.type() == ColumnType.BLOB ? 0L : 19L,
            0L,
            (long) (required ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable),
            null,
            defaultText(column),
            (long) (i + 1),
            required ? "NO" : "YES",
            column.autoincrement() ? "YES" : "NO"));
      }
    }
    return JdbcResultSet.create(database, COLUMN_COLUMNS, rows);
  }

  private ResultSet indexInfo(Method method, Object[] args) throws SQLException {
    if (args.length != 5) {
      throw JdbcProxy.unsupported(method);
    }
    String tableName = args[2] instanceof String text ? text : null;
    boolean uniqueOnly = args[3] instanceof Boolean flag && flag;
    List<String> columns = List.of(
        "TABLE_CAT",
        "TABLE_SCHEM",
        "TABLE_NAME",
        "NON_UNIQUE",
        "INDEX_QUALIFIER",
        "INDEX_NAME",
        "TYPE",
        "ORDINAL_POSITION",
        "COLUMN_NAME",
        "ASC_OR_DESC",
        "CARDINALITY",
        "PAGES",
        "FILTER_CONDITION");
    List<Row> rows = new ArrayList<>();
    for (IndexDef index : database.indexes()) {
      if (tableName != null && !index.table().equalsIgnoreCase(tableName)) {
        continue;
      }
      if (uniqueOnly && !index.unique()) {
        continue;
      }
      List<String> names = index.columns();
      for (int i = 0; i < names.size(); i++) {
        rows.add(row(
            columns,
            null,
            null,
            index.table(),
            index.unique() ? 0L : 1L,
            null,
            index.name(),
            (long) DatabaseMetaData.tableIndexOther,
            (long) (i + 1),
            names.get(i),
            "A",
            null,
            null,
            null));
      }
    }
    return JdbcResultSet.create(database, columns, rows);
  }

  private ResultSet emptyKeys() {
    return JdbcResultSet.create(database, List.of(
        "PKTABLE_CAT",
        "PKTABLE_SCHEM",
        "PKTABLE_NAME",
        "PKCOLUMN_NAME",
        "FKTABLE_CAT",
        "FKTABLE_SCHEM",
        "FKTABLE_NAME",
        "FKCOLUMN_NAME",
        "KEY_SEQ",
        "UPDATE_RULE",
        "DELETE_RULE",
        "FK_NAME",
        "PK_NAME",
        "DEFERRABILITY"), List.of());
  }

  private Object metadataDefault(Method method) throws SQLException {
    Class<?> type = method.getReturnType();
    if (type == boolean.class) {
      return switch (method.getName()) {
        case "supportsTransactions", "supportsGetGeneratedKeys", "supportsBatchUpdates",
            "supportsMixedCaseIdentifiers", "supportsMixedCaseQuotedIdentifiers",
            "storesMixedCaseIdentifiers", "storesMixedCaseQuotedIdentifiers",
            "nullsAreSortedHigh", "nullsAreSortedAtStart" -> true;
        default -> false;
      };
    }
    if (type == int.class) {
      return switch (method.getName()) {
        case "getDatabaseMajorVersion" -> 3;
        case "getDriverMinorVersion" -> 1;
        // Write-serial / read-shared — not snapshot isolation (jsqlite-concurrency.md).
        case "getDefaultTransactionIsolation" -> java.sql.Connection.TRANSACTION_SERIALIZABLE;
        default -> 0;
      };
    }
    if (type == String.class) {
      return "";
    }
    if (ResultSet.class.isAssignableFrom(type)) {
      return JdbcResultSet.create(database, List.of(), List.of());
    }
    throw JdbcProxy.unsupported(method);
  }

  private static int jdbcType(ColumnType type) {
    return switch (type) {
      case INTEGER -> Types.INTEGER;
      case TEXT -> Types.VARCHAR;
      case REAL -> Types.DOUBLE;
      case BLOB -> Types.BLOB;
    };
  }

  private ResultSet primaryKeys(Method method, Object[] args) throws SQLException {
    if (args.length != 3) {
      throw JdbcProxy.unsupported(method);
    }
    String tableName = args[2] instanceof String text ? text : null;
    List<Row> rows = new ArrayList<>();
    for (TableInfo table : database.tables()) {
      if (tableName != null && !table.name().equalsIgnoreCase(tableName)) {
        continue;
      }
      List<String> key = table.primaryKey();
      for (int i = 0; i < key.size(); i++) {
        rows.add(row(KEY_COLUMNS, null, null, table.name(), key.get(i), (long) (i + 1), null));
      }
    }
    return JdbcResultSet.create(database, KEY_COLUMNS, rows);
  }

  private static int keySeq(TableInfo table, int column) {
    List<Column> columns = table.columns();
    if (column < 0 || column >= columns.size()) {
      return 0;
    }
    String name = columns.get(column).name();
    List<String> key = table.primaryKey();
    for (int i = 0; i < key.size(); i++) {
      if (key.get(i).equalsIgnoreCase(name)) {
        return i + 1;
      }
    }
    return 0;
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

  private static boolean wantsTables(Object types) {
    if (!(types instanceof String[] names) || names.length == 0) {
      return true;
    }
    for (String name : names) {
      if (name != null && name.equalsIgnoreCase("TABLE")) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(String pattern, String name) {
    return pattern == null || pattern.equals("%") || name.equalsIgnoreCase(pattern);
  }

  private static Row row(List<String> columns, Object... values) {
    List<Object> cells = new ArrayList<>();
    for (Object value : values) {
      cells.add(value);
    }
    return new Row(columns, cells);
  }
}
