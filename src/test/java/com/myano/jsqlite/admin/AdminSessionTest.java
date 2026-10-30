package com.myano.jsqlite.admin;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.TableInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSessionTest {
  @Test
  void createsADatabaseAndListsTablesInCreationOrder() {
    try (AdminSession session = new AdminSession()) {
      assertThrows(IllegalStateException.class, session::tables);
      session.create(Path.of("main"));
      assertEquals(Path.of("main"), session.path());
      assertTrue(session.tables().isEmpty());

      session.run("""
          CREATE TABLE note (
            id INTEGER,
            body TEXT
          )
          """);
      session.run("CREATE TABLE tag (name TEXT)");
      List<TableInfo> tables = session.tables();
      assertEquals(List.of("note", "tag"), tables.stream().map(TableInfo::name).toList());
      assertEquals(ColumnType.INTEGER, tables.get(0).columns().get(0).type());
      assertEquals("body", tables.get(0).columns().get(1).name());
      assertEquals(0, tables.get(0).rowCount());
    }
  }

  @Test
  void runsQueriesAndKeepsHeadersWhenNoRowMatches() {
    try (AdminSession session = new AdminSession()) {
      session.create(Path.of("main"));
      session.run("CREATE TABLE note (id INTEGER, body TEXT)");
      ScriptResult empty = session.run("SELECT body, id FROM note");
      assertEquals(ScriptResult.Kind.QUERY, empty.kind());
      assertEquals(List.of("body", "id"), empty.columns());
      assertTrue(empty.rows().isEmpty());

      assertEquals(ScriptResult.Kind.EXECUTE, session.run("INSERT INTO note VALUES (1, 'hello')").kind());
      ScriptResult rows = session.run("SELECT * FROM note WHERE id = 1");
      assertEquals(List.of("id", "body"), rows.columns());
      assertEquals(1L, rows.rows().get(0).values().get(0));
      assertEquals("hello", rows.rows().get(0).values().get(1));
      assertEquals(1, session.tables().get(0).rowCount());

      ScriptResult missed = session.run("SELECT body, id FROM note WHERE id = 2");
      assertEquals(List.of("body", "id"), missed.columns());
      assertTrue(missed.rows().isEmpty());
    }
  }

  @Test
  void failedStatementLeavesThePreviousRows() {
    try (AdminSession session = new AdminSession()) {
      session.create(Path.of("main"));
      session.run("CREATE TABLE note (id INTEGER, body TEXT)");
      session.run("INSERT INTO note VALUES (1, 'ok')");
      assertThrows(SqlException.class, () -> session.run("INSERT INTO note VALUES ('x', 'y')"));
      assertEquals(1, session.tables().get(0).rowCount());
      assertEquals(1, session.run("SELECT * FROM note").rows().size());
    }
  }

  @Test
  void aNewDatabaseDoesNotKeepThePreviousTables() {
    try (AdminSession session = new AdminSession()) {
      session.create(Path.of("first"));
      session.run("CREATE TABLE note (id INTEGER)");
      session.create(Path.of("second"));
      assertEquals(Path.of("second"), session.path());
      assertTrue(session.tables().isEmpty());
      session.closeDatabase();
      assertThrows(IllegalStateException.class, session::path);
    }
  }

  @Test
  void formCreatesATableAndInsertsValues() {
    try (AdminSession session = new AdminSession()) {
      session.create(Path.of("main"));
      String create = session.createTable("note", List.of(
          new Column("id", ColumnType.INTEGER),
          new Column("body", ColumnType.TEXT)));
      assertEquals("CREATE TABLE note (id INTEGER, body TEXT)", create);

      String insert = session.insertRow("note", List.of(1L, "hello"));
      assertEquals("INSERT INTO note VALUES (1, 'hello')", insert);
      session.insertRow("note", Arrays.asList(null, "a'b"));

      ScriptResult rows = session.run("SELECT * FROM note");
      assertEquals(2, rows.rows().size());
      assertEquals(1L, rows.rows().get(0).values().get(0));
      assertEquals("hello", rows.rows().get(0).values().get(1));
      assertNull(rows.rows().get(1).values().get(0));
      assertEquals("a'b", rows.rows().get(1).values().get(1));
      assertEquals(2, session.tables().get(0).rowCount());

      assertThrows(SqlException.class, () -> session.createTable("table", List.of(
          new Column("id", ColumnType.INTEGER))));
      assertThrows(SqlException.class, () -> session.createTable("t", List.of(
          new Column("id", ColumnType.INTEGER),
          new Column("id", ColumnType.TEXT))));
      assertThrows(SqlException.class, () -> session.insertRow("note", List.of("x", "y")));
      assertEquals(2, session.tables().get(0).rowCount());
    }
  }

  @Test
  void reopenKeepsTablesCreatedInThisSession() {
    try (AdminSession session = new AdminSession()) {
      Path path = Path.of("main");
      session.create(path);
      session.run("CREATE TABLE note (id INTEGER)");
      session.closeDatabase();
      assertThrows(IllegalStateException.class, session::tables);

      session.open(path);
      assertEquals(path, session.path());
      assertEquals(List.of("note"), session.tables().stream().map(TableInfo::name).toList());
      assertThrows(SqlException.class, () -> session.create(path));
      assertEquals(List.of("note"), session.tables().stream().map(TableInfo::name).toList());
    }
  }

  @Test
  void openingAMissingDatabaseLeavesTheCurrentOne() {
    try (AdminSession session = new AdminSession()) {
      session.create(Path.of("main"));
      session.run("CREATE TABLE note (id INTEGER)");
      SqlException missing = assertThrows(SqlException.class, () -> session.open(Path.of("missing-db-name")));
      assertTrue(missing.getMessage().contains("找不到資料庫"));
      assertEquals(Path.of("main"), session.path());
      assertEquals(1, session.tables().size());
    }
  }

  @Test
  void settingsPragmasCanBeReadAndApplied() {
    try (AdminSession session = new AdminSession()) {
      session.create(Path.of("settings-db"));
      assertTrue(
          "memory".equals(session.journalMode()) || "delete".equals(session.journalMode()),
          session.journalMode());
      session.applySettings("delete", 2_500L, true, false, 50_000L, 80);
      assertEquals("delete", session.journalMode());
      assertEquals(2_500L, session.busyTimeout());
      assertTrue(session.foreignKeys());
      assertTrue(!session.bulkLoad());
      assertEquals(50_000L, session.ratioMinRows());
      assertEquals(80, session.ratioMinRatio());
      ScriptResult mode = session.run("PRAGMA busy_timeout");
      assertEquals(ScriptResult.Kind.QUERY, mode.kind());
      assertEquals(2_500L, mode.rows().get(0).values().get(0));
    }
  }

  @Test
  void rowCountReadsFileBackedTablesThatListAsZero() throws Exception {
    Path file = Files.createTempFile("jsqlite-admin-count", ".sqlite");
    Files.deleteIfExists(file);
    try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:jsqlite:" + file);
        java.sql.Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE note (id INTEGER, body TEXT)");
      statement.executeUpdate("INSERT INTO note VALUES (1, 'a')");
      statement.executeUpdate("INSERT INTO note VALUES (2, 'b')");
    }
    try (AdminSession session = new AdminSession()) {
      session.open(file);
      assertEquals(0, session.tables().get(0).rowCount());
      assertEquals(2, session.rowCount("note"));
      assertEquals(2, session.run("SELECT * FROM note").rows().size());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void sqliteFileOpensReadOnlyAndStaysUntouched() throws Exception {
    Path file = Files.createTempFile("jsqlite-open", ".sqlite");
    byte[] header = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    Files.write(file, header);
    try (AdminSession session = new AdminSession()) {
      SqlException refused = assertThrows(SqlException.class, () -> session.create(file));
      assertTrue(refused.getMessage().contains("請用「打開資料庫」"));
      assertThrows(SqlException.class, () -> session.open(file));
      assertTrue(session.databases().isEmpty());
    } finally {
      assertEquals(header.length, Files.size(file));
      assertTrue(Arrays.equals(header, Files.readAllBytes(file)));
      Files.deleteIfExists(file);
    }
  }

  @Test
  @EnabledIf("hasDisplay")
  void frameConstructsWithoutOpeningADatabase() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      DbAdmin admin = new DbAdmin();
      JFrame frame = admin.frame();
      frame.pack();
      frame.dispose();
    });
  }

  static boolean hasDisplay() {
    return !GraphicsEnvironment.isHeadless();
  }
}
