package com.myano.jsqlite;

import com.myano.jsqlite.format.Pager;
import com.myano.jsqlite.format.SqliteReader;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.wal.WalReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 同一個檔案在行程內共用一份目錄。交易期間用 gate 互斥，避免連線池各自看到過期資料。 */
final class Engine {
  private static final ConcurrentHashMap<String, Engine> OPEN = new ConcurrentHashMap<>();

  final Path path;
  final MemoryCatalog catalog = new MemoryCatalog();
  final Semaphore gate = new Semaphore(1);
  Pager pager;
  String journalMode = "memory";
  boolean foreignKeys;
  /** Default finite wait — 0 means acquire() blocks forever and freezes Tomcat under pool contention. */
  long busyTimeout = 60_000;
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
    }
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
    engine.pager = Pager.open(path);
    byte[][] pages = WalReader.apply(path, engine.pager.copyPages());
    engine.pager.replace(pages);
    SqliteReader.loadBytes(Pager.flatten(pages), engine.catalog);
    engine.journalMode = Files.isRegularFile(WalReader.pathFor(path)) ? "wal" : "delete";
    return engine;
  }
}
