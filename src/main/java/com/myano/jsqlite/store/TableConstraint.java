package com.myano.jsqlite.store;

public sealed interface TableConstraint
    permits PrimaryKeyConstraint, UniqueConstraint, ForeignKeyConstraint {}
