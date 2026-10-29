package com.myano.jsqlite;

import com.myano.jsqlite.format.DbImage;
import com.myano.jsqlite.format.Pager;
import com.myano.jsqlite.format.SqliteReader;
import com.myano.jsqlite.format.TableSidecars;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.RatioAutoIndex;
import com.myano.jsqlite.store.TableInfo;
import com.myano.jsqlite.wal.WalReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 同一個檔案在行程內共用一份目錄。交易期間用 gate 互斥，避免連線池各自看到過期資料。 */
final class Engine {
  private static final ConcurrentHashMap<String, Engine> OPEN = new ConcurrentHashMap<>();
  /** One worker: large-table ratio analyze must not stampede the heap on open. */
  private static final ExecutorService RATIO_ANALYZE =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "jsqlite-ratio-analyze");
            t.setDaemon(true);
            return t;
          });

  final Path path;
  final MemoryCatalog catalog = new MemoryCatalog();
  final Semaphore gate = new Semaphore(1);
  Pager pager;
  String journalMode = "memory";
  boolean foreignKeys;
  /** Default finite wait — 0 means acquire() blocks forever and freezes Tomcat under pool contention. */
  long busyTimeout = 60_000;
  /** Keeps the mapped database image alive for deferred table loads. */
  SqliteReader.Pin dataPin;
  /** Sidecar file images mounted into {@link #catalog}. */
  final List<DbImage> sidecarImages = new ArrayList<>();
  private final boolean shared;
  private int refs;

  private Engine(Path path, boolean shared) {
    this.path = path;
    this.shared = shared;
  }

  static Engine memory(Path path) {
    return new Engine(path, false);
  }

  static Engine openShared(Path path) {
    String key = path.toAbsolutePath().normalize().toString();
    synchronized (Engine.class) {
      Engine engine = OPEN.get(key);
      if (engine == null) {
        engine = load(path.toAbsolutePath().normalize());
        OPEN.put(key, engine);
      }
      engine.refs++;
      return engine;
    }
  }

  void release() {
    if (!shared) {
      return;
    }
    synchronized (Engine.class) {
      refs--;
      if (refs > 0) {
        return;
      }
      OPEN.remove(path.toAbsolutePath().normalize().toString());
      releaseSidecars();
      if (dataPin != null) {
        dataPin.release();
        dataPin = null;
      }
    }
  }

  void releaseDataPin() {
    if (dataPin != null) {
      dataPin.release();
      dataPin = null;
    }
  }

  void releaseSidecars() {
    for (DbImage image : sidecarImages) {
      image.close();
    }
    sidecarImages.clear();
  }

  void mountSidecars() {
    releaseSidecars();
    if (path != null && Files.isRegularFile(path)) {
      sidecarImages.addAll(TableSidecars.mount(path, catalog));
    }
  }

  /**
   * After main-file rewrite: rebind unmodified file-backed tables and remount sidecars. Skip opening
   * the main mapping when every table is already in-memory (avoids Windows lock churn on autocommit).
   */
  void reattachAfterPersist() {
    releaseSidecars();
    if (path == null || !Files.isRegularFile(path)) {
      return;
    }
    boolean needMain = false;
    for (var info : catalog.tables()) {
      var table = catalog.find(info.name());
      if (table != null && !table.isMutated() && !table.isSidecar() && table.hasDeferredLoad()) {
        needMain = true;
        break;
      }
    }
    if (needMain) {
      dataPin = SqliteReader.reattach(path, catalog);
    }
    if (!TableSidecars.listed(path).isEmpty()) {
      mountSidecars();
    }
    bindRatioMeta();
  }

  boolean tryEnter(long busyTimeoutMs) {
    try {
      if (busyTimeoutMs <= 0) {
        gate.acquire();
        return true;
      }
      return gate.tryAcquire(busyTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  void exit() {
    gate.release();
  }

  private static Engine load(Path path) {
    Engine engine = new Engine(path, true);
    if (!SqliteReader.isDatabase(path)) {
      return engine;
    }
    boolean wal = Files.isRegularFile(WalReader.pathFor(path));
    engine.journalMode = wal ? "wal" : "delete";
    if (!wal) {
      // Schema now; each table's rows load on first touch. Mapped file stays off-heap.
      engine.dataPin = SqliteReader.load(path, engine.catalog);
      engine.mountSidecars();
      engine.bindRatioMeta();
      engine.pager = Pager.detached(path);
      return engine;
    }
    // WAL: merge frames, then defer row loads from the flattened image (no lasting page copy).
    Pager pager = Pager.open(path);
    byte[][] pages = WalReader.apply(path, pager.stealPages());
    byte[] flat = Pager.flatten(pages);
    pages = null;
    engine.dataPin = SqliteReader.loadBytes(flat, engine.catalog);
    engine.mountSidecars();
    engine.bindRatioMeta();
    engine.pager = Pager.detached(path);
    return engine;
  }

  void bindRatioMeta() {
    if (path == null) {
      return;
    }
    var dir = RatioAutoIndex.metaDir(path);
    for (TableInfo info : catalog.tables()) {
      var table = catalog.find(info.name());
      if (table != null) {
        table.setRatioMetaDir(dir);
      }
    }
    scheduleRatioAnalyze();
  }

  /**
   * Sidecar / large tables that never see a single-column seek (e.g. GLJrnDtl uses composite
   * Company+BookID+JournalNum) would never write {@code .ratio-cols}. Queue a background analyze
   * so meta appears without blocking open or the first composite query.
   */
  void scheduleRatioAnalyze() {
    for (TableInfo info : catalog.tables()) {
      var table = catalog.find(info.name());
      if (table == null) {
        continue;
      }
      RATIO_ANALYZE.execute(
          () -> {
            try {
              RatioAutoIndex.ensure(table);
            } catch (Throwable ignored) {
              // Best-effort; queries still work via composite auto-index.
            }
          });
    }
  }
}
