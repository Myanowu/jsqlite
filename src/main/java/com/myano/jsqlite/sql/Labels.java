package com.myano.jsqlite.sql;

import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Concat;
import com.myano.jsqlite.sql.Ast.CountStar;
import com.myano.jsqlite.sql.Ast.Expr;
import com.myano.jsqlite.sql.Ast.ExpressionItem;
import com.myano.jsqlite.sql.Ast.IfNullExpr;
import com.myano.jsqlite.sql.Ast.Literal;
import com.myano.jsqlite.sql.Ast.LiteralExpr;
import com.myano.jsqlite.sql.Ast.MaxItem;
import com.myano.jsqlite.sql.Ast.Now;
import com.myano.jsqlite.sql.Ast.NullLiteral;
import com.myano.jsqlite.sql.Ast.NumberLiteral;
import com.myano.jsqlite.sql.Ast.Parameter;
import com.myano.jsqlite.sql.Ast.SelectItem;
import com.myano.jsqlite.sql.Ast.Star;
import com.myano.jsqlite.sql.Ast.StringLiteral;

public final class Labels {
  private Labels() {}

  public static String item(SelectItem item) {
    return switch (item) {
      case Star ignored -> "*";
      case CountStar ignored -> "COUNT(*)";
      case MaxItem max -> "MAX(" + max.column() + ")";
      case ExpressionItem expression ->
          expression.alias() != null ? expression.alias() : expr(expression.expr());
    };
  }

  public static String expr(Expr expr) {
    return switch (expr) {
      case ColumnRef column -> column.name();
      case LiteralExpr literal -> literal(literal.literal());
      case Parameter ignored -> "?";
      case Now ignored -> "datetime('now')";
      case IfNullExpr ignored -> "IFNULL";
      case Concat ignored -> "||";
    };
  }

  private static String literal(Literal literal) {
    return switch (literal) {
      case NumberLiteral number -> Long.toString(number.value());
      case StringLiteral string -> string.value();
      case NullLiteral ignored -> "NULL";
    };
  }
}
