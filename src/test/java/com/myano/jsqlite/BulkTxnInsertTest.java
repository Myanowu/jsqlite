package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkTxnInsertTest {
  @Test
  void fiftyThousandRowsInOneTransactionStayLinear() throws Exception {
    Path file = Files.createTempFile("jsqlite-bulk", ".sqlite");
    Files.deleteIfExists(file);
    long started = System.nanoTime();
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE t (id INTEGER, name TEXT)");
      }
      try (PreparedStatement insert = connection.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
        for (int i = 0; i < 50_000; i++) {
          insert.setLong(1, i);
          insert.setString(2, "row-" + i);
          insert.addBatch();
          if (i % 2000 == 1999) {
            insert.executeBatch();
          }
        }
        insert.executeBatch();
      }
      connection.commit();
    }
    long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = again.createStatement();
        ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM t")) {
      assertTrue(count.next());
      assertEquals(50_000L, count.getLong(1));
    }
    // Old per-statement full-catalog capture made this path quadratic and multi-minute.
    assertTrue(elapsedMs < 30_000L, "bulk insert took " + elapsedMs + "ms");
    Files.deleteIfExists(file);
  }
}
