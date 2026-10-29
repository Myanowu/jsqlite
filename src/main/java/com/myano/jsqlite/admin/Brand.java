package com.myano.jsqlite.admin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

final class Brand {
  static final Color INK = new Color(0x0E3A4D);
  static final Color TEAL = new Color(0x1F7A6B);
  static final Color PAPER = new Color(0xF3F6F5);
  static final Color SURFACE = Color.WHITE;
  static final Color LINE = new Color(0xD5E0DC);
  static final Color ZEBRA = new Color(0xF4F8F6);
  static final Color SELECT = new Color(0xD7EBE4);
  static final Color MUTED = new Color(0x5E736C);
  static final Color ERROR = new Color(0x9B2335);

  private static final BufferedImage SOURCE = load();

  private Brand() {
  }

  static void install(Window window) {
    window.setIconImages(icons());
  }

  static ImageIcon icon(int size) {
    return new ImageIcon(image(size));
  }

  private static List<Image> icons() {
    List<Image> images = new ArrayList<>();
    for (int size : new int[] {16, 20, 24, 32, 40, 48, 64, 256}) {
      images.add(image(size));
    }
    return images;
  }

  private static BufferedImage image(int size) {
    BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = out.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.drawImage(SOURCE, 0, 0, size, size, null);
    graphics.dispose();
    return out;
  }

  private static BufferedImage load() {
    try (var input = Brand.class.getResourceAsStream("logo.png")) {
      if (input != null) {
        BufferedImage image = ImageIO.read(input);
        if (image != null) {
          return image;
        }
      }
    } catch (IOException ignored) {
      // Fall through to the drawn mark so a missing resource cannot kill admin.cmd.
    }
    return drawnMark();
  }

  /** Fallback window icon when logo.png is not on the classpath. */
  private static BufferedImage drawnMark() {
    int size = 256;
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setColor(INK);
    graphics.fillRoundRect(16, 16, size - 32, size - 32, 48, 48);
    graphics.setColor(TEAL);
    graphics.fillRoundRect(48, 48, size - 96, size - 96, 36, 36);
    graphics.setColor(Color.WHITE);
    graphics.fillOval(88, 88, 80, 80);
    graphics.dispose();
    return image;
  }
}
