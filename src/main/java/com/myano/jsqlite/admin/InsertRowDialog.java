package com.myano.jsqlite.admin;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.TableInfo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

final class InsertRowDialog extends JDialog {
  @FunctionalInterface
  interface Writer {
    void write(List<Object> values);
  }

  private final TableInfo table;
  private final Writer writer;
  private final List<Object> initial;
  private final List<Field> fields = new ArrayList<>();
  private final JTextArea preview = new JTextArea();
  private final JLabel error = new JLabel(" ");
  private boolean accepted;

  InsertRowDialog(Window owner, TableInfo table, Writer writer) {
    this(owner, table, writer, null);
  }

  InsertRowDialog(Window owner, TableInfo table, Writer writer, List<Object> initial) {
    super(owner, (initial == null ? "插入資料 — " : "修改資料 — ") + table.name(), ModalityType.APPLICATION_MODAL);
    Brand.install(this);
    this.table = table;
    this.writer = writer;
    this.initial = initial == null ? null : List.copyOf(initial);
    boolean editing = this.initial != null;
    getContentPane().setBackground(Brand.PAPER);

    JLabel title = new JLabel((editing ? "修改 " : "插入到 ") + table.name());
    title.setFont(uiFont(20f, Font.BOLD));
    title.setForeground(Brand.INK);
    JLabel hint = new JLabel(table.columns().size() + " 個欄位");
    hint.setFont(uiFont(13f, Font.PLAIN));
    hint.setForeground(Brand.MUTED);
    JPanel heading = new JPanel(new BorderLayout(0, 4));
    heading.setOpaque(false);
    heading.add(title, BorderLayout.NORTH);
    heading.add(hint, BorderLayout.SOUTH);

    JPanel form = new JPanel(new GridBagLayout());
    form.setBackground(Brand.SURFACE);
    form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    int row = 0;
    for (Column column : table.columns()) {
      JTextField value = new JTextField();
      value.setFont(uiFont(15f, Font.PLAIN));
      value.setMargin(new Insets(8, 10, 8, 10));
      value.setForeground(Brand.INK);
      JCheckBox nullValue = new JCheckBox("NULL");
      nullValue.setFont(uiFont(13f, Font.PLAIN));
      nullValue.setForeground(Brand.MUTED);
      nullValue.setBackground(Brand.SURFACE);
      nullValue.addActionListener(event -> {
        applyNull(value, nullValue.isSelected());
        refreshPreview();
      });
      if (editing && row < this.initial.size()) {
        Object current = this.initial.get(row);
        if (current == null) {
          nullValue.setSelected(true);
          applyNull(value, true);
        } else {
          value.setText(String.valueOf(current));
        }
      }
      value.getDocument().addDocumentListener(new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent event) {
          refreshPreview();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
          refreshPreview();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
          refreshPreview();
        }
      });
      fields.add(new Field(column, value, nullValue));
      form.add(columnHeading(column), constraints(0, row, 0));
      GridBagConstraints input = constraints(1, row, 1);
      input.fill = GridBagConstraints.HORIZONTAL;
      form.add(value, input);
      form.add(nullValue, constraints(2, row, 0));
      row++;
    }

    JScrollPane formScroll = new JScrollPane(form);
    int formHeight = Math.min(420, Math.max(168, table.columns().size() * 64));
    formScroll.setPreferredSize(new Dimension(800, formHeight));
    formScroll.setBorder(BorderFactory.createLineBorder(Brand.LINE));
    formScroll.getViewport().setBackground(Brand.SURFACE);

    preview.setEditable(false);
    preview.setLineWrap(false);
    preview.setFont(sqlFont());
    preview.setBackground(new Color(0xFBFCFB));
    preview.setForeground(Brand.INK);
    preview.setMargin(new Insets(12, 14, 12, 14));
    preview.setTabSize(2);
    JScrollPane previewScroll = new JScrollPane(preview);
    previewScroll.setBorder(BorderFactory.createTitledBorder("將執行的 SQL"));
    previewScroll.setPreferredSize(new Dimension(800, 240));
    previewScroll.getViewport().setBackground(preview.getBackground());

    error.setForeground(Brand.ERROR);
    error.setFont(uiFont(13f, Font.PLAIN));

    JButton cancel = new JButton("取消");
    cancel.setFont(uiFont(14f, Font.PLAIN));
    cancel.addActionListener(event -> dispose());
    JButton ok = new JButton(editing ? "儲存" : "插入");
    ok.setFont(uiFont(14f, Font.BOLD));
    ok.addActionListener(event -> confirm());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    buttons.setBorder(BorderFactory.createEmptyBorder(8, 24, 18, 24));
    buttons.add(cancel);
    buttons.add(ok);

    JPanel body = new JPanel(new BorderLayout(0, 14));
    body.setOpaque(false);
    body.setBorder(BorderFactory.createEmptyBorder(20, 24, 8, 24));
    body.add(heading, BorderLayout.NORTH);

    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, previewScroll);
    split.setResizeWeight(0.55);
    split.setDividerSize(8);
    split.setBorder(BorderFactory.createEmptyBorder());
    split.setBackground(Brand.PAPER);
    split.setContinuousLayout(true);
    body.add(split, BorderLayout.CENTER);

    JPanel south = new JPanel(new BorderLayout(0, 6));
    south.setOpaque(false);
    south.setBorder(BorderFactory.createEmptyBorder(8, 24, 0, 24));
    south.add(error, BorderLayout.CENTER);

    JPanel bottom = new JPanel(new BorderLayout());
    bottom.setOpaque(false);
    bottom.add(south, BorderLayout.CENTER);
    bottom.add(buttons, BorderLayout.SOUTH);

    add(body, BorderLayout.CENTER);
    add(bottom, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    refreshPreview();
    pack();
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    int width = Math.min(screen.width - 80, Math.max(getWidth(), 860));
    int height = Math.min(screen.height - 80, Math.max(getHeight(), 640));
    setSize(width, height);
    setMinimumSize(new Dimension(720, 520));
    setLocationRelativeTo(owner);
  }

  boolean choose() {
    setVisible(true);
    return accepted;
  }

  private void confirm() {
    try {
      writer.write(readValues());
      accepted = true;
      dispose();
    } catch (SqlException ex) {
      error.setText(ex.getMessage());
    }
  }

  private void refreshPreview() {
    if (fields.size() != table.columns().size()) {
      return;
    }
    try {
      List<Object> values = readValues();
      preview.setText(initial == null
          ? SqlForms.insertValues(table, values)
          : SqlForms.updateExisting(table, initial, values));
      preview.setCaretPosition(0);
      error.setText(" ");
    } catch (SqlException ex) {
      preview.setText(" ");
    }
  }

  private List<Object> readValues() {
    List<Object> values = new ArrayList<>();
    for (Field field : fields) {
      if (field.nullValue.isSelected()) {
        values.add(null);
        continue;
      }
      String text = field.value.getText();
      if (field.column.type() == ColumnType.INTEGER) {
        if (text.isBlank()) {
          throw new SqlException(field.column.name() + " 需要整數，或勾選 NULL。");
        }
        try {
          values.add(Long.parseLong(text.strip()));
        } catch (NumberFormatException ex) {
          throw new SqlException(field.column.name() + " 不是整數。");
        }
      } else {
        values.add(text);
      }
    }
    return values;
  }

  private static void applyNull(JTextField value, boolean nil) {
    value.setEnabled(!nil);
    value.setBackground(nil ? Brand.ZEBRA : Color.WHITE);
  }

  private static JPanel columnHeading(Column column) {
    JLabel name = new JLabel(column.name());
    name.setFont(uiFont(14f, Font.BOLD));
    name.setForeground(Brand.INK);
    JLabel type = new JLabel(column.type().name());
    type.setFont(uiFont(12f, Font.PLAIN));
    type.setForeground(Brand.MUTED);
    JPanel lines = new JPanel(new BorderLayout(0, 2));
    lines.setOpaque(false);
    lines.add(name, BorderLayout.NORTH);
    lines.add(type, BorderLayout.SOUTH);
    JLabel mark = new JLabel(column.type() == ColumnType.INTEGER ? SchemaIcons.INTEGER : SchemaIcons.TEXT);
    JPanel heading = new JPanel(new BorderLayout(8, 0));
    heading.setOpaque(false);
    heading.add(mark, BorderLayout.WEST);
    heading.add(lines, BorderLayout.CENTER);
    Dimension natural = heading.getPreferredSize();
    heading.setPreferredSize(new Dimension(Math.max(220, natural.width), Math.max(40, natural.height)));
    return heading;
  }

  private static GridBagConstraints constraints(int x, int y, double weightX) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.gridy = y;
    constraints.weightx = weightX;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(10, 12, 10, 12);
    constraints.fill = GridBagConstraints.NONE;
    return constraints;
  }

  private static Font uiFont(float size, int style) {
    Font font = new Font("Microsoft JhengHei UI", style, Math.round(size));
    if (!"Microsoft JhengHei UI".equals(font.getFamily())) {
      font = new Font(Font.DIALOG, style, Math.round(size));
    }
    return font.deriveFont(style, size);
  }

  private static Font sqlFont() {
    Font font = new Font("Consolas", Font.PLAIN, 14);
    if ("Consolas".equals(font.getFamily())) {
      return font;
    }
    return new Font(Font.MONOSPACED, Font.PLAIN, 14);
  }

  private static final class Field {
    private final Column column;
    private final JTextField value;
    private final JCheckBox nullValue;

    private Field(Column column, JTextField value, JCheckBox nullValue) {
      this.column = column;
      this.value = value;
      this.nullValue = nullValue;
    }
  }
}
