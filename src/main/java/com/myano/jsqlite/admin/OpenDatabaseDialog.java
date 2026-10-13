package com.myano.jsqlite.admin;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;

final class OpenDatabaseDialog extends JDialog {
  private final JTextField pathField = new JTextField(24);
  private final JLabel error = new JLabel(" ");
  private final DefaultListModel<Path> names = new DefaultListModel<>();
  private final JList<Path> list = new JList<>(names);
  private Path chosen;

  OpenDatabaseDialog(Window owner, List<Path> existing) {
    super(owner, "打開資料庫", ModalityType.APPLICATION_MODAL);
    Brand.install(this);
    for (Path path : existing) {
      names.addElement(path);
    }
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.addListSelectionListener(event -> {
      Path selected = list.getSelectedValue();
      if (!event.getValueIsAdjusting() && selected != null) {
        pathField.setText(selected.toString());
      }
    });
    if (!names.isEmpty()) {
      list.setSelectedIndex(0);
    }

    JLabel title = new JLabel("已有的資料庫");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    JLabel hint = new JLabel("<html>可以打開這次執行裡建立過的資料庫，或磁碟上的 sqlite3 檔。<br>sqlite3 檔以唯讀讀入記憶體，不會寫回。旁邊有 -wal 的庫仍會拒絕。</html>");

    JScrollPane listScroll = new JScrollPane(list);
    listScroll.setPreferredSize(new Dimension(420, 140));
    if (names.isEmpty()) {
      list.setListData(new Path[0]);
    }

    JButton browse = new JButton("瀏覽");
    browse.addActionListener(event -> browse());
    JPanel pathRow = new JPanel(new BorderLayout(8, 0));
    pathRow.add(new JLabel("路徑"), BorderLayout.WEST);
    pathRow.add(pathField, BorderLayout.CENTER);
    pathRow.add(browse, BorderLayout.EAST);

    error.setForeground(new java.awt.Color(0x9B2335));

    JPanel form = new JPanel(new BorderLayout(0, 12));
    form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JPanel heading = new JPanel(new BorderLayout(0, 8));
    heading.add(title, BorderLayout.NORTH);
    heading.add(hint, BorderLayout.CENTER);
    JPanel body = new JPanel(new BorderLayout(0, 12));
    body.add(names.isEmpty() ? new JLabel("這次執行裡還沒有資料庫。") : listScroll, BorderLayout.CENTER);
    body.add(pathRow, BorderLayout.SOUTH);
    form.add(heading, BorderLayout.NORTH);
    form.add(body, BorderLayout.CENTER);
    form.add(error, BorderLayout.SOUTH);

    JButton cancel = new JButton("取消");
    cancel.addActionListener(event -> dispose());
    JButton ok = new JButton("打開");
    ok.addActionListener(event -> confirm());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
    buttons.add(cancel);
    buttons.add(ok);

    add(form, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    pack();
    setMinimumSize(getSize());
    setLocationRelativeTo(owner);
  }

  Path choose() {
    setVisible(true);
    return chosen;
  }

  private void browse() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("選擇資料庫");
    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
      pathField.setText(chooser.getSelectedFile().getAbsolutePath());
    }
  }

  private void confirm() {
    String text = pathField.getText().strip();
    if (text.isEmpty()) {
      error.setText("請選擇一個資料庫。");
      return;
    }
    try {
      chosen = Path.of(text);
    } catch (InvalidPathException ex) {
      error.setText("這個路徑無法打開。");
      return;
    }
    dispose();
  }
}
