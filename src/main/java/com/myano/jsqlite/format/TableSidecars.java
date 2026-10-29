package com.myano.jsqlite.format;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.store.Table;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Design A — table-level sidecars. Large tables live in {@code main.jsqlite.sidecars/Name.jsqlite};
 * the engine mounts them into the same {@link MemoryCatalog} so ERP SQL / JOINs stay unchanged.
 *
 * <p>Extract copies one table's btree into a sibling file and registers it in {@code manifest}. The
 * main file is not rewritten immediately (orphan pages remain until a later vacuum); on open the
 * sidecar overlays the in-catalog table via {@link Table#attachFile}.
 */
public final class TableSidecars {
  public static final String DIR_SUFFIX = ".sidecars";
  public static final String MANIFEST = "manifest";

  private TableSidecars() {}

  public static Path directory(Path main) {
    return Path.of(main.toAbsolutePath().normalize() + DIR_SUFFIX);
  }

  public static Path tableFile(Path main, String tableName) {
    return directory(main).resolve(safeFileName(tableName) + ".jsqlite");
  }

  /** Sidecar table names listed in manifest (and existing {@code *.jsqlite} files). */
  public static List<String> listed(Path main) {
    Set<String> names = new LinkedHashSet<>();
    Path dir = directory(main);
    Path manifest = dir.resolve(MANIFEST);
    if (Files.isRegularFile(manifest)) {
      try {
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
          String name = line.trim();
          if (!name.isEmpty() && !name.startsWith("#")) {
            names.add(name);
          }
        }
      } catch (IOException ex) {
        throw new SqlException("unable to read sidecar manifest: " + ex.getMessage());
      }
    }
    if (Files.isDirectory(dir)) {
      try (var stream = Files.list(dir)) {
        stream
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsqlite"))
            .forEach(
                p -> {
                  String file = p.getFileName().toString();
                  names.add(file.substring(0, file.length() - ".jsqlite".length()));
                });
      } catch (IOException ex) {
        throw new SqlException("unable to list sidecars: " + ex.getMessage());
      }
    }
    return List.copyOf(names);
  }

  /**
   * Rewrite {@code main} so listed sidecar tables (and their indexes) are empty leaves. Data stays
   * in sibling files; this is what actually shrinks the main file after {@link #extract}.
   *
   * @return destination byte length after compact
   */
  public static long compactMain(Path main) {
    if (!Files.isRegularFile(main)) {
      throw new SqlException("database not found: " + main);
    }
    List<String> names = listed(main);
    if (names.isEmpty()) {
      try {
        return Files.size(main);
      } catch (IOException ex) {
        throw new SqlException("unable to size " + main + ": " + ex.getMessage());
      }
    }
    DbImage source = DbImage.ofFile(main);
    byte[][] pages;
    try {
      int pageSize = pageSize(source);
      if (pageSize != Pager.PAGE_SIZE) {
        throw new SqlException("sidecar compact needs page size " + Pager.PAGE_SIZE);
      }
      pages = Btree.rewriteSkippingTables(source, pageSize, new LinkedHashSet<>(names));
    } finally {
      source.close();
    }
    Path tmp = Path.of(main.toAbsolutePath().normalize() + ".compact-tmp");
    writePages(tmp, pages);
    try {
      try {
        Files.move(
            tmp,
            main,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException
          | java.nio.file.AccessDeniedException ex) {
        Files.move(tmp, main, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return Files.size(main);
    } catch (IOException ex) {
      throw new SqlException("unable to replace " + main + ": " + ex.getMessage());
    } finally {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
      }
    }
  }

  /**
   * Copy {@code tableName} from {@code main} into a sidecar file and register it. Does not shrink
   * the main file; call {@link #compactMain} afterward (or reopen / {@link #mount} to read sidecars).
   */
  public static Path extract(Path main, String tableName) {
    if (!Files.isRegularFile(main)) {
      throw new SqlException("database not found: " + main);
    }
    String name = tableName.trim();
    Path dest = tableFile(main, name);
    Path dir = directory(main);
    try {
      Files.createDirectories(dir);
    } catch (IOException ex) {
      throw new SqlException("unable to create " + dir + ": " + ex.getMessage());
    }
    DbImage source = DbImage.ofFile(main);
    try {
      int pageSize = pageSize(source);
      if (pageSize != Pager.PAGE_SIZE) {
        throw new SqlException("sidecar extract needs page size " + Pager.PAGE_SIZE);
      }
      writePages(dest, Btree.extractTable(source, pageSize, name));
    } finally {
      source.close();
    }
    addManifest(main, name);
    return dest;
  }

  /**
   * Overlay sidecar files onto catalog tables. Returns images that must stay reachable for the
   * lifetime of the engine (close via {@link DbImage#close}).
   */
  public static List<DbImage> mount(Path main, MemoryCatalog catalog) {
    List<DbImage> images = new ArrayList<>();
    for (String name : listed(main)) {
      Path file = tableFile(main, name);
      if (!Files.isRegularFile(file)) {
        continue;
      }
      Table table = catalog.find(name);
      if (table == null) {
        continue;
      }
      DbImage image = DbImage.ofFile(file);
      try {
        int pageSize = pageSize(image);
        int reserved = image.get(20) & 0xff;
        Btree.MasterInfo entry = Btree.tableMaster(image, pageSize, name);
        if (entry.rootPage() <= 1) {
          image.close();
          continue;
        }
        table.attachFile(image, pageSize, reserved, entry.rootPage());
        table.bindSidecar(file);
        images.add(image);
      } catch (RuntimeException ex) {
        image.close();
        for (DbImage held : images) {
          held.close();
        }
        throw ex;
      }
    }
    return images;
  }

  /** Rewrite a mutated sidecar table to its sibling file. */
  public static void writeMutated(Table table) {
    Path dest = table.sidecarPath();
    if (dest == null) {
      throw new SqlException("table " + table.name() + " is not a sidecar");
    }
    try {
      Files.createDirectories(dest.getParent());
    } catch (IOException ex) {
      throw new SqlException("unable to create " + dest.getParent() + ": " + ex.getMessage());
    }
    writePages(dest, Btree.singleTableImage(table));
  }

  private static void addManifest(Path main, String tableName) {
    Path manifest = directory(main).resolve(MANIFEST);
    Set<String> names = new LinkedHashSet<>(listed(main));
    names.add(tableName);
    try {
      Files.createDirectories(directory(main));
      StringBuilder body = new StringBuilder();
      body.append("# jsqlite table sidecars — one table name per line\n");
      for (String name : names) {
        body.append(name).append('\n');
      }
      Files.writeString(manifest, body.toString(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new SqlException("unable to write sidecar manifest: " + ex.getMessage());
    }
  }

  private static String safeFileName(String tableName) {
    String name = tableName.trim();
    if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('.') == 0) {
      throw new SqlException("invalid sidecar table name: " + tableName);
    }
    return name;
  }

  private static void writePages(Path path, byte[][] pages) {
    try {
      Files.deleteIfExists(path);
      Files.deleteIfExists(Path.of(path + "-wal"));
      Files.deleteIfExists(Path.of(path + "-shm"));
      Files.deleteIfExists(Path.of(path + "-journal"));
    } catch (IOException ignored) {
    }
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength((long) pages.length * Pager.PAGE_SIZE);
      for (int i = 0; i < pages.length; i++) {
        file.seek((long) i * Pager.PAGE_SIZE);
        file.write(pages[i]);
      }
      file.getFD().sync();
    } catch (IOException ex) {
      throw new SqlException("unable to write " + path + ": " + ex.getMessage());
    }
  }

  private static int pageSize(DbImage header) {
    int size = ((header.get(16) & 0xff) << 8) | (header.get(17) & 0xff);
    return size == 1 ? 65536 : size;
  }
}
