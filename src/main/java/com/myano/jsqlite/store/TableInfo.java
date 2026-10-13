package com.myano.jsqlite.store;

import java.util.List;
import java.util.Objects;

public record TableInfo(String name, List<Column> columns, int rowCount, String sql, List<String> primaryKey) {
  public TableInfo {
    name = Objects.requireNonNull(name, "name");
    columns = List.copyOf(columns);
    if (rowCount < 0) {
      throw new IllegalArgumentException("rowCount");
    }
    primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
  }

  public TableInfo(String name, List<Column> columns, int rowCount) {
    this(name, columns, rowCount, null, List.of());
  }
}
