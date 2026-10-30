package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcContractTest {
  @Test
  void hikariPoolSetupMethodsAreSupported() throws Exception {
    try (Connection connection = memory()) {
      assertFalse(connection.isReadOnly());
      connection.setReadOnly(false);
      assertFalse(connection.isReadOnly());
      assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
      assertTrue(connection.isValid(1));
      assertEquals(0, connection.getNetworkTimeout());
      connection.setNetworkTimeout(Runnable::run, 5_000);
      assertEquals(5_000, connection.getNetworkTimeout());
      connection.setCatalog(null);
      assertEquals("", connection.getCatalog());
      connection.setSchema("main");
      assertEquals("main", connection.getSchema());
      connection.clearWarnings();
      assertEquals(null, connection.getWarnings());
      assertEquals("SELECT 1", connection.nativeSQL("SELECT 1"));
    }
  }

  @Test
  void driverCreatesASqliteFile() throws Exception {
    Path dir = Files.createTempDirectory("jsqlite-r2");
    Path file = dir.resolve("new.sqlite");
    // Recommended pool URL shape — see .cursor/plans/jsqlite-concurrency.md
    String url = "jdbc:jsqlite:" + file + "?journal_mode=WAL&busy_timeout=8000";
    try (Connection connection = DriverManager.getConnection(url)) {
      assertEquals("", connection.getCatalog());
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE t (id INTEGER)");
        statement.executeUpdate("INSERT INTO t VALUES (1)");
        try (ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
          assertTrue(result.next());
          assertEquals("wal", result.getString(1));
        }
        try (ResultSet result = statement.executeQuery("PRAGMA busy_timeout")) {
          assertTrue(result.next());
          assertEquals(8000, result.getInt(1));
        }
      }
    }
    assertTrue(Files.isRegularFile(file));
    byte[] created = Files.readAllBytes(file);
    assertEquals(0, created.length % 4096);
    assertTrue(created.length >= 4096);
    assertArrayEquals("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(created, 16));
    assertTrue(Files.exists(Path.of(file.toString() + "-wal")));
    assertFalse(Files.exists(Path.of(file.toString() + "-journal")));
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = again.createStatement();
        ResultSet result = statement.executeQuery("SELECT id FROM t")) {
      assertTrue(result.next());
      assertEquals(1, result.getInt("id"));
      assertFalse(result.next());
    }
    Path other = dir.resolve("other.sqlite");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + other);
        PreparedStatement statement = connection.prepareStatement("SELECT 1");
        ResultSet result = statement.executeQuery()) {
      assertTrue(result.next());
      assertEquals(1, result.getInt(1));
    }
    assertTrue(Files.isRegularFile(other));
    assertArrayEquals(
        "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII),
        Arrays.copyOf(Files.readAllBytes(other), 16));
  }

  @Test
  void preparedSelectAndBooleanNull() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE flags (v INTEGER)");
        statement.executeUpdate("INSERT INTO flags VALUES (0)");
        statement.executeUpdate("INSERT INTO flags VALUES (2)");
        statement.executeUpdate("INSERT INTO flags VALUES (NULL)");
        try (ResultSet result = statement.executeQuery("SELECT v FROM flags")) {
          assertTrue(result.next());
          assertFalse(result.getBoolean("v"));
          assertFalse(result.wasNull());
          assertTrue(result.next());
          assertTrue(result.getBoolean(1));
          assertTrue(result.next());
          assertFalse(result.getBoolean(1));
          assertTrue(result.wasNull());
        }
      }
    }
  }

  @Test
  void readsTheThreeTimestampForms() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE ts (v TEXT)");
        statement.executeUpdate("INSERT INTO ts VALUES ('2020-01-02 03:04:05')");
        statement.executeUpdate("INSERT INTO ts VALUES ('2020-01-02 03:04:05.1234567')");
        statement.executeUpdate("INSERT INTO ts VALUES ('1600000000')");
        statement.executeUpdate("INSERT INTO ts VALUES ('1600000000000')");
        statement.executeUpdate("CREATE TABLE n (v INTEGER)");
        statement.executeUpdate("INSERT INTO n VALUES (1600000000000)");
        try (ResultSet result = statement.executeQuery("SELECT v FROM ts")) {
          assertTrue(result.next());
          assertEquals(instant("2020-01-02 03:04:05", 0), result.getTimestamp(1).toInstant());
          assertTrue(result.next());
          assertEquals(instant("2020-01-02 03:04:05", 123_456_700), result.getTimestamp(1).toInstant());
          assertTrue(result.next());
          assertEquals(Instant.ofEpochSecond(1_600_000_000L), result.getTimestamp(1).toInstant());
          assertTrue(result.next());
          assertEquals(Instant.ofEpochMilli(1_600_000_000_000L), result.getTimestamp(1).toInstant());
        }
        try (ResultSet result = statement.executeQuery("SELECT v FROM n")) {
          assertTrue(result.next());
          assertEquals(Instant.ofEpochMilli(1_600_000_000_000L), result.getTimestamp(1).toInstant());
          assertFalse(result.wasNull());
        }
      }
    }
  }

  @Test
  void generatedKeysComeFromTheAutoincrementColumn() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE)");
        statement.executeUpdate("CREATE TABLE u (id TEXT PRIMARY KEY, name TEXT)");
      }
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO t (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
        insert.setString(1, "a");
        assertEquals(1, insert.executeUpdate());
        try (ResultSet keys = insert.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(1L, keys.getLong(1));
        }
        insert.setString(1, "a");
        try (PreparedStatement ignored = connection.prepareStatement(
            "INSERT OR IGNORE INTO t (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
          ignored.setString(1, "a");
          assertEquals(0, ignored.executeUpdate());
          try (ResultSet keys = ignored.getGeneratedKeys()) {
            assertFalse(keys.next());
          }
        }
      }
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO u (id, name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
        insert.setString(1, "a");
        insert.setString(2, "ann");
        insert.executeUpdate();
        try (ResultSet keys = insert.getGeneratedKeys()) {
          assertFalse(keys.next());
        }
      }
    }
  }

  @Test
  void rollbackDropsTheTransactionAndAFailedStatementDropsOnlyItself() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT UNIQUE)");
        connection.setAutoCommit(false);
        statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'a')");
        assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO t (id, name) VALUES (2, 'a')"));
        try (ResultSet result = statement.executeQuery("SELECT name FROM t")) {
          assertTrue(result.next());
          assertEquals("a", result.getString(1));
          assertFalse(result.next());
        }
        connection.rollback();
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM t")) {
          assertTrue(result.next());
          assertEquals(0, result.getInt(1));
        }
      }
    }
  }

  @Test
  void tableInfoUsesTheSqliteColumnNames() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("""
            CREATE TABLE t (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL DEFAULT 'x',
              note TEXT)
            """);
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(t)")) {
          assertEquals("cid", result.getMetaData().getColumnLabel(1));
          assertEquals("name", result.getMetaData().getColumnLabel(2));
          assertEquals("type", result.getMetaData().getColumnLabel(3));
          assertEquals("notnull", result.getMetaData().getColumnLabel(4));
          assertEquals("dflt_value", result.getMetaData().getColumnLabel(5));
          assertEquals("pk", result.getMetaData().getColumnLabel(6));
          assertTrue(result.next());
          assertEquals(0, result.getInt("cid"));
          assertEquals("id", result.getString("name"));
          assertEquals("INTEGER", result.getString("type"));
          assertEquals(1, result.getInt("notnull"));
          assertTrue(result.getString("dflt_value") == null);
          assertTrue(result.wasNull());
          assertEquals(1, result.getInt("pk"));
          assertTrue(result.next());
          assertEquals("name", result.getString("name"));
          assertEquals(1, result.getInt("notnull"));
          assertEquals("'x'", result.getString("dflt_value"));
          assertEquals(0, result.getInt("pk"));
          assertTrue(result.next());
          assertEquals("note", result.getString("name"));
          assertEquals(0, result.getInt("notnull"));
          assertEquals(0, result.getInt("pk"));
        }
      }
    }
  }

  @Test
  void metadataMatchesTheTableDefinition() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("""
            CREATE TABLE t (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL DEFAULT 'x')
            """);
      }
      assertTrue(connection.getMetaData().getURL().startsWith("jdbc:jsqlite:"));
      try (ResultSet columns = connection.getMetaData().getColumns(null, null, "t", null)) {
        assertTrue(columns.next());
        assertEquals("id", columns.getString("COLUMN_NAME"));
        assertEquals("INTEGER", columns.getString("TYPE_NAME"));
        assertEquals("YES", columns.getString("IS_AUTOINCREMENT"));
        assertTrue(columns.next());
        assertEquals("name", columns.getString("COLUMN_NAME"));
        assertEquals("TEXT", columns.getString("TYPE_NAME"));
        assertEquals(0, columns.getInt("COLUMN_SIZE"));
        assertEquals("'x'", columns.getString("COLUMN_DEF"));
        assertEquals("NO", columns.getString("IS_AUTOINCREMENT"));
      }
      try (ResultSet keys = connection.getMetaData().getPrimaryKeys(null, null, "t")) {
        assertTrue(keys.next());
        assertEquals("id", keys.getString("COLUMN_NAME"));
        assertEquals(1, keys.getInt("KEY_SEQ"));
      }
      try (Statement statement = connection.createStatement();
          ResultSet empty = statement.executeQuery("SELECT * FROM t WHERE 1=0")) {
        assertEquals("id", empty.getMetaData().getColumnName(1));
        assertEquals("INTEGER", empty.getMetaData().getColumnTypeName(1));
        assertTrue(empty.getMetaData().isAutoIncrement(1));
        assertEquals(ResultSetMetaData.columnNoNulls, empty.getMetaData().isNullable(1));
      }
      try (Statement statement = connection.createStatement();
          ResultSet mode = statement.executeQuery("PRAGMA journal_mode")) {
        // No next(): matches xerial / IDServer SqliteSchemaTest.
        assertEquals("delete", mode.getString(1).toLowerCase());
      }
    }
  }

  @Test
  void foreignKeysRejectDanglingRowsWhenEnabled() throws Exception {
    try (Connection connection = memory()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
        statement.executeUpdate("""
            CREATE TABLE child (
              id INTEGER PRIMARY KEY,
              parent_id INTEGER,
              FOREIGN KEY (parent_id) REFERENCES parent (id))
            """);
        statement.executeUpdate("INSERT INTO child (id, parent_id) VALUES (1, 9)");
        statement.executeUpdate("DELETE FROM child");
        try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys=ON")) {
          assertTrue(result.next());
          assertEquals(1, result.getInt(1));
        }
        assertThrows(
            SQLException.class,
            () -> statement.executeUpdate("INSERT INTO child (id, parent_id) VALUES (1, 9)"));
        statement.executeUpdate("INSERT INTO parent (id) VALUES (1)");
        statement.executeUpdate("INSERT INTO child (id, parent_id) VALUES (1, 1)");
        assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM parent"));
        try (ResultSet result = statement.executeQuery("SELECT id FROM parent")) {
          assertTrue(result.next());
          assertEquals(1, result.getInt(1));
        }
        try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys=OFF")) {
          assertTrue(result.next());
          assertEquals(0, result.getInt(1));
        }
        statement.executeUpdate("DELETE FROM parent");
        statement.executeUpdate("INSERT INTO child (id, parent_id) VALUES (2, 9)");
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM parent")) {
          assertTrue(result.next());
          assertEquals(0, result.getInt(1));
        }
      }
    }
  }

  @Test
  void existingFileAcceptsWritesAndABadWalFileRefusesToOpen() throws Exception {
    Path sample = Path.of("src/test/resources/sample.sqlite").toAbsolutePath();
    byte[] before = Files.readAllBytes(sample);
    Path writable = Files.createTempFile("jsqlite-write", ".sqlite");
    Files.copy(sample, writable, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + writable)) {
      try (Statement statement = connection.createStatement()) {
        try (ResultSet result = statement.executeQuery("SELECT Id FROM Users ORDER BY Id")) {
          assertTrue(result.next());
          assertEquals("a", result.getString(1));
        }
        assertEquals(1, statement.executeUpdate("DELETE FROM Users WHERE Id = 'b'"));
      }
    }
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + writable);
        Statement statement = again.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM Users")) {
      assertTrue(result.next());
      assertEquals(1, result.getInt(1));
    }
    assertArrayEquals(before, Files.readAllBytes(sample));
    Files.deleteIfExists(writable);

    Path copy = Files.createTempFile("jsqlite-wal", ".sqlite");
    Files.copy(sample, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Path wal = Path.of(copy.toString() + "-wal");
    Files.writeString(wal, "not a real wal");
    byte[] copied = Files.readAllBytes(copy);
    try {
      SQLException refused = assertThrows(
          SQLException.class, () -> DriverManager.getConnection("jdbc:jsqlite:" + copy));
      assertTrue(refused.getMessage().contains("WAL"));
      assertArrayEquals(copied, Files.readAllBytes(copy));
    } finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(copy);
    }
  }

  private static Connection memory() throws SQLException {
    Path file = Path.of(System.getProperty("java.io.tmpdir"), "jsqlite-mem-" + System.nanoTime() + ".sqlite");
    return DriverManager.getConnection("jdbc:jsqlite:" + file);
  }

  private static Instant instant(String text, int nanos) {
    return LocalDateTime.parse(text.replace(" ", "T")).withNano(nanos).toInstant(ZoneOffset.UTC);
  }
}
