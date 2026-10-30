package com.myano.jsqlite;

import com.myano.jsqlite.store.RatioAutoIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatioAutoIndexTest {
  private long savedRows;
  private int savedRatio;

  @BeforeEach
  void lowerThresholds() {
    savedRows = RatioAutoIndex.minRows;
    savedRatio = RatioAutoIndex.minRatio;
    RatioAutoIndex.configure(1_000, 10);
  }

  @AfterEach
  void restoreThresholds() {
    RatioAutoIndex.configure(savedRows, savedRatio);
  }

  @Test
  void groupCountsExposeWinnerDistinctAndPersistInMeta() throws Exception {
    Path file = Files.createTempFile("jsqlite-ratio-groups", ".jsqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, grp TEXT, note TEXT)");
      connection.setAutoCommit(false);
      for (int i = 1; i <= 2000; i++) {
        String g = "G" + (i % 5);
        statement.executeUpdate(
            "INSERT INTO t (id, grp, note) VALUES (" + i + ", '" + g + "', 'n" + i + "')");
      }
      connection.commit();
      statement.execute("PRAGMA jsqlite_analyze(t)");
    }
    try (Database db = Database.open(file)) {
      var groups = db.ratioGroupCounts("t");
      assertEquals(5, groups.get("grp").intValue());
      assertTrue(!groups.containsKey("id") || groups.get("id") == null);
    }
    Path meta = Path.of(file.toAbsolutePath().normalize() + ".ratio-idx").resolve("t.ratio-cols");
    String body = Files.readString(meta);
    assertTrue(body.contains("grp=5"), body);
    try (Database again = Database.open(file)) {
      assertEquals(5, again.ratioGroupCounts("t").get("grp").intValue());
    } finally {
      Files.deleteIfExists(meta);
      try {
        Files.deleteIfExists(meta.getParent());
      } catch (Exception ignored) {
      }
      Files.deleteIfExists(file);
    }
  }

  @Test
  void singleEqualityUsesRatioIndexAndSurvivesInsert() throws Exception {
    Path file = Files.createTempFile("jsqlite-ratio", ".jsqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE t (id INTEGER PRIMARY KEY, grp TEXT, note TEXT)");
      connection.setAutoCommit(false);
      for (int i = 1; i <= 2000; i++) {
        String g = "G" + (i % 5);
        statement.executeUpdate(
            "INSERT INTO t (id, grp, note) VALUES (" + i + ", '" + g + "', 'n" + i + "')");
      }
      connection.commit();
    }

    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA jsqlite_analyze(t)");
      try (ResultSet rs =
          statement.executeQuery("SELECT COUNT(*) FROM t WHERE grp = 'G1'")) {
        assertTrue(rs.next());
        assertEquals(400L, rs.getLong(1));
      }
      statement.executeUpdate("INSERT INTO t (id, grp, note) VALUES (9001, 'G1', 'new')");
      try (ResultSet rs =
          statement.executeQuery("SELECT COUNT(*) FROM t WHERE grp = 'G1'")) {
        assertTrue(rs.next());
        assertEquals(401L, rs.getLong(1));
      }
    } finally {
      Path meta = Path.of(file.toAbsolutePath().normalize() + ".ratio-idx").resolve("t.ratio-cols");
      Files.deleteIfExists(meta);
      try {
        Files.deleteIfExists(meta.getParent());
      } catch (Exception ignored) {
      }
      Files.deleteIfExists(file);
    }
  }
}
