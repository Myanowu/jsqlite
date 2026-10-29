package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSidecarTest {
  @Test
  void splitTableMountsSiblingAndKeepsJoinWorking() throws Exception {
    Path file = Files.createTempFile("jsqlite-sidecar", ".jsqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE hed (Company TEXT, GroupID TEXT, JournalNum INTEGER, PRIMARY KEY (Company, GroupID, JournalNum))");
      statement.executeUpdate(
          "CREATE TABLE dtl (Company TEXT, JournalNum INTEGER, LineNum INTEGER, Amount INTEGER, PRIMARY KEY (Company, JournalNum, LineNum))");
      statement.executeUpdate("INSERT INTO hed VALUES ('ELCSS', 'G1', 1)");
      statement.executeUpdate("INSERT INTO hed VALUES ('ELCSS', 'G1', 2)");
      for (int i = 1; i <= 200; i++) {
        statement.executeUpdate(
            "INSERT INTO dtl VALUES ('ELCSS', " + ((i % 2) + 1) + ", " + i + ", " + i + ")");
      }
    }

    Path sidecar = Database.splitTable(file, "dtl");
    assertTrue(Files.isRegularFile(sidecar));
    assertTrue(
        Files.isRegularFile(
            Path.of(file.toAbsolutePath().normalize() + ".sidecars").resolve("manifest")));

    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      try (ResultSet sides = statement.executeQuery("PRAGMA jsqlite_sidecars")) {
        assertTrue(sides.next());
        assertEquals("dtl", sides.getString(1));
        assertEquals(1L, sides.getLong(3));
      }
      try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM dtl")) {
        assertTrue(count.next());
        assertEquals(200L, count.getLong(1));
      }
      try (ResultSet joined =
          statement.executeQuery(
              """
              SELECT h.JournalNum, COUNT(d.LineNum) AS lines, SUM(d.Amount) AS amt
              FROM hed h
              LEFT JOIN dtl d ON d.Company = h.Company AND d.JournalNum = h.JournalNum
              WHERE h.Company = 'ELCSS' AND h.GroupID = 'G1'
              GROUP BY h.JournalNum
              ORDER BY h.JournalNum
              """)) {
        assertTrue(joined.next());
        assertEquals(1L, joined.getLong(1));
        assertEquals(100L, joined.getLong(2));
        assertTrue(joined.next());
        assertEquals(2L, joined.getLong(1));
        assertEquals(100L, joined.getLong(2));
      }
    } finally {
      Files.deleteIfExists(sidecar);
      Files.deleteIfExists(
          Path.of(file.toAbsolutePath().normalize() + ".sidecars").resolve("manifest"));
      try {
        Files.deleteIfExists(Path.of(file.toAbsolutePath().normalize() + ".sidecars"));
      } catch (Exception ignored) {
      }
      Files.deleteIfExists(file);
    }
  }

  @Test
  void pragmaSplitWhileOpenOverlaysImmediately() throws Exception {
    Path file = Files.createTempFile("jsqlite-sidecar-pragma", ".jsqlite");
    Files.deleteIfExists(file);
    try (Connection seed = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = seed.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("CREATE TABLE u (id INTEGER PRIMARY KEY, t_id INTEGER, note TEXT)");
      statement.executeUpdate("INSERT INTO t VALUES (1, 'a')");
      statement.executeUpdate("INSERT INTO u VALUES (1, 1, 'x')");
      statement.executeUpdate("INSERT INTO u VALUES (2, 1, 'y')");
    }
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA jsqlite_split_table(u)");
      try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM u")) {
        assertTrue(count.next());
        assertEquals(2L, count.getLong(1));
      }
      try (ResultSet joined =
          statement.executeQuery(
              "SELECT t.name, COUNT(u.id) FROM t LEFT JOIN u ON u.t_id = t.id GROUP BY t.name")) {
        assertTrue(joined.next());
        assertEquals("a", joined.getString(1));
        assertEquals(2L, joined.getLong(2));
      }
    } finally {
      Path dir = Path.of(file.toAbsolutePath().normalize() + ".sidecars");
      Files.deleteIfExists(dir.resolve("u.jsqlite"));
      Files.deleteIfExists(dir.resolve("manifest"));
      try {
        Files.deleteIfExists(dir);
      } catch (Exception ignored) {
      }
      Files.deleteIfExists(file);
    }
  }
}
