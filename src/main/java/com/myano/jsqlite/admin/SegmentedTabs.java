package com.myano.jsqlite.admin;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Two-or-more page switcher that reads as distinct controls, not a single run of text. Selected
 * segment uses a teal underline and filled chip; others stay muted with a light border.
 */
final class SegmentedTabs extends JPanel {
  private final CardLayout cards = new CardLayout();
  private final JPanel body = new JPanel(cards);
  private final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
  private final List<Segment> segments = new ArrayList<>();
  private int selected;

  SegmentedTabs() {
    super(new BorderLayout(0, 10));
    setOpaque(false);
    strip.setOpaque(false);
    strip.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
    body.setOpaque(false);
    add(strip, BorderLayout.NORTH);
    add(body, BorderLayout.CENTER);
  }

  void addPage(String title, JComponent page) {
    String key = "page-" + segments.size();
    body.add(page, key);
    Segment segment = new Segment(title, segments.size());
    segments.add(segment);
    strip.add(segment);
    if (segments.size() == 1) {
      select(0);
    } else {
      segment.setSelected(false);
    }
  }

  private void select(int index) {
    selected = index;
    for (int i = 0; i < segments.size(); i++) {
      segments.get(i).setSelected(i == index);
    }
    cards.show(body, "page-" + index);
    revalidate();
    repaint();
  }

  private final class Segment extends JLabel {
    private final int index;
    private boolean on;

    private Segment(String title, int index) {
      super(title, SwingConstants.CENTER);
      this.index = index;
      setFont(getFont().deriveFont(Font.BOLD, 12.5f));
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
      setPreferredSize(new Dimension(Math.max(108, getPreferredSize().width + 8), 34));
      addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent event) {
          select(index);
        }
      });
    }

    private void setSelected(boolean selected) {
      on = selected;
      setForeground(on ? Brand.INK : Brand.MUTED);
      setFont(getFont().deriveFont(on ? Font.BOLD : Font.PLAIN, 12.5f));
      repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int width = getWidth();
      int height = getHeight();
      if (on) {
        g.setColor(Brand.SELECT);
        g.fillRoundRect(0, 0, width - 1, height - 1, 10, 10);
        g.setColor(Brand.TEAL);
        g.fillRoundRect(0, height - 4, width - 1, 4, 4, 4);
      } else {
        g.setColor(new Color(Brand.PAPER.getRed(), Brand.PAPER.getGreen(), Brand.PAPER.getBlue(), 220));
        g.fillRoundRect(0, 0, width - 1, height - 1, 10, 10);
        g.setColor(Brand.LINE);
        g.drawRoundRect(0, 0, width - 1, height - 1, 10, 10);
      }
      g.dispose();
      super.paintComponent(graphics);
    }
  }
}
