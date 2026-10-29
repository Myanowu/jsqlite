package com.myano.jsqlite.store;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * For large tables, discover low-cardinality columns (compression ratio = rows/distinct) and install
 * in-memory posting indexes so {@code SeekIdx} can probe single-column equalities.
 *
 * <p>Meta file {@code {metaDir}/{table}.ratio-cols} remembers winning column names across opens so
 * the next process only rebuilds postings for those columns (one scan), not every column.
 *
 * <p>Writes: {@link Table} materialize + {@code indexRow} keep postings up to date after mutation.
 */
public final class RatioAutoIndex {
  /** Default: only auto-index tables at least this large. */
  public static volatile long minRows = 100_000L;
  /** Default: keep columns where rows/distinct >= this ratio. */
  public static volatile int minRatio = 100;
  /**
   * Skip columns with fewer distinct values — e.g. a single {@code Company} value indexes the whole
   * table and makes multi-equality seeks worse if chosen first.
   */
  public static volatile int minDistinct = 2;

  private RatioAutoIndex() {}

  public static void configure(long rows, int ratio) {
    configure(rows, ratio, minDistinct);
  }

  public static void configure(long rows, int ratio, int distinct) {
    if (rows < 1) {
      throw new SqlException("ratio-index minRows must be >= 1");
    }
    if (ratio < 2) {
      throw new SqlException("ratio-index minRatio must be >= 2");
    }
    if (distinct < 2) {
      throw new SqlException("ratio-index minDistinct must be >= 2");
    }
    minRows = rows;
    minRatio = ratio;
    minDistinct = distinct;
  }

  public static Path metaDir(Path mainDatabase) {
    return Path.of(mainDatabase.toAbsolutePath().normalize() + ".ratio-idx");
  }

  public static Path metaFile(Path metaDir, String tableName) {
    return metaDir.resolve(safeName(tableName) + ".ratio-cols");
  }

  /** Run once per table open (lazy). Safe to call often; synchronized per table. */
  public static void ensure(Table table) {
    if (table == null) {
      return;
    }
    synchronized (table) {
      if (table.ratioAnalyzed()) {
        return;
      }
      int n = table.rowCount();
      if (n < minRows) {
        table.markRatioAnalyzed();
        return;
      }
      Path metaDir = table.ratioMetaDir();
      List<String> cached = metaDir == null ? null : readMeta(metaFile(metaDir, table.name()));
      if (cached != null) {
        // Remember winners only — populate posting on first SeekIdx via ensureIndex (one column).
        table.setRatioWinners(cached);
        table.markRatioAnalyzed();
        return;
      }
      analyzeAndInstall(table, n);
    }
  }

  /** True when meta is missing and the table is large enough to warrant a background scan. */
  public static boolean needsAnalyze(Table table) {
    if (table == null || table.ratioAnalyzed()) {
      return false;
    }
    Path metaDir = table.ratioMetaDir();
    if (metaDir == null) {
      return false;
    }
    if (Files.isRegularFile(metaFile(metaDir, table.name()))) {
      return false;
    }
    return table.rowCount() >= minRows;
  }

  private static void analyzeAndInstall(Table table, int n) {
    int cols = table.columns().size();
    if (cols == 0) {
      table.markRatioAnalyzed();
      return;
    }
    int maxDistinct = Math.max(1, n / minRatio);
    // Pass 1: distinct sets only (no rowid lists) so multi-million-row tables do not OOM.
    @SuppressWarnings("unchecked")
    java.util.HashSet<Object>[] distincts = new java.util.HashSet[cols];
    boolean[] dead = new boolean[cols];
    int[] pk = table.primaryKey();
    for (int p : pk) {
      if (p >= 0 && p < cols) {
        dead[p] = true;
      }
    }
    for (int c = 0; c < cols; c++) {
      if (!dead[c]) {
        distincts[c] = new java.util.HashSet<>();
      }
    }
    for (int i = 0; i < n; i++) {
      Object[] row = table.peekRow(i);
      for (int c = 0; c < cols; c++) {
        if (dead[c]) {
          continue;
        }
        Object value = row[c];
        if (value == null) {
          continue;
        }
        java.util.HashSet<Object> set = distincts[c];
        set.add(value);
        if (set.size() > maxDistinct) {
          dead[c] = true;
          distincts[c] = null;
        }
      }
    }
    List<Integer> winnerCols = new ArrayList<>();
    List<String> winners = new ArrayList<>();
    for (int c = 0; c < cols; c++) {
      if (dead[c] || distincts[c] == null || distincts[c].isEmpty()) {
        continue;
      }
      long distinct = distincts[c].size();
      if (distinct < minDistinct || (long) n / distinct < minRatio) {
        continue;
      }
      winnerCols.add(c);
      winners.add(table.columns().get(c).name());
    }
    distincts = null; // free pass-1 sets before optional posting build

    // Small/medium tables: build postings now. Huge tables: meta only; ensureIndex fills one
    // column at a time on first SeekIdx (avoids holding every winner's  N rowids at once).
    if (n < 500_000 && !winnerCols.isEmpty()) {
      @SuppressWarnings("unchecked")
      HashMap<Object, ArrayList<Integer>>[] maps = new HashMap[cols];
      for (int c : winnerCols) {
        maps[c] = new HashMap<>();
      }
      for (int i = 0; i < n; i++) {
        Object[] row = table.peekRow(i);
        for (int c : winnerCols) {
          Object value = row[c];
          if (value == null) {
            continue;
          }
          maps[c].computeIfAbsent(value, ignored -> new ArrayList<>()).add(i);
        }
      }
      for (int c : winnerCols) {
        table.installRatioPosting(c, maps[c]);
      }
    }

    table.setRatioWinners(winners);
    Path metaDir = table.ratioMetaDir();
    if (metaDir != null) {
      writeMeta(metaFile(metaDir, table.name()), winners, n, minRatio);
    }
    table.markRatioAnalyzed();
  }

  private static List<String> readMeta(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return null;
    }
    try {
      List<String> names = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String name = line.trim();
        if (name.isEmpty() || name.startsWith("#")) {
          continue;
        }
        names.add(name);
      }
      return names;
    } catch (IOException ex) {
      return null;
    }
  }

  private static void writeMeta(Path file, List<String> columns, int rows, int ratio) {
    try {
      Files.createDirectories(file.getParent());
      StringBuilder body = new StringBuilder();
      body.append("# jsqlite ratio-index columns  rows=")
          .append(rows)
          .append(" minRatio=")
          .append(ratio)
          .append('\n');
      Set<String> unique = new LinkedHashSet<>(columns);
      for (String name : unique) {
        body.append(name).append('\n');
      }
      Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new SqlException("unable to write ratio-index meta " + file + ": " + ex.getMessage());
    }
  }

  private static String safeName(String tableName) {
    String name = tableName.trim();
    if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      throw new SqlException("invalid ratio-index table name: " + tableName);
    }
    return name;
  }
}
