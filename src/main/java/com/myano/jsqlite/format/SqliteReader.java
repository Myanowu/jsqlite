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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 只讀 sqlite3 主檔。旁邊有 -wal 時拒絕。不寫回檔案。 */
public final class SqliteReader {
  private static final int HEADER = SqliteHeader.SIZE;

  private final byte[] file;
  private final int pageSize;
  private final int usable;
  private final int pageCount;

  private SqliteReader(byte[] file, int pageSize, int reserved, int pageCount) {
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

  public static void load(Path path, MemoryCatalog catalog) {
    byte[] file;
    try {
      file = Files.readAllBytes(path);
    } catch (IOException ex) {
      throw new SqlException("unable to read " + path + ": " + ex.getMessage());
    }
    loadBytes(file, catalog);
  }

  public static void loadBytes(byte[] file, MemoryCatalog catalog) {
    if (file.length < HEADER || !SqliteHeader.matchesMagic(file)) {
      throw new SqlException("not a sqlite database");
    }
    int pageSize = SqliteHeader.pageSize(file);
    int reserved = SqliteHeader.reserved(file);
    int pageCount = SqliteHeader.pageCount(file);
    int encoding = SqliteHeader.encoding(file);
    if (encoding != 1) {
      throw new SqlException("only UTF-8 sqlite databases can be opened");
    }
    if (reserved >= pageSize || pageCount < 1 || (long) pageCount * pageSize != file.length) {
      throw new SqlException("sqlite page count does not match the file");
    }
    new SqliteReader(file, pageSize, reserved, pageCount).loadSchema(catalog);
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
      for (Record row : readTable(entry.rootPage)) {
        table.place(align(table, row), InsertMode.APPEND, null);
      }
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
    readTable(pageNumber, rows);
    return rows;
  }

  private void readTable(int pageNumber, List<Record> rows) {
    int base = pageBase(pageNumber);
    int header = pageNumber == 1 ? HEADER : 0;
    int type = file[base + header] & 0xff;
    int cells = SqliteHeader.u16(file, base + header + 3);
    if (type == 0x05) {
      int right = SqliteHeader.u32(file, base + header + 8);
      int array = base + header + 12;
      for (int i = 0; i < cells; i++) {
        int cell = base + SqliteHeader.u16(file, array + i * 2);
        readTable(SqliteHeader.u32(file, cell), rows);
      }
      readTable(right, rows);
      return;
    }
    if (type != 0x0d) {
      throw new SqlException("unsupported btree page type " + type + " at page " + pageNumber);
    }
    int array = base + header + 8;
    for (int i = 0; i < cells; i++) {
      rows.add(readLeaf(base + SqliteHeader.u16(file, array + i * 2)));
    }
  }

  private Record readLeaf(int cell) {
    int[] cursor = {cell};
    int payloadSize = (int) RecordCodec.varint(file, cursor);
    long rowId = RecordCodec.varint(file, cursor);
    int local = RecordCodec.localPayload(usable, payloadSize);
    byte[] payload = new byte[payloadSize];
    int stored = Math.min(local, payloadSize);
    System.arraycopy(file, cursor[0], payload, 0, stored);
    cursor[0] += stored;
    if (stored < payloadSize) {
      readOverflow(SqliteHeader.u32(file, cursor[0]), payload, stored);
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
      int base = pageBase(page);
      int next = SqliteHeader.u32(file, base);
      int take = Math.min(usable - 4, payload.length - pos);
      System.arraycopy(file, base + 4, payload, pos, take);
      pos += take;
      page = next;
    }
  }

  private int pageBase(int pageNumber) {
    if (pageNumber < 1 || pageNumber > pageCount) {
      throw new SqlException("page " + pageNumber + " is outside the database");
    }
    return (pageNumber - 1) * pageSize;
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
