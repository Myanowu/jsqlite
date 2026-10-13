package com.myano.jsqlite;

import com.myano.jsqlite.jdbc.JdbcConnection;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public final class JDBC implements Driver {
  static {
    try {
      DriverManager.registerDriver(new JDBC());
    } catch (SQLException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  public JDBC() {}

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    return JdbcConnection.open(url);
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && (url.startsWith("jdbc:sqlite:") || url.startsWith("jdbc:jsqlite:"));
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 1;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("Driver.getParentLogger");
  }
}
