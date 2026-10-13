package com.myano.jsqlite.wal;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.format.Pager;
import com.myano.jsqlite.format.SqliteHeader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** 把已提交 WAL 幀寫回主庫。失敗不刪 -wal。 */
public final class Checkpoint {
  private Checkpoint() {}

  public static void run(Path database) {
    Path wal = WalReader.pathFor(database);
    if (!Files.isRegularFile(wal)) {
      return;
    }
    byte[] file;
    try {
      file = Files.readAllBytes(database);
    } catch (IOException ex) {
      throw new SqlException("unable to read " + database + ": " + ex.getMessage());
    }
    int pageCount = SqliteHeader.pageCount(file);
    byte[][] pages = new byte[pageCount][];
    for (int i = 0; i < pageCount; i++) {
      pages[i] = new byte[Pager.PAGE_SIZE];
      System.arraycopy(file, i * Pager.PAGE_SIZE, pages[i], 0, Pager.PAGE_SIZE);
    }
    byte[][] merged = WalReader.apply(database, pages);
    try (RandomAccessFile out = new RandomAccessFile(database.toFile(), "rw")) {
      out.setLength((long) merged.length * Pager.PAGE_SIZE);
      for (int i = 0; i < merged.length; i++) {
        out.seek((long) i * Pager.PAGE_SIZE);
        out.write(merged[i]);
      }
      out.getFD().sync();
    } catch (IOException ex) {
      throw new SqlException("unable to checkpoint " + database + ": " + ex.getMessage());
    }
    try {
      Files.write(wal, WalFormat.newHeader(Pager.PAGE_SIZE));
    } catch (IOException ex) {
      throw new SqlException("unable to reset WAL " + wal + ": " + ex.getMessage());
    }
  }
}
