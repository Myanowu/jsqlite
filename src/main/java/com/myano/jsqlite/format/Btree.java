package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.store.TableInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 把目錄裡的表編成 sqlite 頁。頁 1 是 sqlite_master。 */
public final class Btree {
  private Btree() {}

  public static byte[][] image(MemoryCatalog catalog) {
    Image image = new Image();
    List<MasterRow> master = new ArrayList<>();
    for (TableInfo info : catalog.tables()) {
      Table table = catalog.find(info.name());
      int root = writeTable(image, cells(table));
      String sql = table.sql();
      if (sql == null && "sqlite_sequence".equals(table.name())) {
        sql = "CREATE TABLE sqlite_sequence(name,seq)";
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
    List<Integer> children = new ArrayList<>();
    for (List<LeafCell> group : groups) {
      int page = image.allocate();
      writeLeaf(image.page(page), group, 0, image);
      children.add(page);
    }
    return writeInterior(image, children, groups, 0);
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
    List<Integer> children = new ArrayList<>();
    for (List<LeafCell> group : groups) {
      int page = image.allocate();
      writeLeaf(image.page(page), group, 0, image);
      children.add(page);
    }
    writeInteriorOnto(image.page(1), children, groups, SqliteHeader.SIZE);
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

  private static int writeInterior(Image image, List<Integer> children, List<List<LeafCell>> groups, int headerAt) {
    int pageNumber = image.allocate();
    writeInteriorOnto(image.page(pageNumber), children, groups, headerAt);
    return pageNumber;
  }

  private static void writeInteriorOnto(
      byte[] page, List<Integer> children, List<List<LeafCell>> groups, int headerAt) {
    int count = children.size() - 1;
    List<byte[]> stored = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      byte[] key = RecordCodec.putVarint(groups.get(i + 1).get(0).rowId);
      byte[] cell = new byte[4 + key.length];
      SqliteHeader.putU32(cell, 0, children.get(i));
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
    SqliteHeader.putU32(page, headerAt + 8, children.get(children.size() - 1));
    for (int i = 0; i < stored.size(); i++) {
      SqliteHeader.putU16(page, headerAt + 12 + i * 2, offsets[i]);
      System.arraycopy(stored.get(i), 0, page, offsets[i], stored.get(i).length);
    }
  }

  private record LeafCell(long rowId, byte[] payload) {}

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
