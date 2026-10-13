package com.myano.jsqlite.wal;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.Pager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** sqlite WAL 頭與幀。魔數用小端校驗 0x377f0682。 */
final class WalFormat {
  static final int MAGIC = 0x377f0682;
  static final int VERSION = 3007000;
  static final int HEADER = 32;
  static final int FRAME_HEADER = 24;

  private WalFormat() {}

  static byte[] newHeader(int pageSize, int salt1, int salt2) {
    byte[] header = new byte[HEADER];
    putBe(header, 0, MAGIC);
    putBe(header, 4, VERSION);
    putBe(header, 8, pageSize);
    putBe(header, 12, 0);
    putBe(header, 16, salt1);
    putBe(header, 20, salt2);
    int[] ck = checksum(true, null, header, 0, 24);
    putBe(header, 24, ck[0]);
    putBe(header, 28, ck[1]);
    return header;
  }

  static byte[] newHeader(int pageSize) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return newHeader(pageSize, random.nextInt(), random.nextInt());
  }

  static List<Frame> readFrames(byte[] wal) {
    if (wal.length < HEADER) {
      throw new SqlException("WAL file is too short");
    }
    int magic = getBe(wal, 0);
    if (magic != MAGIC && magic != 0x377f0683) {
      throw new SqlException("WAL file has a bad magic number");
    }
    boolean little = magic == MAGIC;
    int version = getBe(wal, 4);
    if (version != VERSION) {
      throw new SqlException("unsupported WAL version " + version);
    }
    int pageSize = getBe(wal, 8);
    if (pageSize == 1) {
      pageSize = 65536;
    }
    if (pageSize != Pager.PAGE_SIZE) {
      throw new SqlException("unsupported WAL page size " + pageSize);
    }
    int salt1 = getBe(wal, 16);
    int salt2 = getBe(wal, 20);
    int[] ck = checksum(little, null, wal, 0, 24);
    if (ck[0] != getBe(wal, 24) || ck[1] != getBe(wal, 28)) {
      throw new SqlException("WAL header checksum mismatch");
    }
    List<Frame> frames = new ArrayList<>();
    int pos = HEADER;
    int frameBytes = FRAME_HEADER + pageSize;
    int[] running = ck;
    int commitAt = -1;
    while (pos + frameBytes <= wal.length) {
      int pageNumber = getBe(wal, pos);
      int dbSize = getBe(wal, pos + 4);
      int frameSalt1 = getBe(wal, pos + 8);
      int frameSalt2 = getBe(wal, pos + 12);
      int stored0 = getBe(wal, pos + 16);
      int stored1 = getBe(wal, pos + 20);
      if (frameSalt1 != salt1 || frameSalt2 != salt2) {
        break;
      }
      running = checksum(little, running, wal, pos, 8);
      running = checksum(little, running, wal, pos + FRAME_HEADER, pageSize);
      if (running[0] != stored0 || running[1] != stored1) {
        break;
      }
      if (pageNumber < 1) {
        break;
      }
      byte[] page = new byte[pageSize];
      System.arraycopy(wal, pos + FRAME_HEADER, page, 0, pageSize);
      frames.add(new Frame(pageNumber, dbSize, page));
      if (dbSize != 0) {
        commitAt = frames.size();
      }
      pos += frameBytes;
    }
    if (commitAt < 0) {
      return List.of();
    }
    return List.copyOf(frames.subList(0, commitAt));
  }

  static byte[] appendCommit(byte[] wal, List<Frame> frames) {
    if (wal.length < HEADER) {
      throw new SqlException("WAL file is too short");
    }
    boolean little = getBe(wal, 0) == MAGIC;
    int salt1 = getBe(wal, 16);
    int salt2 = getBe(wal, 20);
    int[] running = checksum(little, null, wal, 0, 24);
    int pos = HEADER;
    int frameBytes = FRAME_HEADER + Pager.PAGE_SIZE;
    while (pos + frameBytes <= wal.length) {
      int frameSalt1 = getBe(wal, pos + 8);
      int frameSalt2 = getBe(wal, pos + 12);
      if (frameSalt1 != salt1 || frameSalt2 != salt2) {
        break;
      }
      int[] next = checksum(little, running, wal, pos, 8);
      next = checksum(little, next, wal, pos + FRAME_HEADER, Pager.PAGE_SIZE);
      if (next[0] != getBe(wal, pos + 16) || next[1] != getBe(wal, pos + 20)) {
        break;
      }
      running = next;
      pos += frameBytes;
    }
    ByteBuffer out = ByteBuffer.allocate(pos + frames.size() * frameBytes);
    out.put(wal, 0, pos);
    for (int i = 0; i < frames.size(); i++) {
      Frame frame = frames.get(i);
      byte[] header = new byte[FRAME_HEADER];
      putBe(header, 0, frame.pageNumber());
      putBe(header, 4, frame.dbSize());
      putBe(header, 8, salt1);
      putBe(header, 12, salt2);
      running = checksum(little, running, header, 0, 8);
      running = checksum(little, running, frame.page(), 0, frame.page().length);
      putBe(header, 16, running[0]);
      putBe(header, 20, running[1]);
      out.put(header);
      out.put(frame.page());
    }
    byte[] result = new byte[out.position()];
    out.flip();
    out.get(result);
    return result;
  }

  static int[] checksum(boolean little, int[] start, byte[] data, int offset, int length) {
    int s0 = start == null ? 0 : start[0];
    int s1 = start == null ? 0 : start[1];
    for (int i = 0; i < length; i += 8) {
      int x0 = little ? getLe(data, offset + i) : getBe(data, offset + i);
      int x1 = little ? getLe(data, offset + i + 4) : getBe(data, offset + i + 4);
      s0 += x0 + s1;
      s1 += x1 + s0;
    }
    return new int[] {s0, s1};
  }

  static void putBe(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value >>> 24);
    raw[offset + 1] = (byte) (value >>> 16);
    raw[offset + 2] = (byte) (value >>> 8);
    raw[offset + 3] = (byte) value;
  }

  static int getBe(byte[] raw, int offset) {
    return ((raw[offset] & 0xff) << 24)
        | ((raw[offset + 1] & 0xff) << 16)
        | ((raw[offset + 2] & 0xff) << 8)
        | (raw[offset + 3] & 0xff);
  }

  static int getLe(byte[] raw, int offset) {
    return (raw[offset] & 0xff)
        | ((raw[offset + 1] & 0xff) << 8)
        | ((raw[offset + 2] & 0xff) << 16)
        | ((raw[offset + 3] & 0xff) << 24);
  }

  record Frame(int pageNumber, int dbSize, byte[] page) {}
}
