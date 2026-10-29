package com.myano.jsqlite.store;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.SqlText;
import com.myano.jsqlite.vm.InsertMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
  private final List<IndexMap> indexMaps = new ArrayList<>();
  private final List<ForeignKeyConstraint> foreignKeys = new ArrayList<>();
  private final List<Object[]> rows = new ArrayList<>();
  /** Compact primary-key tuple → row index. SeekPk must be O(1), not a table scan. */
  private final Map<Object, Integer> primaryIndex = new HashMap<>();
  /** Lowercase column name → ordinal. Avoid O(cols) + toLowerCase on every lookup. */
  private final Map<String, Integer> columnByLower = new HashMap<>();
  private static final int[] EMPTY_ROWS = new int[0];
  private int[] primaryKey = new int[0];
  private int integerPkIndex = -1;
  private long nextId = 1;
  private int pendingConflict = -1;
  /** Loads row data on first access so opening a large file does not fill the heap. */
  private Runnable deferredLoad;
  /** Disk-backed rows (preferred for large deferred tables — no Object[] materialization). */
  private FileBackedRows fileRows;
  /** Original btree root in the source file; used to copy pages on persist without materializing. */
  private int sourceRootPage;
  private boolean mutated;
  private boolean loading;
  /** When set, row data lives in a sibling sidecar file; main persist writes an empty leaf. */
  private java.nio.file.Path sidecarPath;
  /** Directory for {@link RatioAutoIndex} meta files; set by Engine on open. */
  private java.nio.file.Path ratioMetaDir;
  private boolean ratioAnalyzed;
  /** Column names that passed ratio thresholds (may not be populated as IndexMap yet). */
  private final java.util.LinkedHashSet<String> ratioWinners = new java.util.LinkedHashSet<>();

  Table(String name, List<Column> columns, List<TableConstraint> constraints, String sql) {
    install(name, columns, constraints, sql);
  }

  public void deferLoad(int rootPage, Runnable loader) {
    this.sourceRootPage = rootPage;
    this.deferredLoad = loader;
    this.fileRows = null;
    this.mutated = false;
    resetLazyIndexes();
  }

  public void deferLoad(Runnable loader) {
    deferLoad(0, loader);
  }

  /** Prefer file-backed reads: builds a cell-offset index, never loads all rows as objects. */
  public void attachFile(
      com.myano.jsqlite.format.DbImage file,
      int pageSize,
      int reserved,
      int rootPage) {
    this.sourceRootPage = rootPage;
    this.deferredLoad = null;
    this.fileRows =
        new FileBackedRows(file, pageSize, reserved, rootPage, columns, name, integerPkIndex);
    this.mutated = false;
    this.ratioAnalyzed = false;
    this.ratioWinners.clear();
    resetLazyIndexes();
  }

  public void setRatioMetaDir(java.nio.file.Path dir) {
    this.ratioMetaDir = dir;
  }

  public java.nio.file.Path ratioMetaDir() {
    return ratioMetaDir;
  }

  public boolean ratioAnalyzed() {
    return ratioAnalyzed;
  }

  public void markRatioAnalyzed() {
    this.ratioAnalyzed = true;
  }

  /** Allow {@code PRAGMA jsqlite_analyze} to rebuild ratio indexes. */
  public void resetRatioAnalyzed() {
    this.ratioAnalyzed = false;
    ratioWinners.clear();
  }

  public void setRatioWinners(java.util.Collection<String> names) {
    ratioWinners.clear();
    if (names != null) {
      ratioWinners.addAll(names);
    }
  }

  public boolean isRatioWinner(int column) {
    if (column < 0 || column >= columns.size()) {
      return false;
    }
    return ratioWinners.contains(columns.get(column).name());
  }

  /**
   * Shared row view for indexing passes — valid until the next row access when file-backed. Callers
   * must not retain or mutate the array.
   */
  public Object[] peekRow(int row) {
    if (fileRows != null && !mutated) {
      return fileRows.peekRow(row);
    }
    ensureLoaded();
    return rows.get(row);
  }

  /** Install a pre-built non-unique posting list for one column (ratio auto-index). */
  public void installRatioPosting(int column, java.util.Map<Object, ArrayList<Integer>> posting) {
    if (column < 0 || column >= columns.size() || posting == null || posting.isEmpty()) {
      return;
    }
    int[] cols = {column};
    if (findIndexMap(cols) != null) {
      return;
    }
    IndexMap map = new IndexMap(cols, -1, false);
    map.multiRows.putAll(posting);
    map.populated = true;
    indexMaps.add(map);
  }

  /** Mark this table as living in a sibling sidecar file (keeps path across mutations for rewrite). */
  public void bindSidecar(java.nio.file.Path path) {
    this.sidecarPath = path;
  }

  public java.nio.file.Path sidecarPath() {
    return sidecarPath;
  }

  public boolean isSidecar() {
    return sidecarPath != null;
  }

  public boolean isMutated() {
    return mutated;
  }

  private void resetLazyIndexes() {
    primaryIndex.clear();
    primaryIndexBuilt = false;
    for (IndexMap map : indexMaps) {
      map.uniqueRows.clear();
      map.multiRows.clear();
      map.populated = false;
    }
  }

  public boolean hasDeferredLoad() {
    return deferredLoad != null || (fileRows != null && rows.isEmpty() && !mutated);
  }

  /** Unmodified deferred/source table: persist can copy btree pages from the file image. */
  public int sourceRootPage() {
    return !mutated && sourceRootPage > 0 ? sourceRootPage : 0;
  }

  private void markMutated() {
    if (loading) {
      return;
    }
    mutated = true;
    sourceRootPage = 0;
    fileRows = null;
  }

  /**
   * Deferred loader only. File-backed tables stay on disk for reads — call {@link
   * #materializeForWrite()} before mutating.
   */
  private void ensureLoaded() {
    Runnable loader = deferredLoad;
    if (loader == null) {
      return;
    }
    deferredLoad = null;
    loading = true;
    try {
      loader.run();
    } finally {
      loading = false;
    }
  }

  /** Pull file-backed / deferred rows into {@code rows} before insert/update/delete. */
  private void materializeForWrite() {
    ensureLoaded();
    if (fileRows == null || !rows.isEmpty()) {
      return;
    }
    FileBackedRows src = fileRows;
    fileRows = null;
    loading = true;
    try {
      boolean indexesReady = primaryIndexBuilt;
      for (IndexMap map : indexMaps) {
        if (map.populated) {
          indexesReady = true;
          break;
        }
      }
      int n = src.rowCount();
      for (int i = 0; i < n; i++) {
        rows.add(src.copyRow(i));
        // Row ordinals stay the same — do not double-fill already-built postings.
        if (!indexesReady) {
          indexRow(rows.get(i), i);
        }
      }
      for (IndexMap map : indexMaps) {
        map.populated = true;
      }
      primaryIndexBuilt = true;
    } finally {
      loading = false;
    }
  }

  private boolean primaryIndexBuilt;

  void restructure(
      String name,
      List<Column> columns,
      List<TableConstraint> constraints,
      String sql,
      List<Object[]> newRows) {
    markMutated();
    deferredLoad = null;
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
      indexRow(checked, rows.size() - 1);
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
    rebuildColumnIndex();
    this.primaryKey = resolvePrimaryKey(constraints, sql);
    this.integerPkIndex = singleIntegerPrimaryKey();
    primaryIndex.clear();
    primaryIndexBuilt = false;
    validate(constraints, sql);
    uniqueKeys.clear();
    indexMaps.clear();
    foreignKeys.clear();
    if (primaryKey.length > 0) {
      uniqueKeys.add(new UniqueKey(primaryKey.clone(), -1));
    }
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      if (column.unique() && !column.primaryKey()) {
        uniqueKeys.add(new UniqueKey(new int[] {i}, -1));
        indexMaps.add(new IndexMap(new int[] {i}, -1, true));
      }
    }
    for (TableConstraint constraint : constraints) {
      if (constraint instanceof UniqueConstraint unique) {
        int[] cols = indexes(unique.columns(), sql);
        uniqueKeys.add(new UniqueKey(cols, -1));
        if (!sameSet(cols, primaryKey)) {
          indexMaps.add(new IndexMap(cols.clone(), -1, true));
        }
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
    Integer hit = columnByLower.get(columnName.toLowerCase(Locale.ROOT));
    return hit == null ? -1 : hit;
  }

  private void rebuildColumnIndex() {
    columnByLower.clear();
    for (int i = 0; i < columns.size(); i++) {
      columnByLower.put(columns.get(i).name().toLowerCase(Locale.ROOT), i);
    }
  }

  public int rowCount() {
    if (fileRows != null && !mutated) {
      return fileRows.rowCount();
    }
    ensureLoaded();
    return rows.size();
  }

  public Object[] copyRow(int row) {
    if (fileRows != null && !mutated) {
      return fileRows.copyRow(row);
    }
    ensureLoaded();
    return rows.get(row).clone();
  }

  public boolean contains(int column, Object value) {
    if (column < 0 || value == null) {
      return false;
    }
    if (fileRows != null && !mutated) {
      int n = fileRows.rowCount();
      for (int i = 0; i < n; i++) {
        if (value.equals(fileRows.value(i, column))) {
          return true;
        }
      }
      return false;
    }
    ensureLoaded();
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
    Object value = value(row, integerPkIndex);
    return value instanceof Long id ? id : null;
  }

  public Object value(int row, int column) {
    if (fileRows != null && !mutated) {
      return fileRows.value(row, column);
    }
    ensureLoaded();
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
    if (primaryKey.length == 0 || primaryKey.length != key.length) {
      return -1;
    }
    ensurePrimaryIndex();
    Object mapKey = compactKey(key);
    if (mapKey == null) {
      return -1;
    }
    Integer row = primaryIndex.get(mapKey);
    return row == null ? -1 : row;
  }

  /** Rows matching a secondary (or unique non-PK) index. Empty when nothing matches. */
  public int[] seekRows(int[] columns, Object[] key) {
    if (primaryKey.length > 0 && sameSet(columns, primaryKey)) {
      int row = seek(key);
      return row < 0 ? EMPTY_ROWS : new int[] {row};
    }
    IndexMap map = findIndexMap(columns);
    if (map == null) {
      return null;
    }
    populateIndexIfNeeded(map);
    Object mapKey = compactKey(key);
    if (mapKey == null) {
      return EMPTY_ROWS;
    }
    if (map.unique) {
      Integer row = map.uniqueRows.get(mapKey);
      return row == null ? EMPTY_ROWS : new int[] {row};
    }
    ArrayList<Integer> hits = map.multiRows.get(mapKey);
    if (hits == null || hits.isEmpty()) {
      return EMPTY_ROWS;
    }
    int[] out = new int[hits.size()];
    for (int i = 0; i < hits.size(); i++) {
      out[i] = hits.get(i);
    }
    return out;
  }

  public boolean isUniqueIndex(int[] columns) {
    if (primaryKey.length > 0 && sameSet(columns, primaryKey)) {
      return true;
    }
    IndexMap map = findIndexMap(columns);
    return map != null && map.unique;
  }

  /** Unique secondary indexes (not the primary key). */
  public List<int[]> uniqueIndexColumns() {
    List<int[]> list = new ArrayList<>();
    for (IndexMap map : indexMaps) {
      if (map.unique) {
        list.add(map.columns.clone());
      }
    }
    return list;
  }

  /** Non-unique secondary indexes. */
  public List<int[]> secondaryIndexColumns() {
    List<int[]> list = new ArrayList<>();
    for (IndexMap map : indexMaps) {
      if (!map.unique) {
        list.add(map.columns.clone());
      }
    }
    return list;
  }

  /**
   * Ensure a (non-unique) secondary index exists for these columns so SeekIdx can probe without a
   * nested full scan. No-op when the primary key or an existing index already covers the set.
   */
  public void ensureIndex(int[] columns) {
    if (columns == null || columns.length == 0) {
      return;
    }
    if (primaryKey.length > 0 && sameSet(columns, primaryKey)) {
      ensurePrimaryIndex();
      return;
    }
    IndexMap existing = findIndexMap(columns);
    if (existing != null) {
      populateIndexIfNeeded(existing);
      return;
    }
    IndexMap map = new IndexMap(columns.clone(), -1, false);
    indexMaps.add(map);
    populateIndexIfNeeded(map);
  }

  public Place place(Object[] raw, InsertMode mode, int[] conflictTarget) {
    materializeForWrite();
    markMutated();
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
    indexRow(row, rows.size() - 1);
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
    materializeForWrite();
    markMutated();
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
    unindexRow(rows.get(row), row);
    rows.set(row, checked);
    indexRow(checked, row);
  }

  public void remove(int row) {
    materializeForWrite();
    markMutated();
    rows.remove(row);
    rebuildAllIndexes();
  }

  int findRow(Object[] values) {
    if (values == null || values.length != columns.size()) {
      return -1;
    }
    int n = rowCount();
    for (int row = 0; row < n; row++) {
      Object[] current = copyRow(row);
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
    materializeForWrite();
    markMutated();
    columns.add(column);
    rebuildColumnIndex();
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
    if (!sameSet(keyColumns, primaryKey)) {
      IndexMap map = new IndexMap(keyColumns.clone(), partialColumn, true);
      indexMaps.add(map);
      populateIndexIfNeeded(map);
    }
  }

  void addNonUniqueIndex(int[] keyColumns, int partialColumn) {
    IndexMap map = new IndexMap(keyColumns.clone(), partialColumn, false);
    indexMaps.add(map);
    populateIndexIfNeeded(map);
  }

  State capture() {
    List<UniqueKey> keys = new ArrayList<>(uniqueKeys.size());
    for (UniqueKey key : uniqueKeys) {
      keys.add(new UniqueKey(key.columns.clone(), key.partial));
    }
    List<IndexSpec> specs = new ArrayList<>(indexMaps.size());
    for (IndexMap map : indexMaps) {
      specs.add(new IndexSpec(map.columns.clone(), map.partial, map.unique));
    }
    // Keep deferred / file-backed tables lazy across transaction snapshots.
    if (deferredLoad != null || (fileRows != null && rows.isEmpty() && !mutated)) {
      return new State(
          name,
          sql,
          List.copyOf(columns),
          primaryKey.clone(),
          integerPkIndex,
          List.of(),
          nextId,
          keys,
          specs,
          List.copyOf(foreignKeys),
          deferredLoad,
          sourceRootPage,
          mutated,
          fileRows,
          sidecarPath);
    }
    // Share row array references: place/replace never mutate an Object[] already in rows.
    List<Object[]> copy = new ArrayList<>(rows);
    return new State(
        name,
        sql,
        List.copyOf(columns),
        primaryKey.clone(),
        integerPkIndex,
        copy,
        nextId,
        keys,
        specs,
        List.copyOf(foreignKeys),
        null,
        sourceRootPage,
        mutated,
        null,
        sidecarPath);
  }

  void restore(State state) {
    name = state.name;
    sql = state.sql;
    columns.clear();
    columns.addAll(state.columns);
    rebuildColumnIndex();
    primaryKey = state.primaryKey.clone();
    integerPkIndex = state.integerPkIndex;
    nextId = state.nextId;
    uniqueKeys.clear();
    uniqueKeys.addAll(state.keys);
    foreignKeys.clear();
    foreignKeys.addAll(state.foreignKeys);
    pendingConflict = -1;
    indexMaps.clear();
    for (IndexSpec spec : state.indexSpecs) {
      indexMaps.add(new IndexMap(spec.columns.clone(), spec.partial, spec.unique));
    }
    rows.clear();
    primaryIndex.clear();
    primaryIndexBuilt = false;
    deferredLoad = state.deferredLoad;
    sourceRootPage = state.sourceRootPage;
    mutated = state.mutated;
    fileRows = state.fileRows;
    sidecarPath = state.sidecarPath;
    if (deferredLoad != null || fileRows != null) {
      return;
    }
    rows.addAll(state.rows);
    rebuildAllIndexes();
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
        if (!accepts(column, column.defaultValue())) {
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

  private static boolean accepts(Column column, Object value) {
    return switch (column.type()) {
      case INTEGER -> value instanceof Long;
      case TEXT -> value instanceof String;
      case REAL -> value instanceof Long || value instanceof Double;
      case BLOB -> value instanceof byte[] || value instanceof String;
    };
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
        if (!accepts(column, value)) {
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
    if (primaryKey.length > 0 && sameSet(key, primaryKey)) {
      Object mapKey = keyOfRow(row, primaryKey);
      if (mapKey == null) {
        return -1;
      }
      Integer hit = primaryIndex.get(mapKey);
      if (hit == null || hit == ignore) {
        return -1;
      }
      return hit;
    }
    IndexMap map = findIndexMap(key);
    if (map != null && map.unique) {
      Object mapKey = keyOfRow(row, map.columns);
      if (mapKey == null) {
        return -1;
      }
      Integer hit = map.uniqueRows.get(mapKey);
      if (hit == null || hit == ignore) {
        return -1;
      }
      return hit;
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

  private void rebuildAllIndexes() {
    primaryIndex.clear();
    primaryIndexBuilt = false;
    for (IndexMap map : indexMaps) {
      map.uniqueRows.clear();
      map.multiRows.clear();
      map.populated = false;
    }
    for (int i = 0; i < rows.size(); i++) {
      indexRow(rows.get(i), i);
    }
    if (!rows.isEmpty() || fileRows == null) {
      primaryIndexBuilt = true;
      for (IndexMap map : indexMaps) {
        map.populated = true;
      }
    }
  }

  private void populateIndexIfNeeded(IndexMap map) {
    if (map.populated) {
      return;
    }
    if (fileRows != null && !mutated && rows.isEmpty()) {
      int n = fileRows.rowCount();
      for (int i = 0; i < n; i++) {
        indexOne(map, fileRows.peekRow(i), i);
      }
      map.populated = true;
      return;
    }
    ensureLoaded();
    map.uniqueRows.clear();
    map.multiRows.clear();
    for (int i = 0; i < rows.size(); i++) {
      indexOne(map, rows.get(i), i);
    }
    map.populated = true;
  }

  private void ensurePrimaryIndex() {
    if (primaryIndexBuilt || primaryKey.length == 0) {
      return;
    }
    if (fileRows != null && !mutated && rows.isEmpty()) {
      int n = fileRows.rowCount();
      for (int i = 0; i < n; i++) {
        Object mapKey = keyOfRow(fileRows.peekRow(i), primaryKey);
        if (mapKey != null) {
          primaryIndex.put(mapKey, i);
        }
      }
      primaryIndexBuilt = true;
      return;
    }
    ensureLoaded();
    primaryIndex.clear();
    for (int i = 0; i < rows.size(); i++) {
      Object mapKey = keyOfRow(rows.get(i), primaryKey);
      if (mapKey != null) {
        primaryIndex.put(mapKey, i);
      }
    }
    primaryIndexBuilt = true;
  }

  private void indexRow(Object[] row, int index) {
    Object mapKey = keyOfRow(row, primaryKey);
    if (mapKey != null) {
      primaryIndex.put(mapKey, index);
    }
    for (IndexMap map : indexMaps) {
      indexOne(map, row, index);
    }
  }

  private void unindexRow(Object[] row, int index) {
    Object mapKey = keyOfRow(row, primaryKey);
    if (mapKey != null) {
      primaryIndex.remove(mapKey);
    }
    for (IndexMap map : indexMaps) {
      if (skips(map, row)) {
        continue;
      }
      Object key = keyOfRow(row, map.columns);
      if (key == null) {
        continue;
      }
      if (map.unique) {
        Integer hit = map.uniqueRows.get(key);
        if (hit != null && hit == index) {
          map.uniqueRows.remove(key);
        }
      } else {
        ArrayList<Integer> hits = map.multiRows.get(key);
        if (hits != null) {
          hits.remove((Integer) index);
          if (hits.isEmpty()) {
            map.multiRows.remove(key);
          }
        }
      }
    }
  }

  private void indexOne(IndexMap map, Object[] row, int index) {
    if (skips(map, row)) {
      return;
    }
    Object key = keyOfRow(row, map.columns);
    if (key == null) {
      return;
    }
    if (map.unique) {
      map.uniqueRows.put(key, index);
    } else {
      map.multiRows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
    }
  }

  private IndexMap findIndexMap(int[] columns) {
    for (IndexMap map : indexMaps) {
      if (sameSet(map.columns, columns)) {
        return map;
      }
    }
    return null;
  }

  private boolean skips(IndexMap map, Object[] row) {
    return map.partial >= 0 && row[map.partial] == null;
  }

  private Object keyOfRow(Object[] row, int[] columns) {
    if (columns.length == 0) {
      return null;
    }
    if (columns.length == 1) {
      return row[columns[0]];
    }
    Object[] parts = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      Object part = row[columns[i]];
      if (part == null) {
        return null;
      }
      parts[i] = part;
    }
    return List.copyOf(Arrays.asList(parts));
  }

  /** Key values already in index column order. */
  private Object compactKey(Object[] key) {
    for (Object part : key) {
      if (part == null) {
        return null;
      }
    }
    if (key.length == 1) {
      return key[0];
    }
    return List.copyOf(Arrays.asList(key.clone()));
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

  private record IndexSpec(int[] columns, int partial, boolean unique) {}

  private static final class IndexMap {
    private final int[] columns;
    private final int partial;
    private final boolean unique;
    private final Map<Object, Integer> uniqueRows = new HashMap<>();
    private final Map<Object, ArrayList<Integer>> multiRows = new HashMap<>();
    /** False until filled from rows / file; schema-only maps start empty. */
    private boolean populated;

    private IndexMap(int[] columns, int partial, boolean unique) {
      this.columns = columns;
      this.partial = partial;
      this.unique = unique;
    }
  }

  static final class State {
    private final String name;
    private final String sql;
    private final List<Column> columns;
    private final int[] primaryKey;
    private final int integerPkIndex;
    private final List<Object[]> rows;
    private final long nextId;
    private final List<UniqueKey> keys;
    private final List<IndexSpec> indexSpecs;
    private final List<ForeignKeyConstraint> foreignKeys;
    private final Runnable deferredLoad;
    private final int sourceRootPage;
    private final boolean mutated;
    private final FileBackedRows fileRows;
    private final java.nio.file.Path sidecarPath;

    private State(
        String name,
        String sql,
        List<Column> columns,
        int[] primaryKey,
        int integerPkIndex,
        List<Object[]> rows,
        long nextId,
        List<UniqueKey> keys,
        List<IndexSpec> indexSpecs,
        List<ForeignKeyConstraint> foreignKeys,
        Runnable deferredLoad,
        int sourceRootPage,
        boolean mutated,
        FileBackedRows fileRows,
        java.nio.file.Path sidecarPath) {
      this.name = name;
      this.sql = sql;
      this.columns = columns;
      this.primaryKey = primaryKey;
      this.integerPkIndex = integerPkIndex;
      this.rows = rows;
      this.nextId = nextId;
      this.keys = keys;
      this.indexSpecs = indexSpecs;
      this.foreignKeys = foreignKeys;
      this.deferredLoad = deferredLoad;
      this.sourceRootPage = sourceRootPage;
      this.mutated = mutated;
      this.fileRows = fileRows;
      this.sidecarPath = sidecarPath;
    }
  }
}
