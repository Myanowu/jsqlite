package com.myano.jsqlite.sql;

import com.myano.jsqlite.SqlException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Tokenizer {
  private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
      Map.entry("CREATE", TokenType.CREATE),
      Map.entry("TABLE", TokenType.TABLE),
      Map.entry("INTEGER", TokenType.INTEGER),
      Map.entry("TEXT", TokenType.TEXT),
      Map.entry("INSERT", TokenType.INSERT),
      Map.entry("INTO", TokenType.INTO),
      Map.entry("VALUES", TokenType.VALUES),
      Map.entry("SELECT", TokenType.SELECT),
      Map.entry("DISTINCT", TokenType.DISTINCT),
      Map.entry("UNION", TokenType.UNION),
      Map.entry("ALL", TokenType.ALL),
      Map.entry("CAST", TokenType.CAST),
      Map.entry("FROM", TokenType.FROM),
      Map.entry("AS", TokenType.AS),
      Map.entry("WHERE", TokenType.WHERE),
      Map.entry("UPDATE", TokenType.UPDATE),
      Map.entry("SET", TokenType.SET),
      Map.entry("DELETE", TokenType.DELETE),
      Map.entry("AND", TokenType.AND),
      Map.entry("OR", TokenType.OR),
      Map.entry("NULL", TokenType.NULL),
      Map.entry("IF", TokenType.IF),
      Map.entry("NOT", TokenType.NOT),
      Map.entry("EXISTS", TokenType.EXISTS),
      Map.entry("PRIMARY", TokenType.PRIMARY),
      Map.entry("KEY", TokenType.KEY),
      Map.entry("AUTOINCREMENT", TokenType.AUTOINCREMENT),
      Map.entry("UNIQUE", TokenType.UNIQUE),
      Map.entry("DEFAULT", TokenType.DEFAULT),
      Map.entry("INDEX", TokenType.INDEX),
      Map.entry("ON", TokenType.ON),
      Map.entry("JOIN", TokenType.JOIN),
      Map.entry("INNER", TokenType.INNER),
      Map.entry("LEFT", TokenType.LEFT),
      Map.entry("CROSS", TokenType.CROSS),
      Map.entry("OUTER", TokenType.OUTER),
      Map.entry("APPLY", TokenType.APPLY),
      Map.entry("ORDER", TokenType.ORDER),
      Map.entry("GROUP", TokenType.GROUP),
      Map.entry("BY", TokenType.BY),
      Map.entry("LIMIT", TokenType.LIMIT),
      Map.entry("OFFSET", TokenType.OFFSET),
      Map.entry("TOP", TokenType.TOP),
      Map.entry("ASC", TokenType.ASC),
      Map.entry("DESC", TokenType.DESC),
      Map.entry("ALTER", TokenType.ALTER),
      Map.entry("ADD", TokenType.ADD),
      Map.entry("COLUMN", TokenType.COLUMN),
      Map.entry("CONFLICT", TokenType.CONFLICT),
      Map.entry("DO", TokenType.DO),
      Map.entry("IGNORE", TokenType.IGNORE),
      Map.entry("IS", TokenType.IS),
      Map.entry("IN", TokenType.IN),
      Map.entry("LIKE", TokenType.LIKE),
      Map.entry("ESCAPE", TokenType.ESCAPE),
      Map.entry("IFNULL", TokenType.IFNULL),
      Map.entry("ISNULL", TokenType.ISNULL),
      Map.entry("NULLIF", TokenType.NULLIF),
      Map.entry("COALESCE", TokenType.COALESCE),
      Map.entry("DATETIME", TokenType.DATETIME),
      Map.entry("COUNT", TokenType.COUNT),
      Map.entry("MAX", TokenType.MAX),
      Map.entry("SUM", TokenType.SUM),
      Map.entry("ABS", TokenType.ABS),
      Map.entry("HAVING", TokenType.HAVING),
      Map.entry("REFERENCES", TokenType.REFERENCES),
      Map.entry("FOREIGN", TokenType.FOREIGN),
      Map.entry("CONSTRAINT", TokenType.CONSTRAINT),
      Map.entry("PRAGMA", TokenType.PRAGMA),
      Map.entry("DROP", TokenType.DROP),
      Map.entry("SHUTDOWN", TokenType.SHUTDOWN),
      Map.entry("FOR", TokenType.FOR),
      Map.entry("TRUE", TokenType.TRUE),
      Map.entry("FALSE", TokenType.FALSE),
      Map.entry("CASE", TokenType.CASE),
      Map.entry("WHEN", TokenType.WHEN),
      Map.entry("THEN", TokenType.THEN),
      Map.entry("ELSE", TokenType.ELSE),
      Map.entry("END", TokenType.END));

  private static final Set<TokenType> KEYWORD_TYPES = EnumSet.copyOf(KEYWORDS.values());

  public static boolean isKeyword(TokenType type) {
    return KEYWORD_TYPES.contains(type);
  }

  private final String sql;
  private int pos;

  public Tokenizer(String sql) {
    this.sql = sql;
  }

  public List<Token> tokenize() {
    List<Token> tokens = new ArrayList<>();
    while (pos < sql.length()) {
      char c = sql.charAt(pos);
      if (Character.isWhitespace(c)) {
        pos++;
        continue;
      }
      if (c == '-' && pos + 1 < sql.length() && Character.isDigit(sql.charAt(pos + 1))) {
        tokens.add(readNumber());
        continue;
      }
      if (Character.isDigit(c)) {
        tokens.add(readNumber());
        continue;
      }
      if (c == '\'') {
        tokens.add(readString());
        continue;
      }
      if (c == '"') {
        tokens.add(readQuotedIdent());
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        tokens.add(readIdent());
        continue;
      }
      tokens.add(readSymbol());
    }
    tokens.add(Token.eof(pos));
    return tokens;
  }

  private Token readIdent() {
    int start = pos;
    pos++;
    while (pos < sql.length()) {
      char c = sql.charAt(pos);
      if (Character.isLetter(c) || Character.isDigit(c) || c == '_') {
        pos++;
      } else {
        break;
      }
    }
    String text = sql.substring(start, pos);
    TokenType keyword = KEYWORDS.get(text.toUpperCase(Locale.ROOT));
    if (keyword != null) {
      return Token.of(keyword, text, start);
    }
    return Token.of(TokenType.IDENT, text, start);
  }

  private Token readQuotedIdent() {
    int start = pos;
    pos++;
    StringBuilder value = new StringBuilder();
    while (pos < sql.length()) {
      char c = sql.charAt(pos++);
      if (c != '"') {
        value.append(c);
        continue;
      }
      if (pos < sql.length() && sql.charAt(pos) == '"') {
        value.append('"');
        pos++;
        continue;
      }
      return Token.of(TokenType.IDENT, value.toString(), start);
    }
    throw new SqlException("unterminated identifier: " + SqlText.snippet(sql));
  }

  private Token readNumber() {
    int start = pos;
    if (sql.charAt(pos) == '-') {
      pos++;
    }
    while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
      pos++;
    }
    String text = sql.substring(start, pos);
    try {
      return Token.number(text, Long.parseLong(text), start);
    } catch (NumberFormatException ex) {
      throw new SqlException("integer out of range " + text + ": " + SqlText.snippet(sql));
    }
  }

  private Token readString() {
    int start = pos;
    pos++;
    StringBuilder value = new StringBuilder();
    while (pos < sql.length()) {
      char c = sql.charAt(pos++);
      if (c != '\'') {
        value.append(c);
        continue;
      }
      if (pos < sql.length() && sql.charAt(pos) == '\'') {
        value.append('\'');
        pos++;
        continue;
      }
      return Token.of(TokenType.STRING, value.toString(), start);
    }
    throw new SqlException("unterminated string: " + SqlText.snippet(sql));
  }

  private Token readSymbol() {
    int start = pos;
    char c = sql.charAt(pos);
    if (c == '<' && pos + 1 < sql.length()) {
      char next = sql.charAt(pos + 1);
      if (next == '=') {
        pos += 2;
        return Token.of(TokenType.LE, "<=", start);
      }
      if (next == '>') {
        pos += 2;
        return Token.of(TokenType.NE, "<>", start);
      }
    }
    if (c == '>' && pos + 1 < sql.length() && sql.charAt(pos + 1) == '=') {
      pos += 2;
      return Token.of(TokenType.GE, ">=", start);
    }
    if (c == '!' && pos + 1 < sql.length() && sql.charAt(pos + 1) == '=') {
      pos += 2;
      return Token.of(TokenType.NE, "!=", start);
    }
    if (c == '|' && pos + 1 < sql.length() && sql.charAt(pos + 1) == '|') {
      pos += 2;
      return Token.of(TokenType.CONCAT, "||", start);
    }
    if (c == '?') {
      pos++;
      return Token.of(TokenType.PARAM, "?", start);
    }
    pos++;
    TokenType type = switch (c) {
      case '*' -> TokenType.STAR;
      case '(' -> TokenType.LPAREN;
      case ')' -> TokenType.RPAREN;
      case ',' -> TokenType.COMMA;
      case '.' -> TokenType.DOT;
      case '=' -> TokenType.EQ;
      case '<' -> TokenType.LT;
      case '>' -> TokenType.GT;
      case ';' -> TokenType.SEMI;
      default -> null;
    };
    if (type == null) {
      throw new SqlException(
          "unexpected character '" + c + "' at offset " + start + ": " + SqlText.snippet(sql));
    }
    return Token.of(type, String.valueOf(c), start);
  }
}
