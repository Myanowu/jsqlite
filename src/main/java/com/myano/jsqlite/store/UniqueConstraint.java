package com.myano.jsqlite.store;

import java.util.List;

public record UniqueConstraint(List<String> columns) implements TableConstraint {
  public UniqueConstraint {
    columns = List.copyOf(columns);
  }
}
