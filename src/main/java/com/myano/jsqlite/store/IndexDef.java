package com.myano.jsqlite.store;

import java.util.List;

public record IndexDef(
    String name,
    String table,
    List<String> columns,
    boolean unique,
    String partialColumn,
    String sql) {

  public IndexDef {
    columns = List.copyOf(columns);
  }

  public IndexDef(
      String name,
      String table,
      List<String> columns,
      boolean unique,
      String partialColumn) {
    this(name, table, columns, unique, partialColumn, null);
  }
}
