package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectTest {
  @Test
  void selectLiteralWithoutFromReturnsOneRow() {
    try (Database db = open()) {
      List<Row> rows = db.query("SELECT 1");
      assertEquals(1, rows.size());
      assertEquals(1L, rows.get(0).values().get(0));
    }
  }

  @Test
  void quotedIdentifiersMatchCaseInsensitively() {
    try (Database db = open()) {
      db.execute("CREATE TABLE \"Users\" (\"Id\" INTEGER)");
      db.execute("INSERT INTO users (id) VALUES (1)");
      assertEquals(1L, db.query("SELECT Id FROM \"users\"").get(0).values().get(0));
      assertEquals("Users", db.tables().get(0).name());
      assertEquals("Id", db.tables().get(0).columns().get(0).name());
      db.execute("CREATE TABLE IF NOT EXISTS \"Users\" (\"Id\" INTEGER)");
      assertEquals(1, db.tables().size());
      assertEquals(1, db.query("SELECT * FROM users").size());
    }
  }

  @Test
  void primaryKeysRejectDuplicates() {
    try (Database db = open()) {
      db.execute("CREATE TABLE users (\"Id\" TEXT PRIMARY KEY, name TEXT)");
      db.execute("INSERT INTO users VALUES ('a', 'ann')");
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO users VALUES ('a', 'bee')"));
      assertEquals("ann", db.query("SELECT name FROM users WHERE Id = 'a'").get(0).values().get(0));

      db.execute("CREATE TABLE ur (UserId TEXT, RoleId TEXT, PRIMARY KEY (UserId, RoleId))");
      db.execute("INSERT INTO ur VALUES ('u', 'r')");
      db.execute("INSERT INTO ur VALUES ('u', 's')");
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO ur VALUES ('u', 'r')"));
      assertEquals(2, db.query("SELECT * FROM ur").size());
    }
  }

  @Test
  void partialUniqueIndexAllowsMultipleNulls() {
    try (Database db = open()) {
      db.execute("CREATE TABLE users (id INTEGER, NormalizedUserName TEXT)");
      db.execute("""
          CREATE UNIQUE INDEX ux ON users (NormalizedUserName)
          WHERE NormalizedUserName IS NOT NULL
          """);
      db.execute("INSERT INTO users VALUES (1, NULL)");
      db.execute("INSERT INTO users VALUES (2, NULL)");
      db.execute("INSERT INTO users VALUES (3, 'Admin')");
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO users VALUES (4, 'Admin')"));
      assertEquals(3, db.query("SELECT * FROM users").size());
    }
  }

  @Test
  void inAndNotInFilterRows() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (2, 'b')");
      db.execute("INSERT INTO t VALUES (3, 'c')");
      assertEquals(2, db.query("SELECT id FROM t WHERE name IN ('a', 'c')").size());
      assertEquals(1, db.query("SELECT id FROM t WHERE id NOT IN (1, 3)").size());
      assertEquals(
          2,
          db.query(
                  """
                  SELECT DISTINCT a.name FROM t a INNER JOIN t b ON a.id = b.id
                  WHERE a.name IN ('a', 'b')
                  """)
              .size());
    }
  }

  @Test
  void joinsKeepLeftRowWhenRightMisses() {
    try (Database db = open()) {
      db.execute("CREATE TABLE a (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE b (id INTEGER, aId INTEGER, label TEXT)");
      db.execute("INSERT INTO a VALUES (1, 'ann')");
      db.execute("INSERT INTO a VALUES (2, 'bob')");
      db.execute("INSERT INTO b VALUES (10, 1, 'x')");

      List<Row> inner = db.query("SELECT a.name, b.label FROM a INNER JOIN b ON a.id = b.aId");
      assertEquals(1, inner.size());
      assertEquals("ann", inner.get(0).values().get(0));
      assertEquals("x", inner.get(0).values().get(1));

      List<Row> left = db.query("SELECT a.name, b.label FROM a LEFT JOIN b ON a.id = b.aId");
      assertEquals(2, left.size());
      assertEquals("ann", left.get(0).values().get(0));
      assertEquals("x", left.get(0).values().get(1));
      assertEquals("bob", left.get(1).values().get(0));
      assertNull(left.get(1).values().get(1));
    }
  }

  @Test
  void characterAndVarcharTypesLoadAsText() {
    try (Database db = open()) {
      db.execute(
          "CREATE TABLE SPRING_SESSION (PRIMARY_ID CHARACTER(36) NOT NULL, NAME VARCHAR(200), FLAG BOOLEAN, N BIGINT, CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID))");
      db.execute("INSERT INTO SPRING_SESSION VALUES ('abc', 'x', 1, 9)");
      assertEquals("abc", db.query("SELECT PRIMARY_ID FROM SPRING_SESSION").get(0).values().get(0));
      assertEquals(1L, db.query("SELECT FLAG FROM SPRING_SESSION").get(0).values().get(0));
      assertEquals(9L, db.query("SELECT N FROM SPRING_SESSION").get(0).values().get(0));
      db.execute(
          """
          CREATE TABLE SPRING_SESSION_ATTRIBUTES (
            SESSION_PRIMARY_ID CHAR(36) NOT NULL,
            ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
            ATTRIBUTE_BYTES BLOB NOT NULL,
            CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
            CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
              REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
          )
          """);
      db.execute("INSERT INTO SPRING_SESSION_ATTRIBUTES VALUES ('abc', 'k', 'bytes')");
      assertEquals(
          "k",
          db.query("SELECT ATTRIBUTE_NAME FROM SPRING_SESSION_ATTRIBUTES").get(0).values().get(0));
    }
  }

  @Test
  void distinctJoinWithAliases() {
    try (Database db = open()) {
      db.execute("CREATE TABLE Clients (Id INTEGER PRIMARY KEY, ClientId TEXT, Enabled INTEGER)");
      db.execute(
          "CREATE TABLE ClientRedirectUris (Id INTEGER PRIMARY KEY, ClientId INTEGER, RedirectUri TEXT)");
      db.execute("INSERT INTO Clients VALUES (1, 'admin', 1)");
      db.execute("INSERT INTO Clients VALUES (2, 'other', 1)");
      db.execute("INSERT INTO ClientRedirectUris VALUES (1, 1, 'https://a/signin-oidc')");
      db.execute("INSERT INTO ClientRedirectUris VALUES (2, 1, 'https://b/signin-oidc')");
      db.execute("INSERT INTO ClientRedirectUris VALUES (3, 2, 'https://c/callback')");
      List<Row> rows =
          db.query(
              """
              SELECT DISTINCT c.ClientId
              FROM Clients AS c
              INNER JOIN ClientRedirectUris AS r ON r.ClientId = c.Id
              WHERE c.Enabled = ? AND r.RedirectUri LIKE ?
              ORDER BY c.ClientId
              """,
              1,
              "%/signin-oidc%");
      assertEquals(1, rows.size());
      assertEquals("admin", rows.get(0).values().get(0));
    }
  }

  @Test
  void likeIsAsciiCaseInsensitive() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (name TEXT)");
      db.execute("INSERT INTO t VALUES ('Admin')");
      assertEquals("Admin", db.query("SELECT name FROM t WHERE name LIKE '%admin%'").get(0).values().get(0));
      assertEquals("Admin", db.query("SELECT name FROM t WHERE name LIKE 'a_min'").get(0).values().get(0));
      assertTrue(db.query("SELECT name FROM t WHERE name LIKE '%nope%'").isEmpty());
    }
  }

  @Test
  void orderByLimitOffset() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (n INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");
      db.execute("INSERT INTO t VALUES (3)");
      db.execute("INSERT INTO t VALUES (2)");
      db.execute("INSERT INTO t VALUES (4)");
      List<Row> rows = db.query("SELECT n FROM t ORDER BY n DESC LIMIT 2 OFFSET 1");
      assertEquals(List.of(3L, 2L), List.of(rows.get(0).values().get(0), rows.get(1).values().get(0)));
    }
  }

  @Test
  void functionsAndBooleanExpressions() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (2, NULL)");
      assertEquals(2L, db.query("SELECT COUNT(*) FROM t").get(0).values().get(0));
      assertEquals(2L, db.query("SELECT MAX(id) FROM t").get(0).values().get(0));
      assertEquals(2L, db.query("SELECT id FROM t WHERE name IS NULL").get(0).values().get(0));
      assertEquals(List.of(1L, 2L), values(db.query("SELECT id FROM t WHERE id = 1 OR name IS NULL")));
      assertEquals(List.of(1L, 2L), values(db.query("SELECT id FROM t WHERE id = 1 OR id = 2 AND name IS NULL")));
      assertEquals("a!", db.query("SELECT name || '!' FROM t WHERE id = 1").get(0).values().get(0));
      assertNull(db.query("SELECT name || '!' FROM t WHERE id = 2").get(0).values().get(0));
      assertEquals("x", db.query("SELECT IFNULL(name, 'x') FROM t WHERE id = 2").get(0).values().get(0));
      String now = (String) db.query("SELECT datetime('now')").get(0).values().get(0);
      assertTrue(now.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
      assertEquals(1, db.query("SELECT 1 WHERE datetime('now') > '2020-01-01 00:00:00'").size());

      db.execute("CREATE TABLE empty (id INTEGER)");
      assertEquals(0L, db.query("SELECT COUNT(*) FROM empty").get(0).values().get(0));
      assertNull(db.query("SELECT MAX(id) FROM empty").get(0).values().get(0));
    }
  }

  @Test
  void insertOrIgnoreAndUpsert() {
    try (Database db = open()) {
      db.execute("CREATE TABLE seq (name TEXT UNIQUE, seq INTEGER)");
      db.execute("INSERT OR IGNORE INTO seq VALUES ('a', 1)");
      db.execute("INSERT OR IGNORE INTO seq VALUES ('a', 9)");
      assertEquals(1L, db.query("SELECT seq FROM seq WHERE name = 'a'").get(0).values().get(0));
      db.execute("""
          INSERT INTO seq (name, seq) VALUES ('a', 4)
          ON CONFLICT(name) DO UPDATE SET seq = excluded.seq
          """);
      assertEquals(4L, db.query("SELECT seq FROM seq WHERE name = 'a'").get(0).values().get(0));
      db.execute("""
          INSERT INTO seq (name, seq) VALUES ('b', 2)
          ON CONFLICT(name) DO UPDATE SET seq = excluded.seq
          """);
      assertEquals(2, db.query("SELECT * FROM seq").size());

      db.execute("CREATE TABLE required (id INTEGER NOT NULL)");
      assertThrows(SqlException.class, () -> db.execute("INSERT OR IGNORE INTO required VALUES (NULL)"));
      assertEquals(0, db.query("SELECT * FROM required").size());
    }
  }

  @Test
  void alterAddsNullColumn() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");
      db.execute("ALTER TABLE t ADD COLUMN name TEXT");
      assertNull(db.query("SELECT name FROM t").get(0).values().get(0));
      assertEquals(List.of("id", "name"), db.query("SELECT * FROM t").get(0).columns());
      db.execute("INSERT INTO t VALUES (2, 'z')");
      assertEquals("z", db.query("SELECT name FROM t WHERE id = 2").get(0).values().get(0));
      assertThrows(SqlException.class, () -> db.execute("ALTER TABLE t ADD COLUMN req INTEGER NOT NULL"));
      assertEquals(2, db.tables().get(0).columns().size());
    }
  }

  @Test
  void bindingsPromoteIntegersAndBooleans() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT, flag INTEGER)");
      db.execute("INSERT INTO t VALUES (?, ?, ?)", 4, "Ann", true);
      List<Row> rows = db.query("SELECT id, name, flag FROM t WHERE name = ?", "Ann");
      assertEquals(4L, rows.get(0).values().get(0));
      assertEquals("Ann", rows.get(0).values().get(1));
      assertEquals(1L, rows.get(0).values().get(2));
      assertEquals(7L, db.query("SELECT ?", 7).get(0).values().get(0));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (?)", 1, 2));
      assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (?, ?, ?)", 1, "a", 1.5));
    }
  }

  @Test
  void failedUpdateRollsBackEarlierRowsInTheSameStatement() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT UNIQUE)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (2, 'b')");
      assertThrows(SqlException.class, () -> db.execute("UPDATE t SET name = 'a'"));
      assertEquals("a", db.query("SELECT name FROM t WHERE id = 1").get(0).values().get(0));
      assertEquals("b", db.query("SELECT name FROM t WHERE id = 2").get(0).values().get(0));
    }
  }

  @Test
  void foreignKeysAreRecordedButNotEnforced() {
    try (Database db = open()) {
      db.execute("CREATE TABLE users (Id TEXT PRIMARY KEY)");
      db.execute("""
          CREATE TABLE child (
            id INTEGER,
            parent TEXT,
            FOREIGN KEY (parent) REFERENCES users (Id)
          )
          """);
      db.execute("INSERT INTO child VALUES (1, 'missing')");
      assertEquals("missing", db.query("SELECT parent FROM child").get(0).values().get(0));
    }
  }

  @Test
  void integerPrimaryKeyAssignsIdsAndDoesNotReuseThem() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
      db.execute("INSERT INTO t (name) VALUES ('a')");
      db.execute("INSERT INTO t (name) VALUES ('b')");
      db.execute("DELETE FROM t WHERE id = 2");
      db.execute("INSERT INTO t (name) VALUES ('c')");
      assertEquals(List.of(1L, 3L), values(db.query("SELECT id FROM t ORDER BY id")));
    }
  }

  @Test
  void parenthesizedOrInWhere() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, display TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a', 'alpha')");
      db.execute("INSERT INTO t VALUES (2, 'b', 'beta')");
      assertEquals(
          2L,
          db.query("SELECT COUNT(*) FROM t WHERE (name LIKE ? OR display LIKE ?)", "%a%", "%a%")
              .get(0)
              .values()
              .get(0));
    }
  }

  @Test
  void defaultDatetimeNowFillsUtcText() {
    try (Database db = open()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, created TEXT DEFAULT (datetime('now')))");
      db.execute("INSERT INTO t (id) VALUES (1)");
      db.execute("INSERT INTO t (id, created) VALUES (2, '2000-01-01 00:00:00')");
      String filled = (String) db.query("SELECT created FROM t WHERE id = 1").get(0).values().get(0);
      assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matcher(filled).matches());
      assertEquals(
          "2000-01-01 00:00:00",
          db.query("SELECT created FROM t WHERE id = 2").get(0).values().get(0));
      List<Row> info = db.run("PRAGMA table_info(t)").rows();
      Row created = info.stream()
          .filter(row -> "created".equals(row.values().get(1)))
          .findFirst()
          .orElseThrow();
      assertEquals("datetime('now')", created.values().get(4));
    }
  }

  @Test
  void derivedFromCountJoinAndCoalesce() {
    try (Database db = open()) {
      db.execute(
          """
          CREATE TABLE "PersistedGrants" (
            "Key" TEXT,
            "SubjectId" TEXT,
            "ClientId" TEXT,
            "Type" TEXT
          )
          """);
      db.execute(
          """
          CREATE TABLE "Users" (
            "Id" TEXT,
            "UserName" TEXT,
            "Email" TEXT
          )
          """);
      db.execute("INSERT INTO \"PersistedGrants\" VALUES ('k1', 'u1', 'c', 'refresh_token')");
      db.execute("INSERT INTO \"PersistedGrants\" VALUES ('k2', 'u1', 'c', 'reference')");
      db.execute("INSERT INTO \"PersistedGrants\" VALUES ('k3', 'u2', 'c', 'refresh_token')");
      db.execute("INSERT INTO \"PersistedGrants\" VALUES ('k4', '', 'c', 'refresh_token')");
      db.execute("INSERT INTO \"PersistedGrants\" VALUES ('k5', NULL, 'c', 'refresh_token')");
      db.execute("INSERT INTO \"Users\" VALUES ('u1', 'alice', 'a@example.com')");

      assertEquals(
          2L,
          db.query(
                  """
                  SELECT COUNT(*) FROM (
                    SELECT DISTINCT g."SubjectId" AS sid
                    FROM "PersistedGrants" g
                    WHERE g."SubjectId" IS NOT NULL AND g."SubjectId" <> ''
                  ) d
                  LEFT JOIN "Users" u ON u."Id" = d.sid
                  """)
              .get(0)
              .values()
              .get(0));

      List<Row> page =
          db.query(
              """
              SELECT d.sid AS SubjectId,
                     COALESCE(u."UserName", u."Email", '') AS SubjectName
              FROM (
                SELECT DISTINCT g."SubjectId" AS sid
                FROM "PersistedGrants" g
                WHERE g."SubjectId" IS NOT NULL AND g."SubjectId" <> ''
              ) d
              LEFT JOIN "Users" u ON u."Id" = d.sid
              ORDER BY d.sid ASC
              LIMIT 10 OFFSET 0
              """);
      assertEquals(2, page.size());
      assertEquals(List.of("SubjectId", "SubjectName"), page.get(0).columns());
      assertEquals("u1", page.get(0).values().get(0));
      assertEquals("alice", page.get(0).values().get(1));
      assertEquals("u2", page.get(1).values().get(0));
      assertEquals("", page.get(1).values().get(1));
    }
  }

  private static Database open() {
    return Database.open(Path.of("dialect"));
  }

  private static List<Object> values(List<Row> rows) {
    return rows.stream().map(row -> row.values().get(0)).toList();
  }
}
