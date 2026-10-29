package com.myano.jsqlite.vm;

/** Type descriptor stored in the constant pool for {@link Opcode#Cast}. */
public record CastSpec(String type, int length, int scale) {}
