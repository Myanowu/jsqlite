package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.store.TableInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Encode catalog tables as sqlite pages. Page 1 is sqlite_master.
 * Interior trees may be multi-level so large tables are not limited to one root page.
 */
public final class Btree {
  private Btree() {}

  public static byte[][] image(MemoryCatalog catalog) {
    return image(catalog, null, Pager.PAGE_SIZE);
  }

  /**
   * Encode the catalog. Unmodified tables with a {@link Table#sourceRootPage()} copy their btree
   * from {@code source} instead of materializing rows (needed for large mmap-backed pilot DBs).
   */
  public static byte[][] image(MemoryCatalog catalog, DbImage source, int pageSize) {
    Image image = new Image();
    List<MasterRow> master = new ArrayList<>();
    for (TableInfo info : catalog.tables()) {
      Table table = catalog.find(info.name());
      int root;
      String sql = table.sql();
      if (sql == null && "sqlite_sequence".equals(table.name())) {
        sql = "CREATE TABLE sqlite_sequence(name,seq)";
      }
      if (table.isSidecar()) {
        // Data lives in the sibling file; main keeps an empty leaf so schema JOINs stay valid.
        root = image.allocate();
        writeLeaf(image.page(root), List.of(), 0, image);
      } else {
        int sourceRoot = table.sourceRootPage();
        if (source != null && sourceRoot > 0 && pageSize == Pager.PAGE_SIZE) {
          root = copyTree(image, source, pageSize, sourceRoot);
        } else {
          root = writeTable(image, cells(table));
        }
      }
      master.add(new MasterRow("table", table.name(), table.name(), root, sql));
    }
    for (IndexDef index : catalog.indexes()) {
      if (index.sql() == null) {
        continue;
      }
      int root = image.allocate();
      writeLeaf(image.page(root), List.of(), 0, image);
      master.add(new MasterRow("index", index.name(), index.table(), root, index.sql()));
    }
    writeMaster(image, master);
    SqliteHeader.putU32(image.page(1), 28, image.pages.size());
    return image.toArray();
  }

  /**
   * Build a single-table sqlite image by page-copying {@code tableName}'s btree from {@code source}.
   * Used by table sidecars — peak heap ≈ size of that table's pages.
   */
  public static byte[][] extractTable(DbImage source, int pageSize, String tableName) {
    MasterRow entry = findTableMaster(source, pageSize, tableName);
    if (entry.root <= 1) {
      throw new SqlException("table " + tableName + " has no data root to extract");
    }
    Image image = new Image();
    int root = copyTree(image, source, pageSize, entry.root);
    writeMaster(image, List.of(new MasterRow("table", entry.name, entry.name, root, entry.sql)));
    SqliteHeader.putU32(image.page(1), 28, image.pages.size());
    return image.toArray();
  }

  /** Encode one mutated table as a standalone sqlite file (sidecar rewrite after INSERT/UPDATE). */
  public static byte[][] singleTableImage(Table table) {
    if (table.sql() == null) {
      throw new SqlException("table " + table.name() + " has no CREATE SQL for sidecar write");
    }
    Image image = new Image();
    int root = writeTable(image, cells(table));
    writeMaster(image, List.of(new MasterRow("table", table.name(), table.name(), root, table.sql())));
    SqliteHeader.putU32(image.page(1), 28, image.pages.size());
    return image.toArray();
  }

  /** Locate a table's master row (type/name/table/root/sql) in a sqlite image. */
  public static MasterInfo tableMaster(DbImage source, int pageSize, String tableName) {
    MasterRow row = findTableMaster(source, pageSize, tableName);
    return new MasterInfo(row.name, row.root, row.sql);
  }

  public record MasterInfo(String name, int rootPage, String sql) {}

  /**
   * Rewrite {@code source} keeping schema; tables named in {@code emptyTableData} (and indexes on
   * those tables) become empty leaves. Other btrees are page-copied. Peak heap ≈ remaining data.
   */
  public static byte[][] rewriteSkippingTables(
      DbImage source, int pageSize, Set<String> emptyTableData) {
    Set<String> skip = new HashSet<>();
    for (String name : emptyTableData) {
      if (name != null && !name.isBlank()) {
        skip.add(name.toLowerCase(Locale.ROOT));
      }
    }
    Image image = new Image();
    List<MasterRow> out = new ArrayList<>();
    for (MasterRow entry : readMaster(source, pageSize)) {
      boolean empty =
          ("table".equals(entry.type) && skip.contains(entry.name.toLowerCase(Locale.ROOT)))
              || ("index".equals(entry.type)
                  && skip.contains(entry.table.toLowerCase(Locale.ROOT)));
      int root;
      if (empty || entry.root <= 1) {
        root = image.allocate();
        writeLeaf(image.page(root), List.of(), 0, image);
      } else {
        root = copyTree(image, source, pageSize, entry.root);
      }
      out.add(new MasterRow(entry.type, entry.name, entry.table, root, entry.sql));
    }
    writeMaster(image, out);
    SqliteHeader.putU32(image.page(1), 28, image.pages.size());
    return image.toArray();
  }

  private static MasterRow findTableMaster(DbImage source, int pageSize, String tableName) {
    for (MasterRow row : readMaster(source, pageSize)) {
      if ("table".equals(row.type) && row.name.equalsIgnoreCase(tableName)) {
        return row;
      }
    }
    throw new SqlException("table not found for sidecar: " + tableName);
  }

  private static List<MasterRow> readMaster(DbImage file, int pageSize) {
    List<MasterRow> entries = new ArrayList<>();
    walkMaster(
        file,
        pageSize,
        1,
        true,
        rec ->
            entries.add(
                new MasterRow(
                    masterText(rec, 0),
                    masterText(rec, 1),
                    masterText(rec, 2),
                    masterInt(rec, 3),
                    masterText(rec, 4))));
    return entries;
  }

  private static void walkMaster(
      DbImage file,
      int pageSize,
      int pageNumber,
      boolean isPage1,
      java.util.function.Consumer<List<Object>> sink) {
    long base = (long) (pageNumber - 1) * pageSize;
    int header = isPage1 ? SqliteHeader.SIZE : 0;
    int type = file.get(base + header) & 0xff;
    int cells = ((file.get(base + header + 3) & 0xff) << 8) | (file.get(base + header + 4) & 0xff);
    if (type == 0x05) {
      int right =
          ((file.get(base + header + 8) & 0xff) << 24)
              | ((file.get(base + header + 9) & 0xff) << 16)
              | ((file.get(base + header + 10) & 0xff) << 8)
              | (file.get(base + header + 11) & 0xff);
      long array = base + header + 12;
      for (int i = 0; i < cells; i++) {
        long cell =
            base
                + (((file.get(array + i * 2L) & 0xff) << 8)
                    | (file.get(array + i * 2L + 1) & 0xff));
        int child =
            ((file.get(cell) & 0xff) << 24)
                | ((file.get(cell + 1) & 0xff) << 16)
                | ((file.get(cell + 2) & 0xff) << 8)
                | (file.get(cell + 3) & 0xff);
        walkMaster(file, pageSize, child, false, sink);
      }
      walkMaster(file, pageSize, right, false, sink);
      return;
    }
    if (type != 0x0d) {
      throw new SqlException("unsupported btree page type " + type);
    }
    long array = base + header + 8;
    for (int i = 0; i < cells; i++) {
      long cell =
          base
              + (((file.get(array + i * 2L) & 0xff) << 8) | (file.get(array + i * 2L + 1) & 0xff));
      long[] cursor = {cell};
      int payloadSize = (int) masterVarint(file, cursor);
      masterVarint(file, cursor);
      int local = RecordCodec.localPayload(pageSize, payloadSize);
      byte[] payload = new byte[payloadSize];
      int stored = Math.min(local, payloadSize);
      file.copyTo(cursor[0], payload, 0, stored);
      cursor[0] += stored;
      if (stored < payloadSize) {
        int page =
            ((file.get(cursor[0]) & 0xff) << 24)
                | ((file.get(cursor[0] + 1) & 0xff) << 16)
                | ((file.get(cursor[0] + 2) & 0xff) << 8)
                | (file.get(cursor[0] + 3) & 0xff);
        int pos = stored;
        while (pos < payload.length) {
          if (page <= 0) {
            throw new SqlException("truncated overflow payload");
          }
          long overflowBase = (long) (page - 1) * pageSize;
          int next =
              ((file.get(overflowBase) & 0xff) << 24)
                  | ((file.get(overflowBase + 1) & 0xff) << 16)
                  | ((file.get(overflowBase + 2) & 0xff) << 8)
                  | (file.get(overflowBase + 3) & 0xff);
          int take = Math.min(pageSize - 4, payload.length - pos);
          file.copyTo(overflowBase + 4, payload, pos, take);
          pos += take;
          page = next;
        }
      }
      sink.accept(RecordCodec.decodeRecord(payload));
    }
  }

  private static String masterText(List<Object> row, int i) {
    Object v = i < row.size() ? row.get(i) : null;
    return v == null ? null : String.valueOf(v);
  }

  private static int masterInt(List<Object> row, int i) {
    Object v = i < row.size() ? row.get(i) : null;
    return v instanceof Number n ? n.intValue() : 0;
  }

  private static long masterVarint(DbImage file, long[] cursor) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      int b = file.get(cursor[0]++) & 0xff;
      value = (value << 7) | (b & 0x7f);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    return (value << 8) | (file.get(cursor[0]++) & 0xff);
  }

  private static int copyTree(Image image, DbImage source, int pageSize, int srcPage) {
    long pageCount = source.byteLength() / pageSize;
    if (srcPage < 1 || srcPage > pageCount) {
      throw new SqlException("copyTree page " + srcPage + " outside database (" + pageCount + ")");
    }
    int dest = image.allocate();
    byte[] page = image.page(dest);
    source.copyTo((long) (srcPage - 1) * pageSize, page, 0, pageSize);
    int type = page[0] & 0xff;
    if (type == 0x05) {
      int cells = u16(page, 3);
      for (int i = 0; i < cells; i++) {
        int cell = u16(page, 12 + i * 2);
        int child = u32(page, cell);
        putU32(page, cell, copyTree(image, source, pageSize, child));
      }
      int right = u32(page, 8);
      putU32(page, 8, copyTree(image, source, pageSize, right));
      return dest;
    }
    if (type != 0x0d) {
      throw new SqlException("unsupported btree page type " + type + " while copying");
    }
    int cells = u16(page, 3);
    for (int i = 0; i < cells; i++) {
      int cell = u16(page, 8 + i * 2);
      rewriteOverflow(image, source, pageSize, page, cell);
    }
    return dest;
  }

  private static void rewriteOverflow(
      Image image, DbImage source, int pageSize, byte[] leaf, int cellOffset) {
    int[] cursor = {cellOffset};
    int payloadSize = (int) readVarint(leaf, cursor);
    readVarint(leaf, cursor); // rowid
    int usable = pageSize; // reserved bytes already reflected in stored pages
    int local = RecordCodec.localPayload(usable, payloadSize);
    cursor[0] += Math.min(local, payloadSize);
    if (local >= payloadSize) {
      return;
    }
    int overflow = u32(leaf, cursor[0]);
    putU32(leaf, cursor[0], copyOverflow(image, source, pageSize, overflow));
  }

  private static int copyOverflow(Image image, DbImage source, int pageSize, int srcPage) {
    long pageCount = source.byteLength() / pageSize;
    int first = 0;
    int previous = 0;
    int page = srcPage;
    int guard = 0;
    while (page > 0) {
      if (page > pageCount || ++guard > pageCount) {
        throw new SqlException("overflow chain corrupt at page " + page);
      }
      int dest = image.allocate();
      byte[] raw = image.page(dest);
      source.copyTo((long) (page - 1) * pageSize, raw, 0, pageSize);
      if (first == 0) {
        first = dest;
      } else {
        putU32(image.page(previous), 0, dest);
      }
      previous = dest;
      page = u32(raw, 0);
      putU32(raw, 0, 0);
    }
    return first;
  }

  private static long readVarint(byte[] page, int[] cursor) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      int b = page[cursor[0]++] & 0xff;
      value = (value << 7) | (b & 0x7f);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    value = (value << 8) | (page[cursor[0]++] & 0xff);
    return value;
  }

  private static int u16(byte[] page, int offset) {
    return ((page[offset] & 0xff) << 8) | (page[offset + 1] & 0xff);
  }

  private static int u32(byte[] page, int offset) {
    return ((page[offset] & 0xff) << 24)
        | ((page[offset + 1] & 0xff) << 16)
        | ((page[offset + 2] & 0xff) << 8)
        | (page[offset + 3] & 0xff);
  }

  private static void putU32(byte[] page, int offset, int value) {
    SqliteHeader.putU32(page, offset, value);
  }

  private static List<LeafCell> cells(Table table) {
    List<LeafCell> cells = new ArrayList<>();
    boolean integerKey = false;
    int key = -1;
    for (int column = 0; column < table.columns().size(); column++) {
      if (table.assignsIdentity(column)) {
        integerKey = true;
        key = column;
      }
    }
    for (int row = 0; row < table.rowCount(); row++) {
      Object[] values = table.copyRow(row);
      long rowId = row + 1L;
      if (integerKey && values[key] instanceof Long id) {
        rowId = id;
      }
      cells.add(new LeafCell(rowId, RecordCodec.encodeRecord(values)));
    }
    return cells;
  }

  private static int writeTable(Image image, List<LeafCell> cells) {
    List<List<LeafCell>> groups = pack(cells, 0);
    if (groups.size() == 1) {
      int page = image.allocate();
      writeLeaf(image.page(page), groups.get(0), 0, image);
      return page;
    }
    List<ChildRef> children = new ArrayList<>(groups.size());
    for (List<LeafCell> group : groups) {
      int page = image.allocate();
      writeLeaf(image.page(page), group, 0, image);
      children.add(new ChildRef(page, group.get(0).rowId));
    }
    return writeInteriorRoot(image, children, 0);
  }

  private static void writeMaster(Image image, List<MasterRow> rows) {
    List<LeafCell> cells = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      MasterRow row = rows.get(i);
      Object[] values = {row.type, row.name, row.table, (long) row.root, row.sql};
      cells.add(new LeafCell(i + 1L, RecordCodec.encodeRecord(values)));
    }
    List<List<LeafCell>> groups = pack(cells, SqliteHeader.SIZE);
    if (groups.size() == 1) {
      writeLeaf(image.page(1), groups.get(0), SqliteHeader.SIZE, image);
      return;
    }
    List<ChildRef> children = new ArrayList<>(groups.size());
    for (List<LeafCell> group : groups) {
      int page = image.allocate();
      writeLeaf(image.page(page), group, 0, image);
      children.add(new ChildRef(page, group.get(0).rowId));
    }
    // Root must live on page 1 (sqlite header reserved). Intermediate interiors use offset 0.
    List<ChildRef> level = children;
    while (!fitsInterior(level, SqliteHeader.SIZE)) {
      level = packInteriorLevel(image, level, 0);
    }
    writeInteriorOnto(image.page(1), level, SqliteHeader.SIZE);
  }

  /** Build an interior tree and return the root page number (not page 1). */
  private static int writeInteriorRoot(Image image, List<ChildRef> children, int headerAt) {
    List<ChildRef> level = children;
    while (level.size() > 1 && !fitsInterior(level, headerAt)) {
      level = packInteriorLevel(image, level, headerAt);
    }
    if (level.size() == 1) {
      return level.get(0).page;
    }
    int page = image.allocate();
    writeInteriorOnto(image.page(page), level, headerAt);
    return page;
  }

  /** Split children into as few interior pages as will fit; return those pages as the next level. */
  private static List<ChildRef> packInteriorLevel(Image image, List<ChildRef> children, int headerAt) {
    if (children.size() < 2) {
      return children;
    }
    List<ChildRef> next = new ArrayList<>();
    int i = 0;
    while (i < children.size()) {
      int take = 2;
      while (take < children.size() - i && fitsInterior(children.subList(i, i + take + 1), headerAt)) {
        take++;
      }
      // If even 2 do not fit, something is wrong with page size assumptions.
      if (!fitsInterior(children.subList(i, i + take), headerAt)) {
        throw new SqlException("interior page cannot hold two children");
      }
      List<ChildRef> batch = new ArrayList<>(children.subList(i, i + take));
      int page = image.allocate();
      writeInteriorOnto(image.page(page), batch, headerAt);
      next.add(new ChildRef(page, batch.get(0).firstKey));
      i += take;
    }
    return next;
  }

  private static boolean fitsInterior(List<ChildRef> children, int headerAt) {
    if (children.size() <= 1) {
      return true;
    }
    int cellCount = children.size() - 1;
    int payload = 0;
    for (int i = 0; i < cellCount; i++) {
      payload += 4 + RecordCodec.putVarint(children.get(i + 1).firstKey).length;
    }
    int pointerEnd = headerAt + 12 + cellCount * 2;
    int cursor = Pager.PAGE_SIZE - payload;
    return cursor >= pointerEnd;
  }

  private static List<List<LeafCell>> pack(List<LeafCell> cells, int headerAt) {
    List<List<LeafCell>> groups = new ArrayList<>();
    List<LeafCell> current = new ArrayList<>();
    int used = headerAt + 8;
    for (LeafCell cell : cells) {
      int cost = 2 + onPage(cell);
      if (!current.isEmpty() && used + cost > Pager.PAGE_SIZE) {
        groups.add(current);
        current = new ArrayList<>();
        used = headerAt + 8;
      }
      current.add(cell);
      used += cost;
    }
    if (!current.isEmpty() || groups.isEmpty()) {
      groups.add(current);
    }
    return groups;
  }

  private static int onPage(LeafCell cell) {
    int local = Math.min(cell.payload.length, RecordCodec.localPayload(Pager.PAGE_SIZE, cell.payload.length));
    int size = RecordCodec.putVarint(cell.payload.length).length
        + RecordCodec.putVarint(cell.rowId).length
        + local;
    if (local < cell.payload.length) {
      size += 4;
    }
    return size;
  }

  private static void writeLeaf(byte[] page, List<LeafCell> cells, int headerAt, Image image) {
    Arrays.fill(page, headerAt, page.length, (byte) 0);
    List<byte[]> stored = new ArrayList<>();
    for (LeafCell cell : cells) {
      stored.add(cellBytes(cell, image));
    }
    int cursor = Pager.PAGE_SIZE;
    int[] offsets = new int[stored.size()];
    for (int i = stored.size() - 1; i >= 0; i--) {
      cursor -= stored.get(i).length;
      offsets[i] = cursor;
    }
    int pointerEnd = headerAt + 8 + stored.size() * 2;
    if (cursor < pointerEnd) {
      throw new SqlException("leaf page ran out of space");
    }
    page[headerAt] = 0x0d;
    SqliteHeader.putU16(page, headerAt + 3, stored.size());
    SqliteHeader.putU16(page, headerAt + 5, cursor == Pager.PAGE_SIZE ? Pager.PAGE_SIZE : cursor);
    for (int i = 0; i < stored.size(); i++) {
      SqliteHeader.putU16(page, headerAt + 8 + i * 2, offsets[i]);
      System.arraycopy(stored.get(i), 0, page, offsets[i], stored.get(i).length);
    }
  }

  private static byte[] cellBytes(LeafCell cell, Image image) {
    int local = Math.min(cell.payload.length, RecordCodec.localPayload(Pager.PAGE_SIZE, cell.payload.length));
    int overflow = 0;
    if (local < cell.payload.length) {
      overflow = writeOverflow(image, cell.payload, local);
    }
    byte[] size = RecordCodec.putVarint(cell.payload.length);
    byte[] rowId = RecordCodec.putVarint(cell.rowId);
    byte[] raw = new byte[size.length + rowId.length + local + (overflow == 0 ? 0 : 4)];
    int pos = 0;
    System.arraycopy(size, 0, raw, pos, size.length);
    pos += size.length;
    System.arraycopy(rowId, 0, raw, pos, rowId.length);
    pos += rowId.length;
    System.arraycopy(cell.payload, 0, raw, pos, local);
    pos += local;
    if (overflow != 0) {
      SqliteHeader.putU32(raw, pos, overflow);
    }
    return raw;
  }

  private static int writeOverflow(Image image, byte[] payload, int local) {
    int first = 0;
    int previous = 0;
    int offset = local;
    while (offset < payload.length) {
      int pageNumber = image.allocate();
      byte[] page = image.page(pageNumber);
      if (previous == 0) {
        first = pageNumber;
      } else {
        SqliteHeader.putU32(image.page(previous), 0, pageNumber);
      }
      int take = Math.min(Pager.PAGE_SIZE - 4, payload.length - offset);
      System.arraycopy(payload, offset, page, 4, take);
      offset += take;
      previous = pageNumber;
    }
    return first;
  }

  private static void writeInteriorOnto(byte[] page, List<ChildRef> children, int headerAt) {
    if (children.size() < 2) {
      throw new SqlException("interior page needs at least two children");
    }
    int count = children.size() - 1;
    List<byte[]> stored = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      byte[] key = RecordCodec.putVarint(children.get(i + 1).firstKey);
      byte[] cell = new byte[4 + key.length];
      SqliteHeader.putU32(cell, 0, children.get(i).page);
      System.arraycopy(key, 0, cell, 4, key.length);
      stored.add(cell);
    }
    Arrays.fill(page, headerAt, page.length, (byte) 0);
    int cursor = Pager.PAGE_SIZE;
    int[] offsets = new int[stored.size()];
    for (int i = stored.size() - 1; i >= 0; i--) {
      cursor -= stored.get(i).length;
      offsets[i] = cursor;
    }
    int pointerEnd = headerAt + 12 + stored.size() * 2;
    if (cursor < pointerEnd) {
      throw new SqlException("interior page ran out of space");
    }
    page[headerAt] = 0x05;
    SqliteHeader.putU16(page, headerAt + 3, count);
    SqliteHeader.putU16(page, headerAt + 5, cursor == Pager.PAGE_SIZE ? Pager.PAGE_SIZE : cursor);
    SqliteHeader.putU32(page, headerAt + 8, children.get(children.size() - 1).page);
    for (int i = 0; i < stored.size(); i++) {
      SqliteHeader.putU16(page, headerAt + 12 + i * 2, offsets[i]);
      System.arraycopy(stored.get(i), 0, page, offsets[i], stored.get(i).length);
    }
  }

  private record LeafCell(long rowId, byte[] payload) {}

  private record ChildRef(int page, long firstKey) {}

  private record MasterRow(String type, String name, String table, int root, String sql) {}

  private static final class Image {
    private final List<byte[]> pages = new ArrayList<>();

    private Image() {
      pages.add(Pager.emptyDatabase());
    }

    private int allocate() {
      pages.add(new byte[Pager.PAGE_SIZE]);
      return pages.size();
    }

    private byte[] page(int pageNumber) {
      return pages.get(pageNumber - 1);
    }

    private byte[][] toArray() {
      return pages.toArray(byte[][]::new);
    }
  }
}
