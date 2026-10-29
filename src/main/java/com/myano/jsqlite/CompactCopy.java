package com.myano.jsqlite;

import com.myano.jsqlite.format.Pager;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * L2.3 逻辑迁库：打开源库，新建目标文件，按表 SQL 重建并拷行与索引。
 * 适合 JSQLite 能整库装进内存的库；超大文件请用 {@link #vacuumIntoWithXerial}。
 */
public final class CompactCopy {
  private CompactCopy() {}

  public record Result(
      Path source,
      Path destination,
      long sourceBytes,
      long destinationBytes,
      int tables,
      long rows) {}

  public static Result copy(Path source, Path destination) {
    ObjectsPath.requireFile(source);
    if (Files.exists(destination)) {
      throw new SqlException("destination already exists: " + destination);
    }
    long sourceBytes;
    try {
      sourceBytes = Files.size(source);
    } catch (IOException ex) {
      throw new SqlException("unable to size " + source + ": " + ex.getMessage());
    }
    Pager.create(destination);
    long rowsCopied = 0;
    int tableCount = 0;
    try (Database src = Database.open(source);
        Database dest = Database.openFile(destination)) {
      List<TableInfo> tables = new ArrayList<>();
      for (TableInfo table : src.tables()) {
        if (table.name().toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
          continue;
        }
        tables.add(table);
      }
      for (TableInfo table : tables) {
        String create = table.sql();
        if (create == null || create.isBlank()) {
          throw new SqlException("table " + table.name() + " has no CREATE SQL for compact copy");
        }
        dest.execute(create);
        tableCount++;
        List<Row> rows = src.query("SELECT * FROM " + quote(table.name()));
        if (rows.isEmpty()) {
          continue;
        }
        List<String> columns = rows.get(0).columns();
        String insert = "INSERT INTO "
            + quote(table.name())
            + " ("
            + columns.stream().map(CompactCopy::quote).collect(Collectors.joining(", "))
            + ") VALUES ("
            + columns.stream().map(ignored -> "?").collect(Collectors.joining(", "))
            + ")";
        for (Row row : rows) {
          dest.execute(insert, row.values().toArray());
          rowsCopied++;
        }
      }
      for (IndexDef index : src.indexes()) {
        if (index.sql() == null || index.sql().isBlank()) {
          continue;
        }
        if (index.table().toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
          continue;
        }
        dest.execute(index.sql());
      }
    }
    long destBytes;
    try {
      destBytes = Files.size(destination);
    } catch (IOException ex) {
      throw new SqlException("unable to size " + destination + ": " + ex.getMessage());
    }
    return new Result(source, destination, sourceBytes, destBytes, tableCount, rowsCopied);
  }

  /**
   * 超大主档用 xerial 流式 {@code VACUUM INTO}，不经过 JSQLite 全量进内存。
   * 必须走 {@code org.sqlite.SQLiteDataSource}，不能 {@code DriverManager}（JSQLite 也登记了 {@code jdbc:sqlite:}）。
   * 需要 classpath 有 xerial（本仓库 test 依赖）。
   */
  public static Result vacuumIntoWithXerial(Path source, Path destination) {
    ObjectsPath.requireFile(source);
    if (Files.exists(destination)) {
      throw new SqlException("destination already exists: " + destination);
    }
    long sourceBytes;
    try {
      sourceBytes = Files.size(source);
    } catch (IOException ex) {
      throw new SqlException("unable to size " + source + ": " + ex.getMessage());
    }
    String url = "jdbc:sqlite:" + source.toAbsolutePath().normalize();
    String destSql = destination.toAbsolutePath().normalize().toString().replace("'", "''");
    try {
      Class<?> dataSourceClass = Class.forName("org.sqlite.SQLiteDataSource");
      Object dataSource = dataSourceClass.getConstructor().newInstance();
      dataSourceClass.getMethod("setUrl", String.class).invoke(dataSource, url);
      try (java.sql.Connection connection =
              (java.sql.Connection) dataSourceClass.getMethod("getConnection").invoke(dataSource);
          java.sql.Statement statement = connection.createStatement()) {
        statement.execute("VACUUM INTO '" + destSql + "'");
      }
    } catch (ClassNotFoundException ex) {
      throw new SqlException("xerial sqlite-jdbc is required for VACUUM INTO");
    } catch (SqlException ex) {
      throw ex;
    } catch (Exception ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      throw new SqlException("VACUUM INTO failed: " + cause.getMessage());
    }
    long destBytes;
    try {
      destBytes = Files.size(destination);
    } catch (IOException ex) {
      throw new SqlException("unable to size " + destination + ": " + ex.getMessage());
    }
    return new Result(source, destination, sourceBytes, destBytes, -1, -1);
  }

  private static String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  private static final class ObjectsPath {
    private ObjectsPath() {}

    static void requireFile(Path path) {
      if (path == null || !Files.isRegularFile(path)) {
        throw new SqlException("source is not a file: " + path);
      }
    }
  }
}
