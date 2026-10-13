package com.myano.jsqlite.store;

public record ForeignKeyConstraint(String column, String refTable, String refColumn)
    implements TableConstraint {}
