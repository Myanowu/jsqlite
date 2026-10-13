package com.myano.jsqlite.wal;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.Pager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 建立 -wal，提交時把髒頁追加成已提交幀。 */
public final class WalWriter {
  private WalWriter() {}

  public static void ensure(Path database) {
    Path wal = WalReader.pathFor(database);
    if (Files.isRegularFile(wal) && fileSize(wal) >= WalFormat.HEADER) {
      return;
    }
    try {
      Files.write(wal, WalFormat.newHeader(Pager.PAGE_SIZE));
    } catch (IOException ex) {
      throw new SqlException("unable to create WAL " + wal + ": " + ex.getMessage());
    }
  }

  public static void commit(Path database, byte[][] pages) {
    ensure(database);
    Path wal = WalReader.pathFor(database);
    byte[] current;
    try {
      current = Files.readAllBytes(wal);
    } catch (IOException ex) {
      throw new SqlException("unable to read WAL " + wal + ": " + ex.getMessage());
    }
    List<WalFormat.Frame> frames = new ArrayList<>();
    for (int i = 0; i < pages.length; i++) {
      int dbSize = i == pages.length - 1 ? pages.length : 0;
      frames.add(new WalFormat.Frame(i + 1, dbSize, pages[i]));
    }
    byte[] next = WalFormat.appendCommit(current, frames);
    try {
      Files.write(wal, next);
    } catch (IOException ex) {
      throw new SqlException("unable to write WAL " + wal + ": " + ex.getMessage());
    }
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException ex) {
      return 0;
    }
  }
}
