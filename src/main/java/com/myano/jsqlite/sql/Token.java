package com.myano.jsqlite.sql;

public record Token(TokenType type, String text, long number, int offset) {
  public static Token of(TokenType type, String text, int offset) {
    return new Token(type, text, 0, offset);
  }

  public static Token number(String text, long number, int offset) {
    return new Token(TokenType.NUMBER, text, number, offset);
  }

  public static Token eof(int offset) {
    return new Token(TokenType.EOF, "", 0, offset);
  }
}
