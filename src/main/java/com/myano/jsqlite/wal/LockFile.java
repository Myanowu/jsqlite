package com.myano.jsqlite.wal;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 進程內用 Semaphore（不可重入），跨進程用 -shm。超時訊息含 database is locked。 */
public final class LockFile implements AutoCloseable {
  private static final ConcurrentHashMap<String, Integer> CONNECTIONS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Semaphore> LOCAL = new ConcurrentHashMap<>();

  private final Path database;
  private final Semaphore local;
  private RandomAccessFile file;
  private FileChannel channel;
  private FileLock lock;
  private final boolean writer;

  private LockFile(Path database, Semaphore local, boolean writer) {
    this.database = database;
    this.local = local;
    this.writer = writer;
  }

  public static Path pathFor(Path database) {
    return Path.of(database.toString() + "-shm");
  }

  public static LockFile register(Path database) {
    CONNECTIONS.merge(key(database), 1, Integer::sum);
    return new LockFile(database, null, false);
  }

  public static LockFile acquireWrite(Path database, long busyTimeoutMs) {
    String id = key(database);
    Semaphore local = LOCAL.computeIfAbsent(id, ignored -> new Semaphore(1));
    long deadline = busyTimeoutMs <= 0 ? 0 : System.currentTimeMillis() + busyTimeoutMs;
    Path lockPath = pathFor(database);
    while (true) {
      boolean acquired;
      try {
        acquired = busyTimeoutMs <= 0
            ? local.tryAcquire()
            : local.tryAcquire(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new SqlException("database is locked");
      }
      if (!acquired) {
        throw new SqlException("database is locked");
      }
      try {
        RandomAccessFile file = new RandomAccessFile(lockPath.toFile(), "rw");
        if (file.length() < 1) {
          file.setLength(1);
        }
        FileChannel channel = file.getChannel();
        FileLock fileLock;
        try {
          fileLock = channel.tryLock(0, 1, false);
        } catch (OverlappingFileLockException ex) {
          channel.close();
          file.close();
          local.release();
          if (busyTimeoutMs <= 0 || System.currentTimeMillis() >= deadline) {
            throw new SqlException("database is locked");
          }
          Thread.sleep(Math.min(50, Math.max(1, deadline - System.currentTimeMillis())));
          continue;
        }
        if (fileLock != null) {
          LockFile held = new LockFile(database, local, true);
          held.file = file;
          held.channel = channel;
          held.lock = fileLock;
          return held;
        }
        channel.close();
        file.close();
        local.release();
      } catch (IOException ex) {
        local.release();
        throw new SqlException("unable to lock " + lockPath + ": " + ex.getMessage());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new SqlException("database is locked");
      }
      if (busyTimeoutMs <= 0 || System.currentTimeMillis() >= deadline) {
        throw new SqlException("database is locked");
      }
      try {
        Thread.sleep(Math.min(50, Math.max(1, deadline - System.currentTimeMillis())));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new SqlException("database is locked");
      }
    }
  }

  public static boolean onlyConnection(Path database) {
    Integer count = CONNECTIONS.get(key(database));
    return count == null || count <= 1;
  }

  private static String key(Path database) {
    return database.toAbsolutePath().normalize().toString();
  }

  @Override
  public void close() {
    if (!writer) {
      CONNECTIONS.computeIfPresent(key(database), (ignored, count) -> count <= 1 ? null : count - 1);
      return;
    }
    try {
      if (lock != null) {
        lock.release();
        lock = null;
      }
      if (channel != null) {
        channel.close();
      }
      if (file != null) {
        file.close();
      }
    } catch (IOException ex) {
      throw new SqlException("unable to unlock " + pathFor(database) + ": " + ex.getMessage());
    } finally {
      if (local != null) {
        local.release();
      }
    }
  }
}
