package com.myano.jsqlite.admin;

import com.myano.jsqlite.Row;

import java.util.List;

public record ScriptResult(Kind kind, List<String> columns, List<Row> rows) {
  public enum Kind {
    EXECUTE,
    QUERY
  }

  public ScriptResult {
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }

  public static ScriptResult executed() {
    return new ScriptResult(Kind.EXECUTE, List.of(), List.of());
  }

  public static ScriptResult query(List<String> columns, List<Row> rows) {
    return new ScriptResult(Kind.QUERY, columns, rows);
  }
}
