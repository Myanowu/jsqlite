package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;

import java.nio.charset.StandardCharsets;

/** sqlite3 檔頭。頁大小、頁數、編碼、freelist 都從這裡讀。 */
public final class SqliteHeader {
  public static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
  public static final int SIZE = 100;

  private SqliteHeader() {}

  public static boolean matchesMagic(byte[] raw) {
    if (raw.length < MAGIC.length) {
      return false;
    }
    for (int i = 0; i < MAGIC.length; i++) {
      if (raw[i] != MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  public static int pageSize(byte[] header) {
    int size = u16(header, 16);
    if (size == 1) {
      size = 65536;
    }
    if (size < 512 || size > 65536 || (size & (size - 1)) != 0) {
      throw new SqlException("unsupported sqlite page size " + size);
    }
    return size;
  }

  public static int reserved(byte[] header) {
    return header[20] & 0xff;
  }

  public static int pageCount(byte[] header) {
    return u32(header, 28);
  }

  public static int encoding(byte[] header) {
    return u32(header, 56);
  }

  public static int freelistTrunk(byte[] header) {
    return u32(header, 32);
  }

  public static int freelistCount(byte[] header) {
    return u32(header, 36);
  }

  public static int u16(byte[] raw, int offset) {
    return ((raw[offset] & 0xff) << 8) | (raw[offset + 1] & 0xff);
  }

  public static int u32(byte[] raw, int offset) {
    return ((raw[offset] & 0xff) << 24)
        | ((raw[offset + 1] & 0xff) << 16)
        | ((raw[offset + 2] & 0xff) << 8)
        | (raw[offset + 3] & 0xff);
  }

  public static void putU16(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value >>> 8);
    raw[offset + 1] = (byte) value;
  }

  public static void putU32(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value >>> 24);
    raw[offset + 1] = (byte) (value >>> 16);
    raw[offset + 2] = (byte) (value >>> 8);
    raw[offset + 3] = (byte) value;
  }
}
