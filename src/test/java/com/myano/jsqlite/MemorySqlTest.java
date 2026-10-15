package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySqlTest {
  @Test
  void closedDatabaseRejectsExecute() {
    Database db = Database.open(Path.of("mem"));
    db.close();
    assertThrows(IllegalStateException.class, () -> db.execute("CREATE TABLE t (id INTEGER)"));
    assertThrows(IllegalStateException.class, () -> db.query("SELECT * FROM t"));
  }

  @Test
  void whereFiltersToOneRow() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (2, 'b')");
      db.execute("INSERT INTO t VALUES (3, 'c')");

      List<Row> all = db.query("SELECT id FROM t");
      assertEquals(List.of(1L, 2L, 3L), values(all));

      List<Row> matched = db.query("SELECT * FROM t WHERE id = 2");
      assertEquals(1, matched.size());
      assertEquals(List.of("id", "name"), matched.get(0).columns());
      assertEquals(2L, matched.get(0).values().get(0));
      assertEquals("b", matched.get(0).values().get(1));

      assertEquals(1, db.query("SELECT name FROM t WHERE id > 1 AND id < 3").size());
      assertTrue(db.query("SELECT name FROM t WHERE id > 1 AND name = 'a'").isEmpty());
      assertEquals(2, db.query("SELECT id FROM t WHERE name < 'c'").size());
    }
  }

  @Test
  void selectListUsesWrittenColumnOrder() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (a INTEGER, b TEXT, c INTEGER)");
      db.execute("INSERT INTO t VALUES (1, 'x', 3)");
      db.execute("INSERT INTO t VALUES (4, 'y', 6)");
      Row row = db.query("SELECT c, a, b FROM t WHERE a = 4").get(0);
      assertEquals(List.of("c", "a", "b"), row.columns());
      assertEquals(List.of(6L, 4L, "y"), row.values());
      assertEquals(List.of("a", "b", "c"), db.query("SELECT * FROM t").get(0).columns());
    }
  }

  @Test
  void badInsertLeavesNoPartialRow() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'ok')");

      SqlException unknownTable = assertThrows(
          SqlException.class, () -> db.execute("INSERT INTO missing VALUES (1, 'x')"));
      assertTrue(unknownTable.getMessage().contains("INSERT INTO missing"));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t (nope) VALUES (1)"));
      assertThrows(SqlException.class, () -> db.query("SELECT nope FROM t"));
      assertThrows(SqlException.class, () -> db.query("SELECT * FROM t WHERE nope = 1"));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES ('x', 'y')"));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (2, 3)"));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (4)"));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (5, 'z', 6)"));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t (id, id) VALUES (7, 8)"));
      assertThrows(SqlException.class, () -> db.execute("CREATE TABLE t (id INTEGER, name TEXT)"));
      assertThrows(SqlException.class, () -> db.execute("CREATE TABLE d (a INTEGER, a TEXT)"));

      List<Row> rows = db.query("SELECT * FROM t");
      assertEquals(1, rows.size());
      assertEquals(List.of(1L, "ok"), rows.get(0).values());
    }
  }

  @Test
  void tablesAreIsolated() {
    try (Database db = open()) {
      db.execute("CREATE TABLE a (n INTEGER)");
      db.execute("CREATE TABLE b (n INTEGER)");
      db.execute("INSERT INTO a VALUES (1)");
      db.execute("INSERT INTO a VALUES (2)");
      db.execute("INSERT INTO b VALUES (9)");
      assertEquals(List.of(1L, 2L), values(db.query("SELECT * FROM a")));
      assertEquals(List.of(9L), values(db.query("SELECT n FROM b")));
      db.execute("DELETE FROM a");
      assertTrue(db.query("SELECT * FROM a").isEmpty());
      assertEquals(List.of(9L), values(db.query("SELECT * FROM b")));
    }
  }

  @Test
  void updateRespectsWhere() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (2, 'b')");
      db.execute("INSERT INTO t VALUES (3, 'c')");
      db.execute("UPDATE t SET name = 'z' WHERE id = 2");
      assertEquals(List.of("a", "z", "c"), texts(db, "SELECT name FROM t"));
      db.execute("UPDATE t SET name = 'all'");
      assertEquals(List.of("all", "all", "all"), texts(db, "SELECT name FROM t"));
      assertThrows(SqlException.class, () -> db.execute("UPDATE t SET id = 'no' WHERE id = 1"));
      assertEquals(List.of(1L, 2L, 3L), values(db.query("SELECT id FROM t")));
    }
  }

  @Test
  void deleteThenSelectCountsRows() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");
      db.execute("INSERT INTO t VALUES (2)");
      db.execute("INSERT INTO t VALUES (3)");
      db.execute("DELETE FROM t WHERE id = 2");
      assertEquals(List.of(1L, 3L), values(db.query("SELECT id FROM t")));
      db.execute("DELETE FROM t WHERE id = 1");
      assertEquals(List.of(3L), values(db.query("SELECT id FROM t")));
      db.execute("DELETE FROM t");
      assertTrue(db.query("SELECT id FROM t").isEmpty());
    }
  }

  @Test
  void nullComparisonMatchesNothing() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (a INTEGER, b TEXT)");
      db.execute("INSERT INTO t VALUES (NULL, NULL)");
      db.execute("INSERT INTO t VALUES (1, 'x')");
      assertTrue(db.query("SELECT * FROM t WHERE a = NULL").isEmpty());
      assertTrue(db.query("SELECT * FROM t WHERE b = NULL").isEmpty());
      assertTrue(db.query("SELECT * FROM t WHERE a > NULL").isEmpty());
      assertEquals(List.of(1L), values(db.query("SELECT a FROM t WHERE a = 1")));
      assertEquals(List.of(1L), values(db.query("SELECT a FROM t WHERE a < 2")));

      Row stored = db.query("SELECT a, b FROM t").get(0);
      assertNull(stored.values().get(0));
      assertNull(stored.values().get(1));

      db.execute("UPDATE t SET b = NULL WHERE a = 1");
      assertNull(db.query("SELECT b FROM t WHERE a = 1").get(0).values().get(0));
    }
  }

  @Test
  void rejectsTwoStatements() {
    try (Database db = open()) {
      SqlException rejected = assertThrows(
          SqlException.class, () -> db.execute("CREATE TABLE t (id INTEGER); INSERT INTO t VALUES (1)"));
      assertTrue(rejected.getMessage().contains("CREATE TABLE t"));
      assertThrows(SqlException.class, () -> db.query("SELECT * FROM t"));

      db.execute("CREATE TABLE t (id INTEGER);");
      db.execute("INSERT INTO t VALUES (1);");
      assertEquals(1, db.query("SELECT * FROM t;").size());
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (2); INSERT INTO t VALUES (3)"));
      assertThrows(SqlException.class, () -> db.execute("CREATE TABLE u (id INTEGER);;"));
      assertEquals(List.of(1L), values(db.query("SELECT * FROM t")));
      assertThrows(SqlException.class, () -> db.query("SELECT * FROM u"));
    }
  }

  @Test
  void namesKeepCreateSpellingAndLookupIsCaseInsensitive() {
    try (Database db = open()) {
      db.execute("create table Foo (Id integer)");
      db.execute("insert into Foo values (1)");
      assertEquals(List.of(1L), values(db.query("select Id from Foo")));
      assertEquals(List.of(1L), values(db.query("select Id from foo")));
      Row lower = db.query("select id from Foo").get(0);
      assertEquals(List.of("id"), lower.columns());
      assertEquals(List.of(1L), lower.values());
      assertEquals(List.of("Id"), db.query("select * from foo").get(0).columns());
      assertThrows(SqlException.class, () -> db.execute("create table select (id integer)"));
    }
  }

  @Test
  void omittedColumnsAreNullAndNamedInsertMapsByName() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t (id) VALUES (7)");
      db.execute("INSERT INTO t (name, id) VALUES ('a', 8)");
      List<Row> rows = db.query("SELECT id, name FROM t");
      assertEquals(7L, rows.get(0).values().get(0));
      assertNull(rows.get(0).values().get(1));
      assertEquals(8L, rows.get(1).values().get(0));
      assertEquals("a", rows.get(1).values().get(1));
    }
  }

  @Test
  void roundTripsTextAndWideIntegers() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (n INTEGER, s TEXT)");
      db.execute("INSERT INTO t VALUES (-5, 'it''s')");
      db.execute("INSERT INTO t VALUES (3000000000, 'ok')");
      List<Row> rows = db.query("SELECT n, s FROM t");
      assertEquals(-5L, rows.get(0).values().get(0));
      assertEquals("it's", rows.get(0).values().get(1));
      assertEquals(3_000_000_000L, rows.get(1).values().get(0));
      assertEquals("ok", rows.get(1).values().get(1));
    }
  }

  @Test
  void queryAndExecuteRejectTheWrongStatementKind() {
    try (Database db = open()) {
      SqlException select = assertThrows(SqlException.class, () -> db.execute("SELECT * FROM t"));
      assertTrue(select.getMessage().contains("query"));
      db.execute("CREATE TABLE t (id INTEGER)");
      SqlException insert = assertThrows(SqlException.class, () -> db.query("INSERT INTO t VALUES (1)"));
      assertTrue(insert.getMessage().contains("SELECT"));
      assertTrue(db.query("SELECT * FROM t").isEmpty());
    }
  }

  @Test
  void eachOpenHasItsOwnMemory() {
    Path path = Path.of("same");
    try (Database first = Database.open(path)) {
      first.execute("CREATE TABLE t (id INTEGER)");
      first.execute("INSERT INTO t VALUES (1)");
      try (Database second = Database.open(path)) {
        assertThrows(SqlException.class, () -> second.query("SELECT * FROM t"));
      }
      assertEquals(List.of(1L), values(first.query("SELECT * FROM t")));
    }
  }

  @Test
  void inSubqueryFiltersRows() {
    try (Database db = open()) {
      db.execute("CREATE TABLE users (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE roles (userId INTEGER, roleId INTEGER)");
      db.execute("INSERT INTO users VALUES (1, 'a')");
      db.execute("INSERT INTO users VALUES (2, 'b')");
      db.execute("INSERT INTO users VALUES (3, 'c')");
      db.execute("INSERT INTO roles VALUES (1, 10)");
      db.execute("INSERT INTO roles VALUES (3, 10)");
      db.execute("INSERT INTO roles VALUES (2, 20)");

      assertEquals(
          List.of(1L, 3L),
          values(db.query("SELECT id FROM users WHERE id IN (SELECT userId FROM roles WHERE roleId = 10)")));
      assertEquals(
          List.of(2L),
          values(db.query("SELECT id FROM users WHERE id NOT IN (SELECT userId FROM roles WHERE roleId = 10)")));
      assertEquals(
          3,
          db.query("SELECT userId FROM roles WHERE userId IN (SELECT id FROM users)").size());
    }
  }

  @Test
  void keywordColumnGroupByAndCountDistinct() {
    try (Database db = open()) {
      db.execute("CREATE TABLE message (id INTEGER, datetime TEXT)");
      db.execute("INSERT INTO message (id, datetime) VALUES (1, '2026-10-15 09:21:02')");
      Row message = db.query(
          "SELECT messageent0_.id, messageent0_.datetime FROM message messageent0_").get(0);
      assertEquals(List.of(1L, "2026-10-15 09:21:02"), message.values());

      db.execute("CREATE TABLE log (username TEXT, tname TEXT, type INTEGER)");
      db.execute("INSERT INTO log VALUES ('a', 'r1', 1)");
      db.execute("INSERT INTO log VALUES ('a', 'r1', 1)");
      db.execute("INSERT INTO log VALUES ('a', 'r2', 1)");
      db.execute("INSERT INTO log VALUES ('b', 'r1', 2)");
      db.execute("INSERT INTO log VALUES ('c', 'r9', 9)");
      db.execute("INSERT INTO log VALUES ('d', NULL, 1)");

      List<Row> grouped = db.query(
          "SELECT username, tname FROM log WHERE type IN (1 , 2) GROUP BY username, tname");
      assertEquals(4, grouped.size());
      assertEquals(List.of("a", "r1"), grouped.get(0).values());
      assertEquals(List.of("a", "r2"), grouped.get(1).values());
      assertEquals(List.of("b", "r1"), grouped.get(2).values());
      assertEquals("d", grouped.get(3).values().get(0));
      assertNull(grouped.get(3).values().get(1));

      assertEquals(2L, db.query(
          "SELECT count(distinct tname) FROM log WHERE type IN (1, 2)").get(0).values().get(0));
      assertEquals(5L, db.query("SELECT count(tname) FROM log").get(0).values().get(0));
      assertEquals(6L, db.query("SELECT count(*) FROM log").get(0).values().get(0));
    }
  }

  private static Database open() {
    return Database.open(Path.of("mem"));
  }

  private static List<Object> values(List<Row> rows) {
    List<Object> values = new ArrayList<>();
    for (Row row : rows) {
      values.add(row.values().get(0));
    }
    return values;
  }

  private static List<String> texts(Database db, String sql) {
    List<String> texts = new ArrayList<>();
    for (Row row : db.query(sql)) {
      texts.add((String) row.values().get(0));
    }
    return texts;
  }
}
