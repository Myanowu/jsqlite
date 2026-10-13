package com.myano.jsqlite.jdbc;

import com.myano.jsqlite.Bindings;
import com.myano.jsqlite.Database;
import com.myano.jsqlite.Row;
import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Parameters;
import com.myano.jsqlite.sql.Parser;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class JdbcStatement {
  private final Database database;
  private final String preparedSql;
  private final boolean generatedKeys;
  private final Object[] parameters;
  private final boolean[] bound;
  private final List<Object[]> batch = new ArrayList<>();
  private boolean closed;
  private Database.Outcome outcome;

  private JdbcStatement(Database database, String preparedSql, boolean generatedKeys) {
    this.database = database;
    this.preparedSql = preparedSql;
    this.generatedKeys = generatedKeys;
    int count = preparedSql == null ? 0 : parameterCount(preparedSql);
    this.parameters = new Object[count];
    this.bound = new boolean[count];
  }

  static Statement create(Database database) {
    JdbcStatement statement = new JdbcStatement(database, null, false);
    return JdbcProxy.create(Statement.class, statement::call);
  }

  static PreparedStatement prepare(Database database, String sql, boolean generatedKeys) {
    JdbcStatement statement = new JdbcStatement(database, sql, generatedKeys);
    return JdbcProxy.create(PreparedStatement.class, statement::call);
  }

  private Object call(Method method, Object[] args) throws SQLException {
    if (method.getName().equals("close") && args.length == 0) {
      closed = true;
      outcome = null;
      return null;
    }
    if (method.getName().equals("isClosed") && args.length == 0) {
      return closed;
    }
    if (closed) {
      throw new SQLException("statement is closed");
    }
    return switch (method.getName()) {
      case "execute" -> execute(method, args);
      case "executeQuery" -> executeQuery(method, args);
      case "executeUpdate" -> executeUpdate(method, args);
      case "getResultSet" -> resultSet(args);
      case "getUpdateCount" -> updateCount(args);
      case "getGeneratedKeys" -> generatedKeys(args);
      case "setObject" -> setObject(method, args);
      case "setNull" -> setNull(method, args);
      case "setString" -> setValue(method, args, stringValue(args));
      case "setInt" -> setValue(method, args, intValue(args));
      case "setLong" -> setValue(method, args, longValue(args));
      case "setBoolean" -> setValue(method, args, boolValue(args));
      case "setTimestamp" -> setTimestamp(method, args);
      case "setFetchSize", "setMaxRows" -> ignoreIntSetter(method, args);
      case "getFetchSize", "getMaxRows" -> 0;
      case "addBatch" -> addBatch(method, args);
      case "executeBatch" -> executeBatch(method, args);
      case "clearBatch" -> {
        if (args.length != 0) {
          throw JdbcProxy.unsupported(method);
        }
        batch.clear();
        yield null;
      }
      case "clearParameters" -> {
        if (args.length != 0) {
          throw JdbcProxy.unsupported(method);
        }
        Arrays.fill(parameters, null);
        Arrays.fill(bound, false);
        yield null;
      }
      default -> throw JdbcProxy.unsupported(method);
    };
  }

  private Object addBatch(Method method, Object[] args) throws SQLException {
    if (args.length != 0) {
      throw JdbcProxy.unsupported(method);
    }
    if (preparedSql == null) {
      throw new SQLException("addBatch requires a prepared statement");
    }
    for (int i = 0; i < bound.length; i++) {
      if (!bound[i]) {
        throw new SQLException("parameter " + (i + 1) + " is not set");
      }
    }
    batch.add(parameters.clone());
    return null;
  }

  private int[] executeBatch(Method method, Object[] args) throws SQLException {
    if (args.length != 0) {
      throw JdbcProxy.unsupported(method);
    }
    if (preparedSql == null) {
      throw new SQLException("executeBatch requires a prepared statement");
    }
    int[] counts = new int[batch.size()];
    for (int i = 0; i < batch.size(); i++) {
      outcome = database.run(preparedSql, batch.get(i));
      if (outcome.hasResultSet()) {
        throw new SQLException("batch statement returned a result set");
      }
      counts[i] = outcome.updateCount();
    }
    batch.clear();
    return counts;
  }

  private Object ignoreIntSetter(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Integer)) {
      throw JdbcProxy.unsupported(method);
    }
    return null;
  }

  private Object setTimestamp(Method method, Object[] args) throws SQLException {
    if (args.length != 2) {
      throw JdbcProxy.unsupported(method);
    }
    try {
      bind(method, args, Bindings.coerce(args[1]));
    } catch (SqlException ex) {
      throw new SQLException(ex.getMessage(), ex);
    }
    return null;
  }

  private boolean execute(Method method, Object[] args) throws SQLException {
    outcome = database.run(sql(method, args), bindings(args));
    return outcome.hasResultSet();
  }

  private ResultSet executeQuery(Method method, Object[] args) throws SQLException {
    if (!execute(method, args)) {
      throw new SQLException("statement did not return a result set");
    }
    return resultSet(new Object[0]);
  }

  private int executeUpdate(Method method, Object[] args) throws SQLException {
    if (execute(method, args)) {
      throw new SQLException("statement returned a result set");
    }
    return outcome.updateCount();
  }

  private ResultSet resultSet(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Statement.getResultSet");
    }
    if (outcome == null || !outcome.hasResultSet()) {
      return null;
    }
    return JdbcResultSet.create(database, outcome.columns(), outcome.rows());
  }

  private int updateCount(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Statement.getUpdateCount");
    }
    if (outcome == null || outcome.hasResultSet()) {
      return -1;
    }
    return outcome.updateCount();
  }

  private ResultSet generatedKeys(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Statement.getGeneratedKeys");
    }
    if (!generatedKeys || outcome == null || outcome.generatedKey() == null) {
      return JdbcResultSet.create(database, List.of("id"), List.of());
    }
    Row row = new Row(List.of("id"), List.of(outcome.generatedKey()));
    return JdbcResultSet.create(database, List.of("id"), List.of(row));
  }

  private Object setObject(Method method, Object[] args) throws SQLException {
    if (args.length != 2) {
      throw JdbcProxy.unsupported(method);
    }
    try {
      bind(method, args, Bindings.coerce(args[1]));
    } catch (SqlException ex) {
      throw new SQLException(ex.getMessage(), ex);
    }
    return null;
  }

  private Object setNull(Method method, Object[] args) throws SQLException {
    if (args.length != 2) {
      throw JdbcProxy.unsupported(method);
    }
    bind(method, args, null);
    return null;
  }

  private Object setValue(Method method, Object[] args, Object value) throws SQLException {
    if (args.length != 2) {
      throw JdbcProxy.unsupported(method);
    }
    bind(method, args, value);
    return null;
  }

  private void bind(Method method, Object[] args, Object value) throws SQLException {
    if (!(args[0] instanceof Integer index)) {
      throw JdbcProxy.unsupported(method);
    }
    if (index < 1 || index > parameters.length) {
      throw new SQLException("parameter index " + index);
    }
    parameters[index - 1] = value;
    bound[index - 1] = true;
  }

  private String sql(Method method, Object[] args) throws SQLException {
    if (args.length == 0) {
      if (preparedSql == null) {
        throw new SQLException("statement has no SQL");
      }
      return preparedSql;
    }
    if (args.length == 1 && args[0] instanceof String text) {
      return text;
    }
    throw JdbcProxy.unsupported(method);
  }

  private Object[] bindings(Object[] args) throws SQLException {
    if (args.length != 0) {
      return new Object[0];
    }
    for (int i = 0; i < bound.length; i++) {
      if (!bound[i]) {
        throw new SQLException("parameter " + (i + 1) + " is not set");
      }
    }
    return parameters.clone();
  }

  private static Object stringValue(Object[] args) {
    return args.length == 2 ? args[1] : null;
  }

  private static Object intValue(Object[] args) {
    return args.length == 2 && args[1] instanceof Integer number ? number : null;
  }

  private static Object longValue(Object[] args) {
    return args.length == 2 && args[1] instanceof Long number ? number : null;
  }

  private static Object boolValue(Object[] args) {
    return args.length == 2 && args[1] instanceof Boolean flag ? flag : null;
  }

  private static int parameterCount(String sql) {
    return Parameters.count(new Parser().parse(sql));
  }
}
