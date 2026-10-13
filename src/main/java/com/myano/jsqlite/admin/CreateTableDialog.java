package com.myano.jsqlite.admin;

import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;

import java.awt.BorderLayout;
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

final class CreateTableDialog extends JDialog {
  private final List<String> existingNames;
  private final JTextField tableName = new JTextField(24);
  private final JPanel rowsPanel = new JPanel();
  private final List<ColumnRow> rows = new ArrayList<>();
  private final JTextArea preview = new JTextArea(4, 40);
  private final JLabel error = new JLabel(" ");
  private NewTable chosen;

  CreateTableDialog(Window owner, List<String> existingNames) {
    super(owner, "新建資料表", ModalityType.APPLICATION_MODAL);
    Brand.install(this);
    this.existingNames = List.copyOf(existingNames);
    rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
    addColumnRow(ColumnType.INTEGER);
    addColumnRow(ColumnType.TEXT);

    JLabel title = new JLabel("新的資料表");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

    JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    nameRow.add(new JLabel("資料表名稱"), BorderLayout.WEST);
    nameRow.add(tableName, BorderLayout.CENTER);
    tableName.getDocument().addDocumentListener(refresh());

    JButton addColumn = new JButton("加入欄位");
    addColumn.addActionListener(event -> {
      addColumnRow(ColumnType.TEXT);
      refreshPreview();
    });
    JPanel columnsHeader = new JPanel(new BorderLayout());
    columnsHeader.add(new JLabel("欄位"), BorderLayout.WEST);
    columnsHeader.add(addColumn, BorderLayout.EAST);

    JScrollPane columnScroll = new JScrollPane(rowsPanel);
    columnScroll.setPreferredSize(new Dimension(480, 160));

    preview.setEditable(false);
    preview.setLineWrap(true);
    preview.setWrapStyleWord(true);
    preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    JScrollPane previewScroll = new JScrollPane(preview);
    previewScroll.setBorder(BorderFactory.createTitledBorder("將執行的 SQL"));

    error.setForeground(new java.awt.Color(0x9B2335));

    JPanel form = new JPanel(new BorderLayout(0, 10));
    form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
    JPanel top = new JPanel(new BorderLayout(0, 10));
    top.add(title, BorderLayout.NORTH);
    top.add(nameRow, BorderLayout.SOUTH);
    form.add(top, BorderLayout.NORTH);
    JPanel middle = new JPanel(new BorderLayout(0, 8));
    middle.add(columnsHeader, BorderLayout.NORTH);
    middle.add(columnScroll, BorderLayout.CENTER);
    form.add(middle, BorderLayout.CENTER);

    JPanel south = new JPanel(new BorderLayout(0, 8));
    south.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
    south.add(previewScroll, BorderLayout.CENTER);
    south.add(error, BorderLayout.SOUTH);

    JButton cancel = new JButton("取消");
    cancel.addActionListener(event -> dispose());
    JButton ok = new JButton("建立資料表");
    ok.addActionListener(event -> confirm());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
    buttons.add(cancel);
    buttons.add(ok);

    add(form, BorderLayout.NORTH);
    add(south, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    refreshPreview();
    pack();
    setMinimumSize(getSize());
    setLocationRelativeTo(owner);
  }

  NewTable choose() {
    setVisible(true);
    return chosen;
  }

  private void addColumnRow(ColumnType type) {
    ColumnRow row = new ColumnRow(type);
    row.remove.addActionListener(event -> removeRow(row));
    row.name.getDocument().addDocumentListener(refresh());
    row.type.addActionListener(event -> refreshPreview());
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
    refreshPreview();
  }

  private void confirm() {
    try {
      String name = tableName.getText().strip();
      if (existingNames.contains(name)) {
        error.setText("資料表 " + name + " 已經存在。");
        return;
      }
      List<Column> columns = readColumns();
      SqlForms.createTable(name, columns);
      chosen = new NewTable(name, columns);
      dispose();
    } catch (SqlException ex) {
      error.setText(ex.getMessage());
    }
  }

  private List<Column> readColumns() {
    List<Column> columns = new ArrayList<>();
    for (ColumnRow row : rows) {
      columns.add(new Column(row.name.getText().strip(), (ColumnType) row.type.getSelectedItem()));
    }
    return columns;
  }

  private void refreshPreview() {
    try {
      preview.setText(SqlForms.createTable(tableName.getText(), readColumns()));
      error.setText(" ");
    } catch (SqlException ex) {
      preview.setText(ex.getMessage());
    }
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

  record NewTable(String name, List<Column> columns) {
    NewTable {
      columns = List.copyOf(columns);
    }
  }

  private static final class ColumnRow extends JPanel {
    private final JTextField name = new JTextField(16);
    private final JComboBox<ColumnType> type = new JComboBox<>(ColumnType.values());
    private final JButton remove = new JButton("移除");

    private ColumnRow(ColumnType initialType) {
      super(new BorderLayout(8, 0));
      type.setSelectedItem(initialType);
      add(name, BorderLayout.CENTER);
      JPanel side = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
      side.add(type);
      side.add(remove);
      add(side, BorderLayout.EAST);
      setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
    }
  }
}
