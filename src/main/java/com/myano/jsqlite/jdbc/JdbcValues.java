package com.myano.jsqlite.jdbc;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JdbcValues {
  private static final Pattern DATE_TIME = Pattern.compile(
      "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,7}))?");

  private JdbcValues() {}

  static String text(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      return text;
    }
    return String.valueOf(value);
  }

  static int integer(Object value) throws SQLException {
    long number = longValue(value);
    if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) {
      throw new SQLException("integer out of range: " + number);
    }
    return (int) number;
  }

  static long longValue(Object value) throws SQLException {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Long number) {
      return number;
    }
    if (value instanceof Integer number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException ex) {
        throw new SQLException("not an integer: " + text);
      }
    }
    throw new SQLException("not an integer: " + value);
  }

  static boolean bool(Object value) throws SQLException {
    if (value == null) {
      return false;
    }
    return longValue(value) != 0L;
  }

  static Timestamp timestamp(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    String text = text(value).trim();
    if (!text.isEmpty() && text.chars().allMatch(Character::isDigit)) {
      if (text.length() < 10 || text.length() > 13) {
        throw new SQLException("cannot read timestamp: " + text);
      }
      long raw = Long.parseLong(text);
      long millis = text.length() == 13 ? raw : raw * 1000L;
      return Timestamp.from(Instant.ofEpochMilli(millis));
    }
    Matcher matcher = DATE_TIME.matcher(text);
    if (!matcher.matches()) {
      throw new SQLException("cannot read timestamp: " + text);
    }
    try {
      LocalDateTime time = LocalDateTime.parse(matcher.group(1), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      String fraction = matcher.group(2);
      if (fraction != null) {
        int nanos = Integer.parseInt(fraction);
        for (int i = fraction.length(); i < 9; i++) {
          nanos *= 10;
        }
        time = time.withNano(nanos);
      }
      return Timestamp.from(time.toInstant(ZoneOffset.UTC));
    } catch (DateTimeParseException ex) {
      throw new SQLException("cannot read timestamp: " + text);
    }
  }

  static String typeName(Object value) {
    if (value instanceof Long || value instanceof Integer) {
      return "INTEGER";
    }
    if (value instanceof String) {
      return "TEXT";
    }
    return "NULL";
  }
}
