package com.myano.jsqlite.sql;

import com.myano.jsqlite.sql.Ast.AlterTable;
import com.myano.jsqlite.sql.Ast.Assign;
import com.myano.jsqlite.sql.Ast.Bool;
import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Compare;
import com.myano.jsqlite.sql.Ast.Concat;
import com.myano.jsqlite.sql.Ast.CreateIndex;
import com.myano.jsqlite.sql.Ast.CreateTable;
import com.myano.jsqlite.sql.Ast.Delete;
import com.myano.jsqlite.sql.Ast.Expr;
import com.myano.jsqlite.sql.Ast.ExpressionItem;
import com.myano.jsqlite.sql.Ast.IfNullExpr;
import com.myano.jsqlite.sql.Ast.Insert;
import com.myano.jsqlite.sql.Ast.IsNull;
import com.myano.jsqlite.sql.Ast.Join;
import com.myano.jsqlite.sql.Ast.Like;
import com.myano.jsqlite.sql.Ast.LiteralExpr;
import com.myano.jsqlite.sql.Ast.Now;
import com.myano.jsqlite.sql.Ast.OrderItem;
import com.myano.jsqlite.sql.Ast.Parameter;
import com.myano.jsqlite.sql.Ast.Pragma;
import com.myano.jsqlite.sql.Ast.Pred;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.SelectItem;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Ast.Update;

public final class Parameters {
  private Parameters() {}

  public static int count(Statement statement) {
    return switch (statement) {
      case CreateTable ignored -> 0;
      case CreateIndex ignored -> 0;
      case AlterTable ignored -> 0;
      case Insert insert -> countInsert(insert);
      case Select select -> countSelect(select);
      case Update update -> countUpdate(update);
      case Delete delete -> countBool(delete.where());
      case Pragma ignored -> 0;
    };
  }

  private static int countInsert(Insert insert) {
    int count = 0;
    for (Expr value : insert.values()) {
      count += countExpr(value);
    }
    if (insert.upsert() != null) {
      for (Assign assign : insert.upsert().assignments()) {
        count += countExpr(assign.value());
      }
    }
    return count;
  }

  private static int countSelect(Select select) {
    int count = 0;
    for (SelectItem item : select.items()) {
      if (item instanceof ExpressionItem expression) {
        count += countExpr(expression.expr());
      }
    }
    if (select.fromSelect() != null) {
      count += countSelect(select.fromSelect());
    }
    for (Join join : select.joins()) {
      count += countBool(join.on());
    }
    count += countBool(select.where());
    for (OrderItem item : select.orderBy()) {
      count += countExpr(item.expr());
    }
    count += countExpr(select.limit());
    count += countExpr(select.offset());
    return count;
  }

  private static int countUpdate(Update update) {
    int count = 0;
    for (Assign assign : update.assignments()) {
      count += countExpr(assign.value());
    }
    return count + countBool(update.where());
  }

  private static int countBool(Bool condition) {
    if (condition == null) {
      return 0;
    }
    return switch (condition) {
      case Ast.And and -> countBool(and.left()) + countBool(and.right());
      case Ast.Or or -> countBool(or.left()) + countBool(or.right());
      case Pred pred -> switch (pred.predicate()) {
        case Compare compare -> countExpr(compare.left()) + countExpr(compare.right());
        case IsNull isNull -> countExpr(isNull.expr());
        case Like like -> countExpr(like.left()) + countExpr(like.right());
      };
    };
  }

  private static int countExpr(Expr expr) {
    if (expr == null) {
      return 0;
    }
    return switch (expr) {
      case Parameter ignored -> 1;
      case LiteralExpr ignored -> 0;
      case ColumnRef ignored -> 0;
      case Now ignored -> 0;
      case Concat concat -> countExpr(concat.left()) + countExpr(concat.right());
      case IfNullExpr ifNull -> countExpr(ifNull.value()) + countExpr(ifNull.fallback());
    };
  }
}
