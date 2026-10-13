package com.myano.jsqlite.jdbc;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.Pager;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.Executor;

public final class JdbcConnection {
  private final Database database;
  private final String url;
  private boolean closed;
  private boolean readOnly;
  private int transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
  private int networkTimeoutMs;
  private String catalog = "";
  private String schema;

  private JdbcConnection(Database database, String url) {
    this.database = database;
    this.url = url;
  }

  public static Connection open(String url) throws SQLException {
    Target target = Target.parse(url);
    Database database;
    try {
      Path path = target.path();
      if (!Files.exists(path)) {
        Pager.create(path);
        database = Database.openFile(path);
      } else {
        database = Database.open(path);
      }
    } catch (SqlException ex) {
      throw new SQLException(ex.getMessage(), ex);
    }
    try {
      if (target.journalMode() != null) {
        database.run("PRAGMA journal_mode=" + target.journalMode());
      }
      if (target.busyTimeout() != null) {
        database.run("PRAGMA busy_timeout=" + target.busyTimeout());
      }
    } catch (RuntimeException ex) {
      database.close();
      if (ex instanceof SqlException sql) {
        throw new SQLException(sql.getMessage(), sql);
      }
      throw ex;
    }
    JdbcConnection connection = new JdbcConnection(database, url);
    return JdbcProxy.create(Connection.class, connection::call);
  }

  private Object call(Method method, Object[] args) throws SQLException {
    if (method.getName().equals("isClosed") && args.length == 0) {
      return closed;
    }
    if (method.getName().equals("close") && args.length == 0) {
      closed = true;
      database.close();
      return null;
    }
    if (closed) {
      throw new SQLException("connection is closed");
    }
    return switch (method.getName()) {
      case "createStatement" -> createStatement(method, args);
      case "prepareStatement" -> prepare(method, args);
      case "setAutoCommit" -> setAutoCommit(method, args);
      case "getAutoCommit" -> getAutoCommit(args);
      case "commit" -> commit(method, args);
      case "rollback" -> rollback(method, args);
      case "getCatalog" -> getCatalog(args);
      case "setCatalog" -> setCatalog(method, args);
      case "getSchema" -> getSchema(args);
      case "setSchema" -> setSchema(method, args);
      case "getMetaData" -> metadata(args);
      case "isReadOnly" -> isReadOnly(args);
      case "setReadOnly" -> setReadOnly(method, args);
      case "getTransactionIsolation" -> getTransactionIsolation(args);
      case "setTransactionIsolation" -> setTransactionIsolation(method, args);
      case "isValid" -> isValid(method, args);
      case "getNetworkTimeout" -> getNetworkTimeout(args);
      case "setNetworkTimeout" -> setNetworkTimeout(method, args);
      case "clearWarnings" -> clearWarnings(args);
      case "getWarnings" -> getWarnings(args);
      case "nativeSQL" -> nativeSql(method, args);
      case "getHoldability" -> getHoldability(args);
      case "setHoldability" -> setHoldability(method, args);
      case "getTypeMap" -> getTypeMap(args);
      case "setClientInfo" -> setClientInfo(method, args);
      case "getClientInfo" -> getClientInfo(method, args);
      default -> throw JdbcProxy.unsupported(method);
    };
  }

  private Statement createStatement(Method method, Object[] args) throws SQLException {
    if (args.length != 0) {
      throw JdbcProxy.unsupported(method);
    }
    return JdbcStatement.create(database);
  }

  private PreparedStatement prepare(Method method, Object[] args) throws SQLException {
    if (args.length == 1 && args[0] instanceof String sql) {
      return JdbcStatement.prepare(database, sql, false);
    }
    if (args.length == 2 && args[0] instanceof String sql && args[1] instanceof Integer keys) {
      if (keys != Statement.RETURN_GENERATED_KEYS && keys != Statement.NO_GENERATED_KEYS) {
        throw new SQLException("unsupported generated keys mode " + keys);
      }
      return JdbcStatement.prepare(database, sql, keys == Statement.RETURN_GENERATED_KEYS);
    }
    throw JdbcProxy.unsupported(method);
  }

  private Object setAutoCommit(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Boolean auto)) {
      throw JdbcProxy.unsupported(method);
    }
    database.setAutoCommit(auto);
    return null;
  }

  private boolean getAutoCommit(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getAutoCommit");
    }
    return database.autoCommit();
  }

  private Object commit(Method method, Object[] args) throws SQLException {
    if (args.length != 0) {
      throw JdbcProxy.unsupported(method);
    }
    database.commit();
    return null;
  }

  private Object rollback(Method method, Object[] args) throws SQLException {
    if (args.length != 0) {
      throw JdbcProxy.unsupported(method);
    }
    database.rollback();
    return null;
  }

  private String getCatalog(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getCatalog");
    }
    return catalog;
  }

  private Object setCatalog(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || (args[0] != null && !(args[0] instanceof String))) {
      throw JdbcProxy.unsupported(method);
    }
    catalog = args[0] == null ? "" : (String) args[0];
    return null;
  }

  private String getSchema(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getSchema");
    }
    return schema;
  }

  private Object setSchema(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || (args[0] != null && !(args[0] instanceof String))) {
      throw JdbcProxy.unsupported(method);
    }
    schema = (String) args[0];
    return null;
  }

  private boolean isReadOnly(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.isReadOnly");
    }
    return readOnly;
  }

  private Object setReadOnly(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Boolean value)) {
      throw JdbcProxy.unsupported(method);
    }
    readOnly = value;
    return null;
  }

  private int getTransactionIsolation(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getTransactionIsolation");
    }
    return transactionIsolation;
  }

  private Object setTransactionIsolation(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Integer level)) {
      throw JdbcProxy.unsupported(method);
    }
    transactionIsolation = level;
    return null;
  }

  private boolean isValid(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Integer timeout)) {
      throw JdbcProxy.unsupported(method);
    }
    if (timeout < 0) {
      throw new SQLException("timeout must be non-negative");
    }
    return !closed;
  }

  private int getNetworkTimeout(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getNetworkTimeout");
    }
    return networkTimeoutMs;
  }

  private Object setNetworkTimeout(Method method, Object[] args) throws SQLException {
    if (args.length != 2 || !(args[1] instanceof Integer millis)) {
      throw JdbcProxy.unsupported(method);
    }
    if (args[0] != null && !(args[0] instanceof Executor)) {
      throw JdbcProxy.unsupported(method);
    }
    if (millis < 0) {
      throw new SQLException("network timeout must be non-negative");
    }
    networkTimeoutMs = millis;
    return null;
  }

  private Object clearWarnings(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.clearWarnings");
    }
    return null;
  }

  private Object getWarnings(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getWarnings");
    }
    return null;
  }

  private String nativeSql(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof String sql)) {
      throw JdbcProxy.unsupported(method);
    }
    return sql;
  }

  private int getHoldability(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getHoldability");
    }
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  private Object setHoldability(Method method, Object[] args) throws SQLException {
    if (args.length != 1 || !(args[0] instanceof Integer)) {
      throw JdbcProxy.unsupported(method);
    }
    return null;
  }

  private Object getTypeMap(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getTypeMap");
    }
    return java.util.Collections.emptyMap();
  }

  private Object setClientInfo(Method method, Object[] args) throws SQLException {
    if (args.length == 1 || args.length == 2) {
      return null;
    }
    throw JdbcProxy.unsupported(method);
  }

  private Object getClientInfo(Method method, Object[] args) throws SQLException {
    if (args.length == 0) {
      return new java.util.Properties();
    }
    if (args.length == 1 && args[0] instanceof String) {
      return null;
    }
    throw JdbcProxy.unsupported(method);
  }

  private DatabaseMetaData metadata(Object[] args) throws SQLException {
    if (args.length != 0) {
      throw new SQLException("Connection.getMetaData");
    }
    return JdbcDatabaseMetaData.create(database, url);
  }

  private record Target(Path path, String journalMode, Long busyTimeout) {
    private static Target parse(String url) throws SQLException {
      String rest;
      if (url.startsWith("jdbc:sqlite:")) {
        rest = url.substring("jdbc:sqlite:".length());
      } else if (url.startsWith("jdbc:jsqlite:")) {
        rest = url.substring("jdbc:jsqlite:".length());
      } else {
        throw new SQLException("unsupported url");
      }
      int query = rest.indexOf('?');
      String pathText = query >= 0 ? rest.substring(0, query) : rest;
      String parameters = query >= 0 ? rest.substring(query + 1) : "";
      if (pathText.isBlank()) {
        throw new SQLException("database path is missing");
      }
      String journalMode = null;
      Long busyTimeout = null;
      if (!parameters.isEmpty()) {
        for (String part : parameters.split("&", -1)) {
          if (part.isEmpty()) {
            continue;
          }
          int eq = part.indexOf('=');
          if (eq <= 0) {
            throw new SQLException("unknown connection property: " + part);
          }
          String key = part.substring(0, eq).toLowerCase(Locale.ROOT);
          String value = part.substring(eq + 1);
          switch (key) {
            case "journal_mode" -> {
              String mode = value.toLowerCase(Locale.ROOT);
              if (!mode.equals("wal") && !mode.equals("delete")) {
                throw new SQLException("unsupported journal mode " + value);
              }
              journalMode = mode;
            }
            case "busy_timeout" -> {
              try {
                long millis = Long.parseLong(value);
                if (millis < 0) {
                  throw new NumberFormatException();
                }
                busyTimeout = millis;
              } catch (NumberFormatException ex) {
                throw new SQLException("busy_timeout needs a non-negative integer");
              }
            }
            default -> throw new SQLException("unknown connection property: " + key);
          }
        }
      }
      return new Target(Path.of(pathText), journalMode, busyTimeout);
    }
  }
}
