package com.myano.jsqlite.wal;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.Pager;
import com.myano.jsqlite.format.SqliteHeader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 讀 -wal 已提交幀，蓋過主庫對應頁。 */
public final class WalReader {
  private WalReader() {}

  public static Path pathFor(Path database) {
    return Path.of(database.toString() + "-wal");
  }

  public static byte[][] apply(Path database, byte[][] pages) {
    Path wal = pathFor(database);
    if (!Files.isRegularFile(wal)) {
      return pages;
    }
    byte[] raw;
    try {
      raw = Files.readAllBytes(wal);
    } catch (IOException ex) {
      throw new SqlException("unable to read WAL " + wal + ": " + ex.getMessage());
    }
    if (raw.length == 0) {
      return pages;
    }
    List<WalFormat.Frame> frames = WalFormat.readFrames(raw);
    if (frames.isEmpty()) {
      return pages;
    }
    int dbSize = 0;
    for (WalFormat.Frame frame : frames) {
      if (frame.dbSize() != 0) {
        dbSize = frame.dbSize();
      }
    }
    if (dbSize < 1) {
      dbSize = pages.length;
    }
    byte[][] merged = new byte[dbSize][];
    for (int i = 0; i < dbSize; i++) {
      if (i < pages.length) {
        merged[i] = Arrays.copyOf(pages[i], Pager.PAGE_SIZE);
      } else {
        merged[i] = new byte[Pager.PAGE_SIZE];
      }
    }
    for (WalFormat.Frame frame : frames) {
      if (frame.pageNumber() >= 1 && frame.pageNumber() <= dbSize) {
        merged[frame.pageNumber() - 1] = Arrays.copyOf(frame.page(), Pager.PAGE_SIZE);
      }
    }
    SqliteHeader.putU32(merged[0], 28, dbSize);
    return merged;
  }
}
