package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

/** 頁大小固定 4096。create 寫空庫；commit 先寫 -journal 再寫主檔。 */
public final class Pager {
  public static final int PAGE_SIZE = 4096;

  private final Path path;
  private byte[][] pages;
  private final Set<Integer> dirty = new HashSet<>();
  private IntConsumer writeHook = pageIndex -> {};
  private int writes;

  private Pager(Path path, byte[][] pages) {
    this.path = path;
    this.pages = pages;
  }

  public static void create(Path path) {
    try {
      Files.write(path, emptyDatabase());
    } catch (IOException ex) {
      throw new SqlException("unable to create " + path + ": " + ex.getMessage());
    }
  }

  public static Pager open(Path path) {
    RollbackJournal.recover(path);
    byte[] file;
    try {
      file = Files.readAllBytes(path);
    } catch (IOException ex) {
      throw new SqlException("unable to read " + path + ": " + ex.getMessage());
    }
    if (file.length < SqliteHeader.SIZE || !SqliteHeader.matchesMagic(file)) {
      throw new SqlException("not a sqlite database: " + path);
    }
    int pageSize = SqliteHeader.pageSize(file);
    if (pageSize != PAGE_SIZE) {
      throw new SqlException("unsupported sqlite page size " + pageSize);
    }
    int pageCount = SqliteHeader.pageCount(file);
    if (pageCount < 1 || (long) pageCount * PAGE_SIZE != file.length) {
      throw new SqlException("sqlite page count does not match the file: " + path);
    }
    byte[][] pages = new byte[pageCount][];
    for (int i = 0; i < pageCount; i++) {
      byte[] page = new byte[PAGE_SIZE];
      System.arraycopy(file, i * PAGE_SIZE, page, 0, PAGE_SIZE);
      pages[i] = page;
    }
    return new Pager(path, pages);
  }

  public Path path() {
    return path;
  }

  public int pageCount() {
    return pages.length;
  }

  public byte[] page(int pageNumber) {
    return pages[pageNumber - 1];
  }

  public void markDirty(int pageNumber) {
    dirty.add(pageNumber);
  }

  /** 測試用：第 n 次寫主庫頁時拋錯。n 從 1 起算。 */
  public void failOnWrite(int n) {
    writeHook = pageIndex -> {
      writes++;
      if (writes >= n) {
        throw new SqlException("injected write failure");
      }
    };
  }

  public void commit(byte[][] next) {
    RollbackJournal.write(path, pages);
    pages = next;
    dirty.clear();
    for (int page = 1; page <= pages.length; page++) {
      dirty.add(page);
    }
    try {
      writeMain();
      RollbackJournal.delete(RollbackJournal.pathFor(path));
    } catch (RuntimeException ex) {
      throw ex;
    }
  }

  public void adopt(byte[][] image) {
    pages = image;
    dirty.clear();
    for (int page = 1; page <= pages.length; page++) {
      dirty.add(page);
    }
  }

  public void replace(byte[][] image) {
    pages = image;
    dirty.clear();
  }

  public byte[][] copyPages() {
    byte[][] copy = new byte[pages.length][];
    for (int i = 0; i < pages.length; i++) {
      copy[i] = pages[i].clone();
    }
    return copy;
  }

  public static byte[] flatten(byte[][] pages) {
    byte[] file = new byte[pages.length * PAGE_SIZE];
    for (int i = 0; i < pages.length; i++) {
      System.arraycopy(pages[i], 0, file, i * PAGE_SIZE, PAGE_SIZE);
    }
    return file;
  }

  public void write() {
    commit(pages);
  }

  private void writeMain() {
    writes = 0;
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength((long) pages.length * PAGE_SIZE);
      for (int i = 0; i < pages.length; i++) {
        writeHook.accept(i + 1);
        file.seek((long) i * PAGE_SIZE);
        file.write(pages[i]);
      }
      file.getFD().sync();
    } catch (IOException ex) {
      throw new SqlException("unable to write " + path + ": " + ex.getMessage());
    }
    dirty.clear();
  }

  static byte[] emptyDatabase() {
    byte[] page = new byte[PAGE_SIZE];
    System.arraycopy(SqliteHeader.MAGIC, 0, page, 0, SqliteHeader.MAGIC.length);
    SqliteHeader.putU16(page, 16, PAGE_SIZE);
    page[18] = 1;
    page[19] = 1;
    page[21] = 64;
    page[22] = 32;
    page[23] = 32;
    SqliteHeader.putU32(page, 28, 1);
    SqliteHeader.putU32(page, 44, 4);
    SqliteHeader.putU32(page, 56, 1);
    page[100] = 0x0d;
    SqliteHeader.putU16(page, 105, PAGE_SIZE);
    return page;
  }
}
