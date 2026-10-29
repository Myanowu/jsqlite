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

class LargeTableBtreeTest {
  @Test
  void wideTableBeyondSingleInteriorRoundTrip() throws Exception {
    Path file = Files.createTempFile("jsqlite-large", ".sqlite");
    Files.deleteIfExists(file);
    String pad = "x".repeat(200);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE t (id INTEGER, a TEXT, b TEXT, c TEXT, d TEXT, e TEXT)");
      }
      try (PreparedStatement insert =
          connection.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?, ?, ?)")) {
        for (int i = 0; i < 2000; i++) {
          insert.setLong(1, i);
          insert.setString(2, pad);
          insert.setString(3, pad);
          insert.setString(4, pad);
          insert.setString(5, pad);
          insert.setString(6, pad);
          insert.addBatch();
          if (i % 200 == 0) {
            insert.executeBatch();
          }
        }
        insert.executeBatch();
      }
      connection.commit();
    }
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = again.createStatement();
        ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM t")) {
      assertTrue(count.next());
      assertEquals(2000L, count.getLong(1));
    }
    try (Connection again = DriverManager.getConnection("jdbc:jsqlite:" + file);
        Statement statement = again.createStatement();
        ResultSet sample = statement.executeQuery("SELECT id FROM t ORDER BY id LIMIT 3")) {
      assertTrue(sample.next());
      assertEquals(0L, sample.getLong(1));
      assertTrue(sample.next());
      assertEquals(1L, sample.getLong(1));
      assertTrue(sample.next());
      assertEquals(2L, sample.getLong(1));
    }
    Files.deleteIfExists(file);
  }
}