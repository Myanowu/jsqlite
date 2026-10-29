package com.myano.jsqlite.sql;

import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.TableConstraint;

import java.util.List;

public final class Ast {
  private Ast() {}

  public sealed interface Statement
      permits CreateTable, CreateIndex, AlterTable, DropTable, Insert, Select, Compound, Update, Delete, Pragma, Shutdown {}

  public record DropTable(String name, boolean ifExists) implements Statement {}

  public record Shutdown() implements Statement {}

  /** argument 是 table_info 的表名。value 是賦值形式的右方。兩者都空則是查詢。 */
  public record Pragma(String name, String argument, String value) implements Statement {}

  public record CreateTable(
      String name, boolean ifNotExists, List<Column> columns, List<TableConstraint> constraints)
      implements Statement {
    public CreateTable {
      columns = List.copyOf(columns);
      constraints = List.copyOf(constraints);
    }
  }

  public record CreateIndex(
      String name,
      boolean unique,
      boolean ifNotExists,
      String table,
      List<String> columns,
      String partialColumn)
      implements Statement {
    public CreateIndex {
      columns = List.copyOf(columns);
    }
  }

  public record AlterTable(String table, Column column) implements Statement {}

  public record Upsert(List<String> conflictColumns, List<Assign> assignments) {
    public Upsert {
      conflictColumns = List.copyOf(conflictColumns);
      assignments = List.copyOf(assignments);
    }
  }

  public record Insert(
      String table, List<String> columns, List<Expr> values, boolean orIgnore, Upsert upsert)
      implements Statement {
    public Insert {
      columns = columns == null ? null : List.copyOf(columns);
      values = List.copyOf(values);
    }
  }

  public record Join(boolean left, String table, String alias, Bool on, Select apply) {
    public Join(boolean left, String table, String alias, Bool on) {
      this(left, table, alias, on, null);
    }
  }

  public record OrderItem(Expr expr, boolean descending) {}

  public record Select(
      boolean distinct,
      List<SelectItem> items,
      String table,
      Select fromSelect,
      String alias,
      List<Join> joins,
      Bool where,
      List<OrderItem> orderBy,
      Expr limit,
      Expr offset,
      List<Expr> groupBy,
      Bool having)
      implements Statement {
    public Select {
      items = List.copyOf(items);
      joins = List.copyOf(joins);
      orderBy = List.copyOf(orderBy);
      groupBy = List.copyOf(groupBy);
    }

    public Select(
        boolean distinct,
        List<SelectItem> items,
        String table,
        Select fromSelect,
        String alias,
        List<Join> joins,
        Bool where,
        List<OrderItem> orderBy,
        Expr limit,
        Expr offset,
        List<Expr> groupBy) {
      this(
          distinct,
          items,
          table,
          fromSelect,
          alias,
          joins,
          where,
          orderBy,
          limit,
          offset,
          groupBy,
          null);
    }
  }

  /**
   * {@code SELECT … UNION [ALL] SELECT …}. {@code unionAll.get(i)} is true when arm {@code i}
   * and arm {@code i + 1} are joined by {@code UNION ALL}. ORDER BY and TOP on the last arm
   * apply to the combined rows.
   */
  public record Compound(
      List<Select> arms, List<Boolean> unionAll, List<OrderItem> orderBy, Expr limit, Expr offset)
      implements Statement {
    public Compound {
      arms = List.copyOf(arms);
      unionAll = List.copyOf(unionAll);
      orderBy = List.copyOf(orderBy);
    }
  }

  public record Update(String table, List<Assign> assignments, Bool where) implements Statement {
    public Update {
      assignments = List.copyOf(assignments);
    }
  }

  public record Delete(String table, Bool where) implements Statement {}

  public record Assign(String column, Expr value) {}

  public sealed interface SelectItem permits Star, ExpressionItem, CountStar, CountItem, MaxItem {}

  public record Star() implements SelectItem {}

  public record ExpressionItem(Expr expr, String alias) implements SelectItem {
    public ExpressionItem(Expr expr) {
      this(expr, null);
    }
  }

  public record CountStar(String alias) implements SelectItem {
    public CountStar() {
      this(null);
    }
  }

  /** {@code COUNT(expr)} / {@code COUNT(DISTINCT expr)}。 */
  public record CountItem(Expr expr, boolean distinct, String alias) implements SelectItem {}

  public record MaxItem(String column, String alias, String table) implements SelectItem {
    public MaxItem(String column, String alias) {
      this(column, alias, null);
    }

    public MaxItem(String column) {
      this(column, null, null);
    }
  }

  public sealed interface Expr
      permits LiteralExpr, ColumnRef, Concat, IfNullExpr, Now, Parameter, CaseExpr, ScalarSubquery,
          SumExpr, CastExpr, AbsExpr {}

  /** {@code ABS(value)}. Null stays null. */
  public record AbsExpr(Expr value) implements Expr {}

  /** {@code CAST(value AS type[(length[, scale])])}. Length and scale are {@code -1} when omitted. */
  public record CastExpr(Expr value, String type, int length, int scale) implements Expr {}

  public record LiteralExpr(Literal literal) implements Expr {}

  public record ColumnRef(String table, String name) implements Expr {}

  public record Concat(Expr left, Expr right) implements Expr {}

  /** {@code SUM(expr)} — aggregate; may appear inside COALESCE/IFNULL. */
  public record SumExpr(Expr expr) implements Expr {}

  public record IfNullExpr(Expr value, Expr fallback) implements Expr {}

  public record Now() implements Expr {}

  public record Parameter(int index) implements Expr {}

  /**
   * {@code CASE [operand] WHEN … THEN … [ELSE …] END}.
   * When {@code operand} is null this is a searched CASE ({@code WHEN} holds a boolean);
   * otherwise each arm's {@code whenValue} is compared to the operand with {@code =}.
   */
  public record CaseExpr(Expr operand, List<CaseArm> arms, Expr elseExpr) implements Expr {
    public CaseExpr {
      arms = List.copyOf(arms);
    }
  }

  /** One {@code WHEN … THEN …} arm. Exactly one of {@code whenCond}/{@code whenValue} is set. */
  public record CaseArm(Bool whenCond, Expr whenValue, Expr thenExpr) {}

  /** {@code (SELECT …)} as a scalar expression — one column; aggregates or first matching row. */
  public record ScalarSubquery(Select query) implements Expr {}

  public sealed interface Bool permits And, Or, Not, Pred {}

  public record And(Bool left, Bool right) implements Bool {}

  public record Or(Bool left, Bool right) implements Bool {}

  public record Not(Bool condition) implements Bool {}

  public record Pred(Predicate predicate) implements Bool {}

  public sealed interface Predicate permits Compare, IsNull, Like, InSubquery, Exists {}

  /** {@code EXISTS (SELECT …)}. */
  public record Exists(Select query) implements Predicate {}

  public record Compare(Expr left, CompareOp op, Expr right) implements Predicate {}

  public record IsNull(Expr expr, boolean not) implements Predicate {}

  public record Like(Expr left, Expr right, char escape) implements Predicate {}

  /** {@code left [NOT] IN (SELECT ...)} — single-column subquery membership. */
  public record InSubquery(Expr left, Select query, boolean not) implements Predicate {}

  public enum CompareOp {
    EQ,
    LT,
    GT,
    LE,
    GE,
    NE
  }

  public sealed interface Literal permits NumberLiteral, StringLiteral, NullLiteral {}

  public record NumberLiteral(long value) implements Literal {}

  public record StringLiteral(String value) implements Literal {}

  public record NullLiteral() implements Literal {}
}
