package com.myano.jsqlite.sql;

import com.myano.jsqlite.sql.Ast.CreateTable;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.store.MemoryCatalog;
import com.myano.jsqlite.vm.Opcode;
import com.myano.jsqlite.vm.Program;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {
  @Test
  void insertLoadsConstantsThenAppendsOneRecord() {
    Program program = compile("CREATE TABLE t (id INTEGER, name TEXT)", "INSERT INTO t VALUES (1, 'a')");
    assertEquals(
        List.of(Opcode.Integer, Opcode.String, Opcode.MakeRecord, Opcode.OpenWrite, Opcode.Insert, Opcode.Halt),
        program.opcodes());
    assertEquals(List.of("a", "t"), program.constants());
  }

  @Test
  void wideIntegerUsesTheConstantPool() {
    Program fits = compile("CREATE TABLE t (n INTEGER)", "INSERT INTO t VALUES (2147483647)");
    assertEquals(Opcode.Integer, fits.instruction(0).op());
    assertEquals(2147483647, fits.instruction(0).p2());

    Program wide = compile("CREATE TABLE t (n INTEGER)", "INSERT INTO t VALUES (2147483648)");
    assertEquals(
        List.of(Opcode.Int64, Opcode.MakeRecord, Opcode.OpenWrite, Opcode.Insert, Opcode.Halt),
        wide.opcodes());
    assertEquals(List.of(2_147_483_648L, "t"), wide.constants());
  }

  @Test
  void selectJumpsIntoTheBodyOnlyWhenTheComparisonHolds() {
    Program program = compile("CREATE TABLE t (id INTEGER)", "SELECT id FROM t WHERE id = 1");
    assertEquals(
        List.of(
            Opcode.OpenRead,
            Opcode.Rewind,
            Opcode.Column,
            Opcode.Integer,
            Opcode.Eq,
            Opcode.Next,
            Opcode.Halt,
            Opcode.Column,
            Opcode.ResultRow,
            Opcode.Next,
            Opcode.Halt),
        program.opcodes());
    assertEquals(program.instructionCount() - 1, program.instruction(1).p2());
    assertEquals(7, program.instruction(4).p2());
    assertFalse(program.opcodes().contains(Opcode.Rowid));
  }

  @Test
  void nullComparisonStillEmitsEq() {
    Program program = compile("CREATE TABLE t (a INTEGER)", "SELECT a FROM t WHERE a = NULL");
    assertTrue(program.opcodes().contains(Opcode.Null));
    assertTrue(program.opcodes().contains(Opcode.Eq));
  }

  @Test
  void updateAndDeleteUseTheMemoryOpcodes() {
    assertTrue(compile("CREATE TABLE t (id INTEGER)", "UPDATE t SET id = 4").opcodes().contains(Opcode.Update));
    assertTrue(compile("CREATE TABLE t (id INTEGER)", "DELETE FROM t WHERE id < 4").opcodes().contains(Opcode.Delete));
    Program scan = compile("CREATE TABLE t (id INTEGER)", "SELECT id FROM t");
    assertEquals(
        List.of(Opcode.OpenRead, Opcode.Rewind, Opcode.Column, Opcode.ResultRow, Opcode.Next, Opcode.Halt),
        scan.opcodes());
  }

  private static Program compile(String createSql, String sql) {
    MemoryCatalog catalog = new MemoryCatalog();
    Statement create = new Parser().parse(createSql);
    CreateTable table = (CreateTable) create;
    catalog.createTable(table.name(), table.columns(), createSql);
    return new Compiler().compile(new Parser().parse(sql), catalog, sql);
  }
}
