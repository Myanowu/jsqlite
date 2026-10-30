package com.myano.jsqlite.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTableCountsTest {
  @Test
  void roundTripsCountsBesideDatabase() throws Exception {
    Path db = Files.createTempFile("jsqlite-admin-counts", ".jsqlite");
    try {
      Map<String, Integer> counts = new LinkedHashMap<>();
      counts.put("Company", 1);
      counts.put("GLJrnDtl", 3_781_648);
      AdminTableCounts.write(db, counts);
      Path side = AdminTableCounts.file(db);
      assertTrue(Files.isRegularFile(side));
      String body = Files.readString(side);
      assertTrue(body.contains("Company=1"));
      assertTrue(body.contains("GLJrnDtl=3781648"));
      Map<String, Integer> loaded = AdminTableCounts.read(db);
      assertEquals(1, loaded.get("Company"));
      assertEquals(3_781_648, loaded.get("GLJrnDtl"));
    } finally {
      Files.deleteIfExists(AdminTableCounts.file(db));
      Files.deleteIfExists(db);
    }
  }

  @Test
  void missingFileReturnsNull() throws Exception {
    Path db = Files.createTempFile("jsqlite-admin-counts-missing", ".jsqlite");
    Files.deleteIfExists(AdminTableCounts.file(db));
    try {
      assertNull(AdminTableCounts.read(db));
    } finally {
      Files.deleteIfExists(db);
    }
  }
}
