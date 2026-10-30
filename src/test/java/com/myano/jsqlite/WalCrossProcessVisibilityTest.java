package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：跨进程读可见 — 进程 A WAL 提交后，进程 B 下一条语句可读到（不必重开连接）。
 */
class WalCrossProcessVisibilityTest {
  @Test
  void readerProcessSeesCommittedWalWithoutReopen() throws Exception {
    Path dir = Files.createTempDirectory("jsqlite-wal-vis");
    Path file = dir.resolve("db.sqlite");
    Path ready = dir.resolve("reader-ready");
    Path go = dir.resolve("writer-done");
    Path result = dir.resolve("reader-result");

    try (Connection seed =
            DriverManager.getConnection("jdbc:jsqlite:" + file + "?journal_mode=WAL&busy_timeout=5000");
        Statement statement = seed.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (1, 'a')");
    }

    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");
    Process reader =
        new ProcessBuilder(
                java,
                "-cp",
                classpath,
                "com.myano.jsqlite.WalCrossProcessVisibilityTest$Reader",
                file.toString(),
                ready.toString(),
                go.toString(),
                result.toString())
            .redirectErrorStream(true)
            .start();

    long deadline = System.currentTimeMillis() + 20_000;
    while (!Files.isRegularFile(ready) && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(Files.isRegularFile(ready), "reader did not become ready");

    try (Connection writer =
            DriverManager.getConnection("jdbc:jsqlite:" + file + "?journal_mode=WAL&busy_timeout=5000");
        Statement statement = writer.createStatement()) {
      statement.executeUpdate("INSERT INTO t (id, name) VALUES (2, 'b')");
    }
    Files.writeString(go, "ok", StandardCharsets.UTF_8);

    boolean finished = reader.waitFor(30, TimeUnit.SECONDS);
    String output = new String(reader.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(finished, output);
    assertEquals(0, reader.exitValue(), output);
    assertEquals("2", Files.readString(result).trim());

    Files.deleteIfExists(Path.of(file.toString() + "-wal"));
    Files.deleteIfExists(Path.of(file.toString() + "-shm"));
    Files.deleteIfExists(file);
    Files.deleteIfExists(ready);
    Files.deleteIfExists(go);
    Files.deleteIfExists(result);
    try {
      Files.deleteIfExists(dir);
    } catch (Exception ignored) {
    }
  }

  public static final class Reader {
    public static void main(String[] args) throws Exception {
      Class.forName("com.myano.jsqlite.JDBC");
      Path db = Path.of(args[0]);
      Path ready = Path.of(args[1]);
      Path go = Path.of(args[2]);
      Path result = Path.of(args[3]);
      try (Connection connection =
              DriverManager.getConnection("jdbc:jsqlite:" + db + "?journal_mode=WAL&busy_timeout=5000");
          Statement statement = connection.createStatement()) {
        try (ResultSet before = statement.executeQuery("SELECT COUNT(*) FROM t")) {
          before.next();
          if (before.getLong(1) != 1L) {
            System.out.println("unexpected initial count " + before.getLong(1));
            System.exit(2);
          }
        }
        Files.writeString(ready, "ready", StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 25_000;
        while (!Files.isRegularFile(go) && System.currentTimeMillis() < deadline) {
          Thread.sleep(20);
        }
        if (!Files.isRegularFile(go)) {
          System.out.println("writer signal missing");
          System.exit(3);
        }
        try (ResultSet after = statement.executeQuery("SELECT COUNT(*) FROM t")) {
          after.next();
          Files.writeString(result, Long.toString(after.getLong(1)), StandardCharsets.UTF_8);
        }
      }
    }
  }
}
