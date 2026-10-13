package com.myano.jsqlite.format;

import com.myano.jsqlite.Database;
import com.myano.jsqlite.Row;
import com.myano.jsqlite.SqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteReaderTest {
  @Test
  void readsTablesIndexesOverflowAndLeavesTheFileUntouched() throws Exception {
    Path file = Path.of("src/test/resources/sample.sqlite");
    byte[] before = Files.readAllBytes(file);
    try (Database db = Database.open(file)) {
      assertFalse(db.readOnly());
      assertEquals(List.of("a", "b"), names(db.query("SELECT Id FROM Users ORDER BY Id")));
      assertEquals("Ann", db.query("SELECT UserName FROM Users WHERE NormalizedUserName = 'ann'").get(0).values().get(0));
      assertNull(db.query("SELECT NormalizedUserName FROM Users WHERE Id = 'b'").get(0).values().get(0));
      assertEquals(List.of(1L, 3L), longs(db.query("SELECT Id FROM UserClaims ORDER BY Id")));
      assertEquals(3L, db.query("SELECT seq FROM sqlite_sequence WHERE name = 'UserClaims'").get(0).values().get(0));
      assertEquals(-5L, db.query("SELECT n FROM nums WHERE s = 'it''s'").get(0).values().get(0));
      assertEquals(3_000_000_000L, db.query("SELECT n FROM nums WHERE s = 'wide'").get(0).values().get(0));
      String body = (String) db.query("SELECT body FROM big WHERE id = 1").get(0).values().get(0);
      assertEquals(4000, body.length());
      assertTrue(body.startsWith("測"));
    }
    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));

    Path copy = Files.createTempFile("jsqlite-sample", ".sqlite");
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
    try (Database db = Database.open(copy)) {
      db.execute("INSERT INTO Users VALUES ('c', 'C', 'c')");
      assertEquals(List.of("a", "b", "c"), names(db.query("SELECT Id FROM Users ORDER BY Id")));
    }
    try (Database again = Database.open(copy)) {
      assertEquals(List.of("a", "b", "c"), names(again.query("SELECT Id FROM Users ORDER BY Id")));
    }
    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
    Files.deleteIfExists(copy);
  }

  @Test
  void badWalFileIsRejected() throws Exception {
    Path file = Files.createTempFile("jsqlite-wal", ".sqlite");
    Files.copy(Path.of("src/test/resources/sample.sqlite"), file, StandardCopyOption.REPLACE_EXISTING);
    Path wal = Path.of(file.toString() + "-wal");
    Files.writeString(wal, "not a real wal");
    byte[] before = Files.readAllBytes(file);
    try {
      SqlException refused = assertThrows(SqlException.class, () -> Database.open(file));
      assertTrue(refused.getMessage().contains("WAL"));
      assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
    } finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(file);
    }
  }

  @Test
  void readsTheIdentityDatabaseWhenPresent() throws Exception {
    Path file = Path.of("C:/GzMian/IDServer/dist/data/skoruba4j.sqlite");
    Assumptions.assumeTrue(Files.isRegularFile(file));
    byte[] before = Files.readAllBytes(file);
    try (Database db = Database.open(file)) {
      assertEquals(910L, count(db, "Users"));
      assertEquals("Admin", db.query("SELECT Name FROM Roles WHERE Name = 'Admin'").get(0).values().get(0));
      assertEquals(421L, count(db, "UserClaims"));
      assertEquals(0L, count(db, "UserClaims", "SELECT COUNT(*) FROM UserClaims WHERE Id = 2"));
      assertEquals(631L, db.query("SELECT seq FROM sqlite_sequence WHERE name = 'UserClaims'").get(0).values().get(0));
      assertEquals(16L, count(db, "Clients"));
    }
    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));

    Path copy = Files.createTempFile("jsqlite-identity", ".sqlite");
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
    try (Database db = Database.open(copy)) {
      db.execute("DELETE FROM Roles WHERE Name = 'Admin'");
      assertEquals(3L, count(db, "Roles"));
    }
    try (Database again = Database.open(copy)) {
      assertEquals(3L, count(again, "Roles"));
    }
    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
    Files.deleteIfExists(copy);
  }

  private static long count(Database db, String table) {
    return count(db, table, "SELECT COUNT(*) FROM \"" + table + "\"");
  }

  private static long count(Database db, String table, String sql) {
    return (Long) db.query(sql).get(0).values().get(0);
  }

  private static List<Object> names(List<Row> rows) {
    return rows.stream().map(row -> row.values().get(0)).toList();
  }

  private static List<Object> longs(List<Row> rows) {
    return names(rows);
  }
}
