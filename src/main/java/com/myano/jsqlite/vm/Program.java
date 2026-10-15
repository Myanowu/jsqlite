package com.myano.jsqlite.vm;

import java.util.ArrayList;
import java.util.List;

public final class Program {
  private final Instruction[] instructions;
  private final Object[] constants;
  private final List<String> resultColumns;
  private final int registerCount;
  private final List<OrderKey> orderKeys;
  private final int[] groupRegisters;
  private final int limitRegister;
  private final int offsetRegister;
  private final InsertMode insertMode;
  private final int[] conflictColumns;
  private final int insertDone;

  public Program(
      List<Instruction> instructions,
      List<Object> constants,
      List<String> resultColumns,
      int registerCount) {
    this(
        instructions,
        constants,
        resultColumns,
        registerCount,
        List.of(),
        new int[0],
        -1,
        -1,
        InsertMode.APPEND,
        new int[0],
        -1);
  }

  public Program(
      List<Instruction> instructions,
      List<Object> constants,
      List<String> resultColumns,
      int registerCount,
      List<OrderKey> orderKeys,
      int[] groupRegisters,
      int limitRegister,
      int offsetRegister,
      InsertMode insertMode,
      int[] conflictColumns,
      int insertDone) {
    this.instructions = instructions.toArray(Instruction[]::new);
    this.constants = constants.toArray();
    this.resultColumns = List.copyOf(resultColumns);
    this.registerCount = registerCount;
    this.orderKeys = List.copyOf(orderKeys);
    this.groupRegisters = groupRegisters.clone();
    this.limitRegister = limitRegister;
    this.offsetRegister = offsetRegister;
    this.insertMode = insertMode;
    this.conflictColumns = conflictColumns.clone();
    this.insertDone = insertDone;
  }

  public int instructionCount() {
    return instructions.length;
  }

  public Instruction instruction(int index) {
    return instructions[index];
  }

  public List<Instruction> instructions() {
    return List.of(instructions);
  }

  public Object constant(int index) {
    return constants[index];
  }

  public List<Object> constants() {
    List<Object> copy = new ArrayList<>(constants.length);
    for (Object constant : constants) {
      copy.add(constant);
    }
    return List.copyOf(copy);
  }

  public List<String> resultColumns() {
    return resultColumns;
  }

  public int registerCount() {
    return registerCount;
  }

  public List<OrderKey> orderKeys() {
    return orderKeys;
  }

  public int[] groupRegisters() {
    return groupRegisters.clone();
  }

  public int limitRegister() {
    return limitRegister;
  }

  public int offsetRegister() {
    return offsetRegister;
  }

  public InsertMode insertMode() {
    return insertMode;
  }

  public int[] conflictColumns() {
    return conflictColumns.clone();
  }

  public int insertDone() {
    return insertDone;
  }

  public List<Opcode> opcodes() {
    List<Opcode> opcodes = new ArrayList<>(instructions.length);
    for (Instruction instruction : instructions) {
      opcodes.add(instruction.op());
    }
    return List.copyOf(opcodes);
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < instructions.length; i++) {
      text.append(i).append(": ").append(instructions[i]).append('\n');
    }
    return text.toString();
  }
}
