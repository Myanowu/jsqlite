package com.myano.jsqlite.format;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordCodecTest {
  @Test
  void stringEncodesAsSqliteTextSerial() {
    byte[] record = RecordCodec.encodeRecord(new Object[] {"table"});
    int[] cursor = {0};
    long headerSize = RecordCodec.varint(record, cursor);
    long serial = RecordCodec.varint(record, cursor);
    assertEquals(13 + "table".getBytes(StandardCharsets.UTF_8).length * 2L, serial);
    assertTrue((serial & 1) == 1, "TEXT serial must be odd");
    assertTrue(headerSize > 0);
  }

  @Test
  void blobEncodesAsSqliteBlobSerial() {
    byte[] payload = {1, 2, 3};
    byte[] record = RecordCodec.encodeRecord(new Object[] {payload});
    int[] cursor = {0};
    RecordCodec.varint(record, cursor);
    long serial = RecordCodec.varint(record, cursor);
    assertEquals(12 + payload.length * 2L, serial);
    assertTrue((serial & 1) == 0, "BLOB serial must be even");
  }

  @Test
  void roundTripTextAndBlob() {
    byte[] record = RecordCodec.encodeRecord(new Object[] {"hello", new byte[] {9, 8}});
    List<Object> values = RecordCodec.decodeRecord(record);
    assertEquals(2, values.size());
    assertEquals("hello", values.get(0));
    assertInstanceOf(byte[].class, values.get(1));
    assertArrayEquals(new byte[] {9, 8}, (byte[]) values.get(1));
  }
}
