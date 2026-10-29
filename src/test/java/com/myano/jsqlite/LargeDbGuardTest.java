package com.myano.jsqlite;

import com.myano.jsqlite.admin.DbAdmin;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeDbGuardTest {
  @Test
  void formatBytesUsesBinaryUnits() {
    assertEquals("512 B", DbAdmin.formatBytes(512));
    assertEquals("1.0 KB", DbAdmin.formatBytes(1024));
    assertEquals("1.5 MB", DbAdmin.formatBytes(1536L * 1024));
  }

  @Test
  void freelistCountPragmaReturnsRow() throws Exception {
    Path file = Files.createTempFile("jsqlite-freelist", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      try (ResultSet result = statement.executeQuery("PRAGMA freelist_count")) {
        assertTrue(result.next());
        assertTrue(result.getLong(1) >= 0);
      }
    }
    Files.deleteIfExists(file);
  }

  @Test
  void walCheckpointTruncateShrinksWal() throws Exception {
    Path file = Files.createTempFile("jsqlite-ckpt-pragma", ".sqlite");
    Files.deleteIfExists(file);
    Path wal = Path.of(file.toString() + "-wal");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
        assertTrue(mode.next());
        assertEquals("wal", mode.getString(1));
      }
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'a')");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (2, 'b')");
      assertTrue(Files.isRegularFile(wal));
      long before = Files.size(wal);
      assertTrue(before > 32, "wal should hold frames before checkpoint");

      try (ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
        assertTrue(result.next());
        assertEquals(0L, result.getLong(1));
      }
      assertTrue(Files.size(wal) < before, "checkpoint should shrink wal");

      try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM t")) {
        assertTrue(result.next());
        assertEquals(2L, result.getLong(1));
      }
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (3, 'c')");
    }
    Files.deleteIfExists(wal);
    Files.deleteIfExists(Path.of(file.toString() + "-shm"));
    Files.deleteIfExists(file);
  }

  @Test
  void probeRunsAgainstSmallCopy() throws Exception {
    Path source = Path.of("C:\\GzMian\\IDServer\\dist\\data\\skoruba4j.sqlite");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source), "no skoruba4j sample");
    Path copy = Files.createTempFile("jsqlite-probe", ".sqlite");
    Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
    Files.deleteIfExists(Path.of(copy.toString() + "-wal"));
    Files.deleteIfExists(Path.of(copy.toString() + "-shm"));
    LargeDbProbe.probe(copy, true);
    Files.deleteIfExists(copy);
  }
}
