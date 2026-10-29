package com.myano.jsqlite;

import com.myano.jsqlite.format.SqliteHeader;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * L0 探针：量文件、头、打开耗时与堆增量。非正式产品入口。
 *
 * <pre>
 *   java -cp target/classes;target/test-classes com.myano.jsqlite.LargeDbProbe &lt;path&gt;...
 * </pre>
 *
 * 大于 100MB 的库默认只读头，不整库打开；设环境变量 {@code JSQLITE_PROBE_OPEN=1} 强制打开。
 */
public final class LargeDbProbe {
  private static final long OPEN_BYTES = 100L * 1024 * 1024;

  private LargeDbProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("usage: LargeDbProbe <sqlite-path>...");
      System.exit(2);
    }
    boolean forceOpen = "1".equals(System.getenv("JSQLITE_PROBE_OPEN"));
    for (String arg : args) {
      probe(Path.of(arg), forceOpen);
      System.out.println();
    }
  }

  static void probe(Path path, boolean forceOpen) throws IOException {
    System.out.println("=== " + path.toAbsolutePath().normalize());
    if (!Files.isRegularFile(path)) {
      System.out.println("missing file");
      return;
    }
    long mainBytes = Files.size(path);
    Path wal = Path.of(path.toString() + "-wal");
    Path shm = Path.of(path.toString() + "-shm");
    long walBytes = Files.isRegularFile(wal) ? Files.size(wal) : -1;
    long shmBytes = Files.isRegularFile(shm) ? Files.size(shm) : -1;
    System.out.println("main_bytes=" + mainBytes + " (" + human(mainBytes) + ")");
    System.out.println("wal_bytes=" + (walBytes < 0 ? "absent" : walBytes + " (" + human(walBytes) + ")"));
    System.out.println("shm_bytes=" + (shmBytes < 0 ? "absent" : shmBytes + " (" + human(shmBytes) + ")"));

    byte[] header = readHeader(path);
    if (header.length < SqliteHeader.SIZE || !SqliteHeader.matchesMagic(header)) {
      System.out.println("not a sqlite database (magic)");
      return;
    }
    int pageSize;
    int pageCount;
    int freelist;
    try {
      pageSize = SqliteHeader.pageSize(header);
      pageCount = SqliteHeader.pageCount(header);
      freelist = SqliteHeader.freelistCount(header);
    } catch (SqlException ex) {
      System.out.println("header error: " + ex.getMessage());
      return;
    }
    System.out.println("page_size=" + pageSize);
    System.out.println("page_count=" + pageCount);
    System.out.println("freelist_count=" + freelist);
    long expected = (long) pageCount * pageSize;
    if (expected != mainBytes) {
      System.out.println("WARN page_count*page_size=" + expected + " != main_bytes");
    }

    if (mainBytes > OPEN_BYTES && !forceOpen) {
      System.out.println(
          "open=skipped (file > "
              + human(OPEN_BYTES)
              + "; set JSQLITE_PROBE_OPEN=1 to force)");
      return;
    }

    System.gc();
    long heapBefore = usedHeap();
    long start = System.nanoTime();
    try (Database db = Database.open(path)) {
      long openMs = (System.nanoTime() - start) / 1_000_000L;
      System.gc();
      long heapAfter = usedHeap();
      System.out.println("open_ms=" + openMs);
      System.out.println("heap_delta_bytes=" + (heapAfter - heapBefore)
          + " (" + human(Math.max(0, heapAfter - heapBefore)) + ")");
      System.out.println("journal_mode=" + db.journalMode());

      List<TableInfo> tables = new ArrayList<>();
      for (TableInfo table : db.tables()) {
        if (!table.name().toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
          tables.add(table);
        }
      }
      tables.sort(Comparator.comparingInt(TableInfo::rowCount).reversed());
      Set<String> indexed = new HashSet<>();
      for (IndexDef index : db.indexes()) {
        indexed.add(index.table().toLowerCase(Locale.ROOT));
      }
      System.out.println("user_tables=" + tables.size());
      int top = Math.min(15, tables.size());
      for (int i = 0; i < top; i++) {
        TableInfo table = tables.get(i);
        boolean hasIndex = indexed.contains(table.name().toLowerCase(Locale.ROOT));
        System.out.println(
            "  table["
                + i
                + "] name="
                + table.name()
                + " rows="
                + table.rowCount()
                + " cols="
                + table.columns().size()
                + " secondary_index="
                + hasIndex);
      }
    } catch (OutOfMemoryError oom) {
      System.out.println("open=OOM: " + oom);
    } catch (SqlException ex) {
      System.out.println("open=error: " + ex.getMessage());
    }
  }

  private static byte[] readHeader(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return in.readNBytes(SqliteHeader.SIZE);
    }
  }

  private static long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  static String human(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double value = bytes;
    String[] units = {"KB", "MB", "GB", "TB"};
    int unit = -1;
    while (value >= 1024 && unit + 1 < units.length) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }
}
