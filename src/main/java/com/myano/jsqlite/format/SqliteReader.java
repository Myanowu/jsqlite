package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Ast.CreateIndex;
import com.myano.jsqlite.sql.Ast.CreateTable;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Parser;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;
import com.myano.jsqlite.vm.InsertMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 只讀 sqlite3 主檔。旁邊有 -wal 時拒絕。不寫回檔案。 */
public final class SqliteReader {
  private static final int HEADER = SqliteHeader.SIZE;

  private final DbImage file;
  private final int pageSize;
  private final int usable;
  private final int pageCount;

  private SqliteReader(DbImage file, int pageSize, int reserved, int pageCount) {
    this.file = file;
    this.pageSize = pageSize;
    this.usable = pageSize - reserved;
    this.pageCount = pageCount;
  }

  public static boolean isDatabase(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return false;
    }
    try (InputStream in = Files.newInputStream(path)) {
      byte[] header = in.readNBytes(SqliteHeader.MAGIC.length);
      return header.length == SqliteHeader.MAGIC.length && SqliteHeader.matchesMagic(header);
    } catch (IOException ex) {
      return false;
    }
  }

  /**
   * Keep this pin reachable for as long as deferred table loads may run. It holds the mapped
   * (or heap) image the loaders read from.
   */
  public static final class Pin {
    private final SqliteReader reader;

    private Pin(SqliteReader reader) {
      this.reader = reader;
    }

    public DbImage image() {
      return reader.file;
    }

    public int pageSize() {
      return reader.pageSize;
    }

    /** Close the underlying file/mapping so the path can be overwritten. */
    public void release() {
      reader.file.close();
    }
  }

  /**
   * Load schema and defer row loads. Uses a file-backed image so databases larger than 2GiB open
   * without copying the whole file into a byte[].
   */
  public static Pin load(Path path, MemoryCatalog catalog) {
    DbImage image = DbImage.ofFile(path);
    try {
      return loadImage(image, catalog);
    } catch (RuntimeException ex) {
      image.close();
      throw ex;
    }
  }

  public static Pin loadBytes(byte[] file, MemoryCatalog catalog) {
    return loadImage(DbImage.of(file), catalog);
  }

  /**
   * After a durable rewrite of {@code path}, re-bind unmodified non-sidecar tables to the new
   * mapping without recreating schema. Mutated (in-memory) and sidecar tables are left alone.
   */
  public static Pin reattach(Path path, MemoryCatalog catalog) {
    DbImage image = DbImage.ofFile(path);
    try {
      if (image.byteLength() < HEADER || !matchesMagic(image)) {
        throw new SqlException("not a sqlite database");
      }
      int pageSize = pageSize(image);
      int reserved = image.get(20) & 0xff;
      int pageCount = u32(image, 28);
      int encoding = u32(image, 56);
      if (encoding != 1) {
        throw new SqlException("only UTF-8 sqlite databases can be opened");
      }
      if (reserved >= pageSize || pageCount < 1 || (long) pageCount * pageSize != image.byteLength()) {
        throw new SqlException("sqlite page count does not match the file");
      }
      SqliteReader reader = new SqliteReader(image, pageSize, reserved, pageCount);
      for (Master entry : reader.readMasterEntries()) {
        if (!"table".equals(entry.type) || entry.rootPage <= 1) {
          continue;
        }
        Table table = catalog.find(entry.name);
        if (table == null || table.isMutated() || table.isSidecar()) {
          continue;
        }
        table.attachFile(image, pageSize, pageSize - reader.usable, entry.rootPage);
      }
      return new Pin(reader);
    } catch (RuntimeException ex) {
      image.close();
      throw ex;
    }
  }

  private List<Master> readMasterEntries() {
    List<Master> entries = new ArrayList<>();
    for (Record row : readTable(1)) {
      entries.add(
          new Master(
              text(row.values, 0),
              text(row.values, 1),
              text(row.values, 2),
              integer(row.values, 3),
              text(row.values, 4)));
    }
    return entries;
  }

  private static Pin loadImage(DbImage file, MemoryCatalog catalog) {
    if (file.byteLength() < HEADER || !matchesMagic(file)) {
      throw new SqlException("not a sqlite database");
    }
    int pageSize = pageSize(file);
    int reserved = file.get(20) & 0xff;
    int pageCount = u32(file, 28);
    int encoding = u32(file, 56);
    if (encoding != 1) {
      throw new SqlException("only UTF-8 sqlite databases can be opened");
    }
    if (reserved >= pageSize || pageCount < 1 || (long) pageCount * pageSize != file.byteLength()) {
      throw new SqlException("sqlite page count does not match the file");
    }
    SqliteReader reader = new SqliteReader(file, pageSize, reserved, pageCount);
    reader.loadSchema(catalog);
    return new Pin(reader);
  }

  private void loadSchema(MemoryCatalog catalog) {
    List<Master> entries = new ArrayList<>();
    for (Record row : readTable(1)) {
      entries.add(new Master(
          text(row.values, 0),
          text(row.values, 1),
          text(row.values, 2),
          integer(row.values, 3),
          text(row.values, 4)));
    }
    Parser parser = new Parser();
    for (Master entry : entries) {
      if (!"table".equals(entry.type)) {
        continue;
      }
      if ("sqlite_sequence".equals(entry.name)) {
        String sql = entry.sql != null ? entry.sql : "CREATE TABLE sqlite_sequence(name,seq)";
        catalog.createTable(
            entry.name,
            List.of(new Column("name", ColumnType.TEXT), new Column("seq", ColumnType.INTEGER)),
            List.of(),
            false,
            sql);
        continue;
      }
      if (entry.sql == null) {
        continue;
      }
      Statement statement = parser.parse(entry.sql);
      if (!(statement instanceof CreateTable create)) {
        throw new SqlException("sqlite schema is not a table: " + entry.name);
      }
      catalog.createTable(create.name(), create.columns(), create.constraints(), create.ifNotExists(), entry.sql);
    }
    for (Master entry : entries) {
      if (!"index".equals(entry.type) || entry.sql == null) {
        continue;
      }
      Statement statement = parser.parse(entry.sql);
      if (!(statement instanceof CreateIndex index)) {
        throw new SqlException("sqlite schema is not an index: " + entry.name);
      }
      catalog.createIndex(
          index.name(),
          index.unique(),
          index.ifNotExists(),
          index.table(),
          index.columns(),
          index.partialColumn(),
          entry.sql);
    }
    for (Master entry : entries) {
      if (!"table".equals(entry.type) || entry.rootPage <= 1) {
        continue;
      }
      Table table = catalog.find(entry.name);
      if (table == null) {
        throw new SqlException("missing table " + entry.name);
      }
      int root = entry.rootPage;
      // Disk-backed access: offset index only — do not place millions of Object[] into the heap.
      table.attachFile(file, pageSize, pageSize - usable, root);
    }
    Table sequence = catalog.find("sqlite_sequence");
    if (sequence == null) {
      return;
    }
    for (int i = 0; i < sequence.rowCount(); i++) {
      Object name = sequence.value(i, 0);
      Object seq = sequence.value(i, 1);
      if (name instanceof String tableName && seq instanceof Long last) {
        Table target = catalog.find(tableName);
        if (target != null) {
          target.advanceSequence(last);
        }
      }
    }
  }

  private Object[] align(Table table, Record row) {
    List<Column> columns = table.columns();
    Object[] values = new Object[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      Object value = i < row.values.size() ? row.values.get(i) : null;
      if (value == null && table.assignsIdentity(i)) {
        value = row.rowId;
      }
      values[i] = adapt(columns.get(i), value, table.name());
    }
    return values;
  }

  private Object adapt(Column column, Object value, String table) {
    if (value == null) {
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
          throw new SqlException("type mismatch for " + table + "." + column.name());
        }
      }
      throw new SqlException("type mismatch for " + table + "." + column.name());
    }
    if (column.type() == ColumnType.REAL) {
      if (value instanceof Long || value instanceof Double) {
        return value;
      }
      if (value instanceof String text) {
        try {
          return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
          throw new SqlException("type mismatch for " + table + "." + column.name());
        }
      }
    }
    if (column.type() == ColumnType.BLOB) {
      if (value instanceof byte[] raw) {
        return raw;
      }
      if (value instanceof String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      }
    }
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
    throw new SqlException("type mismatch for " + table + "." + column.name());
  }

  private List<Record> readTable(int pageNumber) {
    List<Record> rows = new ArrayList<>();
    readTable(pageNumber, rows::add);
    return rows;
  }

  private void readTable(int pageNumber, Consumer<Record> sink) {
    long base = pageBase(pageNumber);
    int header = pageNumber == 1 ? HEADER : 0;
    int type = file.get(base + header) & 0xff;
    int cells = u16(file, base + header + 3);
    if (type == 0x05) {
      int right = u32(file, base + header + 8);
      long array = base + header + 12;
      for (int i = 0; i < cells; i++) {
        long cell = base + u16(file, array + i * 2L);
        readTable(u32(file, cell), sink);
      }
      readTable(right, sink);
      return;
    }
    if (type != 0x0d) {
      throw new SqlException("unsupported btree page type " + type + " at page " + pageNumber);
    }
    long array = base + header + 8;
    for (int i = 0; i < cells; i++) {
      sink.accept(readLeaf(base + u16(file, array + i * 2L)));
    }
  }

  private Record readLeaf(long cell) {
    long[] cursor = {cell};
    int payloadSize = (int) varint(file, cursor);
    long rowId = varint(file, cursor);
    int local = RecordCodec.localPayload(usable, payloadSize);
    byte[] payload = new byte[payloadSize];
    int stored = Math.min(local, payloadSize);
    file.copyTo(cursor[0], payload, 0, stored);
    cursor[0] += stored;
    if (stored < payloadSize) {
      readOverflow(u32(file, cursor[0]), payload, stored);
    }
    return new Record(rowId, RecordCodec.decodeRecord(payload));
  }

  private void readOverflow(int pageNumber, byte[] payload, int filled) {
    int page = pageNumber;
    int pos = filled;
    while (pos < payload.length) {
      if (page <= 0) {
        throw new SqlException("truncated overflow payload");
      }
      long base = pageBase(page);
      int next = u32(file, base);
      int take = Math.min(usable - 4, payload.length - pos);
      file.copyTo(base + 4, payload, pos, take);
      pos += take;
      page = next;
    }
  }

  private long pageBase(int pageNumber) {
    if (pageNumber < 1 || pageNumber > pageCount) {
      throw new SqlException("page " + pageNumber + " is outside the database");
    }
    return (long) (pageNumber - 1) * pageSize;
  }

  private static boolean matchesMagic(DbImage file) {
    if (file.byteLength() < SqliteHeader.MAGIC.length) {
      return false;
    }
    for (int i = 0; i < SqliteHeader.MAGIC.length; i++) {
      if (file.get(i) != SqliteHeader.MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  private static int pageSize(DbImage header) {
    int size = u16(header, 16);
    if (size == 1) {
      size = 65536;
    }
    if (size < 512 || size > 65536 || (size & (size - 1)) != 0) {
      throw new SqlException("unsupported sqlite page size " + size);
    }
    return size;
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

  private static long varint(DbImage raw, long[] cursor) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      int bite = raw.get(cursor[0]++) & 0xff;
      value = (value << 7) | (bite & 0x7f);
      if ((bite & 0x80) == 0) {
        return value;
      }
    }
    return (value << 8) | (raw.get(cursor[0]++) & 0xff);
  }

  private static String text(List<Object> values, int index) {
    Object value = index < values.size() ? values.get(index) : null;
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] raw) {
      return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
    return value.toString();
  }

  private static int integer(List<Object> values, int index) {
    Object value = index < values.size() ? values.get(index) : null;
    if (value instanceof Long number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      return Integer.parseInt(text);
    }
    return 0;
  }

  private record Master(String type, String name, String table, int rootPage, String sql) {}

  private record Record(long rowId, List<Object> values) {}
}
