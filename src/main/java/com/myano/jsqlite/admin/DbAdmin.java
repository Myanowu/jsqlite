package com.myano.jsqlite.admin;

import com.myano.jsqlite.Row;
import com.myano.jsqlite.SqlException;
import com.myano.jsqlite.store.Column;
import com.myano.jsqlite.store.ColumnType;
import com.myano.jsqlite.store.IndexDef;
import com.myano.jsqlite.store.TableInfo;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public final class DbAdmin {
  /** 「全部」超過此行數時二次確認，避免大表一次載入打爆記憶體。 */
  static final int ALL_ROW_CONFIRM = 100_000;

  private static final Color ERROR = Brand.ERROR;
  private static final Color NORMAL = Brand.INK;

  private final AdminSession session = new AdminSession();
  private final JFrame frame = new JFrame("JSQLite");
  private final JLabel databaseLabel = new JLabel("尚未開啟資料庫");
  private final JTree tree = new JTree(closedRoot());
  private final JTextArea editor = new JTextArea();
  private final JTable grid = new JTable() {
    @Override
    public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
      java.awt.Component component = super.prepareRenderer(renderer, row, column);
      if (component instanceof JComponent painted) {
        painted.setOpaque(true);
      }
      if (isRowSelected(row)) {
        component.setBackground(Brand.SELECT);
        component.setForeground(Brand.INK);
      } else {
        component.setBackground(row % 2 == 0 ? Brand.SURFACE : Brand.ZEBRA);
      }
      return component;
    }
  };
  private final JTextArea status = new JTextArea(2, 20);
  private final JButton runButton = new JButton("執行");
  private final JButton createTableButton = new JButton("新建資料表");
  private final JButton insertButton = new JButton("插入資料");
  private final JButton editButton = new JButton("修改資料");
  private final JButton deleteButton = new JButton("刪除所選");
  private final JCheckBox selectAll = new JCheckBox("全選");
  private final JPanel rowActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
  private final CardLayout resultCards = new CardLayout();
  private final JPanel resultBody = new JPanel(resultCards);
  private final JPanel columnView = new JPanel(new BorderLayout(0, 10));
  private final TitledBorder resultBorder = BorderFactory.createTitledBorder("結果");
  private JPanel resultPanel;
  private JPanel sqlPanel;
  private JPanel workspace;
  private JComboBox<String> tableLimit;
  private int rowLimit = 100;
  private boolean syncingLimit;
  private TableInfo gridTable;
  private boolean syncingSelection;
  private final TreeSelectionListener onTreeSelect = event -> inspectSelection();

  static {
    applyLook();
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> new DbAdmin().show());
  }

  DbAdmin() {
    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent event) {
        if (confirmDiscard("結束 JSQLite", "結束程式會關掉這次執行裡的資料庫，記憶體裡的資料會消失。要結束嗎？")) {
          session.close();
          frame.dispose();
        }
      }
    });

    Brand.install(frame);
    frame.getContentPane().setBackground(Brand.PAPER);

    editor.setFont(sqlFont());
    editor.setBackground(new Color(0xFBFCFB));
    editor.setForeground(Brand.INK);
    editor.setCaretColor(Brand.TEAL);
    editor.setMargin(new Insets(8, 10, 8, 10));
    editor.setText(exampleSql());
    editor.setLineWrap(true);
    editor.setWrapStyleWord(true);
    editor.setTabSize(2);
    editor.getInputMap().put(KeyStroke.getKeyStroke("TAB"), "insert-tab");
    editor.getActionMap().put("insert-tab", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        editor.replaceSelection("  ");
      }
    });
    editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "run-sql");
    editor.getActionMap().put("run-sql", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        runSql();
      }
    });

    runButton.setFont(uiFont().deriveFont(Font.BOLD));
    runButton.setEnabled(false);
    runButton.setToolTipText("Ctrl+Enter");
    runButton.addActionListener(event -> runSql());
    createTableButton.setFont(uiFont());
    createTableButton.setEnabled(false);
    createTableButton.addActionListener(event -> createTable());

    tree.setRootVisible(true);
    tree.setShowsRootHandles(true);
    tree.setRowHeight(22);
    tree.setFont(listFont());
    tree.setBackground(Brand.SURFACE);
    tree.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
    tree.setCellRenderer(new SchemaTreeRenderer());
    tree.addTreeSelectionListener(onTreeSelect);
    tree.addTreeExpansionListener(new TreeExpansionListener() {
      @Override
      public void treeExpanded(TreeExpansionEvent event) {
        fitTreeWidth();
      }

      @Override
      public void treeCollapsed(TreeExpansionEvent event) {
        fitTreeWidth();
      }
    });
    tree.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        showTreeMenu(event);
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        showTreeMenu(event);
      }
    });

    grid.setFillsViewportHeight(true);
    grid.setRowHeight(22);
    grid.setFont(listFont());
    grid.setBackground(Brand.SURFACE);
    grid.setSelectionBackground(Brand.SELECT);
    grid.setSelectionForeground(Brand.INK);
    grid.setShowHorizontalLines(false);
    grid.setShowVerticalLines(false);
    grid.setIntercellSpacing(new Dimension(0, 0));
    grid.getTableHeader().setFont(listFont().deriveFont(Font.BOLD));
    grid.getTableHeader().setBackground(Brand.ZEBRA);
    grid.getTableHeader().setForeground(Brand.INK);
    grid.getTableHeader().setDefaultRenderer(new HeaderRenderer());
    grid.getTableHeader().setReorderingAllowed(false);
    grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    grid.setDefaultRenderer(Object.class, new NullCellRenderer());
    grid.setModel(emptyModel());
    grid.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) {
        updateRowActions();
      }
    });
    grid.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        if (event.getClickCount() != 2 || grid.columnAtPoint(event.getPoint()) <= 0) {
          return;
        }
        int row = grid.rowAtPoint(event.getPoint());
        if (row >= 0) {
          grid.setRowSelectionInterval(row, row);
          editRow(row);
        }
      }
    });
    installCopy();

    status.setEditable(false);
    status.setLineWrap(true);
    status.setWrapStyleWord(true);
    status.setFont(uiFont());
    status.setBackground(Brand.PAPER);
    status.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
    setStatus("先新建資料庫，或打開這次執行裡已經有的資料庫。結束程式前資料留在記憶體裡。", false);

    JButton createButton = button("新建資料庫", event -> createDatabase());
    JButton openButton = button("打開資料庫", event -> openDatabase());
    JButton closeButton = button("關閉資料庫", event -> closeDatabase());

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
    actions.setBackground(Brand.PAPER);
    actions.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
    actions.add(createButton);
    actions.add(openButton);
    actions.add(createTableButton);
    actions.add(closeButton);
    JPanel north = new JPanel(new BorderLayout());
    north.setBackground(Brand.PAPER);
    north.add(brandBar(), BorderLayout.NORTH);
    north.add(actions, BorderLayout.SOUTH);

    JScrollPane treeScroll = new JScrollPane(tree);
    treeScroll.setBorder(BorderFactory.createTitledBorder("資料表"));
    treeScroll.setPreferredSize(new Dimension(280, 400));
    treeScroll.getViewport().setBackground(Brand.SURFACE);

    sqlPanel = new JPanel(new BorderLayout(0, 6));
    sqlPanel.setBackground(Brand.SURFACE);
    sqlPanel.setBorder(BorderFactory.createTitledBorder("SQL"));
    JScrollPane editorScroll = new JScrollPane(editor);
    editorScroll.setPreferredSize(new Dimension(600, 150));
    editorScroll.setBorder(BorderFactory.createLineBorder(Brand.LINE));
    editorScroll.getViewport().setBackground(editor.getBackground());
    sqlPanel.add(editorScroll, BorderLayout.CENTER);
    JLabel shortcut = new JLabel("Ctrl+Enter");
    shortcut.setForeground(Brand.MUTED);
    JPanel runBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    runBar.setOpaque(false);
    runBar.add(shortcut);
    runBar.add(runButton);
    sqlPanel.add(runBar, BorderLayout.SOUTH);

    JScrollPane gridScroll = new JScrollPane(grid);
    gridScroll.setBorder(BorderFactory.createEmptyBorder());
    gridScroll.getViewport().setBackground(Brand.SURFACE);
    insertButton.setFont(uiFont());
    insertButton.setEnabled(false);
    insertButton.addActionListener(event -> insertRow());
    editButton.setFont(uiFont());
    editButton.setEnabled(false);
    editButton.addActionListener(event -> editSelectedRow());
    deleteButton.setFont(uiFont());
    deleteButton.setEnabled(false);
    deleteButton.addActionListener(event -> deleteSelectedRows());
    selectAll.setFont(uiFont());
    selectAll.setBackground(Brand.SURFACE);
    selectAll.addActionListener(event -> toggleAllRows());
    JLabel limitLabel = new JLabel("顯示");
    limitLabel.setFont(uiFont());
    tableLimit = new JComboBox<>(new String[] {"100", "500", "1000", "全部"});
    tableLimit.setFont(uiFont());
    tableLimit.setSelectedItem("100");
    tableLimit.addActionListener(event -> {
      if (syncingLimit || gridTable == null || selectedColumn() != null) {
        return;
      }
      int next = limitValue(tableLimit);
      if (next == 0 && !confirmLoadAll(gridTable)) {
        syncingLimit = true;
        tableLimit.setSelectedItem(rowLimit == 0 ? "全部" : Integer.toString(rowLimit));
        syncingLimit = false;
        return;
      }
      rowLimit = next;
      editor.setText(browseSql(gridTable));
      editor.setCaretPosition(0);
      showTable(gridTable);
    });
    rowActions.setBackground(Brand.SURFACE);
    rowActions.add(selectAll);
    rowActions.add(insertButton);
    rowActions.add(editButton);
    rowActions.add(deleteButton);
    rowActions.add(limitLabel);
    rowActions.add(tableLimit);
    JLabel limitUnit = new JLabel("筆");
    limitUnit.setFont(uiFont());
    rowActions.add(limitUnit);
    resultBody.setBackground(Brand.SURFACE);
    resultBody.add(gridScroll, "rows");
    columnView.setBackground(Brand.SURFACE);
    columnView.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
    resultBody.add(columnView, "column");
    resultPanel = new JPanel(new BorderLayout(0, 6));
    resultPanel.setBackground(Brand.SURFACE);
    resultPanel.setBorder(resultBorder);
    resultPanel.add(rowActions, BorderLayout.NORTH);
    resultPanel.add(resultBody, BorderLayout.CENTER);

    workspace = new JPanel(new BorderLayout(0, 8));
    workspace.setBackground(Brand.PAPER);
    workspace.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
    workspace.add(sqlPanel, BorderLayout.NORTH);
    workspace.add(resultPanel, BorderLayout.CENTER);

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, workspace);
    split.setDividerLocation(280);
    split.setDividerSize(8);
    split.setBackground(Brand.PAPER);
    split.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

    JScrollPane statusScroll = new JScrollPane(status);
    statusScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Brand.LINE));
    statusScroll.setBackground(Brand.PAPER);
    statusScroll.getViewport().setBackground(Brand.PAPER);
    statusScroll.setPreferredSize(new Dimension(800, 64));

    frame.add(north, BorderLayout.NORTH);
    frame.add(split, BorderLayout.CENTER);
    frame.add(statusScroll, BorderLayout.SOUTH);
    frame.setSize(1080, 720);
    frame.setLocationByPlatform(true);
  }

  void show() {
    frame.setVisible(true);
  }

  JFrame frame() {
    return frame;
  }

  private void createDatabase() {
    if (!confirmDiscard("新建資料庫", "建立新的資料庫會先離開目前這一個。目前這個還留在這次執行裡，可以用「打開資料庫」再進來。要繼續嗎？")) {
      return;
    }
    java.nio.file.Path path = new CreateDatabaseDialog(frame).choose();
    if (path == null) {
      return;
    }
    try {
      session.create(path);
    } catch (SqlException ex) {
      setStatus(ex.getMessage(), true);
      return;
    }
    syncChrome();
    rebuildTree(null);
    clearGrid();
    setStatus("已建立 " + path + "。接著按「新建資料表」。", false);
  }

  private void openDatabase() {
    if (!confirmDiscard("打開資料庫", "打開另一個資料庫會先離開目前這一個。目前這個還留在這次執行裡。要繼續嗎？")) {
      return;
    }
    java.nio.file.Path path = new OpenDatabaseDialog(frame, session.databases()).choose();
    if (path == null) {
      return;
    }
    try {
      session.open(path);
    } catch (SqlException ex) {
      setStatus(ex.getMessage(), true);
      return;
    }
    syncChrome();
    rebuildTree(null);
    clearGrid();
    setStatus(openStatus(path), false);
  }

  private void createTable() {
    if (!session.isOpen()) {
      setStatus("尚未開啟資料庫", true);
      return;
    }
    List<String> names = session.tables().stream().map(TableInfo::name).toList();
    CreateTableDialog.NewTable spec = new CreateTableDialog(frame, names).choose();
    if (spec == null) {
      return;
    }
    try {
      String sql = session.createTable(spec.name(), spec.columns());
      focusTable(spec.name());
      setStatus("已建立資料表：" + sql, false);
    } catch (SqlException | IllegalStateException ex) {
      setStatus(ex.getMessage(), true);
    }
  }

  private void closeDatabase() {
    if (!session.isOpen()) {
      setStatus("尚未開啟資料庫", false);
      return;
    }
    if (!confirmDiscard("關閉資料庫", "關閉只是離開這個資料庫。這次執行期間還可以用「打開資料庫」再進來。要關閉嗎？")) {
      return;
    }
    discardDatabase();
  }

  private void discardDatabase() {
    session.closeDatabase();
    syncChrome();
    rebuildTree(null);
    clearGrid();
    setStatus("已關閉資料庫", false);
  }

  private void syncChrome() {
    boolean open = session.isOpen();
    runButton.setEnabled(open);
    createTableButton.setEnabled(open);
    updateInsertButton();
    String label = open
        ? "資料庫：" + session.path() + (session.readOnly() ? "（不寫回檔案）" : "")
        : "尚未開啟資料庫";
    databaseLabel.setForeground(open ? Brand.INK : Brand.MUTED);
    databaseLabel.setText(label);
    databaseLabel.setToolTipText(label);
  }

  private void updateInsertButton() {
    updateRowActions();
  }

  private void updateRowActions() {
    TableInfo table = session.isOpen() ? selectedTable() : null;
    boolean memoryNote = session.isOpen() && session.readOnly();
    insertButton.setEnabled(table != null);
    editButton.setEnabled(gridTable != null && grid.getSelectedRow() >= 0);
    deleteButton.setEnabled(gridTable != null && !rowsToChange().isEmpty());
    selectAll.setEnabled(gridTable != null && grid.getRowCount() > 0);
    if (!session.isOpen()) {
      insertButton.setToolTipText("先打開資料庫");
    } else if (table == null) {
      insertButton.setToolTipText("先在左側選擇一個資料表");
    } else if (memoryNote) {
      insertButton.setToolTipText("寫入這次執行的記憶體，不會改磁碟上的檔案");
    } else {
      insertButton.setToolTipText("為 " + table.name() + " 插入一列");
    }
    editButton.setToolTipText("雙擊一列，或選中後按這裡修改");
    deleteButton.setToolTipText("刪除勾選的列；沒有勾選時刪除反白的列");
  }

  private boolean confirmDiscard(String title, String message) {
    if (!session.isOpen()) {
      return true;
    }
    int choice = JOptionPane.showConfirmDialog(frame, message, title, JOptionPane.OK_CANCEL_OPTION);
    return choice == JOptionPane.OK_OPTION;
  }

  private void runSql() {
    if (!session.isOpen()) {
      setStatus("尚未開啟資料庫", true);
      return;
    }
    try {
      ScriptResult result = session.run(editor.getText());
      rebuildTree(null);
      if (result.kind() == ScriptResult.Kind.QUERY) {
        gridTable = null;
        showResult(result);
        setStatus(result.rows().size() + " 列", false);
      } else if (selectedTable() == null) {
        clearGrid();
        setStatus("已執行", false);
      } else {
        showTable(selectedTable());
        setStatus("已執行", false);
      }
    } catch (SqlException | IllegalStateException ex) {
      setStatus(ex.getMessage(), true);
    }
  }

  private void showTreeMenu(MouseEvent event) {
    if (!event.isPopupTrigger()) {
      return;
    }
    TreePath path = tree.getPathForLocation(event.getX(), event.getY());
    if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
      return;
    }
    tree.setSelectionPath(path);
    JPopupMenu menu = menuFor(node);
    if (menu == null) {
      return;
    }
    menu.show(tree, event.getX(), event.getY());
  }

  private JPopupMenu menuFor(DefaultMutableTreeNode node) {
    Object user = node.getUserObject();
    if (user instanceof TableNode table) {
      return tableMenu(table.table);
    }
    if (user instanceof ColumnNode column
        && node.getParent() instanceof DefaultMutableTreeNode parent
        && parent.getUserObject() instanceof TableNode table) {
      return columnMenu(table.table, column.column);
    }
    if (session.isOpen() && node.isRoot()) {
      JPopupMenu menu = new JPopupMenu();
      menu.add(item("複製資料庫路徑", () -> copyText(session.path().toString())));
      return menu;
    }
    return null;
  }

  private JPopupMenu tableMenu(TableInfo table) {
    JPopupMenu menu = new JPopupMenu();
    menu.add(item("查詢資料", () -> useSql(SqlForms.selectAll(table), true)));
    menu.add(item("查詢筆數", () -> useSql(SqlForms.countRows(table), true)));
    menu.add(item("修改表結構", () -> editStructure(table)));
    menu.addSeparator();
    JMenu generate = menuGroup("生成 SQL");
    generate.add(item("SELECT", () -> useSql(SqlForms.selectColumns(table), false)));
    generate.add(item("INSERT", () -> useSql(SqlForms.insert(table), false)));
    generate.add(item("UPDATE", () -> useSql(SqlForms.update(table), false)));
    generate.add(item("DELETE", () -> useSql(SqlForms.delete(table), false)));
    generate.add(item("CREATE TABLE", () -> useSql(SqlForms.create(table), false)));
    menu.add(generate);
    List<IndexDef> indexes = indexesOf(table);
    if (!indexes.isEmpty()) {
      JMenu indexMenu = menuGroup("索引");
      for (IndexDef index : indexes) {
        indexMenu.add(item(index.name(), () -> useSql(SqlForms.createIndex(index), false)));
      }
      menu.add(indexMenu);
    }
    menu.addSeparator();
    menu.add(item("複製資料表名稱", () -> copyText(table.name())));
    return menu;
  }

  private JPopupMenu columnMenu(TableInfo table, Column column) {
    JPopupMenu menu = new JPopupMenu();
    menu.add(item("查詢此欄", () -> useSql(SqlForms.selectColumn(table, column), true)));
    JMenu generate = menuGroup("生成 SQL");
    generate.add(item("WHERE", () -> useSql(SqlForms.filterColumn(table, column), false)));
    generate.add(item("ORDER BY", () -> useSql(SqlForms.orderBy(table, column), false)));
    generate.add(item("UPDATE 此欄", () -> useSql(SqlForms.updateColumn(table, column), false)));
    menu.add(generate);
    menu.addSeparator();
    menu.add(item("複製欄位名稱", () -> copyText(column.name())));
    return menu;
  }

  private List<IndexDef> indexesOf(TableInfo table) {
    List<IndexDef> found = new ArrayList<>();
    for (IndexDef index : session.indexes()) {
      if (index.table().equals(table.name())) {
        found.add(index);
      }
    }
    return found;
  }

  private JMenu menuGroup(String text) {
    JMenu menu = new JMenu(text);
    menu.setFont(uiFont());
    return menu;
  }

  private JMenuItem item(String text, Runnable action) {
    JMenuItem item = new JMenuItem(text);
    item.setFont(uiFont());
    item.addActionListener(event -> action.run());
    return item;
  }

  private void editStructure(TableInfo table) {
    TableInfo current = table;
    for (TableInfo item : session.tables()) {
      if (item.name().equals(table.name())) {
        current = item;
        break;
      }
    }
    TableInfo editing = current;
    String saved = new AlterTableDialog(frame, editing, change -> {
      if (session.readOnly()) {
        throw new SqlException("這個資料庫是唯讀的，不能修改表結構。");
      }
      session.redefineTable(editing.name(), change.name(), change.columns(), change.source(), change.primaryKey());
    }).choose();
    if (saved == null) {
      return;
    }
    focusTable(saved);
    setStatus("已修改資料表 " + saved, false);
  }

  private void useSql(String sql, boolean run) {
    editor.setText(sql);
    editor.setCaretPosition(0);
    editor.requestFocusInWindow();
    if (run) {
      runSql();
      return;
    }
    setStatus("已產生 SQL，可修改後按執行。", false);
  }

  private void copyText(String text) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    setStatus("已複製 " + text, false);
  }

  private void inspectSelection() {
    updateInsertButton();
    if (!session.isOpen()) {
      return;
    }
    Column column = selectedColumn();
    TableInfo table = selectedTable();
    if (table == null) {
      return;
    }
    try {
      if (column != null) {
        showColumn(table, column);
      } else {
        editor.setText(browseSql(table));
        editor.setCaretPosition(0);
        showTable(table);
      }
    } catch (SqlException | IllegalStateException ex) {
      setStatus(ex.getMessage(), true);
    }
  }

  private void focusTable(String tableName) {
    rebuildTree(tableName);
    if (selectedTable() != null) {
      showTable(selectedTable());
    }
  }

  private void showTable(TableInfo table) {
    gridTable = table;
    showResult(session.run(browseSql(table)));
    int shown = grid.getRowCount();
    int total = tableCount(table);
    if (rowLimit > 0 && total > shown) {
      setStatus(table.name() + "：顯示前 " + shown + " 列，共 " + total + " 列", false);
    } else {
      setStatus(table.name() + "：" + shown + " 列", false);
    }
  }

  private String browseSql(TableInfo table) {
    String sql = SqlForms.selectAll(table);
    if (rowLimit > 0) {
      return sql + "\nLIMIT " + rowLimit;
    }
    return sql;
  }

  private int tableCount(TableInfo table) {
    for (TableInfo item : session.tables()) {
      if (item.name().equals(table.name())) {
        return item.rowCount();
      }
    }
    return table.rowCount();
  }

  private String openStatus(Path path) {
    String note = session.readOnly()
        ? "。資料先放在記憶體裡，插入、修改和刪除不會寫回這個檔。"
        : "。";
    List<TableInfo> tables = visibleTables();
    int maxRows = 0;
    for (TableInfo table : tables) {
      maxRows = Math.max(maxRows, table.rowCount());
    }
    String size = "?";
    try {
      if (Files.isRegularFile(path)) {
        size = formatBytes(Files.size(path));
      }
    } catch (IOException ignored) {
      size = "?";
    }
    return "已打開 "
        + path
        + " · "
        + size
        + " · "
        + tables.size()
        + " 個資料表 · 最大表 "
        + maxRows
        + " 列"
        + note;
  }

  private boolean confirmLoadAll(TableInfo table) {
    int total = tableCount(table);
    if (total <= ALL_ROW_CONFIRM) {
      return true;
    }
    int choice = JOptionPane.showConfirmDialog(
        frame,
        table.name()
            + " 有 "
            + total
            + " 列。載入全部可能佔用大量記憶體。仍要繼續嗎？",
        "顯示全部",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE);
    return choice == JOptionPane.YES_OPTION;
  }

  public static String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double value = bytes;
    String[] units = {"KB", "MB", "GB", "TB"};
    int unit = -1;
    while (value >= 1024 && unit + 1 < units.length) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }

  private static int limitValue(JComboBox<String> box) {
    Object item = box.getSelectedItem();
    if (item == null || "全部".equals(item.toString())) {
      return 0;
    }
    return Integer.parseInt(item.toString());
  }

  private void showColumn(TableInfo table, Column column) {
    gridTable = null;
    List<Object> values = loadColumn(table, column);
    columnView.removeAll();
    columnView.add(columnHeading(table, column), BorderLayout.NORTH);
    JTabbedPane tabs = new JTabbedPane();
    tabs.setFont(uiFont());
    tabs.addTab("欄位資訊", columnInfo(table, column));
    tabs.addTab("此欄的值", columnValues(table, column, values));
    columnView.add(tabs, BorderLayout.CENTER);
    resultBorder.setTitle("欄位 · " + table.name() + "." + column.name());
    rowActions.setVisible(false);
    sqlPanel.setVisible(false);
    resultCards.show(resultBody, "column");
    resultPanel.revalidate();
    workspace.revalidate();
    resultPanel.repaint();
    setStatus(table.name() + "." + column.name() + " · " + column.type().name() + " · " + values.size() + " 個值，" + distinctCount(values) + " 種", false);
    updateInsertButton();
  }

  private List<Object> loadColumn(TableInfo table, Column column) {
    List<Object> values = new ArrayList<>();
    for (Row row : session.run(SqlForms.selectColumn(table, column)).rows()) {
      values.add(row.values().isEmpty() ? null : row.values().get(0));
    }
    return values;
  }

  private static int distinctCount(List<Object> values) {
    return (int) values.stream().distinct().count();
  }

  private JPanel columnHeading(TableInfo table, Column column) {
    JLabel mark = new JLabel(column.type() == ColumnType.INTEGER ? SchemaIcons.INTEGER : SchemaIcons.TEXT);
    JLabel name = new JLabel(column.name());
    name.setFont(uiFont(15f, Font.BOLD));
    name.setForeground(Brand.INK);
    JLabel meta = new JLabel(table.name() + "  ·  " + column.type().name());
    meta.setFont(uiFont(11f, Font.PLAIN));
    meta.setForeground(Brand.MUTED);
    JPanel lines = new JPanel(new BorderLayout(0, 2));
    lines.setOpaque(false);
    lines.add(name, BorderLayout.NORTH);
    lines.add(meta, BorderLayout.SOUTH);
    JPanel heading = new JPanel(new BorderLayout(10, 0));
    heading.setOpaque(false);
    heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
    heading.add(mark, BorderLayout.WEST);
    heading.add(lines, BorderLayout.CENTER);
    return heading;
  }

  private JScrollPane columnProperties(TableInfo table, Column column) {
    DefaultTableModel model = new DefaultTableModel(new Object[] {"屬性", "值"}, 0) {
      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
    model.addRow(new Object[] {"資料表", table.name()});
    model.addRow(new Object[] {"欄位", column.name()});
    model.addRow(new Object[] {"型別", column.type().name()});
    model.addRow(new Object[] {"主鍵", yesNo(column.primaryKey())});
    model.addRow(new Object[] {"不可為空", yesNo(column.notNull())});
    model.addRow(new Object[] {"唯一", yesNo(column.unique())});
    model.addRow(new Object[] {"自動編號", yesNo(column.autoincrement())});
    model.addRow(new Object[] {"預設值", defaultText(column)});
    model.addRow(new Object[] {"索引", indexText(table, column)});
    JTable props = new JTable(model);
    props.setFont(listFont());
    props.setRowHeight(22);
    props.setBackground(Brand.SURFACE);
    props.setShowVerticalLines(false);
    props.setGridColor(Brand.LINE);
    props.getTableHeader().setDefaultRenderer(new HeaderRenderer());
    props.getTableHeader().setReorderingAllowed(false);
    props.setDefaultRenderer(Object.class, new NullCellRenderer());
    props.getColumnModel().getColumn(0).setPreferredWidth(120);
    props.getColumnModel().getColumn(0).setMaxWidth(160);
    JScrollPane scroll = new JScrollPane(props);
    scroll.setBorder(BorderFactory.createLineBorder(Brand.LINE));
    scroll.getViewport().setBackground(Brand.SURFACE);
    return scroll;
  }

  private JPanel columnInfo(TableInfo table, Column column) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setOpaque(false);
    panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    panel.add(columnProperties(table, column), BorderLayout.CENTER);
    return panel;
  }

  private JComponent columnValues(TableInfo table, Column column, List<Object> values) {
    List<Map.Entry<Object, Integer>> groups = grouped(values);
    DefaultTableModel groupModel = new DefaultTableModel(new Object[] {"值", "數量"}, 0) {
      @Override
      public boolean isCellEditable(int row, int columnIndex) {
        return false;
      }
    };
    groupModel.addRow(new Object[] {"（全部）", values.size()});
    for (Map.Entry<Object, Integer> group : groups) {
      groupModel.addRow(new Object[] {group.getKey(), group.getValue()});
    }
    JTable groupTable = plainTable(groupModel);
    groupTable.getColumnModel().getColumn(1).setMaxWidth(80);
    groupTable.getColumnModel().getColumn(1).setPreferredWidth(72);

    JTable detailTable = plainTable(new DefaultTableModel());
    detailTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    JLabel detailCaption = new JLabel("明細");
    detailCaption.setFont(uiFont().deriveFont(Font.BOLD));
    detailCaption.setForeground(Brand.TEAL);
    boolean[] filling = {false};

    Runnable refill = () -> {
      int selected = groupTable.getSelectedRow();
      boolean all = selected <= 0;
      Object filter = all ? null : groupModel.getValueAt(selected, 0);
      int total = all ? values.size() : ((Number) groupModel.getValueAt(selected, 1)).intValue();
      ScriptResult result = session.run(all
          ? browseSql(table)
          : SqlForms.selectMatching(table, column, filter, rowLimit));
      DefaultTableModel detailModel = new DefaultTableModel(result.columns().toArray(), 0) {
        @Override
        public boolean isCellEditable(int row, int columnIndex) {
          return false;
        }
      };
      for (Row row : result.rows()) {
        detailModel.addRow(row.values().toArray());
      }
      detailTable.setModel(detailModel);
      detailTable.setDefaultRenderer(Object.class, new NullCellRenderer());
      detailTable.getTableHeader().setDefaultRenderer(new HeaderRenderer());
      fitColumns(detailTable);
      int shown = result.rows().size();
      if (rowLimit > 0 && total > shown) {
        detailCaption.setText("明細 · 顯示前 " + shown + " 筆，共 " + total + " 筆");
      } else {
        detailCaption.setText("明細 · " + shown + " 筆");
      }
    };
    groupTable.getSelectionModel().addListSelectionListener((ListSelectionEvent event) -> {
      if (!event.getValueIsAdjusting() && !filling[0]) {
        refill.run();
      }
    });

    JComboBox<String> limit = new JComboBox<>(new String[] {"100", "500", "1000", "全部"});
    limit.setFont(uiFont());
    syncingLimit = true;
    limit.setSelectedItem(rowLimit == 0 ? "全部" : Integer.toString(rowLimit));
    syncingLimit = false;
    limit.addActionListener(event -> {
      if (syncingLimit) {
        return;
      }
      int next = limitValue(limit);
      if (next == rowLimit) {
        return;
      }
      if (next == 0 && gridTable != null && !confirmLoadAll(gridTable)) {
        syncingLimit = true;
        limit.setSelectedItem(rowLimit == 0 ? "全部" : Integer.toString(rowLimit));
        syncingLimit = false;
        return;
      }
      rowLimit = next;
      syncingLimit = true;
      tableLimit.setSelectedItem(limit.getSelectedItem());
      syncingLimit = false;
      refill.run();
    });
    JLabel limitLabel = new JLabel("顯示");
    limitLabel.setFont(uiFont());
    JLabel limitUnit = new JLabel("筆");
    limitUnit.setFont(uiFont());
    JPanel limitBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    limitBar.setOpaque(false);
    limitBar.add(limitLabel);
    limitBar.add(limit);
    limitBar.add(limitUnit);

    JLabel groupCaption = new JLabel("分組");
    groupCaption.setFont(uiFont().deriveFont(Font.BOLD));
    groupCaption.setForeground(Brand.TEAL);
    JPanel groupPanel = new JPanel(new BorderLayout(0, 6));
    groupPanel.setOpaque(false);
    groupPanel.add(groupCaption, BorderLayout.NORTH);
    groupPanel.add(scrollOf(groupTable), BorderLayout.CENTER);

    JPanel detailHead = new JPanel(new BorderLayout());
    detailHead.setOpaque(false);
    detailHead.add(detailCaption, BorderLayout.WEST);
    detailHead.add(limitBar, BorderLayout.EAST);
    JPanel detailPanel = new JPanel(new BorderLayout(0, 6));
    detailPanel.setOpaque(false);
    detailPanel.add(detailHead, BorderLayout.NORTH);
    detailPanel.add(scrollOf(detailTable), BorderLayout.CENTER);

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, groupPanel, detailPanel);
    split.setDividerLocation(280);
    split.setDividerSize(8);
    split.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    split.setBackground(Brand.SURFACE);
    split.setContinuousLayout(true);

    filling[0] = true;
    groupTable.setRowSelectionInterval(0, 0);
    filling[0] = false;
    refill.run();
    return split;
  }

  private static List<Map.Entry<Object, Integer>> grouped(List<Object> values) {
    Map<Object, Integer> counts = new LinkedHashMap<>();
    for (Object value : values) {
      counts.merge(value, 1, Integer::sum);
    }
    List<Map.Entry<Object, Integer>> entries = new ArrayList<>(counts.entrySet());
    entries.sort((left, right) -> {
      int byCount = Integer.compare(right.getValue(), left.getValue());
      if (byCount != 0) {
        return byCount;
      }
      String leftText = left.getKey() == null ? "" : String.valueOf(left.getKey());
      String rightText = right.getKey() == null ? "" : String.valueOf(right.getKey());
      return leftText.compareTo(rightText);
    });
    return entries;
  }

  private JTable plainTable(DefaultTableModel model) {
    JTable data = new JTable(model) {
      @Override
      public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int columnIndex) {
        java.awt.Component component = super.prepareRenderer(renderer, row, columnIndex);
        if (component instanceof JComponent painted) {
          painted.setOpaque(true);
        }
        if (!isRowSelected(row)) {
          component.setBackground(row % 2 == 0 ? Brand.SURFACE : Brand.ZEBRA);
        }
        return component;
      }
    };
    data.setFont(listFont());
    data.setRowHeight(22);
    data.setBackground(Brand.SURFACE);
    data.setSelectionBackground(Brand.SELECT);
    data.setSelectionForeground(Brand.INK);
    data.setShowVerticalLines(false);
    data.setGridColor(Brand.LINE);
    data.setFillsViewportHeight(true);
    data.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
    data.getTableHeader().setDefaultRenderer(new HeaderRenderer());
    data.getTableHeader().setReorderingAllowed(false);
    data.setDefaultRenderer(Object.class, new NullCellRenderer());
    return data;
  }

  private static JScrollPane scrollOf(JTable table) {
    JScrollPane scroll = new JScrollPane(table);
    scroll.setBorder(BorderFactory.createLineBorder(Brand.LINE));
    scroll.getViewport().setBackground(Brand.SURFACE);
    return scroll;
  }

  private String indexText(TableInfo table, Column column) {
    List<String> names = new ArrayList<>();
    for (IndexDef index : indexesOf(table)) {
      if (index.columns().contains(column.name())) {
        names.add(index.name() + (index.unique() ? "（唯一）" : ""));
      }
    }
    return names.isEmpty() ? "沒有" : String.join("、", names);
  }

  private static String yesNo(boolean value) {
    return value ? "是" : "否";
  }

  private static String defaultText(Column column) {
    if (!column.hasDefault()) {
      return "沒有";
    }
    if (column.defaultNow()) {
      return "datetime('now')";
    }
    if (column.defaultValue() == null) {
      return "NULL";
    }
    return String.valueOf(column.defaultValue());
  }

  private void clearGrid() {
    gridTable = null;
    grid.setModel(emptyModel());
    showDataCard("結果");
    refreshSelectAll();
  }

  private void insertRow() {
    TableInfo table = selectedTable();
    if (table == null || !session.isOpen()) {
      setStatus("請先在左側選擇一個資料表。", true);
      return;
    }
    String[] written = new String[1];
    boolean inserted = new InsertRowDialog(frame, table, values -> written[0] = session.insertRow(table.name(), values)).choose();
    if (!inserted) {
      return;
    }
    focusTable(table.name());
    setStatus("已插入：" + written[0], false);
  }

  private void editSelectedRow() {
    int row = grid.getSelectedRow();
    if (row < 0 && rowsToChange().size() == 1) {
      row = rowsToChange().get(0);
    }
    if (row < 0) {
      setStatus("請先選擇一列。", true);
      return;
    }
    editRow(row);
  }

  private void editRow(int row) {
    if (gridTable == null) {
      setStatus("請先在左側選擇一個資料表。", true);
      return;
    }
    stopGridEdit();
    List<Object> original = rowValues(row);
    TableInfo table = gridTable;
    try {
      boolean saved = new InsertRowDialog(
          frame, table, values -> session.updateRow(table.name(), original, values), original).choose();
      if (!saved) {
        return;
      }
      showTable(table);
      setStatus("已修改 " + table.name() + " 的一列", false);
    } catch (RuntimeException ex) {
      setStatus(ex.getMessage(), true);
    }
  }

  private void deleteSelectedRows() {
    if (gridTable == null) {
      setStatus("請先在左側選擇一個資料表。", true);
      return;
    }
    stopGridEdit();
    List<Integer> rows = rowsToChange();
    if (rows.isEmpty()) {
      setStatus("請先勾選要刪除的資料，或反白一列。", true);
      return;
    }
    int choice = JOptionPane.showConfirmDialog(
        frame,
        "要刪除 " + rows.size() + " 列嗎？這只改這次執行的記憶體。",
        "刪除記錄",
        JOptionPane.OK_CANCEL_OPTION);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }
    List<List<Object>> values = new ArrayList<>();
    for (int row : rows) {
      values.add(rowValues(row));
    }
    TableInfo table = gridTable;
    try {
      session.deleteRows(table.name(), values);
      showTable(table);
      setStatus("已刪除 " + values.size() + " 列", false);
    } catch (SqlException | IllegalStateException ex) {
      setStatus(ex.getMessage(), true);
    }
  }

  private void stopGridEdit() {
    if (grid.isEditing()) {
      grid.getCellEditor().stopCellEditing();
    }
  }

  private void toggleAllRows() {
    if (syncingSelection) {
      return;
    }
    syncingSelection = true;
    boolean selected = selectAll.isSelected();
    for (int row = 0; row < grid.getRowCount(); row++) {
      grid.setValueAt(selected, row, 0);
    }
    syncingSelection = false;
    updateRowActions();
  }

  private void refreshSelectAll() {
    if (syncingSelection) {
      return;
    }
    boolean all = grid.getRowCount() > 0;
    for (int row = 0; row < grid.getRowCount() && all; row++) {
      if (!Boolean.TRUE.equals(grid.getValueAt(row, 0))) {
        all = false;
      }
    }
    syncingSelection = true;
    selectAll.setSelected(grid.getRowCount() > 0 && all);
    syncingSelection = false;
    updateRowActions();
  }

  private List<Integer> rowsToChange() {
    List<Integer> checked = new ArrayList<>();
    for (int row = 0; row < grid.getRowCount(); row++) {
      if (Boolean.TRUE.equals(grid.getValueAt(row, 0))) {
        checked.add(row);
      }
    }
    if (!checked.isEmpty()) {
      return checked;
    }
    List<Integer> selected = new ArrayList<>();
    for (int row : grid.getSelectedRows()) {
      selected.add(row);
    }
    return selected;
  }

  private List<Object> rowValues(int row) {
    List<Object> values = new ArrayList<>();
    for (int column = 1; column < grid.getColumnCount(); column++) {
      values.add(grid.getValueAt(row, column));
    }
    return values;
  }

  private void showResult(ScriptResult result) {
    showDataCard(gridTable == null ? "結果" : "資料 · " + gridTable.name());
    Object[] headers = new Object[result.columns().size() + 1];
    headers[0] = "選";
    for (int i = 0; i < result.columns().size(); i++) {
      headers[i + 1] = result.columns().get(i);
    }
    DefaultTableModel model = new DefaultTableModel(headers, 0) {
      @Override
      public boolean isCellEditable(int row, int column) {
        return column == 0;
      }

      @Override
      public Class<?> getColumnClass(int column) {
        return column == 0 ? Boolean.class : Object.class;
      }
    };
    for (Row row : result.rows()) {
      Object[] values = new Object[row.values().size() + 1];
      values[0] = Boolean.FALSE;
      for (int i = 0; i < row.values().size(); i++) {
        values[i + 1] = row.values().get(i);
      }
      model.addRow(values);
    }
    model.addTableModelListener(event -> {
      if (event.getColumn() == 0 || event.getColumn() == javax.swing.event.TableModelEvent.ALL_COLUMNS) {
        refreshSelectAll();
      }
    });
    grid.setModel(model);
    sizeColumns();
    refreshSelectAll();
  }

  private void showDataCard(String title) {
    resultBorder.setTitle(title);
    rowActions.setVisible(true);
    if (sqlPanel != null) {
      sqlPanel.setVisible(true);
    }
    resultCards.show(resultBody, "rows");
    if (resultPanel != null) {
      resultPanel.revalidate();
      resultPanel.repaint();
    }
    if (workspace != null) {
      workspace.revalidate();
      workspace.repaint();
    }
  }

  private static void fitColumns(JTable table) {
    FontMetrics cells = table.getFontMetrics(table.getFont());
    FontMetrics headers = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());
    int sample = Math.min(table.getRowCount(), 20);
    for (int col = 0; col < table.getColumnCount(); col++) {
      TableColumn column = table.getColumnModel().getColumn(col);
      int width = headers.stringWidth(String.valueOf(column.getHeaderValue())) + 28;
      for (int row = 0; row < sample; row++) {
        Object value = table.getValueAt(row, col);
        width = Math.max(width, cells.stringWidth(value == null ? "NULL" : String.valueOf(value)) + 28);
      }
      column.setPreferredWidth(Math.min(Math.max(width, 72), 280));
    }
  }

  private void sizeColumns() {
    FontMetrics cells = grid.getFontMetrics(grid.getFont());
    FontMetrics headers = grid.getTableHeader().getFontMetrics(grid.getTableHeader().getFont());
    int sample = Math.min(grid.getRowCount(), 40);
    for (int col = 0; col < grid.getColumnCount(); col++) {
      TableColumn column = grid.getColumnModel().getColumn(col);
      if (col == 0) {
        column.setMinWidth(48);
        column.setMaxWidth(64);
        column.setPreferredWidth(52);
        continue;
      }
      String header = String.valueOf(column.getHeaderValue());
      int width = headers.stringWidth(header) + 28;
      for (int row = 0; row < sample; row++) {
        width = Math.max(width, cells.stringWidth(cellText(grid.getValueAt(row, col))) + 20);
      }
      column.setMinWidth(64);
      column.setPreferredWidth(Math.min(Math.max(width, 64), 420));
    }
  }

  private static String cellText(Object value) {
    if (value == null) {
      return "NULL";
    }
    String text = String.valueOf(value);
    return text.length() > 80 ? text.substring(0, 80) : text;
  }

  private void rebuildTree(String selectName) {
    TableInfo current = selectedTable();
    String selected = selectName != null ? selectName : current == null ? null : current.name();
    tree.removeTreeSelectionListener(onTreeSelect);
    tree.setModel(new DefaultTreeModel(buildRoot()));
    expandRoot();
    reselect(selected);
    tree.addTreeSelectionListener(onTreeSelect);
    fitTreeWidth();
    updateInsertButton();
  }

  private DefaultMutableTreeNode buildRoot() {
    if (!session.isOpen()) {
      return closedRoot();
    }
    DefaultMutableTreeNode root = new DefaultMutableTreeNode(session.path().toString());
    for (TableInfo table : visibleTables()) {
      DefaultMutableTreeNode tableNode = new DefaultMutableTreeNode(new TableNode(table));
      for (Column column : table.columns()) {
        tableNode.add(new DefaultMutableTreeNode(new ColumnNode(column)));
      }
      root.add(tableNode);
    }
    return root;
  }

  private List<TableInfo> visibleTables() {
    List<TableInfo> shown = new ArrayList<>();
    for (TableInfo table : session.tables()) {
      if (!table.name().startsWith("sqlite_")) {
        shown.add(table);
      }
    }
    shown.sort(Comparator.comparing(TableInfo::name, String.CASE_INSENSITIVE_ORDER));
    return shown;
  }

  private void expandRoot() {
    tree.expandPath(new TreePath(tree.getModel().getRoot()));
  }

  private void fitTreeWidth() {
    if (!fitTreeNow()) {
      SwingUtilities.invokeLater(this::fitTreeNow);
    }
  }

  private boolean fitTreeNow() {
    int rows = tree.getRowCount();
    if (rows == 0) {
      return true;
    }
    int width = 0;
    int bottom = 0;
    for (int row = 0; row < rows; row++) {
      Rectangle bounds = tree.getRowBounds(row);
      if (bounds == null) {
        return false;
      }
      width = Math.max(width, bounds.x + bounds.width);
      bottom = Math.max(bottom, bounds.y + bounds.height);
    }
    tree.setPreferredSize(new Dimension(width + 24, bottom + 8));
    tree.revalidate();
    return true;
  }

  private void reselect(String tableName) {
    if (tableName == null) {
      return;
    }
    Object root = tree.getModel().getRoot();
    int count = tree.getModel().getChildCount(root);
    for (int i = 0; i < count; i++) {
      Object child = tree.getModel().getChild(root, i);
      if (child instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof TableNode table
          && table.table.name().equals(tableName)) {
        TreePath path = new TreePath(new Object[] {root, node});
        tree.expandPath(path);
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
        return;
      }
    }
  }

  private TableInfo selectedTable() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return null;
    }
    Object selected = path.getLastPathComponent();
    if (!(selected instanceof DefaultMutableTreeNode node)) {
      return null;
    }
    if (node.getUserObject() instanceof TableNode table) {
      return table.table;
    }
    if (node.getUserObject() instanceof ColumnNode && node.getParent() instanceof DefaultMutableTreeNode parent
        && parent.getUserObject() instanceof TableNode table) {
      return table.table;
    }
    return null;
  }

  private Column selectedColumn() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return null;
    }
    Object selected = path.getLastPathComponent();
    if (selected instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof ColumnNode column) {
      return column.column();
    }
    return null;
  }

  private void installCopy() {
    KeyStroke copy = KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK);
    grid.getInputMap(JComponent.WHEN_FOCUSED).put(copy, "copy-cell");
    grid.getActionMap().put("copy-cell", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        int row = grid.getSelectedRow();
        int column = grid.getSelectedColumn();
        if (row < 0 || column < 0) {
          return;
        }
        Object value = grid.getValueAt(row, column);
        String text = value == null ? "NULL" : String.valueOf(value);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
      }
    });
  }

  private void setStatus(String text, boolean error) {
    status.setForeground(error ? ERROR : NORMAL);
    status.setText(text);
  }

  private static DefaultMutableTreeNode closedRoot() {
    return new DefaultMutableTreeNode("尚未開啟資料庫");
  }

  private static DefaultTableModel emptyModel() {
    return new DefaultTableModel() {
      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  private static String exampleSql() {
    return """
        CREATE TABLE note (
          id INTEGER,
          body TEXT
        );
        """;
  }

  private static void applyLook() {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception ignored) {
      // 系統外觀不可用時沿用 Swing 預設。
    }
    Font ui = uiFont();
    Font list = listFont();
    UIManager.put("Button.font", ui);
    UIManager.put("Label.font", ui);
    UIManager.put("Tree.font", list);
    UIManager.put("Table.font", list);
    UIManager.put("TableHeader.font", list.deriveFont(Font.BOLD));
    UIManager.put("TextField.font", ui);
    UIManager.put("TextArea.font", ui);
    UIManager.put("ComboBox.font", ui);
    UIManager.put("CheckBox.font", ui);
    UIManager.put("OptionPane.messageFont", ui);
    UIManager.put("TitledBorder.font", ui.deriveFont(Font.BOLD));
    UIManager.put("TitledBorder.titleColor", Brand.TEAL);
    UIManager.put("TitledBorder.border", BorderFactory.createLineBorder(Brand.LINE));
    UIManager.put("TabbedPane.font", ui);
    UIManager.put("PopupMenu.font", ui);
    UIManager.put("Menu.font", ui);
    UIManager.put("MenuItem.font", ui);
  }

  /** 工具列、按鈕、標籤：略密於系統預設，仍保持可點。 */
  private static Font uiFont() {
    return uiFont(12.5f, Font.PLAIN);
  }

  /** 左側物件樹與資料格：再小一號，接近 DBeaver / DataGrip 密度。 */
  private static Font listFont() {
    return uiFont(12f, Font.PLAIN);
  }

  private static Font uiFont(float size, int style) {
    Font font = new Font("Microsoft JhengHei UI", style, Math.round(size));
    if (!"Microsoft JhengHei UI".equalsIgnoreCase(font.getFamily())) {
      font = new Font(Font.DIALOG, style, Math.round(size));
    }
    return font.deriveFont(style, size);
  }

  private JPanel brandBar() {
    JLabel mark = new JLabel(Brand.icon(40));
    JLabel title = new JLabel("JSQLite");
    title.setFont(uiFont(18f, Font.BOLD));
    title.setForeground(Brand.INK);
    JLabel subtitle = new JLabel("資料庫");
    subtitle.setFont(uiFont(11f, Font.PLAIN));
    subtitle.setForeground(Brand.MUTED);
    JPanel names = new JPanel(new BorderLayout());
    names.setOpaque(false);
    names.add(title, BorderLayout.NORTH);
    names.add(subtitle, BorderLayout.SOUTH);
    JPanel left = new JPanel(new BorderLayout(10, 0));
    left.setOpaque(false);
    left.add(mark, BorderLayout.WEST);
    left.add(names, BorderLayout.CENTER);

    databaseLabel.setFont(uiFont());
    databaseLabel.setForeground(Brand.MUTED);
    JPanel bar = new JPanel(new BorderLayout(16, 0));
    bar.setBackground(Brand.SURFACE);
    bar.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, Brand.LINE),
        BorderFactory.createEmptyBorder(10, 14, 10, 16)));
    bar.add(left, BorderLayout.WEST);
    bar.add(databaseLabel, BorderLayout.EAST);
    return bar;
  }

  private static JButton button(String text, java.awt.event.ActionListener action) {
    JButton button = new JButton(text);
    button.setFont(uiFont());
    button.addActionListener(action);
    return button;
  }

  private static Font sqlFont() {
    Font font = new Font("Consolas", Font.PLAIN, 13);
    if ("Consolas".equalsIgnoreCase(font.getFamily())) {
      return font;
    }
    return new Font(Font.MONOSPACED, Font.PLAIN, 13);
  }

  private record TableNode(TableInfo table) {
    @Override
    public String toString() {
      return table.name() + " (" + table.rowCount() + ")";
    }
  }

  private record ColumnNode(Column column) {
    @Override
    public String toString() {
      return column.name() + "  " + column.type();
    }
  }

  private static final class NullCellRenderer extends DefaultTableCellRenderer {
    @Override
    public java.awt.Component getTableCellRendererComponent(
        JTable table, Object value, boolean selected, boolean focus, int row, int column) {
      java.awt.Component component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
      String text = value == null ? "NULL" : String.valueOf(value);
      setToolTipText(text.length() > 500 ? text.substring(0, 500) : text);
      setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
      if (value == null) {
        setText("NULL");
        if (!selected) {
          setForeground(Brand.MUTED);
        }
      } else if (!selected) {
        setForeground(table.getForeground());
      }
      return component;
    }
  }

  private static final class SchemaTreeRenderer extends DefaultTreeCellRenderer {
    SchemaTreeRenderer() {
      setBackgroundSelectionColor(Brand.SELECT);
      setTextSelectionColor(Brand.INK);
      setBorderSelectionColor(Brand.TEAL);
      setBackgroundNonSelectionColor(Brand.SURFACE);
      setTextNonSelectionColor(Brand.INK);
      setIconTextGap(5);
      setFont(listFont());
    }

    @Override
    public java.awt.Component getTreeCellRendererComponent(
        JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
      super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
      javax.swing.Icon icon = SchemaIcons.DATABASE;
      if (value instanceof DefaultMutableTreeNode node) {
        Object user = node.getUserObject();
        if (user instanceof TableNode) {
          icon = SchemaIcons.TABLE;
        } else if (user instanceof ColumnNode column) {
          icon = column.column().type() == ColumnType.INTEGER ? SchemaIcons.INTEGER : SchemaIcons.TEXT;
        }
      }
      setIcon(icon);
      return this;
    }
  }

  private static final class HeaderRenderer extends DefaultTableCellRenderer {
    @Override
    public java.awt.Component getTableCellRendererComponent(
        JTable table, Object value, boolean selected, boolean focus, int row, int column) {
      super.getTableCellRendererComponent(table, value, selected, focus, row, column);
      setText(value == null ? "" : String.valueOf(value));
      setFont(listFont().deriveFont(Font.BOLD));
      setBackground(Brand.ZEBRA);
      setForeground(Brand.INK);
      setHorizontalAlignment(LEFT);
      setBorder(BorderFactory.createCompoundBorder(
          BorderFactory.createMatteBorder(0, 0, 1, 0, Brand.LINE),
          BorderFactory.createEmptyBorder(4, 8, 4, 8)));
      return this;
    }
  }
}
