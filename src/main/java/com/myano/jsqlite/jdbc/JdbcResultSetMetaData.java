package com.myano.jsqlite.jdbc;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.Row;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.TableInfo;

import java.lang.reflect.Method;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

final class JdbcResultSetMetaData {
  private final Database database;
  private final List<String> columns;
  private final List<Row> rows;

  private JdbcResultSetMetaData(Database database, List<String> columns, List<Row> rows) {
    this.database = database;
    this.columns = columns;
    this.rows = rows;
  }

  static ResultSetMetaData create(Database database, List<String> columns, List<Row> rows) {
    JdbcResultSetMetaData meta = new JdbcResultSetMetaData(database, columns, rows);
    return JdbcProxy.create(ResultSetMetaData.class, meta::call);
  }

  private Object call(Method method, Object[] args) throws SQLException {
    return switch (method.getName()) {
      case "getColumnCount" -> {
        if (args.length != 0) {
          throw JdbcProxy.unsupported(method);
        }
        yield columns.size();
      }
      case "getColumnLabel", "getColumnName" -> label(method, args);
      case "getColumnTypeName" -> typeName(method, args);
      case "getColumnType" -> columnType(method, args);
      case "getPrecision" -> precision(method, args);
      case "getScale" -> scale(method, args);
      case "isNullable" -> nullable(method, args);
      case "isAutoIncrement" -> autoIncrement(method, args);
      case "getColumnClassName" -> className(method, args);
      case "isSigned", "isCurrency", "isSearchable", "isCaseSensitive", "isReadOnly", "isWritable",
          "isDefinitelyWritable" -> false;
      case "getColumnDisplaySize" -> 0;
      default -> throw JdbcProxy.unsupported(method);
    };
  }

  private String label(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Integer index)) {
      throw JdbcProxy.unsupported(method);
    }
    if (index < 1 || index > columns.size()) {
      throw new SQLException("column index " + index);
    }
    return columns.get(index - 1);
  }

  private String typeName(Method method, Object[] args) throws SQLException {
    Column column = schemaColumn(method, args);
    if (column != null) {
      return column.type().name();
    }
    if (args.length != 1 || !(args[0] instanceof Integer index)) {
      throw JdbcProxy.unsupported(method);
    }
    if (index < 1 || index > columns.size()) {
      throw new SQLException("column index " + index);
    }
    for (Row row : rows) {
      if (index - 1 >= row.values().size()) {
        continue;
      }
      Object value = row.values().get(index - 1);
      if (value != null) {
        return JdbcValues.typeName(value);
      }
    }
    return "NULL";
  }

  private int columnType(Method method, Object[] args) throws SQLException {
    Column column = schemaColumn(method, args);
    if (column != null) {
      return jdbcType(column.type());
    }
    String name = typeName(method, args);
    return switch (name) {
      case "INTEGER" -> Types.INTEGER;
      case "TEXT" -> Types.VARCHAR;
      case "REAL" -> Types.DOUBLE;
      case "BLOB" -> Types.BLOB;
      default -> Types.NULL;
    };
  }

  private int precision(Method method, Object[] args) throws SQLException {
    Column column = schemaColumn(method, args);
    if (column != null) {
      return column.type() == ColumnType.INTEGER || column.type() == ColumnType.REAL ? 19 : 0;
    }
    return 0;
  }

  private int scale(Method method, Object[] args) throws SQLException {
    schemaColumn(method, args);
    return 0;
  }

  private int nullable(Method method, Object[] args) throws SQLException {
    Column column = schemaColumn(method, args);
    if (column == null) {
      return ResultSetMetaData.columnNullableUnknown;
    }
    if (column.notNull() || column.primaryKey()) {
      return ResultSetMetaData.columnNoNulls;
    }
    return ResultSetMetaData.columnNullable;
  }

  private String className(Method method, Object[] args) throws SQLException {
    return switch (typeName(method, args)) {
      case "INTEGER" -> "java.lang.Long";
      case "REAL" -> "java.lang.Double";
      case "BLOB" -> "[B";
      case "TEXT" -> "java.lang.String";
      default -> "java.lang.Object";
    };
  }

  private static int jdbcType(ColumnType type) {
    return switch (type) {
      case INTEGER -> Types.INTEGER;
      case TEXT -> Types.VARCHAR;
      case REAL -> Types.DOUBLE;
      case BLOB -> Types.BLOB;
    };
  }

  private boolean autoIncrement(Method method, Object[] args) throws SQLException {
    Column column = schemaColumn(method, args);
    return column != null && column.autoincrement();
  }

  private Column schemaColumn(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Integer index)) {
      throw JdbcProxy.unsupported(method);
    }
    if (index < 1 || index > columns.size()) {
      throw new SQLException("column index " + index);
    }
    if (database == null) {
      return null;
    }
    TableInfo matched = matchingTable();
    if (matched != null) {
      return matched.columns().get(index - 1);
    }
    String name = columns.get(index - 1);
    Column unique = null;
    for (TableInfo table : database.tables()) {
      for (Column column : table.columns()) {
        if (!column.name().equalsIgnoreCase(name)) {
          continue;
        }
        if (unique != null) {
          return null;
        }
        unique = column;
      }
    }
    return unique;
  }

  /** Prefer a table whose columns match this result set in order (SELECT *). */
  private TableInfo matchingTable() {
    for (TableInfo table : database.tables()) {
      List<Column> tableColumns = table.columns();
      if (tableColumns.size() != columns.size()) {
        continue;
      }
      boolean same = true;
      for (int i = 0; i < columns.size(); i++) {
        if (!tableColumns.get(i).name().equalsIgnoreCase(columns.get(i))) {
          same = false;
          break;
        }
      }
      if (same) {
        return table;
      }
    }
    return null;
  }
}
