package com.myano.jsqlite.vm;

public record Instruction(Opcode op, int p1, int p2, int p3, int p4) {
  public Instruction(Opcode op, int p1, int p2, int p3) {
    this(op, p1, p2, p3, 0);
  }

  @Override
  public String toString() {
    return p4 == 0
        ? op + " " + p1 + " " + p2 + " " + p3
        : op + " " + p1 + " " + p2 + " " + p3 + " " + p4;
  }
}
