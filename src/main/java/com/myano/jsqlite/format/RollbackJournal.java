package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
/** 提交前把舊頁寫進路徑-journal；打開時若日誌還在就還原完整記錄。 */
public final class RollbackJournal {
  private static final byte[] MAGIC = "JSQJ001\0".getBytes();

  private RollbackJournal() {}

  public static Path pathFor(Path database) {
    return Path.of(database.toString() + "-journal");
  }

  public static void write(Path database, byte[][] oldPages) {
    Path journal = pathFor(database);
    try {
      ByteBuffer header = ByteBuffer.allocate(MAGIC.length + 4);
      header.put(MAGIC);
      header.putInt(oldPages.length);
      Files.write(journal, header.array());
      for (int i = 0; i < oldPages.length; i++) {
        ByteBuffer record = ByteBuffer.allocate(8 + Pager.PAGE_SIZE);
        record.putInt(i + 1);
        record.putInt(Pager.PAGE_SIZE);
        record.put(oldPages[i]);
        Files.write(journal, record.array(), StandardOpenOption.APPEND);
      }
    } catch (IOException ex) {
      throw new SqlException("unable to write journal " + journal + ": " + ex.getMessage());
    }
  }

  public static void recover(Path database) {
    Path journal = pathFor(database);
    if (!Files.isRegularFile(journal)) {
      return;
    }
    byte[] raw;
    try {
      raw = Files.readAllBytes(journal);
    } catch (IOException ex) {
      throw new SqlException("unable to read journal " + journal + ": " + ex.getMessage());
    }
    if (raw.length < MAGIC.length + 4) {
      delete(journal);
      return;
    }
    for (int i = 0; i < MAGIC.length; i++) {
      if (raw[i] != MAGIC[i]) {
        delete(journal);
        return;
      }
    }
    int oldCount = ByteBuffer.wrap(raw, MAGIC.length, 4).getInt();
    if (oldCount < 1) {
      delete(journal);
      return;
    }
    byte[] file;
    try {
      file = Files.exists(database) ? Files.readAllBytes(database) : new byte[0];
    } catch (IOException ex) {
      throw new SqlException("unable to read " + database + ": " + ex.getMessage());
    }
    byte[] restored = new byte[oldCount * Pager.PAGE_SIZE];
    if (file.length > 0) {
      System.arraycopy(file, 0, restored, 0, Math.min(file.length, restored.length));
    }
    int pos = MAGIC.length + 4;
    int record = 8 + Pager.PAGE_SIZE;
    while (pos + record <= raw.length) {
      int pageNumber = ByteBuffer.wrap(raw, pos, 4).getInt();
      int pageLen = ByteBuffer.wrap(raw, pos + 4, 4).getInt();
      if (pageLen != Pager.PAGE_SIZE || pageNumber < 1 || pageNumber > oldCount) {
        break;
      }
      System.arraycopy(raw, pos + 8, restored, (pageNumber - 1) * Pager.PAGE_SIZE, Pager.PAGE_SIZE);
      pos += record;
    }
    try {
      Files.write(database, restored);
    } catch (IOException ex) {
      throw new SqlException("unable to restore " + database + ": " + ex.getMessage());
    }
    delete(journal);
  }

  public static void delete(Path journal) {
    try {
      Files.deleteIfExists(journal);
    } catch (IOException ex) {
      throw new SqlException("unable to delete journal " + journal + ": " + ex.getMessage());
    }
  }
}
