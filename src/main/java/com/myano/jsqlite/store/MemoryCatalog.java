package com.myano.jsqlite.store;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.SqlText;
import com.myano.jsqlite.vm.InsertMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class MemoryCatalog {
  private final Map<String, Table> tables = new LinkedHashMap<>();
  private final List<IndexDef> indexes = new ArrayList<>();
  private Snapshot statementSnapshot;
  private Snapshot transactionSnapshot;
  private boolean enforceForeignKeys;
  private int changes;
  private Long generatedKey;

  public void createTable(String name, List<Column> columns, String sql) {
    createTable(name, columns, List.of(), false, sql);
  }

  public void createTable(
      String name,
      List<Column> columns,
      List<TableConstraint> constraints,
      boolean ifNotExists,
      String sql) {
    if (find(name) != null) {
      if (ifNotExists) {
        return;
      }
      throw new SqlException("table already exists " + name + ": " + SqlText.snippet(sql));
    }
    if (columns.isEmpty()) {
      throw new SqlException("table " + name + " needs a column: " + SqlText.snippet(sql));
    }
    List<String> seen = new ArrayList<>();
    for (Column column : columns) {
      String key = key(column.name());
      if (seen.contains(key)) {
        throw new SqlException("duplicate column " + column.name() + ": " + SqlText.snippet(sql));
      }
      seen.add(key);
    }
    tables.put(key(name), new Table(name, columns, constraints, sql));
  }

  public void createIndex(
      String name,
      boolean unique,
      boolean ifNotExists,
      String tableName,
      List<String> columns,
      String partialColumn,
      String sql) {
    if (findIndex(name) != null) {
      if (ifNotExists) {
        return;
      }
      throw new SqlException("index already exists " + name + ": " + SqlText.snippet(sql));
    }
    Table table = find(tableName);
    if (table == null) {
      throw new SqlException("unknown table " + tableName + ": " + SqlText.snippet(sql));
    }
    int[] indexes = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      int index = table.columnIndex(columns.get(i));
      if (index < 0) {
        throw new SqlException("unknown column " + columns.get(i) + ": " + SqlText.snippet(sql));
      }
      indexes[i] = index;
    }
    int partial = -1;
    if (partialColumn != null) {
      partial = table.columnIndex(partialColumn);
      if (partial < 0) {
        throw new SqlException("unknown column " + partialColumn + ": " + SqlText.snippet(sql));
      }
    }
    if (unique) {
      table.addUnique(indexes, partial);
    } else {
      table.addNonUniqueIndex(indexes, partial);
    }
    this.indexes.add(new IndexDef(name, table.name(), columns, unique, partialColumn, sql));
  }

  public void addColumn(String tableName, Column column, String sql) {
    Table table = find(tableName);
    if (table == null) {
      throw new SqlException("unknown table " + tableName + ": " + SqlText.snippet(sql));
    }
    table.addColumn(column, sql);
  }

  public void replaceFirst(String tableName, List<Object> original, List<Object> values, String sql) {
    Table table = find(tableName);
    if (table == null) {
      throw new SqlException("unknown table " + tableName + ": " + SqlText.snippet(sql));
    }
    int row = table.findRow(original.toArray());
    if (row < 0) {
      throw new SqlException("row not found: " + SqlText.snippet(sql));
    }
    Object[] previous = table.copyRow(row);
    table.replace(row, values.toArray());
    addChange();
    checkReplaced(table, previous, table.copyRow(row));
  }

  public void deleteFirst(String tableName, List<Object> values, String sql) {
    Table table = find(tableName);
    if (table == null) {
      throw new SqlException("unknown table " + tableName + ": " + SqlText.snippet(sql));
    }
    int row = table.findRow(values.toArray());
    if (row < 0) {
      throw new SqlException("row not found: " + SqlText.snippet(sql));
    }
    Object[] previous = table.copyRow(row);
    table.remove(row);
    addChange();
    checkRemoved(table, previous);
  }

  public void redefineTable(
      String tableName,
      String newName,
      List<Column> columns,
      int[] source,
      List<String> primaryKey,
      String sql) {
    Table table = find(tableName);
    if (table == null) {
      throw new SqlException("unknown table " + tableName + ": " + SqlText.snippet(sql));
    }
    if (newName == null || newName.isBlank()) {
      throw new SqlException("table name is empty: " + SqlText.snippet(sql));
    }
    if (find(newName) != null && !key(newName).equals(key(table.name()))) {
      throw new SqlException("table already exists " + newName + ": " + SqlText.snippet(sql));
    }
    if (columns == null || columns.isEmpty() || source == null || source.length != columns.size()) {
      throw new SqlException("table " + newName + " needs a column: " + SqlText.snippet(sql));
    }
    if (primaryKey == null) {
      primaryKey = List.of();
    }
    String oldName = table.name();
    List<Column> oldColumns = table.columns();
    int[] oldToNew = new int[oldColumns.size()];
    Arrays.fill(oldToNew, -1);
    boolean[] used = new boolean[oldColumns.size()];
    for (int i = 0; i < source.length; i++) {
      int from = source[i];
      if (from < 0) {
        continue;
      }
      if (from >= oldColumns.size() || used[from]) {
        throw new SqlException("column mapping is invalid: " + SqlText.snippet(sql));
      }
      used[from] = true;
      oldToNew[from] = i;
    }
    List<Column> shaped = shape(columns, primaryKey, sql);
    List<TableConstraint> constraints = new ArrayList<>();
    if (primaryKey.size() > 1) {
      constraints.add(new PrimaryKeyConstraint(primaryKey));
    }
    for (ForeignKeyConstraint foreignKey : table.foreignKeys()) {
      constraints.add(retarget(table, foreignKey, oldToNew, shaped, newName, sql));
    }
    List<Object[]> newRows = convertRows(table, shaped, source, primaryKey, sql);
    List<IndexDef> nextIndexes = new ArrayList<>();
    for (IndexDef index : indexes) {
      if (!key(index.table()).equals(key(oldName))) {
        nextIndexes.add(index);
        continue;
      }
      List<String> names = new ArrayList<>();
      for (String column : index.columns()) {
        names.add(mappedName(table, column, oldToNew, shaped, sql));
      }
      String partial = index.partialColumn() == null
          ? null
          : mappedName(table, index.partialColumn(), oldToNew, shaped, sql);
      nextIndexes.add(new IndexDef(index.name(), newName, names, index.unique(), partial, index.sql()));
    }
    for (Table other : tables.values()) {
      if (other == table) {
        continue;
      }
      List<ForeignKeyConstraint> next = new ArrayList<>();
      boolean changed = false;
      for (ForeignKeyConstraint foreignKey : other.foreignKeys()) {
        if (!key(foreignKey.refTable()).equals(key(oldName))) {
          next.add(foreignKey);
          continue;
        }
        String refColumn = mappedName(table, foreignKey.refColumn(), oldToNew, shaped, sql);
        next.add(new ForeignKeyConstraint(foreignKey.column(), newName, refColumn));
        changed = true;
      }
      if (changed) {
        other.replaceForeignKeys(next);
      }
    }
    table.restructure(newName, shaped, constraints, sql, newRows);
    indexes.clear();
    indexes.addAll(nextIndexes);
    for (IndexDef index : nextIndexes) {
      if (!key(index.table()).equals(key(newName)) || !index.unique()) {
        continue;
      }
      int[] keyColumns = new int[index.columns().size()];
      for (int i = 0; i < keyColumns.length; i++) {
        keyColumns[i] = table.columnIndex(index.columns().get(i));
      }
      int partial = index.partialColumn() == null ? -1 : table.columnIndex(index.partialColumn());
      table.addUnique(keyColumns, partial);
    }
    if (!key(newName).equals(key(oldName))) {
      Map<String, Table> ordered = new LinkedHashMap<>();
      for (Map.Entry<String, Table> entry : tables.entrySet()) {
        if (entry.getKey().equals(key(oldName))) {
          ordered.put(key(newName), table);
        } else {
          ordered.put(entry.getKey(), entry.getValue());
        }
      }
      tables.clear();
      tables.putAll(ordered);
    }
  }

  public Table find(String name) {
    if (name == null) {
      return null;
    }
    return tables.get(key(name));
  }

  public void dropTable(String name) {
    dropTable(name, true, "");
  }

  public void dropTable(String name, boolean ifExists, String sql) {
    if (name == null) {
      return;
    }
    String id = key(name);
    if (tables.remove(id) == null) {
      if (ifExists) {
        return;
      }
      throw new SqlException("unknown table " + name + ": " + SqlText.snippet(sql));
    }
    indexes.removeIf(index -> key(index.table()).equals(id));
  }

  /** Temporary table filled from a derived SELECT; not persisted unless a later write syncs. */
  public void loadDerived(String name, List<String> columnNames, List<Object[]> rows, String sql) {
    if (columnNames == null || columnNames.isEmpty()) {
      throw new SqlException("derived table needs a column: " + SqlText.snippet(sql));
    }
    List<Column> columns = new ArrayList<>(columnNames.size());
    for (int i = 0; i < columnNames.size(); i++) {
      columns.add(new Column(columnNames.get(i), inferType(rows, i)));
    }
    createTable(name, columns, List.of(), false, sql);
    Table table = find(name);
    for (Object[] row : rows) {
      table.place(row, InsertMode.APPEND, null);
    }
  }

  private static ColumnType inferType(List<Object[]> rows, int column) {
    if (rows == null) {
      return ColumnType.TEXT;
    }
    for (Object[] row : rows) {
      if (row == null || column >= row.length) {
        continue;
      }
      Object value = row[column];
      if (value instanceof Long) {
        return ColumnType.INTEGER;
      }
      if (value instanceof Double) {
        return ColumnType.REAL;
      }
      if (value instanceof byte[]) {
        return ColumnType.BLOB;
      }
      if (value instanceof String) {
        return ColumnType.TEXT;
      }
    }
    return ColumnType.TEXT;
  }

  /** 把 AUTOINCREMENT 表的序號寫進 sqlite_sequence，供寫頁使用。 */
  public void syncSequences() {
    List<Table> tracked = new ArrayList<>();
    for (Table table : tables.values()) {
      if (table.tracksSequence() && table.lastSequence() > 0) {
        tracked.add(table);
      }
    }
    if (tracked.isEmpty()) {
      return;
    }
    Table sequence = find("sqlite_sequence");
    if (sequence == null) {
      createTable(
          "sqlite_sequence",
          List.of(new Column("name", ColumnType.TEXT), new Column("seq", ColumnType.INTEGER)),
          List.of(),
          false,
          "CREATE TABLE sqlite_sequence(name,seq)");
      sequence = find("sqlite_sequence");
    }
    for (Table table : tracked) {
      Object[] row = {table.name(), table.lastSequence()};
      int existing = -1;
      for (int i = 0; i < sequence.rowCount(); i++) {
        if (table.name().equals(sequence.value(i, 0))) {
          existing = i;
          break;
        }
      }
      if (existing >= 0) {
        sequence.replace(existing, row);
      } else {
        sequence.place(row, com.myano.jsqlite.vm.InsertMode.APPEND, null);
      }
    }
  }

  public List<TableInfo> tables() {
    List<TableInfo> snapshot = new ArrayList<>();
    for (Table table : tables.values()) {
      List<Column> columns = table.columns();
      List<String> primaryKey = new ArrayList<>();
      for (int index : table.primaryKey()) {
        primaryKey.add(columns.get(index).name());
      }
      snapshot.add(new TableInfo(table.name(), columns, table.rowCount(), table.sql(), primaryKey));
    }
    return List.copyOf(snapshot);
  }

  public List<IndexDef> indexes() {
    return List.copyOf(indexes);
  }

  public void setEnforceForeignKeys(boolean on) {
    enforceForeignKeys = on;
  }

  public void resetChanges() {
    changes = 0;
  }

  public void addChange() {
    changes++;
  }

  public int changes() {
    return changes;
  }

  public void clearGeneratedKey() {
    generatedKey = null;
  }

  public void noteGeneratedKey(Long id) {
    generatedKey = id;
  }

  public Long generatedKey() {
    return generatedKey;
  }

  public void checkInserted(Table table) {
    if (!enforceForeignKeys || table.rowCount() == 0) {
      return;
    }
    checkChild(table, table.copyRow(table.rowCount() - 1));
  }

  public void checkReplaced(Table table, Object[] previous, Object[] current) {
    if (!enforceForeignKeys) {
      return;
    }
    checkChild(table, current);
    rejectDanglingParent(table, previous, current);
  }

  public void checkRemoved(Table table, Object[] previous) {
    if (!enforceForeignKeys) {
      return;
    }
    rejectDanglingParent(table, previous, null);
  }

  public void beginTransaction() {
    if (transactionSnapshot == null) {
      transactionSnapshot = capture();
    }
  }

  public void commitTransaction() {
    transactionSnapshot = null;
  }

  public void rollbackTransaction() {
    if (transactionSnapshot == null) {
      statementSnapshot = null;
      return;
    }
    restore(transactionSnapshot);
    transactionSnapshot = null;
    statementSnapshot = null;
  }

  public boolean transactionOpen() {
    return transactionSnapshot != null;
  }

  public void beginStatement() {
    statementSnapshot = capture();
  }

  public void endStatement() {
    statementSnapshot = null;
  }

  public void rollbackStatement() {
    if (statementSnapshot == null) {
      return;
    }
    restore(statementSnapshot);
    statementSnapshot = null;
  }

  private Snapshot capture() {
    List<Held> held = new ArrayList<>();
    for (Map.Entry<String, Table> entry : tables.entrySet()) {
      held.add(new Held(entry.getKey(), entry.getValue(), entry.getValue().capture()));
    }
    return new Snapshot(held, List.copyOf(indexes));
  }

  private void restore(Snapshot snapshot) {
    tables.clear();
    for (Held held : snapshot.tables) {
      held.table.restore(held.state);
      tables.put(held.key, held.table);
    }
    indexes.clear();
    indexes.addAll(snapshot.indexes);
  }

  private void checkChild(Table table, Object[] row) {
    for (ForeignKeyConstraint foreignKey : table.foreignKeys()) {
      int child = table.columnIndex(foreignKey.column());
      Object value = child < 0 ? null : row[child];
      if (value == null) {
        continue;
      }
      Table parent = find(foreignKey.refTable());
      int parentColumn = parent == null ? -1 : parent.columnIndex(foreignKey.refColumn());
      if (parent == null || !parent.contains(parentColumn, value)) {
        throw new SqlException("foreign key constraint failed: " + table.name());
      }
    }
  }

  private void rejectDanglingParent(Table table, Object[] previous, Object[] current) {
    for (Table other : tables.values()) {
      for (ForeignKeyConstraint foreignKey : other.foreignKeys()) {
        if (!key(foreignKey.refTable()).equals(key(table.name()))) {
          continue;
        }
        int referenced = table.columnIndex(foreignKey.refColumn());
        if (referenced < 0) {
          continue;
        }
        Object oldValue = previous[referenced];
        Object newValue = current == null ? null : current[referenced];
        if (oldValue == null || Objects.equals(oldValue, newValue)) {
          continue;
        }
        int child = other.columnIndex(foreignKey.column());
        if (other.contains(child, oldValue)) {
          throw new SqlException("foreign key constraint failed: " + table.name());
        }
      }
    }
  }

  private IndexDef findIndex(String name) {
    for (IndexDef index : indexes) {
      if (key(index.name()).equals(key(name))) {
        return index;
      }
    }
    return null;
  }

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  private List<Column> shape(List<Column> columns, List<String> primaryKey, String sql) {
    List<String> seen = new ArrayList<>();
    List<Column> shaped = new ArrayList<>();
    for (Column column : columns) {
      if (column.name() == null || column.name().isBlank()) {
        throw new SqlException("column name is empty: " + SqlText.snippet(sql));
      }
      String nameKey = key(column.name());
      if (seen.contains(nameKey)) {
        throw new SqlException("duplicate column " + column.name() + ": " + SqlText.snippet(sql));
      }
      seen.add(nameKey);
      boolean primary = primaryKey.size() == 1 && key(primaryKey.get(0)).equals(nameKey);
      if (primaryKey.size() != 1 && column.primaryKey()) {
        primary = false;
      }
      boolean autoincrement = primary && column.autoincrement() && column.type() == ColumnType.INTEGER;
      shaped.add(new Column(
          column.name(),
          column.type(),
          column.notNull(),
          primary,
          autoincrement,
          column.unique() && !primary,
          column.hasDefault(),
          column.defaultValue(),
          column.defaultNow()));
    }
    for (String name : primaryKey) {
      if (shaped.stream().noneMatch(column -> key(column.name()).equals(key(name)))) {
        throw new SqlException("unknown column " + name + ": " + SqlText.snippet(sql));
      }
    }
    return shaped;
  }

  private List<Object[]> convertRows(
      Table table, List<Column> columns, int[] source, List<String> primaryKey, String sql) {
    int identity = -1;
    if (primaryKey.size() == 1) {
      for (int i = 0; i < columns.size(); i++) {
        if (key(columns.get(i).name()).equals(key(primaryKey.get(0)))
            && columns.get(i).type() == ColumnType.INTEGER) {
          identity = i;
        }
      }
    }
    List<Object[]> rows = new ArrayList<>();
    long generated = 1;
    for (int rowIndex = 0; rowIndex < table.rowCount(); rowIndex++) {
      Object[] row = new Object[columns.size()];
      for (int i = 0; i < columns.size(); i++) {
        Column column = columns.get(i);
        if (source[i] < 0) {
          if (i == identity) {
            row[i] = generated++;
          } else if (column.hasDefault()) {
            row[i] = column.defaultValue();
          } else if (column.notNull()) {
            throw new SqlException("NOT NULL column needs a default: " + SqlText.snippet(sql));
          }
        } else {
          row[i] = convert(table.value(rowIndex, source[i]), column, sql);
        }
      }
      rows.add(row);
    }
    return rows;
  }

  private static Object convert(Object value, Column column, String sql) {
    if (value == null) {
      if (column.notNull() && column.hasDefault()) {
        return column.defaultValue();
      }
      return null;
    }
    if (column.type() == ColumnType.INTEGER) {
      if (value instanceof Long number) {
        return number;
      }
      if (value instanceof String text) {
        try {
          return Long.parseLong(text);
        } catch (NumberFormatException ex) {
          throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
        }
      }
    }
    if (column.type() == ColumnType.REAL) {
      if (value instanceof Long || value instanceof Double) {
        return value;
      }
      if (value instanceof String text) {
        try {
          return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
          try {
            return Double.parseDouble(text);
          } catch (NumberFormatException ex) {
            throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
          }
        }
      }
    }
    if (column.type() == ColumnType.BLOB && value instanceof byte[] raw) {
      return raw;
    }
    if (column.type() == ColumnType.TEXT) {
      if (value instanceof String text) {
        return text;
      }
      if (value instanceof Long number) {
        return Long.toString(number);
      }
      if (value instanceof Double number) {
        return Double.toString(number);
      }
      if (value instanceof byte[] raw) {
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
  }

  private String mappedName(Table table, String column, int[] oldToNew, List<Column> shaped, String sql) {
    int old = table.columnIndex(column);
    if (old < 0 || oldToNew[old] < 0) {
      throw new SqlException("column " + column + " is still in use: " + SqlText.snippet(sql));
    }
    return shaped.get(oldToNew[old]).name();
  }

  private ForeignKeyConstraint retarget(
      Table table,
      ForeignKeyConstraint foreignKey,
      int[] oldToNew,
      List<Column> shaped,
      String newName,
      String sql) {
    String column = mappedName(table, foreignKey.column(), oldToNew, shaped, sql);
    String refTable = foreignKey.refTable();
    String refColumn = foreignKey.refColumn();
    if (key(refTable).equals(key(table.name()))) {
      refTable = newName;
      refColumn = mappedName(table, foreignKey.refColumn(), oldToNew, shaped, sql);
    }
    return new ForeignKeyConstraint(column, refTable, refColumn);
  }

  private record Held(String key, Table table, Table.State state) {}

  private record Snapshot(List<Held> tables, List<IndexDef> indexes) {}
}
