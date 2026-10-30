package com.myano.jsqlite.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Side file {@code main.jsqlite.admin-counts} caches per-table row totals for DbAdmin so open does
 * not re-walk every btree. Refresh button recomputes and overwrites.
 */
final class AdminTableCounts {
  static final String SUFFIX = ".admin-counts";

  private AdminTableCounts() {}

  static Path file(Path database) {
    return Path.of(database.toAbsolutePath().normalize() + SUFFIX);
  }

  /** {@code null} when the cache file is missing or unreadable. */
  static Map<String, Integer> read(Path database) {
    Path file = file(database);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#")) {
          continue;
        }
        int split = text.lastIndexOf('=');
        if (split <= 0) {
          continue;
        }
        String name = text.substring(0, split).trim();
        String number = text.substring(split + 1).trim();
        if (name.isEmpty()) {
          continue;
        }
        try {
          counts.put(name, Integer.parseInt(number));
        } catch (NumberFormatException ignored) {
          // Skip corrupt lines.
        }
      }
      return counts;
    } catch (IOException ex) {
      return null;
    }
  }

  static void write(Path database, Map<String, Integer> counts) {
    if (database == null || counts == null) {
      return;
    }
    Path file = file(database);
    try {
      long size = Files.isRegularFile(database) ? Files.size(database) : -1L;
      long mtime = Files.isRegularFile(database) ? Files.getLastModifiedTime(database).toMillis() : -1L;
      StringBuilder body = new StringBuilder();
      body.append("# jsqlite admin table counts  size=")
          .append(size)
          .append(" mtime=")
          .append(mtime)
          .append('\n');
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          continue;
        }
        body.append(entry.getKey()).append('=').append(entry.getValue().intValue()).append('\n');
      }
      Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // Cache is best-effort; admin still works with live counts.
    }
  }
}
