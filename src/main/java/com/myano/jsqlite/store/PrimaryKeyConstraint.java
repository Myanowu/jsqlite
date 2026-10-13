package com.myano.jsqlite.store;

import java.util.List;

public record PrimaryKeyConstraint(List<String> columns) implements TableConstraint {
  public PrimaryKeyConstraint {
    columns = List.copyOf(columns);
  }
}
