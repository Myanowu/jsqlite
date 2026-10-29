package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaseAndScalarSubqueryTest {
  @Test
  void searchedCaseWhenThenElse() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, n INTEGER)");
      db.execute("INSERT INTO t VALUES (1, 10)");
      db.execute("INSERT INTO t VALUES (2, 0)");
      List<Row> rows = db.query("""
          SELECT id,
                 CASE WHEN n > 0 THEN 'pos' WHEN n = 0 THEN 'zero' ELSE 'neg' END AS s
          FROM t
          ORDER BY id
          """);
      assertEquals(2, rows.size());
      assertEquals("pos", rows.get(0).values().get(1));
      assertEquals("zero", rows.get(1).values().get(1));
    }
  }

  @Test
  void simpleCaseForOrderBy() {
    try (Database db = open()) {
      db.execute("CREATE TABLE w (id INTEGER, severity TEXT)");
      db.execute("INSERT INTO w VALUES (1, 'yellow')");
      db.execute("INSERT INTO w VALUES (2, 'red')");
      db.execute("INSERT INTO w VALUES (3, 'orange')");
      List<Row> rows = db.query("""
          SELECT id FROM w
          ORDER BY CASE severity WHEN 'red' THEN 0 WHEN 'orange' THEN 1 ELSE 2 END, id
          """);
      assertEquals(List.of(2L, 3L, 1L),
          rows.stream().map(r -> r.values().get(0)).toList());
    }
  }

  @Test
  void caseWithoutElseYieldsNull() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (x TEXT)");
      db.execute("INSERT INTO t VALUES ('a')");
      Object v = db.query("SELECT CASE x WHEN 'b' THEN 1 END FROM t").get(0).values().get(0);
      assertNull(v);
    }
  }

  @Test
  void scalarSubqueryFirstRow() {
    try (Database db = open()) {
      db.execute("CREATE TABLE p (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE e (project_id INTEGER, uploaded_at TEXT)");
      db.execute("INSERT INTO p VALUES (1, 'A')");
      db.execute("INSERT INTO e VALUES (1, '2026-01-01')");
      db.execute("INSERT INTO e VALUES (1, '2026-02-01')");
      Object v = db.query("""
          SELECT name,
                 (SELECT uploaded_at FROM e WHERE project_id = p.id) AS lastUp
          FROM p
          """).get(0).values().get(1);
      // First matching row (scan order), not MAX.
      assertEquals("2026-01-01", v);
    }
  }

  @Test
  void scalarSubqueryMaxAndCount() {
    try (Database db = open()) {
      db.execute("CREATE TABLE p (id INTEGER)");
      db.execute("CREATE TABLE e (project_id INTEGER, uploaded_at TEXT)");
      db.execute("CREATE TABLE w (project_id INTEGER, acknowledged INTEGER)");
      db.execute("INSERT INTO p VALUES (1)");
      db.execute("INSERT INTO e VALUES (1, '2026-01-01')");
      db.execute("INSERT INTO e VALUES (1, '2026-03-01')");
      db.execute("INSERT INTO w VALUES (1, 0)");
      db.execute("INSERT INTO w VALUES (1, 0)");
      db.execute("INSERT INTO w VALUES (1, 1)");
      Row row = db.query("""
          SELECT
            (SELECT MAX(uploaded_at) FROM e WHERE project_id = p.id) AS lastUp,
            (SELECT COUNT(*) FROM w WHERE project_id = p.id AND acknowledged = 0) AS openWarn
          FROM p
          """).get(0);
      assertEquals("2026-03-01", row.values().get(0));
      assertEquals(2L, row.values().get(1));
    }
  }

  @Test
  void scalarSubqueryEmptyIsNullOrZero() {
    try (Database db = open()) {
      db.execute("CREATE TABLE p (id INTEGER)");
      db.execute("CREATE TABLE e (project_id INTEGER, uploaded_at TEXT)");
      db.execute("CREATE TABLE w (project_id INTEGER)");
      db.execute("INSERT INTO p VALUES (1)");
      Row row = db.query("""
          SELECT
            (SELECT uploaded_at FROM e WHERE project_id = p.id) AS lastUp,
            (SELECT COUNT(*) FROM w WHERE project_id = p.id) AS cnt
          FROM p
          """).get(0);
      assertNull(row.values().get(0));
      assertEquals(0L, row.values().get(1));
    }
  }

  @Test
  void selectTopLimitsOuterRows() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (3, 'c')");
      db.execute("INSERT INTO t VALUES (2, 'b')");
      List<Row> rows = db.query("SELECT TOP 1 name FROM t ORDER BY id DESC");
      assertEquals(1, rows.size());
      assertEquals("c", rows.get(0).values().get(0));
      List<Row> paren = db.query("SELECT TOP (2) id FROM t ORDER BY id");
      assertEquals(List.of(1L, 2L), paren.stream().map(r -> r.values().get(0)).toList());
    }
  }

  @Test
  void scalarTopOneHonorsOrderBy() {
    try (Database db = open()) {
      db.execute("CREATE TABLE c (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE n (cust INTEGER, seq INTEGER, name TEXT)");
      db.execute("INSERT INTO c VALUES (1, 'acme')");
      db.execute("INSERT INTO n VALUES (1, 2, 'second')");
      db.execute("INSERT INTO n VALUES (1, 1, 'first')");
      db.execute("INSERT INTO n VALUES (1, NULL, 'none')");
      Row row = db.query("""
          SELECT c.name,
                 (SELECT TOP 1 n.name FROM n WHERE n.cust = c.id ORDER BY n.seq) AS contact
          FROM c
          """).get(0);
      assertEquals("acme", row.values().get(0));
      assertEquals("none", row.values().get(1));
      Row desc = db.query("""
          SELECT (SELECT TOP 1 n.name FROM n WHERE n.cust = c.id ORDER BY n.seq DESC)
          FROM c
          """).get(0);
      assertEquals("second", desc.values().get(0));
    }
  }

  @Test
  void scalarSubqueryRejectsMultiColumn() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");
      assertThrows(SqlException.class,
          () -> db.query("SELECT (SELECT id, id FROM t) FROM t"));
    }
  }

  private static Database open() {
    return Database.open(Path.of("case-scalar"));
  }
}
