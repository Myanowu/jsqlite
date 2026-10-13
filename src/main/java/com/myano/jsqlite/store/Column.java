package com.myano.jsqlite.store;

public record Column(
    String name,
    ColumnType type,
    boolean notNull,
    boolean primaryKey,
    boolean autoincrement,
    boolean unique,
    boolean hasDefault,
    Object defaultValue,
    boolean defaultNow) {

  public Column(String name, ColumnType type) {
    this(name, type, false, false, false, false, false, null, false);
  }

  public Column(
      String name,
      ColumnType type,
      boolean notNull,
      boolean primaryKey,
      boolean autoincrement,
      boolean unique,
      boolean hasDefault,
      Object defaultValue) {
    this(name, type, notNull, primaryKey, autoincrement, unique, hasDefault, defaultValue, false);
  }
}
