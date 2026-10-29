package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Read-only database image. Supports files larger than 2GiB via {@link #ofFile(Path)}. */
public interface DbImage {
  long byteLength();

  byte get(long index);

  void copyTo(long srcPos, byte[] dest, int destPos, int length);

  default void close() {}

  static DbImage of(byte[] bytes) {
    return new ArrayImage(bytes);
  }

  static DbImage of(ByteBuffer buffer) {
    return new BufferImage(buffer.duplicate().asReadOnlyBuffer());
  }

  /** Page-at-a-time file view — works for databases larger than 2GiB. */
  static DbImage ofFile(Path path) {
    try {
      FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
      return new FileImage(channel, channel.size());
    } catch (IOException ex) {
      throw new SqlException("unable to open " + path + ": " + ex.getMessage());
    }
  }

  final class ArrayImage implements DbImage {
    private final byte[] bytes;

    private ArrayImage(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public long byteLength() {
      return bytes.length;
    }

    @Override
    public byte get(long index) {
      return bytes[Math.toIntExact(index)];
    }

    @Override
    public void copyTo(long srcPos, byte[] dest, int destPos, int length) {
      System.arraycopy(bytes, Math.toIntExact(srcPos), dest, destPos, length);
    }
  }

  final class BufferImage implements DbImage {
    private final ByteBuffer buffer;

    private BufferImage(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public long byteLength() {
      return buffer.capacity();
    }

    @Override
    public byte get(long index) {
      return buffer.get(Math.toIntExact(index));
    }

    @Override
    public void copyTo(long srcPos, byte[] dest, int destPos, int length) {
      ByteBuffer view = buffer.duplicate();
      view.position(Math.toIntExact(srcPos));
      view.get(dest, destPos, length);
    }
  }

  /**
   * File-backed image with a sliding window cache. Sequential btree walks (index build, table
   * scan) must not issue one {@code FileChannel.read} per byte — that turns a few-million-row
   * scan into tens of millions of syscalls.
   */
  final class FileImage implements DbImage {
    private static final int WINDOW = 256 * 1024;

    private final FileChannel channel;
    private final long size;
    private final byte[] window = new byte[WINDOW];
    private final ByteBuffer windowBuf = ByteBuffer.wrap(window);
    private long windowBase = -1;
    private int windowLen;

    private FileImage(FileChannel channel, long size) {
      this.channel = channel;
      this.size = size;
    }

    @Override
    public long byteLength() {
      return size;
    }

    @Override
    public byte get(long index) {
      if (index < 0 || index >= size) {
        throw new SqlException("short read at " + index);
      }
      fillWindow(index);
      return window[(int) (index - windowBase)];
    }

    @Override
    public void copyTo(long srcPos, byte[] dest, int destPos, int length) {
      if (length <= 0) {
        return;
      }
      if (srcPos < 0 || srcPos + length > size) {
        throw new SqlException("short read at " + srcPos);
      }
      // Small / window-local copies stay in the cache; large spans read the channel once.
      if (length <= WINDOW) {
        long end = srcPos + length;
        if (windowBase >= 0 && srcPos >= windowBase && end <= windowBase + windowLen) {
          System.arraycopy(window, (int) (srcPos - windowBase), dest, destPos, length);
          return;
        }
        if (length <= WINDOW / 4) {
          fillWindow(srcPos);
          if (srcPos >= windowBase && end <= windowBase + windowLen) {
            System.arraycopy(window, (int) (srcPos - windowBase), dest, destPos, length);
            return;
          }
        }
      }
      try {
        ByteBuffer buf = ByteBuffer.wrap(dest, destPos, length);
        int total = 0;
        while (total < length) {
          int n = channel.read(buf, srcPos + total);
          if (n < 0) {
            throw new SqlException("short read at " + srcPos);
          }
          total += n;
        }
      } catch (IOException ex) {
        throw new SqlException("read failed: " + ex.getMessage());
      }
    }

    private void fillWindow(long index) {
      if (windowBase >= 0 && index >= windowBase && index < windowBase + windowLen) {
        return;
      }
      long base = index - (index % WINDOW);
      int want = (int) Math.min(WINDOW, size - base);
      windowBuf.clear();
      windowBuf.limit(want);
      try {
        int total = 0;
        while (total < want) {
          int n = channel.read(windowBuf, base + total);
          if (n < 0) {
            throw new SqlException("short read at " + base);
          }
          total += n;
        }
      } catch (IOException ex) {
        throw new SqlException("read failed: " + ex.getMessage());
      }
      windowBase = base;
      windowLen = want;
    }

    @Override
    public void close() {
      try {
        channel.close();
      } catch (IOException ignored) {
      }
    }
  }
}
