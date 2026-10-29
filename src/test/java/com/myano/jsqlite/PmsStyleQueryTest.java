package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Mirrors PMS project list + attention ORDER BY patterns. */
class PmsStyleQueryTest {
  @Test
  void projectListWithJoinAndScalarSubqueries() {
    try (Database db = Database.open(Path.of("pms-style"))) {
      db.execute("CREATE TABLE fund_sources (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE users (id INTEGER, display_name TEXT)");
      db.execute("""
          CREATE TABLE projects (
            id INTEGER, name TEXT, status TEXT, center TEXT,
            fund_source_id INTEGER, pm_user_id INTEGER, ic_user_id INTEGER
          )
          """);
      db.execute("CREATE TABLE excel_versions (id INTEGER, project_id INTEGER, uploaded_at TEXT)");
      db.execute("CREATE TABLE warnings (id INTEGER, project_id INTEGER, acknowledged INTEGER, severity TEXT)");
      db.execute("INSERT INTO fund_sources VALUES (1, 'FS')");
      db.execute("INSERT INTO users VALUES (10, 'PM')");
      db.execute("INSERT INTO users VALUES (20, 'IC')");
      db.execute("INSERT INTO projects VALUES (1, 'A', '執行中', 'C1', 1, 10, 20)");
      db.execute("INSERT INTO projects VALUES (2, 'B', '籌備中', 'C1', 1, 10, NULL)");
      db.execute("INSERT INTO excel_versions VALUES (1, 1, '2026-01-01')");
      db.execute("INSERT INTO excel_versions VALUES (2, 1, '2026-03-01')");
      db.execute("INSERT INTO warnings VALUES (1, 1, 0, 'red')");
      db.execute("INSERT INTO warnings VALUES (2, 1, 1, 'yellow')");

      List<Row> rows = db.query("""
          SELECT p.id, p.name,
                 (SELECT MAX(uploaded_at) FROM excel_versions ev WHERE ev.project_id=p.id) AS lastUploadAt,
                 (SELECT COUNT(*) FROM warnings w WHERE w.project_id=p.id AND w.acknowledged=0) AS openWarningCount
          FROM projects p
          JOIN fund_sources fs ON fs.id = p.fund_source_id
          LEFT JOIN users pm ON pm.id = p.pm_user_id
          LEFT JOIN users ic ON ic.id = p.ic_user_id
          ORDER BY p.id DESC
          """);
      assertEquals(2, rows.size());
      assertEquals(2L, rows.get(0).values().get(0));
      assertNull(rows.get(0).values().get(2));
      assertEquals(0L, rows.get(0).values().get(3));
      assertEquals(1L, rows.get(1).values().get(0));
      assertEquals("2026-03-01", rows.get(1).values().get(2));
      assertEquals(1L, rows.get(1).values().get(3));
    }
  }

  /**
   * Nested {@code MAX(id)} must bind to the subquery's excel_versions.id, not outer JOIN ids
   * (projects / fund_sources / users all have {@code id}).
   */
  @Test
  void nestedMaxIdForLatestStageWithOuterJoins() {
    try (Database db = Database.open(Path.of("pms-latest-stage"))) {
      db.execute("CREATE TABLE fund_sources (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE users (id INTEGER, display_name TEXT)");
      db.execute("""
          CREATE TABLE projects (
            id INTEGER, name TEXT, fund_source_id INTEGER, pm_user_id INTEGER, ic_user_id INTEGER
          )
          """);
      db.execute("CREATE TABLE excel_versions (id INTEGER, project_id INTEGER, stage TEXT)");
      db.execute("INSERT INTO fund_sources VALUES (1, 'FS')");
      db.execute("INSERT INTO users VALUES (10, 'PM')");
      db.execute("INSERT INTO users VALUES (20, 'IC')");
      db.execute("INSERT INTO projects VALUES (1, 'A', 1, 10, 20)");
      db.execute("INSERT INTO excel_versions VALUES (1, 1, '投標前')");
      db.execute("INSERT INTO excel_versions VALUES (5, 1, '執行期')");

      List<Row> rows = db.query("""
          SELECT p.id,
                 (SELECT ev2.stage FROM excel_versions ev2 WHERE ev2.id =
                    (SELECT MAX(id) FROM excel_versions WHERE project_id=p.id)) AS latestStage
          FROM projects p
          JOIN fund_sources fs ON fs.id = p.fund_source_id
          LEFT JOIN users pm ON pm.id = p.pm_user_id
          LEFT JOIN users ic ON ic.id = p.ic_user_id
          """);
      assertEquals(1, rows.size());
      assertEquals(1L, rows.get(0).values().get(0));
      assertEquals("執行期", rows.get(0).values().get(1));
    }
  }

  @Test
  void attentionOrderByCaseSeverity() {
    try (Database db = Database.open(Path.of("pms-attn"))) {
      db.execute("CREATE TABLE fund_sources (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE users (id INTEGER, display_name TEXT)");
      db.execute("CREATE TABLE projects (id INTEGER, name TEXT, status TEXT, center TEXT, fund_source_id INTEGER, pm_user_id INTEGER)");
      db.execute("CREATE TABLE warnings (id INTEGER, project_id INTEGER, acknowledged INTEGER, severity TEXT, rule_code TEXT, message TEXT, created_at TEXT)");
      db.execute("INSERT INTO fund_sources VALUES (1, 'FS')");
      db.execute("INSERT INTO users VALUES (10, 'PM')");
      db.execute("INSERT INTO projects VALUES (1, 'A', '執行中', 'C1', 1, 10)");
      db.execute("INSERT INTO warnings VALUES (1, 1, 0, 'yellow', 'W1', 'y', 't1')");
      db.execute("INSERT INTO warnings VALUES (2, 1, 0, 'red', 'W2', 'r', 't2')");
      db.execute("INSERT INTO warnings VALUES (3, 1, 0, 'orange', 'W3', 'o', 't3')");

      List<Row> rows = db.query("""
          SELECT w.id, w.severity
          FROM warnings w
          JOIN projects p ON p.id = w.project_id
          JOIN fund_sources fs ON fs.id = p.fund_source_id
          LEFT JOIN users pm ON pm.id = p.pm_user_id
          WHERE w.acknowledged=0
          ORDER BY CASE w.severity WHEN 'red' THEN 0 WHEN 'orange' THEN 1 ELSE 2 END, w.id DESC
          """);
      assertEquals(List.of(2L, 3L, 1L),
          rows.stream().map(r -> r.values().get(0)).toList());
    }
  }
}
