package com.myano.jsqlite.sql;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.sql.Ast.AlterTable;
import com.myano.jsqlite.sql.Ast.And;
import com.myano.jsqlite.sql.Ast.Assign;
import com.myano.jsqlite.sql.Ast.Bool;
import com.myano.jsqlite.sql.Ast.ColumnRef;
import com.myano.jsqlite.sql.Ast.Compare;
import com.myano.jsqlite.sql.Ast.CompareOp;
import com.myano.jsqlite.sql.Ast.Concat;
import com.myano.jsqlite.sql.Ast.CountStar;
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
import com.myano.jsqlite.sql.Ast.Literal;
import com.myano.jsqlite.sql.Ast.LiteralExpr;
import com.myano.jsqlite.sql.Ast.MaxItem;
import com.myano.jsqlite.sql.Ast.Now;
import com.myano.jsqlite.sql.Ast.NullLiteral;
import com.myano.jsqlite.sql.Ast.NumberLiteral;
import com.myano.jsqlite.sql.Ast.Or;
import com.myano.jsqlite.sql.Ast.OrderItem;
import com.myano.jsqlite.sql.Ast.Parameter;
import com.myano.jsqlite.sql.Ast.Pred;
import com.myano.jsqlite.sql.Ast.Select;
import com.myano.jsqlite.sql.Ast.SelectItem;
import com.myano.jsqlite.sql.Ast.Star;
import com.myano.jsqlite.sql.Ast.Statement;
import com.myano.jsqlite.sql.Ast.StringLiteral;
import com.myano.jsqlite.sql.Ast.Update;
import com.myano.jsqlite.sql.Ast.Upsert;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.ForeignKeyConstraint;
import com.myano.jsqlite.store.PrimaryKeyConstraint;
import com.myano.jsqlite.store.TableConstraint;
import com.myano.jsqlite.store.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

public final class Parser {
  public Statement parse(String sql) {
    if (sql == null || sql.isBlank()) {
      throw new SqlException("empty SQL");
    }
    List<Token> tokens = new Tokenizer(sql).tokenize();
    Cursor cursor = new Cursor(sql, tokens);
    Statement statement = cursor.parseStatement();
    cursor.match(TokenType.SEMI);
    if (cursor.peek().type() != TokenType.EOF) {
      throw new SqlException("only one statement is allowed: " + SqlText.snippet(sql));
    }
    return statement;
  }

  private static final class Cursor {
    private final String sql;
    private final List<Token> tokens;
    private int pos;
    private int parameters;

    private Cursor(String sql, List<Token> tokens) {
      this.sql = sql;
      this.tokens = tokens;
    }

    private Statement parseStatement() {
      return switch (peek().type()) {
        case CREATE -> parseCreate();
        case ALTER -> parseAlter();
        case INSERT -> parseInsert();
        case SELECT -> parseSelect();
        case UPDATE -> parseUpdate();
        case DELETE -> parseDelete();
        case PRAGMA -> parsePragma();
        default -> throw syntax("expected a statement");
      };
    }

    private Statement parsePragma() {
      expect(TokenType.PRAGMA);
      String name = expect(TokenType.IDENT).text();
      String argument = null;
      if (match(TokenType.LPAREN)) {
        argument = expect(TokenType.IDENT).text();
        expect(TokenType.RPAREN);
      }
      String value = null;
      if (match(TokenType.EQ)) {
        value = readPragmaValue();
      }
      return new Ast.Pragma(name, argument, value);
    }

    private String readPragmaValue() {
      Token token = peek();
      if (token.type() == TokenType.IDENT
          || token.type() == TokenType.NUMBER
          || token.type() == TokenType.ON
          || token.type() == TokenType.DELETE) {
        pos++;
        return token.text();
      }
      throw syntax("expected a pragma value");
    }

    private Statement parseCreate() {
      expect(TokenType.CREATE);
      if (match(TokenType.UNIQUE)) {
        return parseIndex(true);
      }
      if (match(TokenType.INDEX)) {
        return parseIndexBody(false);
      }
      expect(TokenType.TABLE);
      boolean ifNotExists = matchIfNotExists();
      String name = expect(TokenType.IDENT).text();
      expect(TokenType.LPAREN);
      List<Column> columns = new ArrayList<>();
      List<TableConstraint> constraints = new ArrayList<>();
      columns.add(parseColumn());
      while (match(TokenType.COMMA)) {
        if (isTableConstraint()) {
          constraints.add(parseTableConstraint());
        } else {
          columns.add(parseColumn());
        }
      }
      expect(TokenType.RPAREN);
      return new CreateTable(name, ifNotExists, columns, constraints);
    }

    private Statement parseIndex(boolean unique) {
      expect(TokenType.INDEX);
      return parseIndexBody(unique);
    }

    private Statement parseIndexBody(boolean unique) {
      boolean ifNotExists = matchIfNotExists();
      String name = expect(TokenType.IDENT).text();
      expect(TokenType.ON);
      String table = expect(TokenType.IDENT).text();
      expect(TokenType.LPAREN);
      List<String> columns = parseIdentList();
      expect(TokenType.RPAREN);
      String partial = null;
      if (match(TokenType.WHERE)) {
        partial = expect(TokenType.IDENT).text();
        expect(TokenType.IS);
        expect(TokenType.NOT);
        expect(TokenType.NULL);
      }
      return new CreateIndex(name, unique, ifNotExists, table, columns, partial);
    }

    private Statement parseAlter() {
      expect(TokenType.ALTER);
      expect(TokenType.TABLE);
      String table = expect(TokenType.IDENT).text();
      expect(TokenType.ADD);
      expect(TokenType.COLUMN);
      return new AlterTable(table, parseColumn());
    }

    private boolean matchIfNotExists() {
      if (!match(TokenType.IF)) {
        return false;
      }
      expect(TokenType.NOT);
      expect(TokenType.EXISTS);
      return true;
    }

    private boolean isTableConstraint() {
      TokenType type = peek().type();
      return type == TokenType.PRIMARY
          || type == TokenType.UNIQUE
          || type == TokenType.FOREIGN
          || type == TokenType.CONSTRAINT;
    }

    private TableConstraint parseTableConstraint() {
      if (match(TokenType.CONSTRAINT)) {
        expect(TokenType.IDENT);
      }
      if (match(TokenType.PRIMARY)) {
        expect(TokenType.KEY);
        expect(TokenType.LPAREN);
        List<String> columns = parseIdentList();
        expect(TokenType.RPAREN);
        return new PrimaryKeyConstraint(columns);
      }
      if (match(TokenType.UNIQUE)) {
        expect(TokenType.LPAREN);
        List<String> columns = parseIdentList();
        expect(TokenType.RPAREN);
        return new UniqueConstraint(columns);
      }
      if (match(TokenType.FOREIGN)) {
        expect(TokenType.KEY);
        expect(TokenType.LPAREN);
        List<String> columns = parseIdentList();
        expect(TokenType.RPAREN);
        expect(TokenType.REFERENCES);
        String refTable = expect(TokenType.IDENT).text();
        expect(TokenType.LPAREN);
        List<String> refColumns = parseIdentList();
        expect(TokenType.RPAREN);
        while (match(TokenType.ON)) {
          if (!match(TokenType.DELETE) && !match(TokenType.UPDATE)) {
            throw syntax("expected DELETE or UPDATE");
          }
          skipReferentialAction();
        }
        // Engine stores single-column FKs; composite is accepted for schema load only.
        String column = columns.isEmpty() ? "" : columns.get(0);
        String refColumn = refColumns.isEmpty() ? "" : refColumns.get(0);
        return new ForeignKeyConstraint(column, refTable, refColumn);
      }
      throw syntax("expected a table constraint");
    }

    private void skipReferentialAction() {
      if (match(TokenType.SET)) {
        if (match(TokenType.NULL)) {
          return;
        }
        if (peek().type() == TokenType.IDENT) {
          advance();
          return;
        }
        throw syntax("expected NULL or DEFAULT");
      }
      if (peek().type() != TokenType.IDENT) {
        throw syntax("expected referential action");
      }
      String word = advance().text().toUpperCase(java.util.Locale.ROOT);
      if (word.equals("CASCADE") || word.equals("RESTRICT")) {
        return;
      }
      if (word.equals("NO")) {
        if (peek().type() != TokenType.IDENT
            || !"ACTION".equalsIgnoreCase(peek().text())) {
          throw syntax("expected ACTION");
        }
        advance();
        return;
      }
      throw syntax("expected referential action");
    }

    private Column parseColumn() {
      String name = expect(TokenType.IDENT).text();
      ColumnType type = parseType();
      boolean notNull = false;
      boolean primaryKey = false;
      boolean autoincrement = false;
      boolean unique = false;
      boolean hasDefault = false;
      Object defaultValue = null;
      boolean defaultNow = false;
      boolean constrained = false;
      while (true) {
        if (match(TokenType.NOT)) {
          expect(TokenType.NULL);
          notNull = true;
          constrained = true;
          continue;
        }
        if (match(TokenType.PRIMARY)) {
          expect(TokenType.KEY);
          primaryKey = true;
          constrained = true;
          if (match(TokenType.AUTOINCREMENT)) {
            autoincrement = true;
          }
          continue;
        }
        if (match(TokenType.UNIQUE)) {
          unique = true;
          constrained = true;
          continue;
        }
        if (match(TokenType.DEFAULT)) {
          if (match(TokenType.LPAREN)) {
            expect(TokenType.DATETIME);
            expect(TokenType.LPAREN);
            Token now = expect(TokenType.STRING);
            if (!"now".equals(now.text())) {
              throw syntax("expected 'now'");
            }
            expect(TokenType.RPAREN);
            expect(TokenType.RPAREN);
            hasDefault = true;
            defaultNow = true;
          } else {
            Literal literal = parseLiteral();
            hasDefault = true;
            defaultValue = switch (literal) {
              case NumberLiteral number -> number.value();
              case StringLiteral string -> string.value();
              case NullLiteral ignored -> null;
            };
          }
          constrained = true;
          continue;
        }
        break;
      }
      if (!constrained) {
        return new Column(name, type);
      }
      return new Column(
          name, type, notNull, primaryKey, autoincrement, unique, hasDefault, defaultValue, defaultNow);
    }

    private ColumnType parseType() {
      // SQLite type names are affinity rules. Identity / Spring Session DDL use CHARACTER(n),
      // VARCHAR, BIGINT, etc. Map everything to INTEGER or TEXT for this engine.
      if (peek().type() != TokenType.INTEGER
          && peek().type() != TokenType.TEXT
          && peek().type() != TokenType.IDENT) {
        return ColumnType.TEXT;
      }
      StringBuilder name = new StringBuilder();
      if (match(TokenType.INTEGER)) {
        name.append("INTEGER");
      } else if (match(TokenType.TEXT)) {
        name.append("TEXT");
      } else {
        name.append(advance().text());
        while (peek().type() == TokenType.IDENT) {
          String next = peek().text().toUpperCase(java.util.Locale.ROOT);
          if (!next.equals("CHARACTER")
              && !next.equals("PRECISION")
              && !next.equals("VARYING")) {
            break;
          }
          name.append(' ').append(advance().text());
        }
      }
      if (match(TokenType.LPAREN)) {
        expect(TokenType.NUMBER);
        if (match(TokenType.COMMA)) {
          expect(TokenType.NUMBER);
        }
        expect(TokenType.RPAREN);
      }
      return affinity(name.toString());
    }

    private static ColumnType affinity(String typeName) {
      String upper = typeName.toUpperCase(java.util.Locale.ROOT);
      if (upper.contains("INT") || upper.equals("BOOLEAN") || upper.equals("BOOL")) {
        return ColumnType.INTEGER;
      }
      return ColumnType.TEXT;
    }

    private Statement parseInsert() {
      expect(TokenType.INSERT);
      boolean orIgnore = false;
      if (match(TokenType.OR)) {
        expect(TokenType.IGNORE);
        orIgnore = true;
      }
      expect(TokenType.INTO);
      String table = expect(TokenType.IDENT).text();
      List<String> columns = null;
      if (match(TokenType.LPAREN)) {
        columns = parseIdentList();
        expect(TokenType.RPAREN);
      }
      expect(TokenType.VALUES);
      expect(TokenType.LPAREN);
      List<Expr> values = new ArrayList<>();
      values.add(parseExpr());
      while (match(TokenType.COMMA)) {
        values.add(parseExpr());
      }
      expect(TokenType.RPAREN);
      Upsert upsert = null;
      if (match(TokenType.ON)) {
        if (orIgnore) {
          throw syntax("OR IGNORE cannot be combined with ON CONFLICT");
        }
        expect(TokenType.CONFLICT);
        expect(TokenType.LPAREN);
        List<String> conflict = parseIdentList();
        expect(TokenType.RPAREN);
        expect(TokenType.DO);
        expect(TokenType.UPDATE);
        expect(TokenType.SET);
        List<Assign> assignments = new ArrayList<>();
        assignments.add(parseAssign());
        while (match(TokenType.COMMA)) {
          assignments.add(parseAssign());
        }
        upsert = new Upsert(conflict, assignments);
      }
      return new Insert(table, columns, values, orIgnore, upsert);
    }

    private Statement parseSelect() {
      expect(TokenType.SELECT);
      boolean distinct = match(TokenType.DISTINCT);
      List<SelectItem> items = new ArrayList<>();
      items.add(parseSelectItem());
      while (match(TokenType.COMMA)) {
        items.add(parseSelectItem());
      }
      String table = null;
      Select fromSelect = null;
      String alias = null;
      List<Join> joins = new ArrayList<>();
      if (match(TokenType.FROM)) {
        if (match(TokenType.LPAREN)) {
          fromSelect = (Select) parseSelect();
          expect(TokenType.RPAREN);
          alias = parseOptionalAlias();
          if (alias == null) {
            throw syntax("derived table needs an alias");
          }
        } else {
          table = expect(TokenType.IDENT).text();
          alias = parseOptionalAlias();
        }
        while (isJoin()) {
          joins.add(parseJoin());
        }
      }
      Bool where = parseOptionalWhere();
      List<OrderItem> orderBy = new ArrayList<>();
      if (match(TokenType.ORDER)) {
        expect(TokenType.BY);
        orderBy.add(parseOrderItem());
        while (match(TokenType.COMMA)) {
          orderBy.add(parseOrderItem());
        }
      }
      Expr limit = null;
      Expr offset = null;
      if (match(TokenType.LIMIT)) {
        limit = parseLimit();
        if (match(TokenType.OFFSET)) {
          offset = parseLimit();
        }
      }
      return new Select(distinct, items, table, fromSelect, alias, joins, where, orderBy, limit, offset);
    }

    private boolean isJoin() {
      TokenType type = peek().type();
      return type == TokenType.JOIN || type == TokenType.INNER || type == TokenType.LEFT;
    }

    private Join parseJoin() {
      boolean left = match(TokenType.LEFT);
      if (!left) {
        match(TokenType.INNER);
      }
      expect(TokenType.JOIN);
      String table = expect(TokenType.IDENT).text();
      String alias = parseOptionalAlias();
      expect(TokenType.ON);
      return new Join(left, table, alias, parseCondition());
    }

    private String parseOptionalAlias() {
      if (match(TokenType.AS)) {
        return expect(TokenType.IDENT).text();
      }
      if (peek().type() == TokenType.IDENT) {
        return advance().text();
      }
      return null;
    }

    private SelectItem parseSelectItem() {
      if (match(TokenType.STAR)) {
        return new Star();
      }
      if (match(TokenType.COUNT)) {
        expect(TokenType.LPAREN);
        expect(TokenType.STAR);
        expect(TokenType.RPAREN);
        return new CountStar();
      }
      if (match(TokenType.MAX)) {
        expect(TokenType.LPAREN);
        String column = expect(TokenType.IDENT).text();
        expect(TokenType.RPAREN);
        return new MaxItem(column);
      }
      return new ExpressionItem(parseExpr(), parseOptionalAlias());
    }

    private OrderItem parseOrderItem() {
      Expr expr = parseExpr();
      boolean descending = false;
      if (match(TokenType.DESC)) {
        descending = true;
      } else {
        match(TokenType.ASC);
      }
      return new OrderItem(expr, descending);
    }

    private Expr parseLimit() {
      if (match(TokenType.PARAM)) {
        return new Parameter(parameters++);
      }
      return new LiteralExpr(parseLiteral());
    }

    private Statement parseUpdate() {
      expect(TokenType.UPDATE);
      String table = expect(TokenType.IDENT).text();
      expect(TokenType.SET);
      List<Assign> assignments = new ArrayList<>();
      assignments.add(parseAssign());
      while (match(TokenType.COMMA)) {
        assignments.add(parseAssign());
      }
      return new Update(table, assignments, parseOptionalWhere());
    }

    private Assign parseAssign() {
      String column = expect(TokenType.IDENT).text();
      expect(TokenType.EQ);
      return new Assign(column, parseExpr());
    }

    private Statement parseDelete() {
      expect(TokenType.DELETE);
      expect(TokenType.FROM);
      String table = expect(TokenType.IDENT).text();
      return new Delete(table, parseOptionalWhere());
    }

    private Bool parseOptionalWhere() {
      if (!match(TokenType.WHERE)) {
        return null;
      }
      return parseCondition();
    }

    private Bool parseCondition() {
      Bool left = parseAnd();
      while (match(TokenType.OR)) {
        left = new Or(left, parseAnd());
      }
      return left;
    }

    private Bool parseAnd() {
      Bool left = parsePredicate();
      while (match(TokenType.AND)) {
        left = new And(left, parsePredicate());
      }
      return left;
    }

    private Bool parsePredicate() {
      if (match(TokenType.LPAREN)) {
        Bool nested = parseCondition();
        expect(TokenType.RPAREN);
        return nested;
      }
      Expr left = parseExpr();
      if (match(TokenType.IS)) {
        boolean not = match(TokenType.NOT);
        expect(TokenType.NULL);
        return new Pred(new IsNull(left, not));
      }
      if (match(TokenType.NOT)) {
        expect(TokenType.IN);
        return parseInList(left, true);
      }
      if (match(TokenType.IN)) {
        return parseInList(left, false);
      }
      if (match(TokenType.LIKE)) {
        return new Pred(new Like(left, parseExpr()));
      }
      return new Pred(new Compare(left, parseCompareOp(), parseExpr()));
    }

    private Bool parseInList(Expr left, boolean not) {
      expect(TokenType.LPAREN);
      List<Expr> values = new ArrayList<>();
      values.add(parseExpr());
      while (match(TokenType.COMMA)) {
        values.add(parseExpr());
      }
      expect(TokenType.RPAREN);
      if (values.isEmpty()) {
        throw syntax("empty IN list");
      }
      Bool combined = null;
      CompareOp op = not ? CompareOp.NE : CompareOp.EQ;
      for (Expr value : values) {
        Bool term = new Pred(new Compare(left, op, value));
        combined =
            combined == null ? term : (not ? new And(combined, term) : new Or(combined, term));
      }
      return combined;
    }

    private CompareOp parseCompareOp() {
      if (match(TokenType.EQ)) {
        return CompareOp.EQ;
      }
      if (match(TokenType.LE)) {
        return CompareOp.LE;
      }
      if (match(TokenType.GE)) {
        return CompareOp.GE;
      }
      if (match(TokenType.NE)) {
        return CompareOp.NE;
      }
      if (match(TokenType.LT)) {
        return CompareOp.LT;
      }
      if (match(TokenType.GT)) {
        return CompareOp.GT;
      }
      throw syntax("expected a comparison");
    }

    private Expr parseExpr() {
      Expr left = parsePrimary();
      while (match(TokenType.CONCAT)) {
        left = new Concat(left, parsePrimary());
      }
      return left;
    }

    private Expr parsePrimary() {
      if (match(TokenType.PARAM)) {
        return new Parameter(parameters++);
      }
      if (peek().type() == TokenType.NUMBER
          || peek().type() == TokenType.STRING
          || peek().type() == TokenType.NULL) {
        return new LiteralExpr(parseLiteral());
      }
      if (match(TokenType.IFNULL)) {
        expect(TokenType.LPAREN);
        Expr value = parseExpr();
        expect(TokenType.COMMA);
        Expr fallback = parseExpr();
        expect(TokenType.RPAREN);
        return new IfNullExpr(value, fallback);
      }
      if (match(TokenType.COALESCE)) {
        expect(TokenType.LPAREN);
        Expr value = parseExpr();
        expect(TokenType.COMMA);
        Expr result = new IfNullExpr(value, parseExpr());
        while (match(TokenType.COMMA)) {
          result = new IfNullExpr(result, parseExpr());
        }
        expect(TokenType.RPAREN);
        return result;
      }
      if (match(TokenType.DATETIME)) {
        expect(TokenType.LPAREN);
        Token now = expect(TokenType.STRING);
        if (!"now".equals(now.text())) {
          throw syntax("expected 'now'");
        }
        expect(TokenType.RPAREN);
        return new Now();
      }
      if (peek().type() == TokenType.IDENT) {
        String name = advance().text();
        if (match(TokenType.DOT)) {
          return new ColumnRef(name, expect(TokenType.IDENT).text());
        }
        return new ColumnRef(null, name);
      }
      throw syntax("expected an expression");
    }

    private Literal parseLiteral() {
      Token token = peek();
      if (match(TokenType.NUMBER)) {
        return new NumberLiteral(token.number());
      }
      if (match(TokenType.STRING)) {
        return new StringLiteral(token.text());
      }
      if (match(TokenType.NULL)) {
        return new NullLiteral();
      }
      throw syntax("expected a literal");
    }

    private List<String> parseIdentList() {
      List<String> names = new ArrayList<>();
      names.add(expect(TokenType.IDENT).text());
      while (match(TokenType.COMMA)) {
        names.add(expect(TokenType.IDENT).text());
      }
      return names;
    }

    private boolean match(TokenType type) {
      if (peek().type() != type) {
        return false;
      }
      advance();
      return true;
    }

    private Token expect(TokenType type) {
      if (peek().type() != type) {
        throw syntax("expected " + type);
      }
      return advance();
    }

    private Token peek() {
      return tokens.get(pos);
    }

    private Token advance() {
      Token token = peek();
      if (token.type() != TokenType.EOF) {
        pos++;
      }
      return token;
    }

    private SqlException syntax(String detail) {
      Token token = peek();
      String near = token.type() == TokenType.EOF ? "end of input" : token.text();
      if (near.isEmpty()) {
        near = token.type().name();
      }
      return new SqlException(
          detail + " near '" + near + "' at offset " + token.offset() + ": " + SqlText.snippet(sql));
    }
  }
}
