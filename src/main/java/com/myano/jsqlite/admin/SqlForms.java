package com.myano.jsqlite.admin;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SqlForms {
  private static final Set<String> KEYWORDS = Set.of(
      "CREATE", "TABLE", "INTEGER", "TEXT", "INSERT", "INTO", "VALUES",
      "SELECT", "DISTINCT", "FROM", "AS", "WHERE", "UPDATE", "SET", "DELETE", "AND", "OR", "NULL",
      "IF", "NOT", "EXISTS", "PRIMARY", "KEY", "AUTOINCREMENT", "UNIQUE", "DEFAULT",
      "INDEX", "ON", "JOIN", "INNER", "LEFT", "CROSS", "ORDER", "BY", "LIMIT", "OFFSET",
      "ASC", "DESC", "ALTER", "ADD", "COLUMN", "CONFLICT", "DO", "IGNORE", "IS",
      "LIKE", "ESCAPE", "IFNULL", "DATETIME", "COUNT", "MAX", "REFERENCES", "FOREIGN", "CONSTRAINT");

  private SqlForms() {}

  static String createTable(String name, List<Column> columns) {
    String table = identifier(name, "資料表名稱");
    if (columns == null || columns.isEmpty()) {
      throw new SqlException("至少需要一個欄位");
    }
    Set<String> seen = new HashSet<>();
    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE ").append(table).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      String columnName = identifier(column.name(), "欄位名稱");
      if (!seen.add(columnName)) {
        throw new SqlException("欄位名稱重複：" + columnName);
      }
      if (column.type() == null) {
        throw new SqlException("欄位 " + columnName + " 還沒有型別");
      }
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(columnName).append(' ').append(column.type().name());
    }
    sql.append(')');
    return sql.toString();
  }

  static String insert(String table, List<Object> values) {
    String name = identifier(table, "資料表名稱");
    if (values == null || values.isEmpty()) {
      throw new SqlException("沒有可插入的值");
    }
    StringBuilder sql = new StringBuilder();
    sql.append("INSERT INTO ").append(name).append(" VALUES (");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(literal(values.get(i)));
    }
    sql.append(')');
    return sql.toString();
  }

  static String insertValues(TableInfo table, List<Object> values) {
    List<Column> columns = table.columns();
    if (values == null || values.size() != columns.size()) {
      throw new SqlException("欄位數量不一致");
    }
    StringBuilder sql = new StringBuilder();
    sql.append("INSERT INTO ").append(quote(table.name())).append(" (\n");
    appendColumns(sql, names(columns), "  ");
    sql.append(") VALUES (\n");
    for (int i = 0; i < values.size(); i++) {
      sql.append("  ").append(literal(values.get(i)));
      if (i + 1 < values.size()) {
        sql.append(',');
      }
      sql.append('\n');
    }
    sql.append(')');
    return sql.toString();
  }

  static String selectAll(TableInfo table) {
    return "SELECT *\nFROM " + quote(table.name());
  }

  static String selectColumns(TableInfo table) {
    StringBuilder sql = new StringBuilder("SELECT\n");
    appendColumns(sql, names(table.columns()), "  ");
    sql.append("FROM ").append(quote(table.name()));
    return sql.toString();
  }

  static String countRows(TableInfo table) {
    return "SELECT COUNT(*)\nFROM " + quote(table.name());
  }

  static String selectColumn(TableInfo table, Column column) {
    return "SELECT " + quote(column.name()) + "\nFROM " + quote(table.name());
  }

  static String selectMatching(TableInfo table, Column column, Object value, int limit) {
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT *\nFROM ").append(quote(table.name())).append("\nWHERE ");
    sql.append(quote(column.name()));
    if (value == null) {
      sql.append(" IS NULL");
    } else {
      sql.append(" = ").append(literal(value));
    }
    if (limit > 0) {
      sql.append("\nLIMIT ").append(limit);
    }
    return sql.toString();
  }

  static String filterColumn(TableInfo table, Column column) {
    return selectAll(table) + "\nWHERE " + quote(column.name()) + " = " + example(column);
  }

  static String orderBy(TableInfo table, Column column) {
    return selectAll(table) + "\nORDER BY " + quote(column.name());
  }

  static String insert(TableInfo table) {
    StringBuilder sql = new StringBuilder();
    sql.append("INSERT INTO ").append(quote(table.name())).append(" (\n");
    appendColumns(sql, names(table.columns()), "  ");
    sql.append(") VALUES (\n");
    List<Column> columns = table.columns();
    for (int i = 0; i < columns.size(); i++) {
      sql.append("  ").append(insertValue(table, columns.get(i)));
      if (i + 1 < columns.size()) {
        sql.append(',');
      }
      sql.append('\n');
    }
    sql.append(')');
    return sql.toString();
  }

  static String update(TableInfo table) {
    List<Column> assignments = assignments(table);
    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE ").append(quote(table.name())).append("\nSET\n");
    for (int i = 0; i < assignments.size(); i++) {
      Column column = assignments.get(i);
      sql.append("  ").append(quote(column.name())).append(" = ").append(example(column));
      if (i + 1 < assignments.size()) {
        sql.append(',');
      }
      sql.append('\n');
    }
    sql.append(where(table));
    return sql.toString();
  }

  static String updateColumn(TableInfo table, Column column) {
    return "UPDATE " + quote(table.name())
        + "\nSET " + quote(column.name()) + " = " + example(column)
        + "\n" + where(table);
  }

  static String delete(TableInfo table) {
    return "DELETE FROM " + quote(table.name()) + "\n" + where(table);
  }

  static String updateExisting(TableInfo table, List<Object> original, List<Object> values) {
    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE ").append(quote(table.name())).append("\nSET\n");
    List<Column> columns = table.columns();
    for (int i = 0; i < columns.size(); i++) {
      sql.append("  ").append(quote(columns.get(i).name())).append(" = ").append(literal(values.get(i)));
      if (i + 1 < columns.size()) {
        sql.append(',');
      }
      sql.append('\n');
    }
    sql.append(whereValues(table, original));
    return sql.toString();
  }

  static String create(TableInfo table) {
    if (table.sql() != null && !table.sql().isBlank()) {
      return table.sql().strip();
    }
    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE ").append(quote(table.name())).append(" (\n");
    List<Column> columns = table.columns();
    for (int i = 0; i < columns.size(); i++) {
      sql.append("  ").append(columnDefinition(columns.get(i)));
      boolean more = i + 1 < columns.size() || needsTablePrimaryKey(table);
      if (more) {
        sql.append(',');
      }
      sql.append('\n');
    }
    if (needsTablePrimaryKey(table)) {
      sql.append("  PRIMARY KEY (");
      List<String> keys = table.primaryKey();
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) {
          sql.append(", ");
        }
        sql.append(quote(keys.get(i)));
      }
      sql.append(")\n");
    }
    sql.append(')');
    return sql.toString();
  }

  static String createIndex(IndexDef index) {
    StringBuilder sql = new StringBuilder("CREATE ");
    if (index.unique()) {
      sql.append("UNIQUE ");
    }
    sql.append("INDEX ").append(quote(index.name()))
        .append(" ON ").append(quote(index.table()))
        .append(" (");
    List<String> columns = index.columns();
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(quote(columns.get(i)));
    }
    sql.append(')');
    if (index.partialColumn() != null) {
      sql.append(" WHERE ").append(quote(index.partialColumn())).append(" IS NOT NULL");
    }
    return sql.toString();
  }

  private static void appendColumns(StringBuilder sql, List<String> names, String indent) {
    for (int i = 0; i < names.size(); i++) {
      sql.append(indent).append(quote(names.get(i)));
      if (i + 1 < names.size()) {
        sql.append(',');
      }
      sql.append('\n');
    }
  }

  private static List<String> names(List<Column> columns) {
    List<String> names = new java.util.ArrayList<>();
    for (Column column : columns) {
      names.add(column.name());
    }
    return names;
  }

  private static List<Column> assignments(TableInfo table) {
    List<String> keys = table.primaryKey();
    List<Column> columns = new java.util.ArrayList<>();
    for (Column column : table.columns()) {
      if (!keys.contains(column.name())) {
        columns.add(column);
      }
    }
    if (columns.isEmpty()) {
      return table.columns();
    }
    return columns;
  }

  private static String where(TableInfo table) {
    List<String> keys = table.primaryKey().isEmpty()
        ? List.of(table.columns().get(0).name())
        : table.primaryKey();
    StringBuilder sql = new StringBuilder("WHERE ");
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        sql.append("\n  AND ");
      }
      Column column = column(table, keys.get(i));
      sql.append(quote(column.name())).append(" = ").append(example(column));
    }
    return sql.toString();
  }

  private static String whereValues(TableInfo table, List<Object> original) {
    List<Integer> indexes = new java.util.ArrayList<>();
    if (table.primaryKey().isEmpty()) {
      for (int i = 0; i < table.columns().size(); i++) {
        indexes.add(i);
      }
    } else {
      for (String key : table.primaryKey()) {
        indexes.add(columnIndex(table, key));
      }
    }
    StringBuilder sql = new StringBuilder("WHERE ");
    for (int i = 0; i < indexes.size(); i++) {
      if (i > 0) {
        sql.append("\n  AND ");
      }
      int index = indexes.get(i);
      Column column = table.columns().get(index);
      Object value = index < original.size() ? original.get(index) : null;
      if (value == null) {
        sql.append(quote(column.name())).append(" IS NULL");
      } else {
        sql.append(quote(column.name())).append(" = ").append(literal(value));
      }
    }
    return sql.toString();
  }

  private static int columnIndex(TableInfo table, String name) {
    List<Column> columns = table.columns();
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).name().equals(name)) {
        return i;
      }
    }
    throw new SqlException("找不到欄位 " + name);
  }

  private static String insertValue(TableInfo table, Column column) {
    if (isIdentity(table, column) || !column.notNull()) {
      return "NULL";
    }
    return example(column);
  }

  private static boolean isIdentity(TableInfo table, Column column) {
    return table.primaryKey().size() == 1
        && table.primaryKey().get(0).equals(column.name())
        && column.type() == ColumnType.INTEGER;
  }

  private static String example(Column column) {
    return column.type() == ColumnType.INTEGER ? "0" : "''";
  }

  private static boolean needsTablePrimaryKey(TableInfo table) {
    if (table.primaryKey().isEmpty()) {
      return false;
    }
    for (Column column : table.columns()) {
      if (column.primaryKey()) {
        return false;
      }
    }
    return true;
  }

  private static String columnDefinition(Column column) {
    StringBuilder sql = new StringBuilder();
    sql.append(quote(column.name())).append(' ').append(column.type().name());
    if (column.primaryKey()) {
      sql.append(" PRIMARY KEY");
      if (column.autoincrement()) {
        sql.append(" AUTOINCREMENT");
      }
    }
    if (column.notNull()) {
      sql.append(" NOT NULL");
    }
    if (column.unique() && !column.primaryKey()) {
      sql.append(" UNIQUE");
    }
    if (column.defaultNow()) {
      sql.append(" DEFAULT (datetime('now'))");
    } else if (column.hasDefault()) {
      sql.append(" DEFAULT ").append(literal(column.defaultValue()));
    }
    return sql.toString();
  }

  private static Column column(TableInfo table, String name) {
    for (Column column : table.columns()) {
      if (column.name().equals(name)) {
        return column;
      }
    }
    throw new SqlException("找不到欄位 " + name);
  }

  private static String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  private static String literal(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Long number) {
      return number.toString();
    }
    if (value instanceof String text) {
      return "'" + text.replace("'", "''") + "'";
    }
    throw new SqlException("只能插入整數、文字或 NULL");
  }

  private static String identifier(String name, String label) {
    if (name == null || name.isBlank()) {
      throw new SqlException(label + "是空的");
    }
    String trimmed = name.strip();
    char first = trimmed.charAt(0);
    if (!(Character.isLetter(first) || first == '_')) {
      throw new SqlException(label + "須以字母或底線開頭：" + trimmed);
    }
    for (int i = 1; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (!(Character.isLetter(c) || Character.isDigit(c) || c == '_')) {
        throw new SqlException(label + "只能包含字母、數字和底線：" + trimmed);
      }
    }
    if (KEYWORDS.contains(trimmed.toUpperCase(Locale.ROOT))) {
      throw new SqlException(label + "不能使用保留字：" + trimmed);
    }
    return trimmed;
  }
}
