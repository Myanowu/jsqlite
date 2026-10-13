package com.myano.jsqlite.store;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.SqlText;
import com.myano.jsqlite.vm.InsertMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Table {
  public enum Place {
    INSERTED,
    IGNORED,
    CONFLICT
  }

  private String name;
  private String sql;
  private final List<Column> columns = new ArrayList<>();
  private final List<UniqueKey> uniqueKeys = new ArrayList<>();
  private final List<ForeignKeyConstraint> foreignKeys = new ArrayList<>();
  private final List<Object[]> rows = new ArrayList<>();
  private int[] primaryKey = new int[0];
  private int integerPkIndex = -1;
  private long nextId = 1;
  private int pendingConflict = -1;

  Table(String name, List<Column> columns, List<TableConstraint> constraints, String sql) {
    install(name, columns, constraints, sql);
  }

  void restructure(
      String name,
      List<Column> columns,
      List<TableConstraint> constraints,
      String sql,
      List<Object[]> newRows) {
    install(name, columns, constraints, sql);
    rows.clear();
    nextId = 1;
    pendingConflict = -1;
    for (Object[] row : newRows) {
      Object[] checked = checkValues(row, false);
      for (UniqueKey key : uniqueKeys) {
        if (skips(key, checked)) {
          continue;
        }
        if (findConflict(checked, key.columns, -1) >= 0) {
          throw new SqlException("unique constraint failed: " + name + ": " + SqlText.snippet(sql));
        }
      }
      rows.add(checked);
      noteIdentity(checked);
    }
  }

  List<ForeignKeyConstraint> foreignKeys() {
    return List.copyOf(foreignKeys);
  }

  void replaceForeignKeys(List<ForeignKeyConstraint> next) {
    foreignKeys.clear();
    foreignKeys.addAll(next);
  }

  private void install(String name, List<Column> columns, List<TableConstraint> constraints, String sql) {
    this.name = name;
    this.sql = sql;
    this.columns.clear();
    this.columns.addAll(columns);
    this.primaryKey = resolvePrimaryKey(constraints, sql);
    this.integerPkIndex = singleIntegerPrimaryKey();
    validate(constraints, sql);
    uniqueKeys.clear();
    foreignKeys.clear();
    if (primaryKey.length > 0) {
      uniqueKeys.add(new UniqueKey(primaryKey.clone(), -1));
    }
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      if (column.unique() && !column.primaryKey()) {
        uniqueKeys.add(new UniqueKey(new int[] {i}, -1));
      }
    }
    for (TableConstraint constraint : constraints) {
      if (constraint instanceof UniqueConstraint unique) {
        uniqueKeys.add(new UniqueKey(indexes(unique.columns(), sql), -1));
      }
      if (constraint instanceof ForeignKeyConstraint foreignKey) {
        indexes(List.of(foreignKey.column()), sql);
        foreignKeys.add(foreignKey);
      }
    }
  }

  public String name() {
    return name;
  }

  public String sql() {
    return sql;
  }

  public List<Column> columns() {
    return List.copyOf(columns);
  }

  public int[] primaryKey() {
    return primaryKey.clone();
  }

  public boolean assignsIdentity(int column) {
    return column == integerPkIndex;
  }

  public int columnIndex(String columnName) {
    for (int i = 0; i < columns.size(); i++) {
      if (same(columns.get(i).name(), columnName)) {
        return i;
      }
    }
    return -1;
  }

  public int rowCount() {
    return rows.size();
  }

  public Object[] copyRow(int row) {
    return rows.get(row).clone();
  }

  public boolean contains(int column, Object value) {
    if (column < 0 || value == null) {
      return false;
    }
    for (Object[] row : rows) {
      if (value.equals(row[column])) {
        return true;
      }
    }
    return false;
  }

  /** 這一列是整數 AUTOINCREMENT 時，回傳該列的值。 */
  public Long identityKey(int row) {
    if (integerPkIndex < 0 || !columns.get(integerPkIndex).autoincrement()) {
      return null;
    }
    Object value = rows.get(row)[integerPkIndex];
    return value instanceof Long id ? id : null;
  }

  public Object value(int row, int column) {
    return rows.get(row)[column];
  }

  public boolean hasUnique(int[] columns) {
    for (UniqueKey key : uniqueKeys) {
      if (sameSet(key.columns, columns)) {
        return true;
      }
    }
    return false;
  }

  public int seek(Object[] key) {
    if (primaryKey.length != key.length) {
      return -1;
    }
    for (Object part : key) {
      if (part == null) {
        return -1;
      }
    }
    for (int row = 0; row < rows.size(); row++) {
      if (keysEqual(rows.get(row), key, primaryKey)) {
        return row;
      }
    }
    return -1;
  }

  public Place place(Object[] raw, InsertMode mode, int[] conflictTarget) {
    Object[] row = checkValues(raw, true);
    if (mode == InsertMode.UPSERT && (conflictTarget == null || !hasUnique(conflictTarget))) {
      throw new SqlException("ON CONFLICT clause does not match a unique constraint: " + name);
    }
    int targetHit = -1;
    for (UniqueKey key : uniqueKeys) {
      if (skips(key, row)) {
        continue;
      }
      int hit = findConflict(row, key.columns, -1);
      if (hit < 0) {
        continue;
      }
      if (mode == InsertMode.UPSERT && sameSet(key.columns, conflictTarget)) {
        targetHit = hit;
        continue;
      }
      if (mode == InsertMode.IGNORE) {
        return Place.IGNORED;
      }
      throw new SqlException("unique constraint failed: " + name);
    }
    if (targetHit >= 0) {
      pendingConflict = targetHit;
      return Place.CONFLICT;
    }
    assignIdentity(row);
    rows.add(row);
    return Place.INSERTED;
  }

  public int takeConflict() {
    int index = pendingConflict;
    pendingConflict = -1;
    if (index < 0) {
      throw new IllegalStateException("no conflicting row");
    }
    return index;
  }

  public void replace(int row, Object[] values) {
    Object[] checked = checkValues(values, false);
    for (UniqueKey key : uniqueKeys) {
      if (skips(key, checked)) {
        continue;
      }
      if (findConflict(checked, key.columns, row) >= 0) {
        throw new SqlException("unique constraint failed: " + name);
      }
    }
    noteIdentity(checked);
    rows.set(row, checked);
  }

  public void remove(int row) {
    rows.remove(row);
  }

  int findRow(Object[] values) {
    if (values == null || values.length != columns.size()) {
      return -1;
    }
    for (int row = 0; row < rows.size(); row++) {
      Object[] current = rows.get(row);
      boolean same = true;
      for (int column = 0; column < current.length; column++) {
        if (!java.util.Objects.equals(current[column], values[column])) {
          same = false;
          break;
        }
      }
      if (same) {
        return row;
      }
    }
    return -1;
  }

  public void addColumn(Column column, String sql) {
    if (columnIndex(column.name()) >= 0) {
      throw new SqlException("duplicate column " + column.name() + ": " + SqlText.snippet(sql));
    }
    if (column.primaryKey() || column.unique() || column.autoincrement()) {
      throw new SqlException("ADD COLUMN cannot add a key: " + SqlText.snippet(sql));
    }
    if (column.notNull() && !column.hasDefault()) {
      throw new SqlException("NOT NULL column needs a default: " + SqlText.snippet(sql));
    }
    columns.add(column);
    Object fill = column.notNull() ? column.defaultValue() : null;
    for (int i = 0; i < rows.size(); i++) {
      Object[] old = rows.get(i);
      Object[] expanded = Arrays.copyOf(old, old.length + 1);
      expanded[old.length] = fill;
      rows.set(i, expanded);
    }
  }

  void addUnique(int[] keyColumns, int partialColumn) {
    uniqueKeys.add(new UniqueKey(keyColumns.clone(), partialColumn));
  }

  State capture() {
    List<Object[]> copy = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      copy.add(row.clone());
    }
    List<UniqueKey> keys = new ArrayList<>(uniqueKeys.size());
    for (UniqueKey key : uniqueKeys) {
      keys.add(new UniqueKey(key.columns.clone(), key.partial));
    }
    return new State(
        name,
        sql,
        List.copyOf(columns),
        primaryKey.clone(),
        integerPkIndex,
        copy,
        nextId,
        keys,
        List.copyOf(foreignKeys));
  }

  void restore(State state) {
    name = state.name;
    sql = state.sql;
    columns.clear();
    columns.addAll(state.columns);
    primaryKey = state.primaryKey.clone();
    integerPkIndex = state.integerPkIndex;
    rows.clear();
    rows.addAll(state.rows);
    nextId = state.nextId;
    uniqueKeys.clear();
    uniqueKeys.addAll(state.keys);
    foreignKeys.clear();
    foreignKeys.addAll(state.foreignKeys);
    pendingConflict = -1;
  }

  private void validate(List<TableConstraint> constraints, String sql) {
    int flagged = 0;
    int auto = 0;
    for (Column column : columns) {
      if (column.primaryKey()) {
        flagged++;
      }
      if (column.autoincrement()) {
        auto++;
      }
      if (column.defaultNow() && column.type() != ColumnType.TEXT) {
        throw new SqlException("datetime default needs TEXT: " + column.name() + ": " + SqlText.snippet(sql));
      }
      if (column.hasDefault() && column.defaultValue() != null) {
        if (column.type() == ColumnType.INTEGER && !(column.defaultValue() instanceof Long)) {
          throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
        }
        if (column.type() == ColumnType.TEXT && !(column.defaultValue() instanceof String)) {
          throw new SqlException("type mismatch for " + column.name() + ": " + SqlText.snippet(sql));
        }
      }
    }
    boolean tablePrimaryKey = false;
    for (TableConstraint constraint : constraints) {
      if (constraint instanceof PrimaryKeyConstraint) {
        if (tablePrimaryKey) {
          throw new SqlException("only one primary key is allowed: " + SqlText.snippet(sql));
        }
        tablePrimaryKey = true;
      }
    }
    if (flagged > 1 || (flagged > 0 && tablePrimaryKey)) {
      throw new SqlException("only one primary key is allowed: " + SqlText.snippet(sql));
    }
    if (auto > 0 && (auto > 1 || integerPkIndex < 0 || !columns.get(integerPkIndex).autoincrement())) {
      throw new SqlException("AUTOINCREMENT needs an integer primary key: " + SqlText.snippet(sql));
    }
  }

  private int[] resolvePrimaryKey(List<TableConstraint> constraints, String sql) {
    List<String> names = new ArrayList<>();
    for (Column column : columns) {
      if (column.primaryKey()) {
        names.add(column.name());
      }
    }
    for (TableConstraint constraint : constraints) {
      if (constraint instanceof PrimaryKeyConstraint primaryKey) {
        names.addAll(primaryKey.columns());
      }
    }
    if (names.isEmpty()) {
      return new int[0];
    }
    return indexes(names, sql);
  }

  private int singleIntegerPrimaryKey() {
    if (primaryKey.length == 1 && columns.get(primaryKey[0]).type() == ColumnType.INTEGER) {
      return primaryKey[0];
    }
    return -1;
  }

  private int[] indexes(List<String> names, String sql) {
    int[] indexes = new int[names.size()];
    for (int i = 0; i < names.size(); i++) {
      int index = columnIndex(names.get(i));
      if (index < 0) {
        throw new SqlException("unknown column " + names.get(i) + ": " + SqlText.snippet(sql));
      }
      for (int earlier = 0; earlier < i; earlier++) {
        if (indexes[earlier] == index) {
          throw new SqlException("duplicate column " + names.get(i) + ": " + SqlText.snippet(sql));
        }
      }
      indexes[i] = index;
    }
    return indexes;
  }

  private Object[] checkValues(Object[] values, boolean inserting) {
    if (values == null || values.length != columns.size()) {
      throw new SqlException("value count mismatch for " + name);
    }
    Object[] row = values.clone();
    for (int i = 0; i < row.length; i++) {
      Object value = row[i];
      Column column = columns.get(i);
      if (value != null) {
        if (column.type() == ColumnType.INTEGER && !(value instanceof Long)) {
          throw new SqlException("type mismatch for " + column.name());
        }
        if (column.type() == ColumnType.TEXT && !(value instanceof String)) {
          throw new SqlException("type mismatch for " + column.name());
        }
      } else if (i == integerPkIndex) {
        if (!inserting) {
          throw new SqlException("NOT NULL constraint failed: " + column.name());
        }
      } else if (column.notNull()) {
        throw new SqlException("NOT NULL constraint failed: " + column.name());
      }
    }
    return row;
  }

  private void assignIdentity(Object[] row) {
    if (integerPkIndex >= 0 && row[integerPkIndex] == null) {
      row[integerPkIndex] = takeId();
    }
    noteIdentity(row);
  }

  private long takeId() {
    if (nextId == Long.MAX_VALUE) {
      throw new SqlException("integer primary key exhausted: " + name);
    }
    return nextId++;
  }

  private void noteIdentity(Object[] row) {
    if (integerPkIndex < 0 || !(row[integerPkIndex] instanceof Long value)) {
      return;
    }
    if (value >= nextId) {
      nextId = value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }
  }

  public void advanceSequence(long lastUsed) {
    if (integerPkIndex < 0 || lastUsed < 0) {
      return;
    }
    long next = lastUsed == Long.MAX_VALUE ? Long.MAX_VALUE : lastUsed + 1;
    if (next > nextId) {
      nextId = next;
    }
  }

  public boolean tracksSequence() {
    return integerPkIndex >= 0 && columns.get(integerPkIndex).autoincrement();
  }

  /** 已分配過的最大 AUTOINCREMENT 值；尚未插入時為 0。 */
  public long lastSequence() {
    if (!tracksSequence() || nextId <= 1) {
      return 0;
    }
    return nextId == Long.MAX_VALUE ? Long.MAX_VALUE : nextId - 1;
  }

  private boolean skips(UniqueKey key, Object[] row) {
    return key.partial >= 0 && row[key.partial] == null;
  }

  private int findConflict(Object[] row, int[] key, int ignore) {
    for (int column : key) {
      if (row[column] == null) {
        return -1;
      }
    }
    for (int i = 0; i < rows.size(); i++) {
      if (i == ignore) {
        continue;
      }
      if (keysEqual(rows.get(i), row, key)) {
        return i;
      }
    }
    return -1;
  }

  private boolean keysEqual(Object[] existing, Object[] candidate, int[] key) {
    for (int column : key) {
      Object left = existing[column];
      Object right = candidate[column];
      if (left == null || !left.equals(right)) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameSet(int[] left, int[] right) {
    if (left.length != right.length) {
      return false;
    }
    int[] a = left.clone();
    int[] b = right.clone();
    Arrays.sort(a);
    Arrays.sort(b);
    return Arrays.equals(a, b);
  }

  private static boolean same(String left, String right) {
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }

  private record UniqueKey(int[] columns, int partial) {}

  static final class State {
    private final String name;
    private final String sql;
    private final List<Column> columns;
    private final int[] primaryKey;
    private final int integerPkIndex;
    private final List<Object[]> rows;
    private final long nextId;
    private final List<UniqueKey> keys;
    private final List<ForeignKeyConstraint> foreignKeys;

    private State(
        String name,
        String sql,
        List<Column> columns,
        int[] primaryKey,
        int integerPkIndex,
        List<Object[]> rows,
        long nextId,
        List<UniqueKey> keys,
        List<ForeignKeyConstraint> foreignKeys) {
      this.name = name;
      this.sql = sql;
      this.columns = columns;
      this.primaryKey = primaryKey;
      this.integerPkIndex = integerPkIndex;
      this.rows = rows;
      this.nextId = nextId;
      this.keys = keys;
      this.foreignKeys = foreignKeys;
    }
  }
}
