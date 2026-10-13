package com.myano.jsqlite.admin;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

final class SchemaIcons {
  static final ImageIcon DATABASE = icon(SchemaIcons::database);
  static final ImageIcon TABLE = icon(SchemaIcons::table);
  static final ImageIcon INTEGER = icon(graphics -> badge(graphics, "#", new Color(0x1F7A6B)));
  static final ImageIcon TEXT = icon(graphics -> badge(graphics, "T", new Color(0xC4A15A)));

  private SchemaIcons() {
  }

  private static ImageIcon icon(Painter painter) {
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    painter.paint(graphics);
    graphics.dispose();
    return new ImageIcon(image);
  }

  private static void database(Graphics2D graphics) {
    int x = 2;
    int width = 12;
    int height = 5;
    int[] tops = {1, 5, 9};
    graphics.setColor(Brand.INK);
    graphics.fillRect(x, tops[0] + 2, width, tops[2] - tops[0] + 1);
    for (int index = 2; index >= 0; index--) {
      graphics.setColor(Brand.INK);
      graphics.fillOval(x, tops[index], width, height);
      graphics.setColor(Brand.TEAL);
      graphics.fillOval(x + 1, tops[index], width - 2, 3);
    }
  }

  private static void table(Graphics2D graphics) {
    graphics.setColor(Brand.INK);
    graphics.fillRoundRect(1, 2, 14, 12, 3, 3);
    graphics.setColor(Brand.TEAL);
    graphics.fillRect(2, 3, 12, 3);
    graphics.setColor(Color.WHITE);
    graphics.fillRect(2, 6, 12, 7);
    graphics.setColor(Brand.LINE);
    graphics.drawLine(2, 9, 13, 9);
    graphics.drawLine(7, 6, 7, 12);
    graphics.setColor(Brand.INK);
    graphics.drawRoundRect(1, 2, 13, 11, 3, 3);
  }

  private static void badge(Graphics2D graphics, String mark, Color color) {
    graphics.setColor(color);
    graphics.fillRoundRect(1, 1, 14, 14, 4, 4);
    graphics.setColor(Color.WHITE);
    graphics.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
    int width = graphics.getFontMetrics().stringWidth(mark);
    int ascent = graphics.getFontMetrics().getAscent();
    int descent = graphics.getFontMetrics().getDescent();
    graphics.drawString(mark, 8 - width / 2, 8 + (ascent - descent) / 2);
  }

  @FunctionalInterface
  private interface Painter {
    void paint(Graphics2D graphics);
  }
}
