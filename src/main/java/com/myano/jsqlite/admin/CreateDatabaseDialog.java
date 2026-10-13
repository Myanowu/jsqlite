package com.myano.jsqlite.admin;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

final class CreateDatabaseDialog extends JDialog {
  private final JTextField nameField = new JTextField("main", 24);
  private final JLabel error = new JLabel(" ");
  private Path chosen;

  CreateDatabaseDialog(Window owner) {
    super(owner, "新建資料庫", ModalityType.APPLICATION_MODAL);
    Brand.install(this);
    JLabel title = new JLabel("新的資料庫");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    JLabel hint = new JLabel("<html>給這個庫一個名稱。資料留在這次執行的記憶體裡。<br>關閉後還可以用「打開資料庫」再進來，結束程式後才會消失。</html>");

    JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    nameRow.add(new JLabel("名稱"), BorderLayout.WEST);
    nameRow.add(nameField, BorderLayout.CENTER);

    error.setForeground(new java.awt.Color(0x9B2335));

    JPanel form = new JPanel(new BorderLayout(0, 12));
    form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JPanel body = new JPanel(new BorderLayout(0, 12));
    body.add(title, BorderLayout.NORTH);
    body.add(hint, BorderLayout.CENTER);
    form.add(body, BorderLayout.NORTH);
    form.add(nameRow, BorderLayout.CENTER);
    form.add(error, BorderLayout.SOUTH);

    JButton cancel = new JButton("取消");
    cancel.addActionListener(event -> dispose());
    JButton ok = new JButton("建立");
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

  private void confirm() {
    String name = nameField.getText().strip();
    if (name.isEmpty()) {
      error.setText("資料庫名稱是空的。");
      return;
    }
    try {
      chosen = Path.of(name);
    } catch (InvalidPathException ex) {
      error.setText("這個名稱不能當作資料庫路徑。");
      return;
    }
    dispose();
  }
}
