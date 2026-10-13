package com.myano.jsqlite.sql;

import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.TableConstraint;

import java.util.List;

public final class Ast {
  private Ast() {}

  public sealed interface Statement
      permits CreateTable, CreateIndex, AlterTable, Insert, Select, Update, Delete, Pragma {}

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

  public record Join(boolean left, String table, String alias, Bool on) {}

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
      Expr offset)
      implements Statement {
    public Select {
      items = List.copyOf(items);
      joins = List.copyOf(joins);
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

  public sealed interface SelectItem permits Star, ExpressionItem, CountStar, MaxItem {}

  public record Star() implements SelectItem {}

  public record ExpressionItem(Expr expr, String alias) implements SelectItem {
    public ExpressionItem(Expr expr) {
      this(expr, null);
    }
  }

  public record CountStar() implements SelectItem {}

  public record MaxItem(String column) implements SelectItem {}

  public sealed interface Expr permits LiteralExpr, ColumnRef, Concat, IfNullExpr, Now, Parameter {}

  public record LiteralExpr(Literal literal) implements Expr {}

  public record ColumnRef(String table, String name) implements Expr {}

  public record Concat(Expr left, Expr right) implements Expr {}

  public record IfNullExpr(Expr value, Expr fallback) implements Expr {}

  public record Now() implements Expr {}

  public record Parameter(int index) implements Expr {}

  public sealed interface Bool permits And, Or, Pred {}

  public record And(Bool left, Bool right) implements Bool {}

  public record Or(Bool left, Bool right) implements Bool {}

  public record Pred(Predicate predicate) implements Bool {}

  public sealed interface Predicate permits Compare, IsNull, Like {}

  public record Compare(Expr left, CompareOp op, Expr right) implements Predicate {}

  public record IsNull(Expr expr, boolean not) implements Predicate {}

  public record Like(Expr left, Expr right) implements Predicate {}

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
