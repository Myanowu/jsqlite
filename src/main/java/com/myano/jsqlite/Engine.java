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

/**
 * 同一個檔案在行程內共用一份目錄。讀共享、寫獨占；寫鎖對「另一個 {@link Database}」不可重入（同執行緒第二連線會 busy），避免共目錄誤寫。
 */
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
  private final Object lock = new Object();
  private int readers;
  private boolean writer;
  private Thread writeOwner;
  Pager pager;
  String journalMode = "memory";
  boolean foreignKeys;
  /** Default finite wait — 0 means acquire() blocks forever and freezes Tomcat under pool contention. */
  long busyTimeout = 60_000;
  /** Keeps the mapped database image alive for deferred table loads. */
  SqliteReader.Pin dataPin;
  /** Sidecar file images mounted into {@link #catalog}. */
  final List<DbImage> sidecarImages = new ArrayList<>();
  /** Last applied {@code -wal} / main-file fingerprint for cross-process refresh. */
  private long walSeenBytes;
  private long mainSeenBytes;
  private long mainSeenMtime;
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

  boolean tryEnterRead(long busyTimeoutMs) {
    long deadline = busyTimeoutMs <= 0 ? 0 : System.currentTimeMillis() + busyTimeoutMs;
    synchronized (lock) {
      while (writer) {
        if (!waitForLock(deadline, busyTimeoutMs)) {
          return false;
        }
      }
      readers++;
      return true;
    }
  }

  boolean tryEnterWrite(long busyTimeoutMs) {
    long deadline = busyTimeoutMs <= 0 ? 0 : System.currentTimeMillis() + busyTimeoutMs;
    synchronized (lock) {
      // Another Database on this thread already holds the write lock — not reentrant across connections.
      if (writer && writeOwner == Thread.currentThread()) {
        return false;
      }
      while (writer || readers > 0) {
        if (!waitForLock(deadline, busyTimeoutMs)) {
          return false;
        }
      }
      writer = true;
      writeOwner = Thread.currentThread();
      return true;
    }
  }

  void exitRead() {
    synchronized (lock) {
      if (readers <= 0) {
        throw new IllegalStateException("read lock not held");
      }
      readers--;
      if (readers == 0) {
        lock.notifyAll();
      }
    }
  }

  void exitWrite() {
    synchronized (lock) {
      if (!writer || writeOwner != Thread.currentThread()) {
        throw new IllegalStateException("write lock not held");
      }
      writer = false;
      writeOwner = null;
      lock.notifyAll();
    }
  }

  /** Drop write ownership and become a reader (WAL refresh downgrade). Write lock must be held. */
  void downgradeWriteToRead() {
    synchronized (lock) {
      if (!writer || writeOwner != Thread.currentThread()) {
        throw new IllegalStateException("write lock not held");
      }
      writer = false;
      writeOwner = null;
      readers++;
      lock.notifyAll();
    }
  }

  private boolean waitForLock(long deadline, long busyTimeoutMs) {
    try {
      if (busyTimeoutMs <= 0) {
        lock.wait();
        return true;
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return false;
      }
      lock.wait(remaining);
      return true;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  boolean walPossiblyStale() {
    if (!"wal".equals(journalMode) || path == null || !Files.isRegularFile(path)) {
      return false;
    }
    try {
      long mainSize = Files.size(path);
      long mainMtime = Files.getLastModifiedTime(path).toMillis();
      if (mainSize != mainSeenBytes || mainMtime != mainSeenMtime) {
        return true;
      }
      Path wal = WalReader.pathFor(path);
      if (!Files.isRegularFile(wal)) {
        return walSeenBytes > 0;
      }
      return Files.size(wal) != walSeenBytes;
    } catch (java.io.IOException ex) {
      return false;
    }
  }

  /** Caller must hold the write lock. Reloads catalog from main + WAL when the on-disk image moved. */
  void refreshFromWalIfNeeded() {
    if (!walPossiblyStale()) {
      return;
    }
    releaseSidecars();
    releaseDataPin();
    catalog.resetForReload();
    Pager opened = Pager.open(path);
    byte[][] pages = WalReader.apply(path, opened.stealPages());
    byte[] flat = Pager.flatten(pages);
    dataPin = SqliteReader.loadBytes(flat, catalog);
    mountSidecars();
    bindRatioMeta();
    pager = Pager.detached(path);
    captureDiskFingerprint();
  }

  void noteWalLength(long bytes) {
    walSeenBytes = bytes;
    captureMainFingerprint();
  }

  /** After a durable persist (delete or WAL), refresh the on-disk fingerprint. */
  void notePersistedImage() {
    captureDiskFingerprint();
  }

  private void captureDiskFingerprint() {
    captureMainFingerprint();
    Path wal = WalReader.pathFor(path);
    try {
      walSeenBytes = Files.isRegularFile(wal) ? Files.size(wal) : 0L;
    } catch (java.io.IOException ex) {
      walSeenBytes = 0L;
    }
  }

  private void captureMainFingerprint() {
    if (path == null || !Files.isRegularFile(path)) {
      return;
    }
    try {
      mainSeenBytes = Files.size(path);
      mainSeenMtime = Files.getLastModifiedTime(path).toMillis();
    } catch (java.io.IOException ignored) {
      // leave previous fingerprint
    }
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
      engine.captureDiskFingerprint();
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
    engine.captureDiskFingerprint();
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
