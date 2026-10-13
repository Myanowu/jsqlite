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
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

final class AlterTableDialog extends JDialog {
  record Change(String name, List<Column> columns, int[] source, List<String> primaryKey) {
    Change {
      columns = List.copyOf(columns);
      source = source.clone();
      primaryKey = List.copyOf(primaryKey);
    }
  }

  @FunctionalInterface
  interface Saver {
    void save(Change change);
  }

  private final Saver saver;
  private final JTextField tableName = new JTextField(24);
  private final JPanel rowsPanel = new JPanel();
  private final List<ColumnRow> rows = new ArrayList<>();
  private final JTextArea preview = new JTextArea(4, 48);
  private final JLabel error = new JLabel(" ");
  private String savedName;

  AlterTableDialog(Window owner, TableInfo table, Saver saver) {
    super(owner, "修改表結構 — " + table.name(), ModalityType.APPLICATION_MODAL);
    Brand.install(this);
    this.saver = saver;
    rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
    tableName.setText(table.name());
    tableName.getDocument().addDocumentListener(refresh());
    List<Column> columns = table.columns();
    for (int i = 0; i < columns.size(); i++) {
      addColumnRow(columns.get(i), i, table.primaryKey());
    }

    JLabel title = new JLabel("修改 " + table.name());
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    nameRow.add(new JLabel("資料表名稱"), BorderLayout.WEST);
    nameRow.add(tableName, BorderLayout.CENTER);

    JButton addColumn = new JButton("加入欄位");
    addColumn.addActionListener(event -> {
      addColumnRow(new Column("欄位" + (rows.size() + 1), ColumnType.TEXT), null, List.of());
      refreshPreview();
    });
    JPanel columnsHeader = new JPanel(new BorderLayout());
    columnsHeader.add(new JLabel("欄位名稱、型別、非空、主鍵、唯一、自動編號和預設"), BorderLayout.WEST);
    columnsHeader.add(addColumn, BorderLayout.EAST);

    JScrollPane columnScroll = new JScrollPane(rowsPanel);
    int height = Math.min(360, Math.max(120, rows.size() * 40));
    columnScroll.setPreferredSize(new Dimension(860, height));

    preview.setEditable(false);
    preview.setLineWrap(true);
    preview.setWrapStyleWord(true);
    JScrollPane previewScroll = new JScrollPane(preview);
    previewScroll.setBorder(BorderFactory.createTitledBorder("修改後的建表語句"));
    previewScroll.setPreferredSize(new Dimension(860, 110));
    error.setForeground(new Color(0x9B2335));

    JLabel hint = new JLabel("預設要先勾選才會寫入。內容填 NULL 表示預設為空值。");
    JPanel south = new JPanel(new BorderLayout(0, 8));
    south.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
    south.add(hint, BorderLayout.NORTH);
    south.add(previewScroll, BorderLayout.CENTER);
    south.add(error, BorderLayout.SOUTH);

    JButton cancel = new JButton("取消");
    cancel.addActionListener(event -> dispose());
    JButton ok = new JButton("儲存");
    ok.addActionListener(event -> confirm());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
    buttons.add(cancel);
    buttons.add(ok);

    JPanel form = new JPanel(new BorderLayout(0, 10));
    form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
    JPanel top = new JPanel(new BorderLayout(0, 10));
    top.add(title, BorderLayout.NORTH);
    top.add(nameRow, BorderLayout.CENTER);
    top.add(columnsHeader, BorderLayout.SOUTH);
    form.add(top, BorderLayout.NORTH);
    form.add(columnScroll, BorderLayout.CENTER);

    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(south, BorderLayout.CENTER);
    bottom.add(buttons, BorderLayout.SOUTH);
    add(form, BorderLayout.CENTER);
    add(bottom, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    syncAutos();
    refreshPreview();
    pack();
    setMinimumSize(getSize());
    setLocationRelativeTo(owner);
  }

  String choose() {
    setVisible(true);
    return savedName;
  }

  private void addColumnRow(Column column, Integer source, List<String> primaryKey) {
    ColumnRow row = new ColumnRow(column, source, isPrimary(column, primaryKey));
    row.remove.addActionListener(event -> removeRow(row));
    row.name.getDocument().addDocumentListener(refresh());
    row.defaultValue.getDocument().addDocumentListener(refresh());
    row.type.addActionListener(event -> {
      syncAutos();
      refreshPreview();
    });
    row.notNull.addActionListener(event -> refreshPreview());
    row.primaryKey.addActionListener(event -> {
      syncAutos();
      refreshPreview();
    });
    row.unique.addActionListener(event -> refreshPreview());
    row.auto.addActionListener(event -> refreshPreview());
    row.hasDefault.addActionListener(event -> {
      row.defaultValue.setEnabled(row.hasDefault.isSelected());
      refreshPreview();
    });
    rows.add(row);
    rowsPanel.add(row);
    rowsPanel.add(Box.createVerticalStrut(6));
    rowsPanel.revalidate();
    rowsPanel.repaint();
  }

  private void removeRow(ColumnRow row) {
    if (rows.size() == 1) {
      error.setText("至少要留一個欄位。");
      return;
    }
    rows.remove(row);
    rowsPanel.removeAll();
    for (ColumnRow kept : rows) {
      rowsPanel.add(kept);
      rowsPanel.add(Box.createVerticalStrut(6));
    }
    rowsPanel.revalidate();
    rowsPanel.repaint();
    error.setText(" ");
    syncAutos();
    refreshPreview();
  }

  private void confirm() {
    try {
      Change change = readChange();
      saver.save(change);
      savedName = change.name();
      dispose();
    } catch (SqlException ex) {
      error.setText(ex.getMessage());
    }
  }

  private void refreshPreview() {
    try {
      Change change = readChange();
      preview.setText(SqlForms.create(new TableInfo(change.name(), change.columns(), 0, null, change.primaryKey())));
      error.setText(" ");
    } catch (SqlException ex) {
      preview.setText(" ");
      error.setText(ex.getMessage());
    }
  }

  private Change readChange() {
    String name = tableName.getText().strip();
    if (name.isEmpty()) {
      throw new SqlException("資料表名稱是空的");
    }
    if (rows.isEmpty()) {
      throw new SqlException("至少需要一個欄位");
    }
    List<String> keys = new ArrayList<>();
    for (ColumnRow row : rows) {
      if (row.primaryKey.isSelected()) {
        String columnName = row.name.getText().strip();
        if (!columnName.isEmpty()) {
          keys.add(columnName);
        }
      }
    }
    boolean singleKey = keys.size() == 1;
    List<Column> columns = new ArrayList<>();
    int[] source = new int[rows.size()];
    List<String> seen = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      ColumnRow row = rows.get(i);
      String columnName = row.name.getText().strip();
      if (columnName.isEmpty()) {
        throw new SqlException("欄位名稱是空的");
      }
      String folded = columnName.toLowerCase(java.util.Locale.ROOT);
      if (seen.contains(folded)) {
        throw new SqlException("欄位名稱重複：" + columnName);
      }
      seen.add(folded);
      ColumnType type = (ColumnType) row.type.getSelectedItem();
      boolean primary = row.primaryKey.isSelected();
      boolean auto = row.auto.isSelected();
      if (auto && !(singleKey && primary && type == ColumnType.INTEGER)) {
        throw new SqlException("自動編號只能用在單一的整數主鍵");
      }
      boolean hasDefault = row.hasDefault.isSelected();
      Object defaultValue = hasDefault ? readDefault(type, row.defaultValue.getText()) : null;
      columns.add(new Column(
          columnName,
          type,
          row.notNull.isSelected(),
          singleKey && primary,
          auto,
          row.unique.isSelected() && !primary,
          hasDefault,
          defaultValue));
      source[i] = row.source == null ? -1 : row.source;
    }
    return new Change(name, columns, source, keys);
  }

  private static Object readDefault(ColumnType type, String text) {
    String trimmed = text.strip();
    if (trimmed.equalsIgnoreCase("NULL")) {
      return null;
    }
    if (type == ColumnType.INTEGER) {
      if (trimmed.isEmpty()) {
        throw new SqlException("整數預設值是空的");
      }
      try {
        return Long.parseLong(trimmed);
      } catch (NumberFormatException ex) {
        throw new SqlException("預設值不是整數");
      }
    }
    return text;
  }

  private void syncAutos() {
    int keys = 0;
    for (ColumnRow row : rows) {
      if (row.primaryKey.isSelected()) {
        keys++;
      }
    }
    for (ColumnRow row : rows) {
      boolean allowed = keys == 1
          && row.primaryKey.isSelected()
          && row.type.getSelectedItem() == ColumnType.INTEGER;
      row.auto.setEnabled(allowed);
      if (!allowed) {
        row.auto.setSelected(false);
      }
    }
  }

  private static boolean isPrimary(Column column, List<String> primaryKey) {
    for (String name : primaryKey) {
      if (name.equalsIgnoreCase(column.name())) {
        return true;
      }
    }
    return column.primaryKey();
  }

  private DocumentListener refresh() {
    return new DocumentListener() {
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
    };
  }

  private static final class ColumnRow extends JPanel {
    private final Integer source;
    private final JTextField name = new JTextField(12);
    private final JComboBox<ColumnType> type = new JComboBox<>(ColumnType.values());
    private final JCheckBox notNull = new JCheckBox("非空");
    private final JCheckBox primaryKey = new JCheckBox("主鍵");
    private final JCheckBox unique = new JCheckBox("唯一");
    private final JCheckBox auto = new JCheckBox("自動編號");
    private final JCheckBox hasDefault = new JCheckBox("預設");
    private final JTextField defaultValue = new JTextField(8);
    private final JButton remove = new JButton("移除");

    private ColumnRow(Column column, Integer source, boolean primary) {
      super();
      setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
      this.source = source;
      name.setText(column.name());
      type.setSelectedItem(column.type());
      notNull.setSelected(column.notNull());
      primaryKey.setSelected(primary);
      unique.setSelected(column.unique() && !primary);
      auto.setSelected(column.autoincrement());
      hasDefault.setSelected(column.hasDefault());
      defaultValue.setText(defaultText(column));
      defaultValue.setEnabled(column.hasDefault());
      add(name);
      add(Box.createHorizontalStrut(6));
      add(type);
      add(Box.createHorizontalStrut(6));
      add(notNull);
      add(primaryKey);
      add(unique);
      add(auto);
      add(hasDefault);
      add(defaultValue);
      add(Box.createHorizontalStrut(6));
      add(remove);
      add(Box.createHorizontalGlue());
      setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
    }

    private static String defaultText(Column column) {
      if (!column.hasDefault() || column.defaultValue() == null) {
        return column.hasDefault() ? "NULL" : "";
      }
      return String.valueOf(column.defaultValue());
    }
  }
}
