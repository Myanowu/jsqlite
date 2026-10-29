package com.myano.jsqlite.store;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.DbImage;
import com.myano.jsqlite.format.RecordCodec;
import com.myano.jsqlite.format.SqliteHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Read rows from a sqlite btree without materializing the whole table as {@code Object[]}.
 * First access builds a compact cell-offset index (~8 bytes/row); each {@link #value} decodes one
 * record.
 */
public final class FileBackedRows {
  private final DbImage file;
  private final int pageSize;
  private final int usable;
  private final int rootPage;
  private final List<Column> columns;
  private final String tableName;
  /** INTEGER PRIMARY KEY column that aliases the btree rowid (−1 if none). */
  private final int integerPkIndex;
  private long[] cellOffsets;
  private Object[] cachedRow;
  private int cachedIndex = -1;

  public FileBackedRows(
      DbImage file, int pageSize, int reserved, int rootPage, List<Column> columns, String tableName) {
    this(file, pageSize, reserved, rootPage, columns, tableName, -1);
  }

  public FileBackedRows(
      DbImage file,
      int pageSize,
      int reserved,
      int rootPage,
      List<Column> columns,
      String tableName,
      int integerPkIndex) {
    this.file = file;
    this.pageSize = pageSize;
    this.usable = pageSize - reserved;
    this.rootPage = rootPage;
    this.columns = columns;
    this.tableName = tableName;
    this.integerPkIndex = integerPkIndex;
  }

  public int rowCount() {
    ensureIndex();
    return cellOffsets.length;
  }

  public Object value(int row, int column) {
    return row(row)[column];
  }

  public Object[] copyRow(int row) {
    return row(row).clone();
  }

  /** Shared decode buffer — valid only until the next row access. Do not store or mutate. */
  public Object[] peekRow(int row) {
    return row(row);
  }

  private Object[] row(int row) {
    ensureIndex();
    if (row < 0 || row >= cellOffsets.length) {
      throw new SqlException("row " + row + " out of range for " + tableName);
    }
    if (cachedIndex == row && cachedRow != null) {
      return cachedRow;
    }
    cachedRow = decode(cellOffsets[row]);
    cachedIndex = row;
    return cachedRow;
  }

  private void ensureIndex() {
    if (cellOffsets != null) {
      return;
    }
    LongList offsets = new LongList();
    walk(rootPage, rootPage == 1, offsets);
    cellOffsets = offsets.toArray();
  }

  private void walk(int pageNumber, boolean page1, LongList offsets) {
    long base = (long) (pageNumber - 1) * pageSize;
    int header = page1 ? SqliteHeader.SIZE : 0;
    int type = file.get(base + header) & 0xff;
    int cells = u16(base + header + 3);
    if (type == 0x05) {
      int right = u32(base + header + 8);
      long array = base + header + 12;
      for (int i = 0; i < cells; i++) {
        long cell = base + u16(array + i * 2L);
        walk(u32(cell), false, offsets);
      }
      walk(right, false, offsets);
      return;
    }
    if (type != 0x0d) {
      throw new SqlException("unsupported btree page type " + type + " in " + tableName);
    }
    long array = base + header + 8;
    for (int i = 0; i < cells; i++) {
      offsets.add(base + u16(array + i * 2L));
    }
  }

  private Object[] decode(long cell) {
    long[] cursor = {cell};
    int payloadSize = (int) varint(cursor);
    long rowId = varint(cursor);
    int local = RecordCodec.localPayload(usable, payloadSize);
    byte[] payload = new byte[payloadSize];
    int stored = Math.min(local, payloadSize);
    file.copyTo(cursor[0], payload, 0, stored);
    cursor[0] += stored;
    if (stored < payloadSize) {
      readOverflow(u32(cursor[0]), payload, stored);
    }
    List<Object> values = RecordCodec.decodeRecord(payload);
    Object[] out = new Object[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      Object value = i < values.size() ? values.get(i) : null;
      // SQLite INTEGER PRIMARY KEY is an alias of the btree rowid.
      if (value == null && i == integerPkIndex) {
        value = rowId;
      }
      out[i] = adapt(columns.get(i), value);
    }
    return out;
  }

  private void readOverflow(int pageNumber, byte[] payload, int filled) {
    int page = pageNumber;
    int pos = filled;
    while (pos < payload.length) {
      if (page <= 0) {
        throw new SqlException("truncated overflow in " + tableName);
      }
      long base = (long) (page - 1) * pageSize;
      int next = u32(base);
      int take = Math.min(usable - 4, payload.length - pos);
      file.copyTo(base + 4, payload, pos, take);
      pos += take;
      page = next;
    }
  }

  private Object adapt(Column column, Object value) {
    if (value == null) {
      return null;
    }
    if (column.type() == ColumnType.INTEGER) {
      if (value instanceof Long number) {
        return number;
      }
      if (value instanceof String text) {
        return Long.parseLong(text);
      }
      if (value instanceof Number number) {
        return number.longValue();
      }
      throw new SqlException("type mismatch for " + tableName + "." + column.name());
    }
    if (column.type() == ColumnType.REAL) {
      if (value instanceof Long || value instanceof Double) {
        return value;
      }
      if (value instanceof String text) {
        return Double.parseDouble(text);
      }
      if (value instanceof Number number) {
        return number.doubleValue();
      }
    }
    if (column.type() == ColumnType.BLOB) {
      if (value instanceof byte[] raw) {
        return raw;
      }
      if (value instanceof String text) {
        return text.getBytes(StandardCharsets.UTF_8);
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
      return new String(raw, StandardCharsets.UTF_8);
    }
    return String.valueOf(value);
  }

  private int u16(long offset) {
    return ((file.get(offset) & 0xff) << 8) | (file.get(offset + 1) & 0xff);
  }

  private int u32(long offset) {
    return ((file.get(offset) & 0xff) << 24)
        | ((file.get(offset + 1) & 0xff) << 16)
        | ((file.get(offset + 2) & 0xff) << 8)
        | (file.get(offset + 3) & 0xff);
  }

  private long varint(long[] cursor) {
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

  private static final class LongList {
    private long[] data = new long[4096];
    private int size;

    private void add(long value) {
      if (size == data.length) {
        long[] next = new long[data.length * 2];
        System.arraycopy(data, 0, next, 0, size);
        data = next;
      }
      data[size++] = value;
    }

    private long[] toArray() {
      long[] out = new long[size];
      System.arraycopy(data, 0, out, 0, size);
      return out;
    }
  }
}
