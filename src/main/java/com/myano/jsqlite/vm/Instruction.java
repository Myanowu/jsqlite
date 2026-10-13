package com.myano.jsqlite.vm;

public record Instruction(Opcode op, int p1, int p2, int p3) {
  @Override
  public String toString() {
    return op + " " + p1 + " " + p2 + " " + p3;
  }
}
