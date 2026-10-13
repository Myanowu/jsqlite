package com.myano.jsqlite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalTest {
  @Test
  void walModeKeepsSidecarAndRowsSurviveReopen() throws Exception {
    Path file = Files.createTempFile("jsqlite-wal-mode", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
        assertTrue(mode.next());
        assertEquals("wal", mode.getString(1));
      }
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'wal-row')");
    }
    assertTrue(Files.isRegularFile(Path.of(file.toString() + "-wal")));
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = again.createStatement();
        ResultSet result = statement.executeQuery("SELECT name FROM t WHERE id = 1")) {
      assertTrue(result.next());
      assertEquals("wal-row", result.getString(1));
    }
    Files.deleteIfExists(Path.of(file.toString() + "-wal"));
    Files.deleteIfExists(Path.of(file.toString() + "-shm"));
    Files.deleteIfExists(file);
  }

  @Test
  void secondWriterTimesOutWithBusyTimeout() throws Exception {
    Path file = Files.createTempFile("jsqlite-wal-lock", ".sqlite");
    Files.deleteIfExists(file);
    com.myano.jsqlite.format.Pager.create(file);
    try (Database first = Database.open(file)) {
      first.run("PRAGMA journal_mode=WAL");
      first.run("PRAGMA busy_timeout=50");
      first.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      first.execute("INSERT INTO t (id) VALUES (1)");
      first.setAutoCommit(false);
      first.execute("INSERT INTO t (id) VALUES (2)");
      try (var write = com.myano.jsqlite.wal.LockFile.acquireWrite(file, 0)) {
        SqlException locked = assertThrows(SqlException.class, () -> {
          try (Database second = Database.open(file)) {
            second.run("PRAGMA journal_mode=WAL");
            second.run("PRAGMA busy_timeout=50");
            second.execute("INSERT INTO t (id) VALUES (3)");
          }
        });
        assertTrue(locked.getMessage().contains("database is locked"), locked.getMessage());
      }
      first.commit();
    }
    try (Database again = Database.open(file)) {
      assertEquals(2L, again.query("SELECT COUNT(*) FROM t").get(0).values().get(0));
    }
    Files.deleteIfExists(Path.of(file.toString() + "-wal"));
    Files.deleteIfExists(Path.of(file.toString() + "-shm"));
    Files.deleteIfExists(file);
  }

  @Test
  void checkpointCopiesCommittedRows() throws Exception {
    Assumptions.assumeTrue(xerialPresent(), "本机没有 xerial");
    Path file = Files.createTempFile("jsqlite-wal-ckpt", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
        assertTrue(mode.next());
        assertEquals("wal", mode.getString(1));
      }
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'ckpt')");
    }
    Path copy = Files.createTempFile("jsqlite-wal-main", ".sqlite");
    Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Class.forName("org.sqlite.JDBC");
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + copy);
    try (Connection xerial = source.getConnection();
        Statement statement = xerial.createStatement();
        ResultSet result = statement.executeQuery("SELECT name FROM t WHERE id = 1")) {
      assertTrue(result.next());
      assertEquals("ckpt", result.getString(1));
    }
    Files.deleteIfExists(Path.of(file.toString() + "-wal"));
    Files.deleteIfExists(Path.of(file.toString() + "-shm"));
    Files.deleteIfExists(file);
    Files.deleteIfExists(copy);
  }

  private static boolean xerialPresent() {
    try {
      Class.forName("org.sqlite.JDBC");
      return true;
    } catch (ClassNotFoundException ex) {
      return false;
    }
  }
}
