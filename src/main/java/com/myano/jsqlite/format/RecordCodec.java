package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** sqlite 記錄的序列類型解碼。不持有檔案。 */
public final class RecordCodec {
  private RecordCodec() {}

  public static int localPayload(int usable, int payload) {
    int maxLocal = usable - 35;
    if (payload <= maxLocal) {
      return payload;
    }
    int minLocal = ((usable - 12) * 32 / 255) - 23;
    int split = minLocal + ((payload - minLocal) % (usable - 4));
    return split <= maxLocal ? split : minLocal;
  }

  public static List<Object> decodeRecord(byte[] payload) {
    int[] cursor = {0};
    int headerSize = (int) varint(payload, cursor);
    int headerEnd = headerSize;
    List<Long> types = new ArrayList<>();
    while (cursor[0] < headerEnd) {
      types.add(varint(payload, cursor));
    }
    List<Object> values = new ArrayList<>(types.size());
    for (long serial : types) {
      values.add(readSerial(payload, cursor, serial));
    }
    return values;
  }

  public static byte[] encodeRecord(Object[] values) {
    ByteArrayOutputStream types = new ByteArrayOutputStream();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (Object value : values) {
      if (value == null) {
        types.write(0);
      } else if (value instanceof Long number) {
        writeInteger(types, body, number);
      } else if (value instanceof String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        types.writeBytes(putVarint(raw.length * 2L + 12));
        body.writeBytes(raw);
      } else {
        throw new SqlException("unsupported record value");
      }
    }
    byte[] typeBytes = types.toByteArray();
    int width = 1;
    while (putVarint(typeBytes.length + width).length != width) {
      width = putVarint(typeBytes.length + width).length;
    }
    ByteArrayOutputStream record = new ByteArrayOutputStream();
    record.writeBytes(putVarint(typeBytes.length + width));
    record.writeBytes(typeBytes);
    record.writeBytes(body.toByteArray());
    return record.toByteArray();
  }

  public static byte[] putVarint(long value) {
    if (value < 0) {
      throw new SqlException("negative varint");
    }
    long v = value;
    byte[] temp = new byte[9];
    int n = 0;
    temp[n++] = (byte) (v & 0x7f);
    v >>>= 7;
    while (v > 0 && n < 9) {
      temp[n++] = (byte) (v & 0x7f);
      v >>>= 7;
    }
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++) {
      out[i] = temp[n - 1 - i];
      if (i < n - 1) {
        out[i] |= (byte) 0x80;
      }
    }
    return out;
  }

  public static long varint(byte[] raw, int[] cursor) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      int bite = raw[cursor[0]++] & 0xff;
      value = (value << 7) | (bite & 0x7f);
      if ((bite & 0x80) == 0) {
        return value;
      }
    }
    return (value << 8) | (raw[cursor[0]++] & 0xff);
  }

  private static Object readSerial(byte[] payload, int[] cursor, long serial) {
    if (serial == 0) {
      return null;
    }
    if (serial == 8) {
      return 0L;
    }
    if (serial == 9) {
      return 1L;
    }
    int length = serialLength(serial);
    if (cursor[0] + length > payload.length) {
      throw new SqlException("record extends past its payload");
    }
    Object value;
    if (serial <= 6) {
      value = readInteger(payload, cursor[0], length);
    } else if (serial == 7) {
      value = readReal(payload, cursor[0]);
    } else {
      value = new String(payload, cursor[0], length, StandardCharsets.UTF_8);
    }
    cursor[0] += length;
    return value;
  }

  private static int serialLength(long serial) {
    return switch ((int) serial) {
      case 1 -> 1;
      case 2 -> 2;
      case 3 -> 3;
      case 4 -> 4;
      case 5 -> 6;
      case 6, 7 -> 8;
      default -> serial >= 12 ? (int) ((serial - 12) / 2) : 0;
    };
  }

  private static Object readReal(byte[] payload, int offset) {
    double value = Double.longBitsToDouble(readInteger(payload, offset, 8));
    if (!Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value)
        && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
      return (long) value;
    }
    return Double.toString(value);
  }

  private static void writeInteger(ByteArrayOutputStream types, ByteArrayOutputStream body, long number) {
    if (number == 0) {
      types.write(8);
      return;
    }
    if (number == 1) {
      types.write(9);
      return;
    }
    int length = 8;
    if (number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE) {
      length = 1;
    } else if (number >= Short.MIN_VALUE && number <= Short.MAX_VALUE) {
      length = 2;
    } else if (number >= -0x800000 && number <= 0x7fffff) {
      length = 3;
    } else if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
      length = 4;
    } else if (number >= -0x800000000000L && number <= 0x7fffffffffffL) {
      length = 6;
    }
    int serial = switch (length) {
      case 1 -> 1;
      case 2 -> 2;
      case 3 -> 3;
      case 4 -> 4;
      case 6 -> 5;
      default -> 6;
    };
    types.write(serial);
    for (int shift = (length - 1) * 8; shift >= 0; shift -= 8) {
      body.write((int) (number >>> shift) & 0xff);
    }
  }

  private static long readInteger(byte[] raw, int offset, int length) {
    long value = 0;
    for (int i = 0; i < length; i++) {
      value = (value << 8) | (raw[offset + i] & 0xff);
    }
    int bits = length * 8;
    if (length < 8 && (value & (1L << (bits - 1))) != 0) {
      value |= -1L << bits;
    }
    return value;
  }
}
