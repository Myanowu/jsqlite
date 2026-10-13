package com.myano.jsqlite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteBackTest {
  @Test
  void newFileRoundTrips() throws Exception {
    Path file = Files.createTempFile("jsqlite-wb", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'a')");
    }
    byte[] created = Files.readAllBytes(file);
    assertArrayEquals("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(created, 16));
    assertFalse(Files.exists(Path.of(file.toString() + "-wal")));
    assertFalse(Files.exists(Path.of(file.toString() + "-journal")));
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = again.createStatement();
        ResultSet result = statement.executeQuery("SELECT name FROM t WHERE id = 1")) {
      assertTrue(result.next());
      assertEquals("a", result.getString(1));
    }
    Files.deleteIfExists(file);
  }

  @Test
  void sequenceSurvivesReopen() throws Exception {
    Path file = Files.createTempFile("jsqlite-seq", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
      statement.executeUpdate("INSERT INTO t (name) VALUES ('a')");
      statement.executeUpdate("INSERT INTO t (name) VALUES ('b')");
      statement.executeUpdate("DELETE FROM t WHERE id = 2");
    }
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        PreparedStatement insert = again.prepareStatement(
            "INSERT INTO t (name) VALUES ('c')", Statement.RETURN_GENERATED_KEYS)) {
      insert.executeUpdate();
      try (ResultSet keys = insert.getGeneratedKeys()) {
        assertTrue(keys.next());
        long generated = keys.getLong(1);
        assertTrue(generated > 2);
        try (Statement statement = again.createStatement();
            ResultSet seq = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name = 't'")) {
          assertTrue(seq.next());
          assertEquals(generated, seq.getLong(1));
        }
      }
    }
    Files.deleteIfExists(file);
  }

  @Test
  void xerialReadsJsqliteFile() throws Exception {
    Assumptions.assumeTrue(xerialPresent(), "本机没有 xerial");
    Path file = Files.createTempFile("jsqlite-xerial-read", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'from-jsqlite')");
    }
    try (Connection xerial = xerial(file);
        Statement statement = xerial.createStatement();
        ResultSet result = statement.executeQuery("SELECT name FROM t WHERE id = 1")) {
      assertTrue(result.next());
      assertEquals("from-jsqlite", result.getString(1));
    }
    Files.deleteIfExists(file);
  }

  @Test
  void jsqliteUpdatesXerialFile() throws Exception {
    Assumptions.assumeTrue(xerialPresent(), "本机没有 xerial");
    Path file = Files.createTempFile("jsqlite-xerial-write", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection xerial = xerial(file);
        Statement statement = xerial.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'old')");
    }
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE t SET name = 'new' WHERE id = 1");
    }
    try (Connection xerial = xerial(file);
        Statement statement = xerial.createStatement();
        ResultSet result = statement.executeQuery("SELECT name FROM t WHERE id = 1")) {
      assertTrue(result.next());
      assertEquals("new", result.getString(1));
    }
    Files.deleteIfExists(file);
  }

  @Test
  void tornWriteRollsBack() throws Exception {
    Path file = Files.createTempFile("jsqlite-torn", ".sqlite");
    Files.deleteIfExists(file);
    com.myano.jsqlite.format.Pager.create(file);
    try (Database db = Database.open(file)) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("INSERT INTO t (id, name) VALUES (1, 'keep')");
    }
    try (Database db = Database.open(file)) {
      db.setAutoCommit(false);
      db.execute("INSERT INTO t (id, name) VALUES (2, 'drop')");
      db.failOnWrite(1);
      assertThrows(SqlException.class, db::commit);
    }
    try (Database db = Database.open(file)) {
      assertEquals(1L, db.query("SELECT COUNT(*) FROM t").get(0).values().get(0));
      assertEquals("keep", db.query("SELECT name FROM t WHERE id = 1").get(0).values().get(0));
    }
    assertFalse(Files.exists(Path.of(file.toString() + "-journal")));
    Files.deleteIfExists(file);
  }

  private static Connection xerial(Path file) throws Exception {
    Class.forName("org.sqlite.JDBC");
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + file);
    return source.getConnection();
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
