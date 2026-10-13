package com.myano.jsqlite.sql;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Ast.And;
import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Compare;
import com.myano.jsqlite.sql.Ast.CompareOp;
import com.myano.jsqlite.sql.Ast.CreateTable;
import com.myano.jsqlite.sql.Ast.Delete;
import com.myano.jsqlite.sql.Ast.ExpressionItem;
import com.myano.jsqlite.sql.Ast.Insert;
import com.myano.jsqlite.sql.Ast.LiteralExpr;
import com.myano.jsqlite.sql.Ast.NullLiteral;
import com.myano.jsqlite.sql.Ast.NumberLiteral;
import com.myano.jsqlite.sql.Ast.Pred;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.Star;
import com.myano.jsqlite.sql.Ast.StringLiteral;
import com.myano.jsqlite.sql.Ast.Update;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {
  private final Parser parser = new Parser();

  @Test
  void parsesCreateWithPreservedNames() {
    CreateTable create = assertInstanceOf(
        CreateTable.class, parser.parse("create table Foo (Id INTEGER, name text)"));
    assertEquals("Foo", create.name());
    assertEquals(
        List.of(new Column("Id", ColumnType.INTEGER), new Column("name", ColumnType.TEXT)),
        create.columns());
  }

  @Test
  void parsesInsertSelectUpdateAndDelete() {
    Insert inserted = assertInstanceOf(
        Insert.class, parser.parse("INSERT INTO Foo (name, Id) VALUES ('it''s', NULL)"));
    assertEquals("Foo", inserted.table());
    assertEquals(List.of("name", "Id"), inserted.columns());
    assertEquals(new LiteralExpr(new StringLiteral("it's")), inserted.values().get(0));
    assertEquals(new LiteralExpr(new NullLiteral()), inserted.values().get(1));

    Insert bare = assertInstanceOf(Insert.class, parser.parse("INSERT INTO Foo VALUES (-5, 3000000000)"));
    assertNull(bare.columns());
    assertEquals(new LiteralExpr(new NumberLiteral(-5)), bare.values().get(0));
    assertEquals(new LiteralExpr(new NumberLiteral(3_000_000_000L)), bare.values().get(1));

    Select select = assertInstanceOf(
        Select.class, parser.parse("SELECT b, a FROM t WHERE a > 1 AND b = 'x'"));
    assertEquals("b", ((ColumnRef) ((ExpressionItem) select.items().get(0)).expr()).name());
    assertEquals("a", ((ColumnRef) ((ExpressionItem) select.items().get(1)).expr()).name());
    assertEquals("t", select.table());
    And where = assertInstanceOf(And.class, select.where());
    Compare first = assertInstanceOf(Compare.class, assertInstanceOf(Pred.class, where.left()).predicate());
    Compare second = assertInstanceOf(Compare.class, assertInstanceOf(Pred.class, where.right()).predicate());
    assertEquals("a", ((ColumnRef) first.left()).name());
    assertEquals(CompareOp.GT, first.op());
    assertEquals(new LiteralExpr(new NumberLiteral(1)), first.right());
    assertEquals(CompareOp.EQ, second.op());
    assertEquals(new LiteralExpr(new StringLiteral("x")), second.right());

    Select star = assertInstanceOf(Select.class, parser.parse("SELECT * FROM t;"));
    assertInstanceOf(Star.class, star.items().get(0));
    assertNull(star.where());

    Update update = assertInstanceOf(Update.class, parser.parse("UPDATE t SET name = 'z', id = 1"));
    assertEquals(2, update.assignments().size());
    assertNull(update.where());

    Delete delete = assertInstanceOf(Delete.class, parser.parse("DELETE FROM t WHERE id < 3"));
    assertEquals("t", delete.table());
    Compare comparison = assertInstanceOf(
        Compare.class, assertInstanceOf(Pred.class, delete.where()).predicate());
    assertEquals(CompareOp.LT, comparison.op());
  }

  @Test
  void acceptsOneOptionalSemicolonAndRejectsASecondStatement() {
    assertInstanceOf(CreateTable.class, parser.parse("CREATE TABLE t (id INTEGER);"));
    SqlException two = assertThrows(
        SqlException.class, () -> parser.parse("CREATE TABLE t (id INTEGER); INSERT INTO t VALUES (1)"));
    assertTrue(two.getMessage().contains("only one statement"));
    assertTrue(two.getMessage().contains("CREATE TABLE t"));
    assertThrows(SqlException.class, () -> parser.parse("CREATE TABLE t (id INTEGER);;"));
    assertThrows(SqlException.class, () -> parser.parse("   "));
    assertThrows(SqlException.class, () -> parser.parse("SELECT * FROM t WHERE"));
  }

  @Test
  void rejectsIntegersOutsideLong() {
    assertEquals(
        new LiteralExpr(new NumberLiteral(Long.MAX_VALUE)),
        assertInstanceOf(Insert.class, parser.parse("INSERT INTO t VALUES (" + Long.MAX_VALUE + ")"))
            .values()
            .get(0));
    assertThrows(
        SqlException.class, () -> parser.parse("INSERT INTO t VALUES (9223372036854775808)"));
  }
}
