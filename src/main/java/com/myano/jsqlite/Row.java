package com.myano.jsqlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record Row(List<String> columns, List<Object> values) {
  public Row {
    columns = List.copyOf(columns);
    values = Collections.unmodifiableList(new ArrayList<>(values));
  }
}
