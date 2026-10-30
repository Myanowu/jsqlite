package com.myano.jsqlite;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformCompatTest {
  @Test
  void twoConnectionsShareCommittedRows() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-share").resolve("mdtdb.db");
    String url = "jdbc:jsqlite:" + file;
    try (Connection first = DriverManager.getConnection(url);
        Connection second = DriverManager.getConnection(url)) {
      try (Statement statement = first.createStatement()) {
        statement.executeUpdate("CREATE TABLE PUBLIC.T_MDT_USER (ID VARCHAR(255) NOT NULL PRIMARY KEY, ENABLE BOOLEAN)");
        statement.executeUpdate("INSERT INTO T_MDT_USER (ID, ENABLE) VALUES ('a', TRUE)");
      }
      try (ResultSet result = second.createStatement().executeQuery("SELECT ID FROM T_MDT_USER WHERE ENABLE = TRUE")) {
        assertTrue(result.next());
        assertEquals("a", result.getString(1));
      }
    }
  }

  @Test
  void openWriteTransactionBlocksTheOtherConnection() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-lock").resolve("mdtdb.db");
    String url = "jdbc:jsqlite:" + file;
    try (Connection writer = DriverManager.getConnection(url);
        Connection reader = DriverManager.getConnection(url)) {
      writer.createStatement().executeUpdate("CREATE TABLE t (id INTEGER)");
      writer.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
      writer.setAutoCommit(false);
      writer.createStatement().executeUpdate("INSERT INTO t VALUES (2)");
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        Future<Integer> pending = pool.submit(() -> {
          try (ResultSet result = reader.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
            assertTrue(result.next());
            return result.getInt(1);
          }
        });
        Thread.sleep(200);
        assertFalse(pending.isDone());
        writer.commit();
        assertEquals(2, pending.get(5, TimeUnit.SECONDS));
      } finally {
        pool.shutdownNow();
      }
    }
  }

  /** 验收：写挡读 — 短 busy_timeout 下读方得到 database is locked。 */
  @Test
  void readerTimesOutWhileWriterHoldsUncommittedWrite() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-busy-read").resolve("mdtdb.db");
    String url = "jdbc:jsqlite:" + file + "?busy_timeout=80";
    try (Connection writer = DriverManager.getConnection(url);
        Connection reader = DriverManager.getConnection(url)) {
      writer.createStatement().executeUpdate("CREATE TABLE t (id INTEGER)");
      writer.setAutoCommit(false);
      writer.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
      long start = System.nanoTime();
      SQLException locked =
          assertThrows(
              SQLException.class,
              () -> {
                try (ResultSet ignored = reader.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
                  // should not reach
                }
              });
      assertTrue(locked.getMessage().contains("database is locked"), locked.getMessage());
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
      writer.rollback();
    }
  }

  /** 验收：池化多读 — 多线程并行 SELECT 不因互锁失败。 */
  @Test
  void parallelReadersCompleteWithoutSerializingErrors() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-parallel-read").resolve("mdtdb.db");
    String url = "jdbc:jsqlite:" + file + "?busy_timeout=5000";
    try (Connection setup = DriverManager.getConnection(url)) {
      setup.createStatement().executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      setup.setAutoCommit(false);
      for (int i = 0; i < 2_000; i++) {
        setup.createStatement().executeUpdate("INSERT INTO t VALUES (" + i + ", 'n" + i + "')");
      }
      setup.commit();
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Long>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        futures.add(
            pool.submit(
                () -> {
                  try (Connection connection = DriverManager.getConnection(url);
                      ResultSet result =
                          connection.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
                    assertTrue(result.next());
                    return result.getLong(1);
                  }
                }));
      }
      for (Future<Long> future : futures) {
        assertEquals(2_000L, future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void readOnlyTransactionDoesNotBlockOtherConnection() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-ro-lock").resolve("mdtdb.db");
    String url = "jdbc:jsqlite:" + file;
    try (Connection first = DriverManager.getConnection(url);
        Connection second = DriverManager.getConnection(url)) {
      first.createStatement().executeUpdate("CREATE TABLE t (id INTEGER)");
      first.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
      first.setAutoCommit(false);
      try (ResultSet ignored = first.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
        // Hibernate-style: autoCommit=false + SELECT must not freeze the pool.
      }
      long start = System.nanoTime();
      try (ResultSet result = second.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
        assertTrue(result.next());
        assertEquals(1, result.getInt(1));
      }
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
      first.commit();
    }
  }

  @Test
  void hibernateStylePoolDoesNotDeadlockOnParallelSelects() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-pool").resolve("mdtdb.db");
    String url = "jdbc:jsqlite:" + file;
    try (Connection setup = DriverManager.getConnection(url)) {
      setup.createStatement().executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      for (int i = 0; i < 100; i++) {
        setup.createStatement().executeUpdate("INSERT INTO t VALUES (" + i + ", 'n" + i + "')");
      }
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        futures.add(pool.submit(() -> {
          try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            for (int i = 0; i < 20; i++) {
              try (PreparedStatement statement =
                  connection.prepareStatement("SELECT id FROM t WHERE id = ?")) {
                statement.setInt(1, i);
                try (ResultSet result = statement.executeQuery()) {
                  assertTrue(result.next());
                }
              }
            }
            connection.commit();
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        }));
      }
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void storesBlobRealTimestampAndHsqlPaging() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + Files.createTempDirectory("jsqlite-types").resolve("t.db"))) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE row (id INTEGER PRIMARY KEY, amount NUMERIC(19,2), body VARBINARY(100), created TIMESTAMP)");
        statement.executeUpdate("INSERT INTO row (id) VALUES (1)");
        statement.executeUpdate("INSERT INTO row (id) VALUES (2)");
        statement.executeUpdate("INSERT INTO row (id) VALUES (3)");
      }
      byte[] payload = "xml".getBytes(StandardCharsets.UTF_8);
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE row SET amount = ?, body = ?, created = ? WHERE id = ?",
          ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY)) {
        statement.setBigDecimal(1, new BigDecimal("1600000000000"));
        statement.setBytes(2, payload);
        statement.setTimestamp(3, Timestamp.valueOf("2020-01-02 03:04:05"));
        statement.setInt(4, 2);
        assertEquals(1, statement.executeUpdate());
      }
      try (ResultSet result = connection.createStatement().executeQuery(
          "SELECT amount, body, created FROM row ORDER BY id OFFSET 1 LIMIT 1")) {
        assertTrue(result.next());
        assertEquals(1_600_000_000_000L, result.getLong(1));
        assertArrayEquals(payload, result.getBytes(2));
        assertEquals(Timestamp.valueOf("2020-01-02 03:04:05"), result.getTimestamp(3));
        assertFalse(result.next());
      }
      try (ResultSet result = connection.createStatement().executeQuery("SELECT id FROM row WHERE id = 1 FOR UPDATE")) {
        assertTrue(result.next());
        assertEquals(1, result.getInt(1));
      }
      try (ResultSet result = connection.createStatement().executeQuery(
          "SELECT sequence_name FROM information_schema.system_sequences")) {
        assertFalse(result.next());
      }
      connection.createStatement().execute("shutdown");
      try (ResultSet result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM row")) {
        assertTrue(result.next());
        assertEquals(3, result.getInt(1));
      }
    }
  }

  @Test
  void crossJoinMatchesWhereEquality() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-cross").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.t_mdt_swt_segments (id TEXT PRIMARY KEY, segmentOrder INTEGER, storeType TEXT)");
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.t_mdt_swt_seg_location (clusterId TEXT, segmentId TEXT, sourceKey TEXT, PRIMARY KEY (clusterId, segmentId))");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.t_mdt_swt_segments VALUES ('s1', 1, 'MEMORY')");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.t_mdt_swt_seg_location VALUES ('n1', 's1', 'tbl')");
      try (PreparedStatement statement = connection.prepareStatement(
          "select swiftsegme0_.id as id1_4_, swiftsegme0_.segmentOrder as segmentO2_4_ from PUBLIC.t_mdt_swt_segments swiftsegme0_ cross join PUBLIC.t_mdt_swt_seg_location swiftsegmelocationentity1_ where swiftsegme0_.id=swiftsegmelocationentity1_.segmentId and swiftsegmelocationentity1_.clusterId=? and swiftsegmelocationentity1_.sourceKey=? and swiftsegme0_.storeType=? order by swiftsegme0_.segmentOrder")) {
        statement.setString(1, "n1");
        statement.setString(2, "tbl");
        statement.setString(3, "MEMORY");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("s1", result.getString(1));
          assertEquals(1, result.getInt(2));
          assertFalse(result.next());
        }
      }
    }
  }

  @Test
  void crossJoinWithPkSeekFinishesOnLargeTables() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-cross-large").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.t_mdt_swt_segments (id TEXT PRIMARY KEY, segmentOrder INTEGER, storeType TEXT)");
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.t_mdt_swt_seg_location (clusterId TEXT, segmentId TEXT, sourceKey TEXT, PRIMARY KEY (clusterId, segmentId))");
      connection.setAutoCommit(false);
      try (PreparedStatement segments =
              connection.prepareStatement("INSERT INTO PUBLIC.t_mdt_swt_segments VALUES (?, ?, ?)");
          PreparedStatement locations =
              connection.prepareStatement(
                  "INSERT INTO PUBLIC.t_mdt_swt_seg_location VALUES (?, ?, ?)")) {
        for (int i = 0; i < 800; i++) {
          segments.setString(1, "s" + i);
          segments.setInt(2, i);
          // All MEMORY so outer scan is not "accidentally" selective — SeekPk must be O(1).
          segments.setString(3, "MEMORY");
          segments.addBatch();
          locations.setString(1, "n1");
          locations.setString(2, "s" + i);
          locations.setString(3, i == 7 ? "tbl" : "other");
          locations.addBatch();
        }
        segments.executeBatch();
        locations.executeBatch();
      }
      connection.commit();
      connection.setAutoCommit(true);
      long start = System.nanoTime();
      int hits = 0;
      for (int round = 0; round < 32; round++) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "select swiftsegme0_.id as id1_4_, swiftsegme0_.segmentOrder as segmentO2_4_ from PUBLIC.t_mdt_swt_segments swiftsegme0_ cross join PUBLIC.t_mdt_swt_seg_location swiftsegmelocationentity1_ where swiftsegme0_.id=swiftsegmelocationentity1_.segmentId and swiftsegmelocationentity1_.clusterId=? and swiftsegmelocationentity1_.sourceKey=? and swiftsegme0_.storeType=? order by swiftsegme0_.segmentOrder")) {
          statement.setString(1, "n1");
          statement.setString(2, "tbl");
          statement.setString(3, "MEMORY");
          try (ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals("s7", result.getString(1));
            assertFalse(result.next());
            hits++;
          }
        }
      }
      assertEquals(32, hits);
      // Linear SeekPk would be ~800*800*32 probes and take seconds; hash SeekPk must stay sub-second.
      assertTrue(
          System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500),
          "cross join still too slow — primary-key seek must be O(1)");
    }
  }

  @Test
  void countColumnEqualsCountStarForNonNull() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-count").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.T_MDT_TRIGGERS (SCHED_NAME TEXT, TRIGGER_NAME TEXT, JOB_NAME TEXT, JOB_GROUP TEXT)");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.T_MDT_TRIGGERS VALUES ('QuartzScheduler','t1','j1','DEFAULT')");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.T_MDT_TRIGGERS VALUES ('QuartzScheduler','t2','j1','DEFAULT')");
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT COUNT(TRIGGER_NAME) FROM PUBLIC.T_MDT_TRIGGERS WHERE SCHED_NAME = 'QuartzScheduler' AND JOB_NAME = ? AND JOB_GROUP = ?")) {
        statement.setString(1, "j1");
        statement.setString(2, "DEFAULT");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(2, result.getInt(1));
          assertFalse(result.next());
        }
      }
    }
  }

  @Test
  void commaJoinMatchesWhereEquality() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-comma").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.T_MDT_TRIGGERS (SCHED_NAME TEXT, TRIGGER_NAME TEXT, TRIGGER_GROUP TEXT, JOB_NAME TEXT, JOB_GROUP TEXT)");
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.T_MDT_JOB_DETAILS (SCHED_NAME TEXT, JOB_NAME TEXT, JOB_GROUP TEXT, IS_DURABLE INTEGER, JOB_CLASS_NAME TEXT, REQUESTS_RECOVERY INTEGER)");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.T_MDT_TRIGGERS VALUES ('QuartzScheduler','t1','DEFAULT','j1','DEFAULT')");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.T_MDT_JOB_DETAILS VALUES ('QuartzScheduler','j1','DEFAULT',1,'mr.Job',0)");
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT J.JOB_NAME, J.JOB_GROUP, J.IS_DURABLE, J.JOB_CLASS_NAME, J.REQUESTS_RECOVERY FROM PUBLIC.T_MDT_TRIGGERS T, PUBLIC.T_MDT_JOB_DETAILS J WHERE T.SCHED_NAME = ? AND J.SCHED_NAME = ? AND T.TRIGGER_NAME = ? AND T.TRIGGER_GROUP = ? AND T.JOB_NAME = J.JOB_NAME AND T.JOB_GROUP = J.JOB_GROUP")) {
        statement.setString(1, "QuartzScheduler");
        statement.setString(2, "QuartzScheduler");
        statement.setString(3, "t1");
        statement.setString(4, "DEFAULT");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("j1", result.getString(1));
          assertEquals("mr.Job", result.getString(4));
          assertFalse(result.next());
        }
      }
    }
  }

  @Test
  void booleanNotNegatesComparison() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-not").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.T_MDT_TRIGGERS (SCHED_NAME TEXT, TRIGGER_NAME TEXT, TRIGGER_GROUP TEXT, MISFIRE_INSTR INTEGER, NEXT_FIRE_TIME INTEGER, TRIGGER_STATE TEXT)");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.T_MDT_TRIGGERS VALUES ('QuartzScheduler','a','DEFAULT',0,10,'WAITING')");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.T_MDT_TRIGGERS VALUES ('QuartzScheduler','b','DEFAULT',-1,10,'WAITING')");
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT TRIGGER_NAME, TRIGGER_GROUP FROM PUBLIC.T_MDT_TRIGGERS WHERE SCHED_NAME = 'QuartzScheduler' AND NOT (MISFIRE_INSTR = -1) AND NEXT_FIRE_TIME < ? AND TRIGGER_STATE = ?")) {
        statement.setLong(1, 20);
        statement.setString(2, "WAITING");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("a", result.getString(1));
          assertFalse(result.next());
        }
      }
    }
  }

  @Test
  void statementAddBatchRunsSqlStrings() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-batch").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE t_mdt_conf_classname (id TEXT, classname TEXT)");
      try (Statement statement = connection.createStatement()) {
        statement.addBatch("insert into t_mdt_conf_classname values ('a','mr.A')");
        statement.addBatch("insert into t_mdt_conf_classname values ('b','mr.B')");
        assertArrayEquals(new int[] {1, 1}, statement.executeBatch());
      }
      try (ResultSet result = connection.createStatement().executeQuery(
          "SELECT COUNT(*) FROM t_mdt_conf_classname")) {
        assertTrue(result.next());
        assertEquals(2, result.getInt(1));
      }
    }
  }

  @Test
  void likeEscapeKeepsPercentAndUnderscoreLiteral() throws Exception {
    Path file = Files.createTempDirectory("jsqlite-like").resolve("t.db");
    try (Connection connection = DriverManager.getConnection("jdbc:jsqlite:" + file)) {
      connection.createStatement().executeUpdate(
          "CREATE TABLE PUBLIC.t_mdt_conf_entity (id TEXT, value TEXT)");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.t_mdt_conf_entity VALUES ('Language.zh', 'x')");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.t_mdt_conf_entity VALUES ('100%', 'y')");
      connection.createStatement().executeUpdate(
          "INSERT INTO PUBLIC.t_mdt_conf_entity VALUES ('a_b', 'z')");
      try (PreparedStatement statement = connection.prepareStatement(
          "select entity0_.id as id1_3_, entity0_.value as value2_3_ from PUBLIC.t_mdt_conf_entity entity0_ where entity0_.id like ? escape '/'")) {
        statement.setString(1, "Language%");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("Language.zh", result.getString(1));
          assertFalse(result.next());
        }
        statement.setString(1, "100/%");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("100%", result.getString(1));
          assertFalse(result.next());
        }
        statement.setString(1, "a/_b");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("a_b", result.getString(1));
          assertFalse(result.next());
        }
      }
    }
  }
}
