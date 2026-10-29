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
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactCopyTest {
  @Test
  void compactCopyPreservesRowsAndCanShrink() throws Exception {
    Path source = Files.createTempFile("jsqlite-compact-src", ".sqlite");
    Path dest = Files.createTempFile("jsqlite-compact-dst", ".sqlite");
    Files.deleteIfExists(source);
    Files.deleteIfExists(dest);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + source);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("CREATE INDEX idx_t_name ON t (name)");
      for (int i = 1; i <= 200; i++) {
        statement.executeUpdate("INSERT INTO t (id, name) VALUES (" + i + ", 'n" + i + "')");
      }
      statement.executeUpdate("DELETE FROM t WHERE id > 20");
    }
    long before = Files.size(source);
    CompactCopy.Result result = CompactCopy.copy(source, dest);
    assertEquals(1, result.tables());
    assertEquals(20, result.rows());
    assertTrue(result.destinationBytes() > 0);
    assertTrue(
        result.destinationBytes() <= before,
        "dest " + result.destinationBytes() + " should be <= source " + before);

    try (Database again = Database.open(dest)) {
      assertEquals(20L, again.query("SELECT COUNT(*) FROM t").get(0).values().get(0));
      assertEquals(1, again.indexes().size());
    }
    Files.deleteIfExists(source);
    Files.deleteIfExists(dest);
  }

  @Test
  void vacuumIntoWithXerialWhenPresent() throws Exception {
    Assumptions.assumeTrue(xerialPresent(), "本机没有 xerial");
    Path source = Files.createTempFile("jsqlite-vacuum-src", ".sqlite");
    Path dest = Files.createTempFile("jsqlite-vacuum-dst", ".sqlite");
    Files.deleteIfExists(source);
    Files.deleteIfExists(dest);
    var dataSource = new org.sqlite.SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + source);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'a')");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (2, 'b')");
      statement.executeUpdate("DELETE FROM t WHERE id = 2");
    }
    CompactCopy.Result result = CompactCopy.vacuumIntoWithXerial(source, dest);
    assertTrue(Files.isRegularFile(dest));
    assertTrue(result.destinationBytes() > 0);
    dataSource.setUrl("jdbc:sqlite:" + dest);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
    Files.deleteIfExists(source);
    Files.deleteIfExists(dest);
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
