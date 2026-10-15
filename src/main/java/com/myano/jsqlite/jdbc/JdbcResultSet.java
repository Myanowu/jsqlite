package com.myano.jsqlite.jdbc;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.Row;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

final class JdbcResultSet {
  private final Database database;
  private final List<String> columns;
  private final List<Row> rows;
  private int cursor = -1;
  private boolean wasNull;
  private boolean closed;

  private JdbcResultSet(Database database, List<String> columns, List<Row> rows) {
    this.database = database;
    this.columns = columns;
    this.rows = rows;
  }

  static ResultSet create(List<String> columns, List<Row> rows) {
    return create(null, columns, rows);
  }

  static ResultSet create(Database database, List<String> columns, List<Row> rows) {
    JdbcResultSet result = new JdbcResultSet(database, List.copyOf(columns), List.copyOf(rows));
    return JdbcProxy.create(ResultSet.class, result::call);
  }

  private Object call(Method method, Object[] args) throws SQLException {
    if (method.getName().equals("close") && args.length == 0) {
      closed = true;
      return null;
    }
    if (method.getName().equals("isClosed") && args.length == 0) {
      return closed;
    }
    if (closed) {
      throw new SQLException("result set is closed");
    }
    return switch (method.getName()) {
      case "next" -> next(args);
      case "wasNull" -> wasNull(args);
      case "findColumn" -> findColumn(args);
      case "getMetaData" -> metadata(args);
      case "getString" -> text(args);
      case "getInt" -> integer(args);
      case "getLong" -> longValue(args);
      case "getBoolean" -> bool(args);
      case "getDouble" -> doubleValue(args);
      case "getFloat" -> (float) doubleValue(args);
      case "getBytes" -> bytes(args);
      case "getBigDecimal" -> decimal(args);
      case "getBlob" -> blob(args);
      case "getDate" -> date(args);
      case "getTime" -> time(args);
      case "getTimestamp" -> timestamp(args);
      case "getObject" -> object(args);
      case "getWarnings", "clearWarnings" -> {
        if (args.length != 0) {
          throw new SQLException("ResultSet." + method.getName());
        }
        yield null;
      }
      case "getRow" -> cursor + 1;
      case "isBeforeFirst" -> cursor < 0;
      case "isAfterLast" -> cursor >= rows.size();
      default -> throw JdbcProxy.unsupported(method);
    };
  }

  private boolean next(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("ResultSet.next");
    }
    if (cursor + 1 >= rows.size()) {
      cursor = rows.size();
      return false;
    }
    cursor++;
    return true;
  }

  private boolean wasNull(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("ResultSet.wasNull");
    }
    return wasNull;
  }

  private int findColumn(Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof String name)) {
      throw new SQLException("ResultSet.findColumn");
    }
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).equalsIgnoreCase(name)) {
        return i + 1;
      }
    }
    throw new SQLException("unknown column " + name);
  }

  private ResultSetMetaData metadata(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("ResultSet.getMetaData");
    }
    return JdbcResultSetMetaData.create(database, columns, rows);
  }

  private String text(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return JdbcValues.text(value);
  }

  private int integer(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return JdbcValues.integer(value);
  }

  private long longValue(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return JdbcValues.longValue(value);
  }

  private boolean bool(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return JdbcValues.bool(value);
  }

  private double doubleValue(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return JdbcValues.real(value);
  }

  private byte[] bytes(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return JdbcValues.bytes(value);
  }

  private Object decimal(Object[] args) throws SQLException {
    Object value = cell(args.length > 1 ? new Object[] {args[0]} : args);
    wasNull = value == null;
    return JdbcValues.decimal(value);
  }

  private Object blob(Object[] args) throws SQLException {
    byte[] raw = bytes(args);
    if (raw == null) {
      return null;
    }
    try {
      return new javax.sql.rowset.serial.SerialBlob(raw);
    } catch (SQLException ex) {
      throw new SQLException(ex.getMessage(), ex);
    }
  }

  private Object date(Object[] args) throws SQLException {
    Object stamp = timestamp(args);
    if (!(stamp instanceof java.sql.Timestamp time)) {
      return null;
    }
    return new java.sql.Date(time.getTime());
  }

  private Object time(Object[] args) throws SQLException {
    Object stamp = timestamp(args);
    if (!(stamp instanceof java.sql.Timestamp time)) {
      return null;
    }
    return new java.sql.Time(time.getTime());
  }

  private Object timestamp(Object[] args) throws SQLException {
    if (args.length < 1 || args.length > 2) {
      throw new SQLException("ResultSet.getTimestamp");
    }
    Object value = cell(new Object[] {args[0]});
    wasNull = value == null;
    return JdbcValues.timestamp(value);
  }

  private Object object(Object[] args) throws SQLException {
    Object value = cell(args);
    wasNull = value == null;
    return value;
  }

  private Object cell(Object[] args) throws SQLException {
    int index = column(args);
    if (cursor < 0) {
      // Match xerial: getters may read the first row without an explicit next().
      if (rows.isEmpty()) {
        throw new SQLException("result set is not positioned on a row");
      }
      cursor = 0;
    }
    if (cursor >= rows.size()) {
      throw new SQLException("result set is not positioned on a row");
    }
    List<Object> values = rows.get(cursor).values();
    if (index < 1 || index > values.size()) {
      throw new SQLException("column index " + index);
    }
    return values.get(index - 1);
  }

  private int column(Object[] args) throws SQLException {
    if (args.length != 1) {
      throw new SQLException("column argument");
    }
    if (args[0] instanceof Integer index) {
      return index;
    }
    if (args[0] instanceof String name) {
      return findColumn(new Object[] {name});
    }
    throw new SQLException("column argument");
  }
}
