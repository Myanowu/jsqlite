package com.myano.jsqlite.admin;

import com.myano.jsqlite.SqlException;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;

/** View / edit connection PRAGMAs and JSQLite ratio-index thresholds for the open database. */
final class DatabaseSettingsDialog extends JDialog {
  private final AdminSession session;
  private final JComboBox<String> journalMode = new JComboBox<>(new String[] {"delete", "wal", "memory"});
  private final JSpinner busyTimeout = new JSpinner(new SpinnerNumberModel(5_000L, 0L, 3_600_000L, 100L));
  private final JCheckBox foreignKeys = new JCheckBox("啟用外鍵檢查");
  private final JCheckBox bulkLoad = new JCheckBox("大量載入（略過部分即時索引維護）");
  private final JSpinner ratioMinRows =
      new JSpinner(new SpinnerNumberModel(100_000L, 1L, Long.MAX_VALUE, 1_000L));
  private final JSpinner ratioMinRatio = new JSpinner(new SpinnerNumberModel(100, 2, 1_000_000, 1));
  private final JLabel error = new JLabel(" ");
  private final JButton checkpoint = new JButton("執行 WAL checkpoint");
  private boolean saved;

  DatabaseSettingsDialog(Window owner, AdminSession session) {
    super(owner, "資料庫設置", ModalityType.APPLICATION_MODAL);
    this.session = session;
    Brand.install(this);
    getContentPane().setBackground(Brand.SURFACE);

    JLabel title = new JLabel("資料庫設置");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    title.setForeground(Brand.INK);
    JLabel path = new JLabel(session.path().toString());
    path.setForeground(Brand.MUTED);
    JLabel note = new JLabel("調整日誌模式、鎖等待、外鍵與高壓縮比索引門檻。");
    note.setForeground(Brand.MUTED);

    journalMode.setSelectedItem(session.journalMode());
    busyTimeout.setValue(session.busyTimeout());
    foreignKeys.setSelected(session.foreignKeys());
    bulkLoad.setSelected(session.bulkLoad());
    ratioMinRows.setValue(session.ratioMinRows());
    ratioMinRatio.setValue(session.ratioMinRatio());
    checkpoint.setEnabled("wal".equalsIgnoreCase(session.journalMode()));
    checkpoint.addActionListener(event -> runCheckpoint());
    journalMode.addActionListener(
        event ->
            checkpoint.setEnabled(
                "wal".equalsIgnoreCase(String.valueOf(journalMode.getSelectedItem()))));

    JPanel form = new JPanel(new GridBagLayout());
    form.setOpaque(false);
    int row = 0;
    row = addRow(form, row, "日誌模式 journal_mode", journalMode);
    row = addRow(form, row, "忙碌逾時 busy_timeout (ms)", busyTimeout);
    row = addRow(form, row, "外鍵 foreign_keys", foreignKeys);
    row = addRow(form, row, "大量載入 bulk_load", bulkLoad);
    row = addRow(form, row, "高壓縮比最小列數", ratioMinRows);
    row = addRow(form, row, "高壓縮比最小倍率", ratioMinRatio);
    addRow(form, row, "WAL", checkpoint);

    JTextArea hint =
        new JTextArea(
            """
            journal_mode=wal 會建立 -wal / -shm，適合多連線。
            busy_timeout 控制鎖等待毫秒數。
            高壓縮比門檻影響大表自動 ratio-index；改完後可對單表執行 PRAGMA jsqlite_analyze(表)。
            """);
    hint.setEditable(false);
    hint.setOpaque(false);
    hint.setLineWrap(true);
    hint.setWrapStyleWord(true);
    hint.setForeground(Brand.MUTED);
    hint.setFont(hint.getFont().deriveFont(11.5f));
    hint.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

    error.setForeground(Brand.ERROR);

    JPanel heading = new JPanel(new BorderLayout(0, 4));
    heading.setOpaque(false);
    heading.add(title, BorderLayout.NORTH);
    heading.add(path, BorderLayout.CENTER);
    heading.add(note, BorderLayout.SOUTH);

    JPanel center = new JPanel(new BorderLayout(0, 10));
    center.setOpaque(false);
    center.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
    center.add(heading, BorderLayout.NORTH);
    center.add(form, BorderLayout.CENTER);
    center.add(hint, BorderLayout.SOUTH);

    JButton cancel = new JButton("取消");
    cancel.addActionListener(event -> dispose());
    JButton apply = new JButton("套用");
    apply.addActionListener(event -> confirm());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    buttons.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
    buttons.add(cancel);
    buttons.add(apply);

    JPanel south = new JPanel(new BorderLayout());
    south.setOpaque(false);
    south.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
    south.add(error, BorderLayout.NORTH);
    south.add(buttons, BorderLayout.SOUTH);

    add(center, BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(apply);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    pack();
    setMinimumSize(new Dimension(520, getHeight()));
    setLocationRelativeTo(owner);
  }

  boolean showDialog() {
    setVisible(true);
    return saved;
  }

  private void confirm() {
    try {
      String mode = String.valueOf(journalMode.getSelectedItem()).toLowerCase();
      long timeout = ((Number) busyTimeout.getValue()).longValue();
      long minRows = ((Number) ratioMinRows.getValue()).longValue();
      int minRatio = ((Number) ratioMinRatio.getValue()).intValue();
      session.applySettings(
          mode, timeout, foreignKeys.isSelected(), bulkLoad.isSelected(), minRows, minRatio);
      saved = true;
      dispose();
    } catch (SqlException | IllegalArgumentException | IllegalStateException ex) {
      error.setForeground(Brand.ERROR);
      error.setText(ex.getMessage());
    }
  }

  private void runCheckpoint() {
    try {
      session.walCheckpoint("TRUNCATE");
      error.setForeground(Brand.TEAL);
      error.setText("已執行 PRAGMA wal_checkpoint(TRUNCATE)。");
    } catch (SqlException | IllegalStateException ex) {
      error.setForeground(Brand.ERROR);
      error.setText(ex.getMessage());
    }
  }

  private static int addRow(JPanel form, int row, String label, java.awt.Component field) {
    GridBagConstraints left = new GridBagConstraints();
    left.gridx = 0;
    left.gridy = row;
    left.anchor = GridBagConstraints.WEST;
    left.insets = new Insets(6, 0, 6, 12);
    JLabel caption = new JLabel(label);
    caption.setForeground(Brand.INK);
    form.add(caption, left);

    GridBagConstraints right = new GridBagConstraints();
    right.gridx = 1;
    right.gridy = row;
    right.weightx = 1;
    right.fill = GridBagConstraints.HORIZONTAL;
    right.anchor = GridBagConstraints.WEST;
    right.insets = new Insets(6, 0, 6, 0);
    form.add(field, right);
    return row + 1;
  }
}
