package com.myano.jsqlite.store;

import com.myano.jsqlite.SqlException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * For large tables, discover low-cardinality columns (compression ratio = rows/distinct) and install
 * in-memory posting indexes so {@code SeekIdx} can probe single-column equalities.
 *
 * <p>Meta file {@code {metaDir}/{table}.ratio-cols} remembers winning column names across opens so
 * the next process only rebuilds postings for those columns (one scan), not every column. Optional
 * {@code name=distinct} lines cache group counts for admin / tooling.
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

  /**
   * Distinct-value counts for ratio-index winner columns (high compression). Empty when the table
   * has no winners. Prefers meta {@code name=distinct}, then live postings, else one scan of winners
   * only (and rewrites meta when possible).
   */
  public static Map<String, Integer> groupCounts(Table table) {
    if (table == null) {
      return Map.of();
    }
    synchronized (table) {
      ensure(table);
      List<String> winners = table.ratioWinnerNames();
      if (winners.isEmpty()) {
        return Map.of();
      }
      Path metaDir = table.ratioMetaDir();
      Path meta = metaDir == null ? null : metaFile(metaDir, table.name());
      Map<String, Integer> cached = readMetaCounts(meta);
      if (completeCounts(winners, cached)) {
        return cached;
      }
      Map<String, Integer> fromIndex = postingCounts(table, winners);
      if (completeCounts(winners, fromIndex)) {
        if (meta != null) {
          writeMeta(meta, fromIndex, table.rowCount(), minRatio);
        }
        return fromIndex;
      }
      Map<String, Integer> scanned = scanWinnerDistinct(table, winners);
      if (meta != null && !scanned.isEmpty()) {
        writeMeta(meta, scanned, table.rowCount(), minRatio);
      }
      return scanned;
    }
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
    Map<String, Integer> winnerCounts = new LinkedHashMap<>();
    for (int c = 0; c < cols; c++) {
      if (dead[c] || distincts[c] == null || distincts[c].isEmpty()) {
        continue;
      }
      long distinct = distincts[c].size();
      if (distinct < minDistinct || (long) n / distinct < minRatio) {
        continue;
      }
      winnerCols.add(c);
      winnerCounts.put(table.columns().get(c).name(), (int) distinct);
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

    table.setRatioWinners(winnerCounts.keySet());
    Path metaDir = table.ratioMetaDir();
    if (metaDir != null) {
      writeMeta(metaFile(metaDir, table.name()), winnerCounts, n, minRatio);
    }
    table.markRatioAnalyzed();
  }

  private static boolean completeCounts(List<String> winners, Map<String, Integer> counts) {
    if (counts == null || counts.isEmpty()) {
      return false;
    }
    for (String name : winners) {
      if (counts.get(name) == null) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, Integer> postingCounts(Table table, List<String> winners) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String name : winners) {
      int distinct = table.ratioPostingDistinct(name);
      if (distinct < 0) {
        return Map.of();
      }
      out.put(name, distinct);
    }
    return out;
  }

  private static Map<String, Integer> scanWinnerDistinct(Table table, List<String> winners) {
    Map<String, Integer> indexes = new LinkedHashMap<>();
    for (String name : winners) {
      int index = table.columnIndex(name);
      if (index >= 0) {
        indexes.put(name, index);
      }
    }
    if (indexes.isEmpty()) {
      return Map.of();
    }
    Map<String, HashSet<Object>> distincts = new LinkedHashMap<>();
    for (String name : indexes.keySet()) {
      distincts.put(name, new HashSet<>());
    }
    int n = table.rowCount();
    for (int i = 0; i < n; i++) {
      Object[] row = table.peekRow(i);
      for (Map.Entry<String, Integer> entry : indexes.entrySet()) {
        Object value = row[entry.getValue()];
        if (value != null) {
          distincts.get(entry.getKey()).add(value);
        }
      }
    }
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Map.Entry<String, HashSet<Object>> entry : distincts.entrySet()) {
      out.put(entry.getKey(), entry.getValue().size());
    }
    return out;
  }

  private static List<String> readMeta(Path file) {
    Map<String, Integer> counts = readMetaCounts(file);
    return counts == null ? null : List.copyOf(counts.keySet());
  }

  /** {@code null} if missing; values may be absent for legacy name-only lines. */
  private static Map<String, Integer> readMetaCounts(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return null;
    }
    try {
      Map<String, Integer> names = new LinkedHashMap<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#")) {
          continue;
        }
        int split = text.lastIndexOf('=');
        if (split > 0) {
          String name = text.substring(0, split).trim();
          String number = text.substring(split + 1).trim();
          if (!name.isEmpty()) {
            try {
              names.put(name, Integer.parseInt(number));
              continue;
            } catch (NumberFormatException ignored) {
              // Fall through and treat the whole line as a column name.
            }
          }
        }
        names.put(text, null);
      }
      return names;
    } catch (IOException ex) {
      return null;
    }
  }

  private static void writeMeta(Path file, Map<String, Integer> columns, int rows, int ratio) {
    try {
      Files.createDirectories(file.getParent());
      StringBuilder body = new StringBuilder();
      body.append("# jsqlite ratio-index columns  rows=")
          .append(rows)
          .append(" minRatio=")
          .append(ratio)
          .append('\n');
      for (Map.Entry<String, Integer> entry : new LinkedHashMap<>(columns).entrySet()) {
        String name = entry.getKey();
        if (name == null || name.isEmpty()) {
          continue;
        }
        Integer distinct = entry.getValue();
        if (distinct == null) {
          body.append(name).append('\n');
        } else {
          body.append(name).append('=').append(distinct.intValue()).append('\n');
        }
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
