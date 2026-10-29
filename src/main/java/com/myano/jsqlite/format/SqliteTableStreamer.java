package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stream rows into a brand-new single-table sqlite file without holding the whole table as Java
 * objects. Only the current leaf page and interior references stay in memory.
 */
public final class SqliteTableStreamer {
  private SqliteTableStreamer() {}

  public static long write(
      Path path, String tableName, String createSql, Iterator<Object[]> rows) {
    try {
      Files.deleteIfExists(path);
      Files.deleteIfExists(Path.of(path + "-journal"));
      Files.deleteIfExists(Path.of(path + "-wal"));
      Files.deleteIfExists(Path.of(path + "-shm"));
    } catch (IOException ex) {
      throw new SqlException("unable to reset " + path + ": " + ex.getMessage());
    }
    Image image = new Image();
    List<ChildRef> leaves = new ArrayList<>();
    List<LeafCell> current = new ArrayList<>();
    int used = 8;
    long rowId = 0;
    long count = 0;
    while (rows.hasNext()) {
      Object[] values = rows.next();
      rowId++;
      count++;
      LeafCell cell = new LeafCell(rowId, RecordCodec.encodeRecord(values));
      int cost = 2 + onPage(cell);
      if (!current.isEmpty() && used + cost > Pager.PAGE_SIZE) {
        leaves.add(flushLeaf(image, current));
        current = new ArrayList<>();
        used = 8;
      }
      current.add(cell);
      used += cost;
      if (count % 100_000 == 0) {
        System.out.println("  ...stream " + tableName + " " + count);
      }
    }
    if (!current.isEmpty() || leaves.isEmpty()) {
      leaves.add(flushLeaf(image, current));
    }
    int tableRoot = writeInteriorRoot(image, leaves, 0);
    List<MasterRow> master = new ArrayList<>();
    master.add(new MasterRow("table", tableName, tableName, tableRoot, createSql));
    writeMaster(image, master);
    SqliteHeader.putU32(image.page(1), 28, image.pages.size());
    writeFile(path, image.toArray());
    return count;
  }

  /**
   * Replace {@code tableName} in {@code dest} with the same-named table from {@code sourceTableFile}
   * by page-copy (no row materialization). Other dest tables are page-copied unchanged.
   */
  public static void replaceTable(Path dest, Path sourceTableFile, String tableName) {
    Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".merge-tmp");
    DbImage destImage = DbImage.ofFile(dest);
    DbImage srcImage = DbImage.ofFile(sourceTableFile);
    try {
      int pageSize = pageSize(destImage);
      if (pageSize != pageSize(srcImage) || pageSize != Pager.PAGE_SIZE) {
        throw new SqlException("page size mismatch");
      }
      Master destMaster = readMaster(destImage, pageSize);
      Master srcMaster = readMaster(srcImage, pageSize);
      Master.Entry replacement = null;
      for (Master.Entry e : srcMaster.entries) {
        if ("table".equals(e.type) && e.name.equalsIgnoreCase(tableName)) {
          replacement = e;
          break;
        }
      }
      if (replacement == null) {
        throw new SqlException("source has no table " + tableName);
      }
      Image out = new Image();
      List<MasterRow> master = new ArrayList<>();
      boolean replaced = false;
      for (Master.Entry e : destMaster.entries) {
        if (!"table".equals(e.type)) {
          continue;
        }
        int root;
        String sql = e.sql;
        if (e.name.equalsIgnoreCase(tableName)) {
          root = copyTree(out, srcImage, pageSize, replacement.rootPage);
          sql = replacement.sql != null ? replacement.sql : sql;
          replaced = true;
        } else if (e.rootPage > 1) {
          root = copyTree(out, destImage, pageSize, e.rootPage);
        } else {
          int page = out.allocate();
          writeLeaf(out.page(page), List.of(), 0, out);
          root = page;
        }
        master.add(new MasterRow("table", e.name, e.table, root, sql));
      }
      if (!replaced) {
        int root = copyTree(out, srcImage, pageSize, replacement.rootPage);
        master.add(
            new MasterRow(
                "table", replacement.name, replacement.table, root, replacement.sql));
      }
      for (Master.Entry e : destMaster.entries) {
        if (!"index".equals(e.type) || e.sql == null) {
          continue;
        }
        int page = out.allocate();
        writeLeaf(out.page(page), List.of(), 0, out);
        master.add(new MasterRow("index", e.name, e.table, page, e.sql));
      }
      writeMaster(out, master);
      SqliteHeader.putU32(out.page(1), 28, out.pages.size());
      writeFile(tmp, out.toArray());
    } finally {
      destImage.close();
      srcImage.close();
    }
    try {
      Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ex) {
      throw new SqlException("unable to replace " + dest + ": " + ex.getMessage());
    }
  }

  private static void writeFile(Path path, byte[][] pages) {
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength((long) pages.length * Pager.PAGE_SIZE);
      for (int i = 0; i < pages.length; i++) {
        file.seek((long) i * Pager.PAGE_SIZE);
        file.write(pages[i]);
      }
      file.getFD().sync();
    } catch (IOException ex) {
      throw new SqlException("unable to write " + path + ": " + ex.getMessage());
    }
  }

  private static ChildRef flushLeaf(Image image, List<LeafCell> cells) {
    int page = image.allocate();
    writeLeaf(image.page(page), cells, 0, image);
    long first = cells.isEmpty() ? 1L : cells.get(0).rowId;
    return new ChildRef(page, first);
  }

  private static int writeInteriorRoot(Image image, List<ChildRef> children, int headerAt) {
    if (children.size() == 1) {
      return children.get(0).page;
    }
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

  private static List<ChildRef> packInteriorLevel(
      Image image, List<ChildRef> children, int headerAt) {
    List<ChildRef> next = new ArrayList<>();
    int i = 0;
    while (i < children.size()) {
      int take = 2;
      while (take < children.size() - i && fitsInterior(children.subList(i, i + take + 1), headerAt)) {
        take++;
      }
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

  private static int onPage(LeafCell cell) {
    int local =
        Math.min(cell.payload.length, RecordCodec.localPayload(Pager.PAGE_SIZE, cell.payload.length));
    int size =
        RecordCodec.putVarint(cell.payload.length).length
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
    int local =
        Math.min(cell.payload.length, RecordCodec.localPayload(Pager.PAGE_SIZE, cell.payload.length));
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
    List<ChildRef> level = children;
    while (!fitsInterior(level, SqliteHeader.SIZE)) {
      level = packInteriorLevel(image, level, 0);
    }
    writeInteriorOnto(image.page(1), level, SqliteHeader.SIZE);
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
    readVarint(leaf, cursor);
    int local = RecordCodec.localPayload(pageSize, payloadSize);
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

  private static Master readMaster(DbImage file, int pageSize) {
    List<Master.Entry> entries = new ArrayList<>();
    walkTable(
        file,
        pageSize,
        1,
        true,
        rec ->
            entries.add(
                new Master.Entry(
                    text(rec, 0),
                    text(rec, 1),
                    text(rec, 2),
                    integer(rec, 3),
                    text(rec, 4))));
    return new Master(entries);
  }

  private static void walkTable(
      DbImage file, int pageSize, int pageNumber, boolean isPage1, Consumer<List<Object>> sink) {
    long base = (long) (pageNumber - 1) * pageSize;
    int header = isPage1 ? SqliteHeader.SIZE : 0;
    int type = file.get(base + header) & 0xff;
    int cells = u16(file, base + header + 3);
    if (type == 0x05) {
      int right = u32(file, base + header + 8);
      long array = base + header + 12;
      for (int i = 0; i < cells; i++) {
        long cell = base + u16(file, array + i * 2L);
        walkTable(file, pageSize, u32(file, cell), false, sink);
      }
      walkTable(file, pageSize, right, false, sink);
      return;
    }
    if (type != 0x0d) {
      throw new SqlException("unsupported btree page type " + type);
    }
    long array = base + header + 8;
    int usable = pageSize;
    for (int i = 0; i < cells; i++) {
      long cell = base + u16(file, array + i * 2L);
      long[] cursor = {cell};
      int payloadSize = (int) varint(file, cursor);
      varint(file, cursor);
      int local = RecordCodec.localPayload(usable, payloadSize);
      byte[] payload = new byte[payloadSize];
      int stored = Math.min(local, payloadSize);
      file.copyTo(cursor[0], payload, 0, stored);
      cursor[0] += stored;
      if (stored < payloadSize) {
        readOverflow(file, pageSize, u32(file, cursor[0]), payload, stored);
      }
      sink.accept(RecordCodec.decodeRecord(payload));
    }
  }

  private static void readOverflow(
      DbImage file, int pageSize, int pageNumber, byte[] payload, int filled) {
    int page = pageNumber;
    int pos = filled;
    int usable = pageSize;
    while (pos < payload.length) {
      if (page <= 0) {
        throw new SqlException("truncated overflow payload");
      }
      long base = (long) (page - 1) * pageSize;
      int next = u32(file, base);
      int take = Math.min(usable - 4, payload.length - pos);
      file.copyTo(base + 4, payload, pos, take);
      pos += take;
      page = next;
    }
  }

  private static String text(List<Object> row, int i) {
    Object v = i < row.size() ? row.get(i) : null;
    return v == null ? null : String.valueOf(v);
  }

  private static int integer(List<Object> row, int i) {
    Object v = i < row.size() ? row.get(i) : null;
    if (v instanceof Long n) {
      return n.intValue();
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    return 0;
  }

  private static int pageSize(DbImage header) {
    int size = u16(header, 16);
    if (size == 1) {
      size = 65536;
    }
    return size;
  }

  private static long varint(DbImage raw, long[] cursor) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      int b = raw.get(cursor[0]++) & 0xff;
      value = (value << 7) | (b & 0x7f);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    value = (value << 8) | (raw.get(cursor[0]++) & 0xff);
    return value;
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

  private static int u16(DbImage raw, long offset) {
    return ((raw.get(offset) & 0xff) << 8) | (raw.get(offset + 1) & 0xff);
  }

  private static int u32(DbImage raw, long offset) {
    return ((raw.get(offset) & 0xff) << 24)
        | ((raw.get(offset + 1) & 0xff) << 16)
        | ((raw.get(offset + 2) & 0xff) << 8)
        | (raw.get(offset + 3) & 0xff);
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

  private record LeafCell(long rowId, byte[] payload) {}

  private record ChildRef(int page, long firstKey) {}

  private record MasterRow(String type, String name, String table, int root, String sql) {}

  private static final class Master {
    private final List<Entry> entries;

    private Master(List<Entry> entries) {
      this.entries = entries;
    }

    private record Entry(String type, String name, String table, int rootPage, String sql) {}
  }

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
