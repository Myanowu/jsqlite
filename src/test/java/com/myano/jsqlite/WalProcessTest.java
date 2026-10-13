package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalProcessTest {
  @Test
  void secondJvmWriterSeesTheLock() throws Exception {
    Path file = Files.createTempFile("jsqlite-wal-proc", ".sqlite");
    Files.deleteIfExists(file);
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file + "?journal_mode=WAL");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      statement.executeUpdate("INSERT INTO t (id) VALUES (1)");
    }
    Path lock = Path.of(file.toString() + "-shm");
    try (var held = com.myano.jsqlite.wal.LockFile.acquireWrite(file, 0)) {
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String classpath = System.getProperty("java.class.path");
      Process process = new ProcessBuilder(
              java,
              "-cp",
              classpath,
              "com.myano.jsqlite.WalProcessTest$Writer",
              file.toString())
          .redirectErrorStream(true)
          .start();
      boolean finished = process.waitFor(20, TimeUnit.SECONDS);
      assertTrue(finished);
      String output = new String(process.getInputStream().readAllBytes());
      assertEquals(0, process.exitValue(), output);
      assertTrue(output.contains("database is locked"), output);
    }
    assertTrue(Files.isRegularFile(lock) || !Files.isRegularFile(lock));
    Files.deleteIfExists(Path.of(file.toString() + "-wal"));
    Files.deleteIfExists(lock);
    Files.deleteIfExists(file);
  }

  public static final class Writer {
    public static void main(String[] args) throws Exception {
      Class.forName("com.myano.jsqlite.JDBC");
      try (Connection connection = DriverManager.getConnection(
          "jdbc:jsqlite:" + args[0] + "?journal_mode=WAL&busy_timeout=0");
          Statement statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO t (id) VALUES (2)");
        System.out.println("wrote");
      } catch (Exception ex) {
        System.out.println(ex.getMessage());
        if (ex.getMessage() != null && ex.getMessage().contains("database is locked")) {
          return;
        }
        throw ex;
      }
    }
  }
}
