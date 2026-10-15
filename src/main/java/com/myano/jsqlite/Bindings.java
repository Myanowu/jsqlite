package com.myano.jsqlite;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

/** Coerce JDBC / API bindings into the engine's null / long / text values. */
public final class Bindings {
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private Bindings() {}

  public static Object coerce(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Long number) {
      return number;
    }
    if (value instanceof Integer number) {
      return number.longValue();
    }
    if (value instanceof Boolean flag) {
      return flag ? 1L : 0L;
    }
    if (value instanceof String text) {
      return text;
    }
    if (value instanceof Short || value instanceof Byte) {
      return ((Number) value).longValue();
    }
    if (value instanceof Double || value instanceof Float) {
      return real(((Number) value).doubleValue());
    }
    if (value instanceof java.math.BigDecimal decimal) {
      try {
        return decimal.longValueExact();
      } catch (ArithmeticException ex) {
        return decimal.doubleValue();
      }
    }
    if (value instanceof byte[] raw) {
      return raw.clone();
    }
    if (value instanceof java.sql.Blob blob) {
      try {
        return blob.getBytes(1, (int) blob.length());
      } catch (java.sql.SQLException ex) {
        throw new SqlException("cannot read blob: " + ex.getMessage());
      }
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof Timestamp timestamp) {
      return formatInstant(timestamp.toInstant());
    }
    if (value instanceof java.sql.Date || value instanceof java.sql.Time) {
      return formatInstant(Instant.ofEpochMilli(((Date) value).getTime()));
    }
    if (value instanceof Instant instant) {
      return formatInstant(instant);
    }
    if (value instanceof LocalDateTime local) {
      return local.format(DATE_TIME);
    }
    if (value instanceof OffsetDateTime offset) {
      return formatInstant(offset.toInstant());
    }
    if (value instanceof Date date) {
      return formatInstant(date.toInstant());
    }
    if (value instanceof UUID id) {
      return id.toString();
    }
    throw new SqlException(
        "binding must be null, integer, real, text, blob, boolean, timestamp, or uuid: "
            + value.getClass().getName());
  }

  private static Object real(double number) {
    if (!Double.isNaN(number) && !Double.isInfinite(number) && number == Math.rint(number)
        && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE) {
      return (long) number;
    }
    return number;
  }

  private static String formatInstant(Instant instant) {
    LocalDateTime local = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    int nanos = local.getNano();
    if (nanos == 0) {
      return local.format(DATE_TIME);
    }
    String fraction = String.format("%09d", nanos).replaceAll("0+$", "");
    return local.format(DATE_TIME) + "." + fraction;
  }
}
