package com.myano.jsqlite.sql;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Ast.And;
import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Compare;
import com.myano.jsqlite.sql.Ast.CompareOp;
import com.myano.jsqlite.sql.Ast.CountItem;
import com.myano.jsqlite.sql.Ast.CountStar;
import com.myano.jsqlite.sql.Ast.CreateTable;
import com.myano.jsqlite.sql.Ast.Delete;
import com.myano.jsqlite.sql.Ast.ExpressionItem;
import com.myano.jsqlite.sql.Ast.InSubquery;
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

  @Test
  void parsesCountStarAlias() {
    Select select = assertInstanceOf(
        Select.class,
        parser.parse(
            "select count(*) as col_0_0_ from PUBLIC.t_mdt_user u where u.id in (select distinct r.userId from PUBLIC.t_mdt_user_role_middle r)"));
    CountStar count = assertInstanceOf(CountStar.class, select.items().get(0));
    assertEquals("col_0_0_", count.alias());
    assertEquals("t_mdt_user", select.table());
    assertInstanceOf(InSubquery.class, assertInstanceOf(Pred.class, select.where()).predicate());
  }

  @Test
  void parsesInSubquery() {
    Select select = assertInstanceOf(
        Select.class,
        parser.parse("SELECT id FROM users WHERE id IN (SELECT DISTINCT userId FROM roles WHERE roleId = 1)"));
    Pred pred = assertInstanceOf(Pred.class, select.where());
    InSubquery in = assertInstanceOf(InSubquery.class, pred.predicate());
    assertEquals("id", ((ColumnRef) in.left()).name());
    assertTrue(in.query().distinct());
    assertEquals("roles", in.query().table());
    assertTrue(!in.not());

    Select notIn = assertInstanceOf(
        Select.class,
        parser.parse("SELECT id FROM users WHERE id NOT IN (SELECT userId FROM roles)"));
    InSubquery negated =
        assertInstanceOf(InSubquery.class, assertInstanceOf(Pred.class, notIn.where()).predicate());
    assertTrue(negated.not());
  }

  @Test
  void parsesKeywordColumnAndGroupedHibernateSelect() {
    Select messages = assertInstanceOf(
        Select.class,
        parser.parse(
            "select messageent0_.id as id1_3_, messageent0_.datetime as datetime3_3_ "
                + "from PUBLIC.message messageent0_"));
    ColumnRef datetime = assertInstanceOf(
        ColumnRef.class, assertInstanceOf(ExpressionItem.class, messages.items().get(1)).expr());
    assertEquals("messageent0_", datetime.table());
    assertEquals("datetime", datetime.name());
    assertTrue(messages.groupBy().isEmpty());

    Select grouped = assertInstanceOf(
        Select.class,
        parser.parse(
            "select executemes0_.username as col_0_0_, executemes0_.tname as col_1_0_ "
                + "from PUBLIC.mdt_record_execute executemes0_ "
                + "where (executemes0_.type in (? , ? , ? , ?)) "
                + "group by executemes0_.username , executemes0_.tname"));
    assertEquals("mdt_record_execute", grouped.table());
    assertEquals("executemes0_", grouped.alias());
    assertEquals(2, grouped.groupBy().size());
    assertEquals("username", ((ColumnRef) grouped.groupBy().get(0)).name());
    assertEquals("tname", ((ColumnRef) grouped.groupBy().get(1)).name());

    Select distinct = assertInstanceOf(
        Select.class,
        parser.parse(
            "select count(distinct executemes0_.tname) as col_0_0_ "
                + "from PUBLIC.mdt_record_execute executemes0_"));
    CountItem count = assertInstanceOf(CountItem.class, distinct.items().get(0));
    assertTrue(count.distinct());
    assertEquals("col_0_0_", count.alias());
    assertEquals("tname", ((ColumnRef) count.expr()).name());
  }
}
