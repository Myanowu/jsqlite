package com.myano.jsqlite;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对 epicor-pilot-bak 做 VACUUM INTO 迁库（不改 bak 本身）。
 *
 * <pre>
 *   mvn -q -DskipTests test-compile
 *   java -cp "target/classes;target/test-classes;..." com.myano.jsqlite.CompactEpicorBakMain
 * </pre>
 */
public final class CompactEpicorBakMain {
  private static final Path SOURCE = Path.of("C:\\Elcss\\Epicor24\\data\\epicor-pilot-bak.jsqlite");
  private static final Path DEST = Path.of("C:\\Elcss\\Epicor24\\data\\epicor-pilot-compact.jsqlite");

  private CompactEpicorBakMain() {}

  public static void main(String[] args) throws Exception {
    Path source = args.length > 0 ? Path.of(args[0]) : SOURCE;
    Path dest = args.length > 1 ? Path.of(args[1]) : DEST;
    if (!Files.isRegularFile(source)) {
      System.err.println("missing source: " + source);
      System.exit(2);
    }
    if (Files.exists(dest)) {
      System.err.println("destination exists, refuse overwrite: " + dest);
      System.exit(3);
    }
    System.out.println("source=" + source.toAbsolutePath() + " (" + Files.size(source) + " bytes)");
    System.out.println("dest=" + dest.toAbsolutePath());
    System.out.println("using xerial VACUUM INTO (JSQLite cannot load multi-GB files)");
    long start = System.nanoTime();
    CompactCopy.Result result = CompactCopy.vacuumIntoWithXerial(source, dest);
    long ms = (System.nanoTime() - start) / 1_000_000L;
    System.out.println("done in " + ms + " ms");
    System.out.println("source_bytes=" + result.sourceBytes());
    System.out.println("dest_bytes=" + result.destinationBytes());
    double ratio = result.sourceBytes() == 0
        ? 0
        : (100.0 * result.destinationBytes() / result.sourceBytes());
    System.out.printf("dest/source=%.1f%%%n", ratio);
  }
}
