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
  void leftJoinAutoIndexAvoidsNestedScan() {
    try (Database db = open()) {
      db.execute("CREATE TABLE acct (Company TEXT, COACode TEXT, GLAccount TEXT, AccountDesc TEXT)");
      db.execute(
          "CREATE TABLE disp (Company TEXT, COACode TEXT, GLAccount TEXT, GLAcctDisp TEXT)");
      for (int i = 0; i < 2000; i++) {
        String code = String.format("%04d", i);
        db.execute(
            "INSERT INTO acct VALUES ('ELCSS', 'MAIN', ?, ?)", code, "desc-" + code);
        if (i % 2 == 0) {
          db.execute(
              "INSERT INTO disp VALUES ('ELCSS', 'MAIN', ?, ?)", code, "disp-" + code);
        }
      }
      db.execute("INSERT INTO acct VALUES ('ELCSS', 'MAIN', 'ZZZZ', 'lonely')");
      List<Row> rows =
          db.query(
              """
              SELECT TOP (100) a.GLAccount, d.GLAcctDisp
              FROM acct a
              LEFT JOIN disp d
                ON d.Company = a.Company AND d.COACode = a.COACode AND d.GLAccount = a.GLAccount
              WHERE a.Company = ?
                AND (a.GLAccount LIKE ? OR COALESCE(d.GLAcctDisp, '') LIKE ?)
              ORDER BY a.GLAccount
              """,
              "ELCSS",
              "%00%",
              "%00%");
      assertTrue(rows.size() <= 100);
      assertTrue(rows.size() > 0);
      List<Row> miss =
          db.query(
              """
              SELECT a.GLAccount, d.GLAcctDisp
              FROM acct a
              LEFT JOIN disp d
                ON d.Company = a.Company AND d.COACode = a.COACode AND d.GLAccount = a.GLAccount
              WHERE a.GLAccount = 'ZZZZ'
              """);
      assertEquals(1, miss.size());
      assertEquals("ZZZZ", miss.get(0).values().get(0));
      assertNull(miss.get(0).values().get(1));
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
      assertEquals("x", db.query("SELECT ISNULL(name, 'x') FROM t WHERE id = 2").get(0).values().get(0));
      assertEquals("a", db.query("SELECT ISNULL(name, 'x') FROM t WHERE id = 1").get(0).values().get(0));
      assertNull(db.query("SELECT NULLIF(name, 'a') FROM t WHERE id = 1").get(0).values().get(0));
      assertEquals("a", db.query("SELECT NULLIF(name, '') FROM t WHERE id = 1").get(0).values().get(0));
      assertEquals("kept", db.query("SELECT COALESCE(NULLIF('', ''), 'kept')").get(0).values().get(0));
      assertNull(db.query("SELECT NULLIF(name, 'x') FROM t WHERE id = 2").get(0).values().get(0));
      String now = (String) db.query("SELECT datetime('now')").get(0).values().get(0);
      assertTrue(now.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
      assertEquals(1, db.query("SELECT 1 WHERE datetime('now') > '2020-01-01 00:00:00'").size());

      db.execute("CREATE TABLE empty (id INTEGER)");
      assertEquals(0L, db.query("SELECT COUNT(*) FROM empty").get(0).values().get(0));
      assertNull(db.query("SELECT MAX(id) FROM empty").get(0).values().get(0));
      assertEquals(3L, db.query("SELECT SUM(id) FROM t").get(0).values().get(0));
      assertNull(db.query("SELECT SUM(id) FROM empty").get(0).values().get(0));
      assertEquals(0L, db.query("SELECT COALESCE(SUM(id),0) FROM empty").get(0).values().get(0));
      assertEquals(3L, db.query("SELECT COALESCE(SUM(id),0) FROM t").get(0).values().get(0));
    }
  }

  @Test
  void coalesceSumWithWhereParameter() {
    try (Database db = open()) {
      db.execute("CREATE TABLE parsed_budget (version_id INTEGER, actual_amount INTEGER)");
      db.execute("INSERT INTO parsed_budget VALUES (1, 10)");
      db.execute("INSERT INTO parsed_budget VALUES (1, 20)");
      db.execute("INSERT INTO parsed_budget VALUES (2, 99)");
      assertEquals(30L,
          db.query("SELECT COALESCE(SUM(actual_amount),0) FROM parsed_budget WHERE version_id=1")
              .get(0).values().get(0));
      assertEquals(0L,
          db.query("SELECT COALESCE(SUM(actual_amount),0) FROM parsed_budget WHERE version_id=9")
              .get(0).values().get(0));
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

  @Test
  void outerApplyKeepsLatestRateAndUnmatchedRows() {
    try (Database db = open()) {
      db.execute("CREATE TABLE sales (company TEXT, code TEXT, name TEXT)");
      db.execute("CREATE TABLE rate (company TEXT, code TEXT, pct INTEGER, effective TEXT)");
      db.execute("INSERT INTO sales (company, code, name) VALUES ('ELCSS', 'GST', 'Goods')");
      db.execute("INSERT INTO sales (company, code, name) VALUES ('ELCSS', 'NONE', 'Missing')");
      db.execute("INSERT INTO sales (company, code, name) VALUES ('OTHER', 'GST', 'Skip')");
      db.execute("INSERT INTO rate (company, code, pct, effective) VALUES ('ELCSS', 'GST', 5, '2020-01-01')");
      db.execute("INSERT INTO rate (company, code, pct, effective) VALUES ('ELCSS', 'GST', 8, '2024-01-01')");
      db.execute("INSERT INTO rate (company, code, pct, effective) VALUES ('ELCSS', 'GST', 3, '2022-01-01')");
      List<Row> rows = db.query(
          """
          SELECT t.code, r.pct, r.effective
          FROM sales t
          OUTER APPLY (
            SELECT TOP 1 pct, effective
            FROM rate tr
            WHERE tr.company = t.company AND tr.code = t.code
            ORDER BY tr.effective DESC
          ) r
          WHERE t.company = 'ELCSS'
          ORDER BY t.code
          """);
      assertEquals(2, rows.size());
      assertEquals(List.of("code", "pct", "effective"), rows.get(0).columns());
      assertEquals("GST", rows.get(0).values().get(0));
      assertEquals(8L, rows.get(0).values().get(1));
      assertEquals("2024-01-01", rows.get(0).values().get(2));
      assertEquals("NONE", rows.get(1).values().get(0));
      assertNull(rows.get(1).values().get(1));
      assertNull(rows.get(1).values().get(2));

      List<Row> crossed = db.query(
          """
          SELECT t.code, r.pct
          FROM sales t
          CROSS APPLY (
            SELECT TOP 1 pct
            FROM rate tr
            WHERE tr.company = t.company AND tr.code = t.code
            ORDER BY tr.effective DESC
          ) r
          WHERE t.company = 'ELCSS'
          ORDER BY t.code
          """);
      assertEquals(1, crossed.size());
      assertEquals("GST", crossed.get(0).values().get(0));
      assertEquals(8L, crossed.get(0).values().get(1));
    }
  }

  @Test
  void unionAllCastAndNotExists() {
    try (Database db = open()) {
      db.execute(
          "CREATE TABLE header (company TEXT, tax TEXT, rate TEXT, description TEXT, defaultRate INTEGER, textCode TEXT)");
      db.execute("CREATE TABLE rates (company TEXT, tax TEXT, rate TEXT)");
      db.execute(
          "INSERT INTO header (company, tax, rate, description, defaultRate, textCode) VALUES ('ELCSS', 'GST', 'A', 'header', 1, 'note')");
      db.execute("INSERT INTO rates (company, tax, rate) VALUES ('ELCSS', 'GST', 'A')");
      db.execute("INSERT INTO rates (company, tax, rate) VALUES ('ELCSS', 'GST', 'B')");
      db.execute("INSERT INTO rates (company, tax, rate) VALUES ('ELCSS', 'GST', 'B')");
      db.execute("INSERT INTO rates (company, tax, rate) VALUES ('OTHER', 'GST', 'C')");
      List<Row> rows = db.query(
          """
          SELECT rate, description, defaultRate, textCode, 1 AS HasHeader
          FROM header
          WHERE company = ? AND tax = ?
          UNION ALL
          SELECT DISTINCT tr.rate, tr.rate, CAST(0 AS bit), CAST('' AS nvarchar(10)), 0
          FROM rates tr
          WHERE tr.company = ? AND tr.tax = ?
          AND NOT EXISTS (
            SELECT 1 FROM header s
            WHERE s.company = tr.company AND s.tax = tr.tax AND s.rate = tr.rate
          )
          ORDER BY rate
          """,
          "ELCSS",
          "GST",
          "ELCSS",
          "GST");
      assertEquals(2, rows.size());
      assertEquals(List.of("rate", "description", "defaultRate", "textCode", "HasHeader"), rows.get(0).columns());
      assertEquals(List.of("A", "header", 1L, "note", 1L), rows.get(0).values());
      assertEquals(List.of("B", "B", 0L, "", 0L), rows.get(1).values());

      List<Row> distinct = db.query(
          """
          SELECT rate FROM header WHERE company = 'ELCSS'
          UNION
          SELECT rate FROM rates WHERE company = 'ELCSS'
          ORDER BY rate
          """);
      assertEquals(List.of("A", "B"), values(distinct));
    }
  }

  @Test
  void groupedSumAbsAndHaving() {
    try (Database db = open()) {
      db.execute("CREATE TABLE bal (company TEXT, code TEXT, name TEXT, debit INTEGER, credit INTEGER)");
      db.execute("CREATE TABLE acct (company TEXT, code TEXT, accountDesc TEXT)");
      db.execute("INSERT INTO bal (company, code, name, debit, credit) VALUES ('ELCSS', '1000', 'cash', -4, 0)");
      db.execute("INSERT INTO bal (company, code, name, debit, credit) VALUES ('ELCSS', '1000', 'cash', 6, 1)");
      db.execute("INSERT INTO bal (company, code, name, debit, credit) VALUES ('ELCSS', '2000', 'zero', 0, NULL)");
      db.execute("INSERT INTO bal (company, code, name, debit, credit) VALUES ('OTHER', '1000', 'skip', 9, 9)");
      db.execute("INSERT INTO acct (company, code, accountDesc) VALUES ('ELCSS', '1000', 'Cash')");
      List<Row> rows = db.query(
          """
          SELECT p.code AS GLAccount, MAX(a.accountDesc) AS AccountName,
                 SUM(ABS(ISNULL(p.debit, 0))) AS DebitAmt,
                 SUM(ABS(ISNULL(p.credit, 0))) AS CreditAmt
          FROM bal p
          LEFT JOIN acct a ON a.company = p.company AND a.code = p.code
          WHERE p.company = ? AND p.code <= ?
          GROUP BY p.code
          HAVING SUM(ABS(ISNULL(p.debit, 0))) <> 0 OR SUM(ABS(ISNULL(p.credit, 0))) <> 0
          ORDER BY p.code
          """,
          "ELCSS",
          "9999");
      assertEquals(1, rows.size());
      assertEquals(List.of("GLAccount", "AccountName", "DebitAmt", "CreditAmt"), rows.get(0).columns());
      assertEquals("1000", rows.get(0).values().get(0));
      assertEquals("Cash", rows.get(0).values().get(1));
      assertEquals(10L, rows.get(0).values().get(2));
      assertEquals(1L, rows.get(0).values().get(3));
    }
  }

  private static Database open() {
    return Database.open(Path.of("dialect"));
  }

  private static List<Object> values(List<Row> rows) {
    return rows.stream().map(row -> row.values().get(0)).toList();
  }
}
