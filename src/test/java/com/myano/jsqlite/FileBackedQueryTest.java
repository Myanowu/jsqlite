package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reopened tables stay file-backed — COUNT / LEFT JOIN must work without rewriting the file. */
class FileBackedQueryTest {
  @Test
  void reopenSupportsCountAndLeftJoinWithoutRewrite() throws Exception {
    Path file = Files.createTempFile("jsqlite-filebacked", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.executeUpdate(
          "CREATE TABLE acct (Company TEXT, GLAccount TEXT, AccountDesc TEXT, PRIMARY KEY (Company, GLAccount))");
      statement.executeUpdate(
          "CREATE TABLE bal (Company TEXT, BalanceAcct TEXT, DebitAmt REAL, CreditAmt REAL)");
      statement.executeUpdate("INSERT INTO acct VALUES ('ELCSS', '1000', 'Cash')");
      statement.executeUpdate("INSERT INTO acct VALUES ('ELCSS', '2000', 'AP')");
      for (int i = 0; i < 500; i++) {
        String acct = i % 2 == 0 ? "1000" : "2000";
        statement.executeUpdate(
            "INSERT INTO bal VALUES ('ELCSS', '" + acct + "', 10, 0)");
      }
      connection.commit();
    }
    byte[] before = Files.readAllBytes(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = connection.createStatement()) {
      try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM bal")) {
        assertTrue(count.next());
        assertEquals(500L, count.getLong(1));
      }
      try (ResultSet joined =
          statement.executeQuery(
              """
              SELECT b.BalanceAcct, MAX(a.AccountDesc) AS AccountDesc, SUM(b.DebitAmt) AS DebitAmt
              FROM bal b
              LEFT JOIN acct a ON a.Company = b.Company AND a.GLAccount = b.BalanceAcct
              WHERE b.Company = 'ELCSS'
              GROUP BY b.BalanceAcct
              ORDER BY b.BalanceAcct
              """)) {
        assertTrue(joined.next());
        assertEquals("1000", joined.getString(1));
        assertEquals("Cash", joined.getString(2));
        assertEquals(2500.0, joined.getDouble(3), 0.001);
        assertTrue(joined.next());
        assertEquals("2000", joined.getString(1));
      }
    }
    assertTrue(
        java.util.Arrays.equals(before, Files.readAllBytes(file)),
        "read-only queries must leave the file untouched");
    Files.deleteIfExists(file);
  }
}
