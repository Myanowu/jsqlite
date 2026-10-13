package com.myano.jsqlite.sql;

public final class SqlText {
  private SqlText() {}

  public static String snippet(String sql) {
    if (sql == null) {
      return "";
    }
    String collapsed = sql.strip().replaceAll("\\s+", " ");
    if (collapsed.length() <= 160) {
      return collapsed;
    }
    return collapsed.substring(0, 160) + "...";
  }
}
