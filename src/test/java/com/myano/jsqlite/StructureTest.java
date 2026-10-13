package com.myano.jsqlite;

import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureTest {
  @Test
  void renamesColumnsAndConvertsValuesThatFit() {
    try (Database db = Database.open(Path.of("main"))) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("INSERT INTO t VALUES (3, '12')");
      db.redefineTable(
          "t",
          "people",
          List.of(
              new Column("userId", ColumnType.INTEGER, true, true, false, false, false, null),
              new Column("code", ColumnType.INTEGER, false, false, false, false, false, null)),
          new int[] {0, 1},
          List.of("userId"),
          "ALTER");
      assertEquals("people", db.tables().get(0).name());
      assertEquals(List.of("userId"), db.tables().get(0).primaryKey());
      assertEquals(12L, db.query("SELECT code FROM people WHERE userId = 3").get(0).values().get(0));
      db.execute("INSERT INTO people (code) VALUES (4)");
      assertEquals(4L, db.query("SELECT userId FROM people WHERE code = 4").get(0).values().get(0));
    }
  }

  @Test
  void rejectedChangeLeavesTheTableUntouched() {
    try (Database db = Database.open(Path.of("main"))) {
      db.execute("CREATE TABLE t (name TEXT)");
      db.execute("INSERT INTO t VALUES ('a')");
      db.execute("INSERT INTO t VALUES ('a')");
      SqlException mismatch = assertThrows(SqlException.class, () -> db.redefineTable(
          "t",
          "t",
          List.of(new Column("name", ColumnType.INTEGER, false, false, false, false, false, null)),
          new int[] {0},
          List.of(),
          "ALTER"));
      assertTrue(mismatch.getMessage().contains("type mismatch"));
      assertEquals(2, db.query("SELECT name FROM t").size());
      assertEquals("a", db.query("SELECT name FROM t").get(0).values().get(0));

      SqlException unique = assertThrows(SqlException.class, () -> db.redefineTable(
          "t",
          "t",
          List.of(new Column("name", ColumnType.TEXT, false, false, false, true, false, null)),
          new int[] {0},
          List.of(),
          "ALTER"));
      assertTrue(unique.getMessage().contains("unique"));
      assertEquals(2, db.tables().get(0).rowCount());
    }
  }

  @Test
  void keepsCompositeKeysIndexesAndNewColumns() {
    try (Database db = Database.open(Path.of("main"))) {
      db.execute("""
          CREATE TABLE link (
            userId TEXT,
            roleId TEXT,
            note TEXT,
            PRIMARY KEY (userId, roleId)
          )
          """);
      db.execute("CREATE UNIQUE INDEX noteIndex ON link (note) WHERE note IS NOT NULL");
      db.execute("INSERT INTO link VALUES ('u', 'r', 'hello')");
      db.redefineTable(
          "link",
          "link",
          List.of(
              new Column("userId", ColumnType.TEXT, true, false, false, false, false, null),
              new Column("roleId", ColumnType.TEXT, true, false, false, false, false, null),
              new Column("comment", ColumnType.TEXT, false, false, false, false, false, null),
              new Column("city", ColumnType.TEXT, false, false, false, false, true, "macau")),
          new int[] {0, 1, 2, -1},
          List.of("userId", "roleId"),
          "ALTER");
      assertEquals(List.of("userId", "roleId"), db.tables().get(0).primaryKey());
      assertEquals("hello", db.query("SELECT comment FROM link").get(0).values().get(0));
      assertEquals("macau", db.query("SELECT city FROM link").get(0).values().get(0));
      assertEquals("comment", db.indexes().get(0).columns().get(0));
      assertEquals("comment", db.indexes().get(0).partialColumn());
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO link VALUES ('u', 'r', 'x', 'y')"));
    }
  }

  @Test
  void structureChangesPersistOnACopyAndLeaveTheOriginal() throws Exception {
    Path original = Path.of("src/test/resources/sample.sqlite");
    byte[] before = Files.readAllBytes(original);
    Path copy = Files.createTempFile("jsqlite-structure", ".sqlite");
    Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
    try (Database db = Database.open(copy)) {
      var users = db.tables().stream().filter(table -> table.name().equals("Users")).findFirst().orElseThrow();
      db.redefineTable(
          "Users",
          "Users",
          users.columns(),
          new int[] {0, 1, 2},
          List.of("Id"),
          "CREATE TABLE Users (Id TEXT PRIMARY KEY, UserName TEXT, NormalizedUserName TEXT)");
      assertEquals(2L, db.query("SELECT COUNT(*) FROM Users").get(0).values().get(0));
    }
    try (Database again = Database.open(copy)) {
      assertEquals(2L, again.query("SELECT COUNT(*) FROM Users").get(0).values().get(0));
    }
    assertArrayEquals(before, Files.readAllBytes(original));
    Files.deleteIfExists(copy);
  }

  @Test
  void editsOneMatchingRowOnACopyAndLeavesTheOriginal() throws Exception {
    Path original = Path.of("src/test/resources/sample.sqlite");
    byte[] before = Files.readAllBytes(original);
    Path copy = Files.createTempFile("jsqlite-edit", ".sqlite");
    Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
    try (Database db = Database.open(copy)) {
      List<Object> ann = db.query("SELECT * FROM Users WHERE Id = 'a'").get(0).values();
      List<Object> renamed = List.of("a", "Annabel", ann.get(2));
      db.updateRow("Users", ann, renamed);
      assertEquals("Annabel", db.query("SELECT UserName FROM Users WHERE Id = 'a'").get(0).values().get(0));
      db.execute("INSERT INTO nums (n, s) VALUES (1, 'same')");
      db.execute("INSERT INTO nums (n, s) VALUES (1, 'same')");
      db.deleteRows("nums", List.of(List.of(1L, "same")));
      assertEquals(1L, db.query("SELECT COUNT(*) FROM nums WHERE s = 'same'").get(0).values().get(0));
    }
    try (Database again = Database.open(copy)) {
      assertEquals("Annabel", again.query("SELECT UserName FROM Users WHERE Id = 'a'").get(0).values().get(0));
    }
    assertArrayEquals(before, Files.readAllBytes(original));
    Files.deleteIfExists(copy);
  }
}
