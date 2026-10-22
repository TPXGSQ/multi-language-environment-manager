package com.langmgr.ui;

import com.langmgr.core.DepInspector;
import com.langmgr.core.Detected;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * 依赖管理页。
 *
 * <p>两个视角：
 * <ul>
 *   <li><b>全局依赖</b>——选定语言后，调用其包管理器列出当前解释器下已安装的所有包；</li>
 *   <li><b>项目依赖</b>——选定一个项目目录，解析其中的 requirements.txt / package.json /
 *       pom.xml / go.mod / Cargo.toml 等声明文件，看清项目需要什么。</li>
 * </ul>
 *
 * <p>全部为只读操作，不会执行安装。
 */
public class DependencyPanel extends JPanel {

    private final AppModel model;
    private final JComboBox<String> langCombo = new JComboBox<>();
    private final JComboBox<String> managerCombo = new JComboBox<>();
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"包名", "版本", "约束", "来源", "类型"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextField filterField = new JTextField();
    private final JLabel countLabel = Theme.muted("尚未查询");
    private final JPanel projectWrap = new JPanel(new BorderLayout());
    private final JTextArea rawArea = new JTextArea();
    private final JTextField projectDirField = new JTextField();
    private final JCheckBox deepSearch = new JCheckBox("递归子目录", true);

    private Detected.Report report;
    private List<DepInspector.Dep> allDeps = new ArrayList<>();
    /** 当前表格中显示的行（过滤后的顺序），复制安装命令时用它索引。 */
    private List<DepInspector.Dep> visibleDeps = new ArrayList<>();
    private String currentLangId;
    private final List<String> langIds = new ArrayList<>();
    private volatile boolean busy;
    /** 查询进行中用户又切换了语言/管理器：完成后按最新选择再查一次。 */
    private boolean requery;

    public DependencyPanel(AppModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG_APP);
        setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 24));

        add(buildHeader(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        Theme.flatTabs(tabs);
        tabs.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        tabs.addTab("全局已安装依赖", buildGlobalTab());
        tabs.addTab("项目依赖清单", buildProjectTab());
        tabs.addTab("命令速查", buildCheatsheetTab());
        add(tabs, BorderLayout.CENTER);

        langCombo.addActionListener(e -> onLangChanged());
        managerCombo.addActionListener(e -> queryGlobal());
        filterField.getDocument().addDocumentListener(new LanguagePanel.SimpleDoc(this::applyFilter));
    }

    /* ---------------------------------------------------------- 布局 */

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(Theme.title("依赖管理"), BorderLayout.NORTH);
        JLabel sub = Theme.sub("列出解释器下已安装的包，或解析项目里的依赖声明文件；只读，不会执行安装");
        sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        left.add(sub, BorderLayout.CENTER);
        p.add(left, BorderLayout.WEST);
        return p;
    }

    private JComponent buildGlobalTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        bar.add(Theme.label("语言"));
        langCombo.setPreferredSize(new Dimension(190, 30));
        langCombo.setFont(Theme.F_SMALL);
        langCombo.setBackground(Color.WHITE);
        langCombo.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        bar.add(langCombo);
        bar.add(Theme.label("包管理器"));
        managerCombo.setPreferredSize(new Dimension(160, 30));
        managerCombo.setFont(Theme.F_SMALL);
        managerCombo.setBackground(Color.WHITE);
        managerCombo.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        bar.add(managerCombo);
        bar.add(Theme.label("筛选"));
        filterField.setPreferredSize(new Dimension(170, 30));
        filterField.setFont(Theme.F_SMALL);
        filterField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        bar.add(filterField);
        JButton refresh = Theme.ghostButton("重新查询");
        refresh.addActionListener(e -> queryGlobal());
        bar.add(refresh);
        bar.add(countLabel);
        p.add(bar, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBackground(Theme.BG_APP);

        configureTable(table);
        JPanel sp = Theme.roundedScroll(table);
        center.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        bottom.add(Theme.sub("原始输出（包管理器的真实返回，排查解析问题时看这里）"), BorderLayout.NORTH);
        rawArea.setEditable(false);
        rawArea.setFont(Theme.F_MONO_SM);
        rawArea.setBackground(Theme.BG_CODE);
        rawArea.setLineWrap(false);
        rawArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane rsp = new JScrollPane(rawArea);
        rsp.setPreferredSize(new Dimension(0, 130));
        rsp.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        bottom.add(rsp, BorderLayout.CENTER);
        center.add(bottom, BorderLayout.SOUTH);

        p.add(center, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildProjectTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JPanel leftBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftBar.setOpaque(false);
        leftBar.add(Theme.label("项目目录"));
        projectDirField.setPreferredSize(new Dimension(430, 30));
        projectDirField.setFont(Theme.F_MONO_SM);
        projectDirField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        leftBar.add(projectDirField);
        JButton pick = Theme.ghostButton("浏览…");
        pick.addActionListener(e -> pickProjectDir());
        leftBar.add(pick);
        JButton scanBtn = Theme.primaryButton("扫描依赖文件");
        scanBtn.addActionListener(e -> scanProject());
        leftBar.add(scanBtn);
        deepSearch.setOpaque(false);
        deepSearch.setFont(Theme.F_SMALL);
        deepSearch.setForeground(Theme.TEXT_MAIN);
        leftBar.add(deepSearch);
        bar.add(leftBar, BorderLayout.CENTER);
        p.add(bar, BorderLayout.NORTH);

        projectWrap.setBackground(Theme.BG_APP);
        projectWrap.add(Theme.notice(
                "选择一个项目目录，这里会解析其中的依赖声明文件（requirements.txt、package.json、"
                        + "pom.xml、build.gradle、go.mod、Cargo.toml、composer.json、Gemfile、"
                        + "pubspec.yaml、*.csproj 等）。\n"
                        + "解析只读取文件内容，不要求对应的包管理器已经安装，也不会联网。",
                Theme.TEXT_SUB, Theme.GRAY_BG), BorderLayout.CENTER);
        p.add(Theme.scroll(projectWrap), BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCheatsheetTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.add(Theme.label("语言"));
        final JComboBox<String> cs = new JComboBox<>(new String[]{
                "python", "node", "java", "go", "rust", "dotnet", "csharp", "msvc", "ruby", "php"});
        cs.setPreferredSize(new Dimension(140, 30));
        cs.setFont(Theme.F_SMALL);
        cs.setBackground(Color.WHITE);
        bar.add(cs);
        JButton copyAll = Theme.ghostButton("复制全部命令");
        bar.add(copyAll);
        p.add(bar, BorderLayout.NORTH);

        final JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.BG_APP);
        Runnable render = () -> {
            body.removeAll();
            Map<String, List<String>> map = DepInspector.cheatsheet(String.valueOf(cs.getSelectedItem()));
            JPanel grid = Theme.vBox(8);
            for (Map.Entry<String, List<String>> e : map.entrySet()) {
                JPanel cell = Theme.card(null);
                cell.setLayout(new BorderLayout(0, 6));
                cell.add(Theme.h2(e.getKey()), BorderLayout.NORTH);
                JPanel rows = Theme.vBox(3);
                rows.setOpaque(false);
                for (String cmd : e.getValue()) {
                    JLabel l = new JLabel(cmd);
                    l.setFont(Theme.F_MONO_SM);
                    l.setForeground(Theme.TEXT_MAIN);
                    l.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
                    l.setToolTipText("双击复制");
                    l.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override
                        public void mouseClicked(java.awt.event.MouseEvent ev) {
                            if (ev.getClickCount() == 2) {
                                Theme.copy(l, cmd, "命令");
                            }
                        }
                    });
                    rows.add(Theme.fixed(l));
                }
                cell.add(rows, BorderLayout.CENTER);
                grid.add(Theme.fixed(cell));
            }
            body.add(Theme.scroll(grid), BorderLayout.CENTER);
            body.revalidate();
            body.repaint();
        };
        cs.addActionListener(e -> render.run());
        copyAll.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> en : DepInspector.cheatsheet(
                    String.valueOf(cs.getSelectedItem())).entrySet()) {
                sb.append("# ").append(en.getKey()).append("\n");
                for (String c : en.getValue()) {
                    sb.append(c).append("\n");
                }
                sb.append("\n");
            }
            Theme.copy(this, sb.toString(), "命令清单");
        });
        render.run();
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private void configureTable(JTable t) {
        configureTable(t, true);
    }

    private void configureTable(JTable t, boolean withInstallMenu) {
        t.setFont(Theme.F_SMALL);
        // 只读展示：双击不进入"假编辑"
        Theme.readOnly(t);
        t.setRowHeight(25);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        t.getTableHeader().setFont(Theme.F_H3);
        t.getTableHeader().setPreferredSize(new Dimension(0, 30));
        t.getTableHeader().setBackground(Theme.TABLE_HEADER_BG);
        t.getTableHeader().setForeground(Theme.TEXT_MAIN);
        Theme.flatTableHeader(t);
        int[] widths = {280, 120, 110, 150, 70};
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        t.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                          boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                ((javax.swing.table.DefaultTableCellRenderer) c).setHorizontalAlignment(CENTER);
                c.setFont(Theme.F_SMALL);
                if (isSelected) {
                    c.setBackground(new Color(0xDD, 0xE9, 0xFC));
                    c.setForeground(Theme.TABLE_CELL_FG);
                } else {
                    c.setBackground(row % 2 == 1 ? new Color(0xFA, 0xFB, 0xFD) : Color.WHITE);
                    c.setForeground(Theme.TABLE_CELL_FG);
                }
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                if (column == 4 && "dev".equalsIgnoreCase(String.valueOf(value))) {
                    c.setForeground(Theme.WARN);
                }
                // 单元格内容被列宽截断时，悬停给出完整文字（长文本限宽换行）
                String text = value == null ? "" : String.valueOf(value);
                int avail = table.getColumnModel().getColumn(column).getWidth() - 16;
                boolean clipped = avail > 0
                        && getFontMetrics(getFont()).stringWidth(text) > avail;
                setToolTipText(clipped ? Theme.wrapTip(text, avail) : null);
                return c;
            }
        });
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyName = new JMenuItem("复制包名");
        copyName.addActionListener(e -> copySelectedColumn(t, 0));
        menu.add(copyName);
        if (withInstallMenu) {
            JMenuItem copyInstall = new JMenuItem("复制安装命令");
            copyInstall.addActionListener(e -> copyInstallCommand(t));
            menu.add(copyInstall);
        }
        t.setComponentPopupMenu(menu);
    }

    private void copySelectedColumn(JTable t, int col) {
        int[] rows = t.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(t.getValueAt(rows[i], col));
        }
        Theme.copy(t, sb.toString(), rows.length + " 个包名");
    }

    private void copyInstallCommand(JTable t) {
        int[] rows = t.getSelectedRows();
        if (rows.length == 0 || visibleDeps.isEmpty()) {
            return;
        }
        List<DepInspector.Dep> sel = new ArrayList<>();
        for (int r : rows) {
            int m = t.convertRowIndexToModel(r);
            if (m >= 0 && m < visibleDeps.size()) {
                sel.add(visibleDeps.get(m));
            }
        }
        String pmId = currentManagerId();
        Theme.copy(t, DepInspector.installCommand(currentLangId, pmId, sel), "安装命令");
    }

    private String currentManagerId() {
        if (report == null || currentLangId == null) {
            return null;
        }
        Detected.LangResult r = report.byId(currentLangId);
        if (r == null) {
            return null;
        }
        Object sel = managerCombo.getSelectedItem();
        if (sel == null) {
            return null;
        }
        for (Detected.PkgState s : r.pkgManagers) {
            if (sel.toString().startsWith(s.displayName)) {
                return s.id;
            }
        }
        return null;
    }

    /* ---------------------------------------------------------- 刷新 */

    public void refresh(Detected.Report report) {
        this.report = report;
        String keep = currentLangId;
        langCombo.removeAllItems();
        langIds.clear();
        if (report == null) {
            countLabel.setText("尚未扫描");
            return;
        }
        for (Detected.LangResult r : report.results) {
            if (r.installed && !r.spec.pkgManagers.isEmpty()) {
                langCombo.addItem(r.spec.displayName);
                langIds.add(r.spec.id);
            }
        }
        if (keep != null) {
            int idx = langIds.indexOf(keep);
            if (idx >= 0) {
                langCombo.setSelectedIndex(idx);
                return;
            }
        }
        if (langCombo.getItemCount() > 0) {
            langCombo.setSelectedIndex(0);
            onLangChanged();
        } else {
            countLabel.setText("本机没有检测到带包管理器的语言");
        }
    }

    public void focusOn(String langId) {
        if (langId == null) {
            return;
        }
        int idx = langIds.indexOf(langId);
        if (idx >= 0) {
            langCombo.setSelectedIndex(idx);
        }
    }

    private void onLangChanged() {
        int i = langCombo.getSelectedIndex();
        if (i < 0 || i >= langIds.size() || report == null) {
            managerCombo.removeAllItems();
            return;
        }
        currentLangId = langIds.get(i);
        Detected.LangResult r = report.byId(currentLangId);
        managerCombo.removeAllItems();
        if (r == null) {
            return;
        }
        for (Detected.PkgState s : r.pkgManagers) {
            managerCombo.addItem(s.displayName + (s.installed ? ("  " + s.version) : "  （未安装）"));
        }
        // 自动选中第一个可用的
        for (int k = 0; k < r.pkgManagers.size(); k++) {
            if (r.pkgManagers.get(k).installed) {
                managerCombo.setSelectedIndex(k);
                return;
            }
        }
    }

    /* ---------------------------------------------------------- 查询 */

    private void queryGlobal() {
        if (report == null || currentLangId == null) {
            return;
        }
        if (busy) {
            // 不直接丢弃：正在跑的查询完成后按最新选择重查，避免“下拉框已切换、
            // 表格还是旧管理器数据”的错位。
            requery = true;
            return;
        }
        Detected.LangResult lang = report.byId(currentLangId);
        if (lang == null) {
            return;
        }
        int mi = managerCombo.getSelectedIndex();
        if (mi < 0 || mi >= lang.spec.pkgManagers.size()) {
            return;
        }
        final com.langmgr.core.LangSpec.PkgManager pm = lang.spec.pkgManagers.get(mi);
        if (!lang.pkgManagers.isEmpty() && mi < lang.pkgManagers.size()
                && !lang.pkgManagers.get(mi).installed) {
            tableModel.setRowCount(0);
            allDeps = new ArrayList<>();
            visibleDeps = new ArrayList<>();
            rawArea.setText("");
            countLabel.setText(pm.displayName + " 未安装，无法查询");
            return;
        }
        busy = true;
        countLabel.setText("正在查询 " + pm.displayName + " …");
        SwingWorker<DepInspector.DepResult, Void> w = new SwingWorker<DepInspector.DepResult, Void>() {
            @Override
            protected DepInspector.DepResult doInBackground() {
                return DepInspector.listPackages(pm, lang);
            }

            @Override
            protected void done() {
                busy = false;
                if (requery) {
                    requery = false;
                    queryGlobal();
                    return;
                }
                try {
                    DepInspector.DepResult res = get();
                    allDeps = res.deps;
                    rawArea.setText(res.rawOutput == null || res.rawOutput.isEmpty()
                            ? (res.error == null ? "（无输出）" : res.error)
                            : truncate(res.rawOutput, 60000));
                    rawArea.setCaretPosition(0);
                    if (!res.ok) {
                        tableModel.setRowCount(0);
                        countLabel.setText(res.managerName + " 查询失败：" + res.error);
                        return;
                    }
                    applyFilter();
                    countLabel.setText(res.managerName + " · " + res.deps.size() + " 个包 · "
                            + (res.millis / 1000.0) + "s"
                            + (res.error == null ? "" : (" · " + res.error)));
                } catch (Exception ex) {
                    tableModel.setRowCount(0);
                    countLabel.setText("查询异常：" + ex.getMessage());
                }
            }
        };
        w.execute();
    }

    private void applyFilter() {
        String kw = filterField.getText() == null ? "" : filterField.getText().trim().toLowerCase();
        tableModel.setRowCount(0);
        visibleDeps = new ArrayList<>();
        int shown = 0;
        for (DepInspector.Dep d : allDeps) {
            if (!kw.isEmpty()) {
                String hay = ((d.name == null ? "" : d.name) + " "
                        + (d.version == null ? "" : d.version) + " "
                        + (d.constraint == null ? "" : d.constraint)).toLowerCase();
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            tableModel.addRow(new Object[]{
                    d.name,
                    d.version == null ? "—" : d.version,
                    d.constraint == null ? "" : d.constraint,
                    d.source,
                    d.dev ? "dev" : "run"});
            visibleDeps.add(d);
            shown++;
        }
        if (!kw.isEmpty()) {
            countLabel.setText("筛选出 " + shown + " / " + allDeps.size() + " 个包");
        }
    }

    private void pickProjectDir() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择项目目录");
        String cur = projectDirField.getText();
        if (cur != null && !cur.trim().isEmpty() && new File(cur).isDirectory()) {
            fc.setCurrentDirectory(new File(cur));
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            projectDirField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void scanProject() {
        String dirText = projectDirField.getText() == null ? "" : projectDirField.getText().trim();
        if (dirText.isEmpty()) {
            Theme.info(this, "请先选择目录", "请在上方填入或浏览选择一个项目目录。");
            return;
        }
        File dir = new File(dirText);
        if (!dir.isDirectory()) {
            Theme.error(this, "目录不存在", "找不到目录：" + dirText);
            return;
        }
        projectWrap.removeAll();
        projectWrap.add(Theme.muted("正在扫描 " + dir.getName() + " …"), BorderLayout.CENTER);
        projectWrap.revalidate();
        projectWrap.repaint();

        boolean deep = deepSearch.isSelected();
        final int maxDepth = deep ? 4 : 0;

        SwingWorker<List<Path>, Void> w = new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() {
                return DepInspector.findManifests(dir, maxDepth);
            }

            @Override
            protected void done() {
                try {
                    List<Path> files = get();
                    renderProjectResult(dir, files);
                } catch (Exception ex) {
                    projectWrap.removeAll();
                    projectWrap.add(Theme.notice("扫描失败：" + ex.getMessage(), Theme.ERR, Theme.ERR_BG),
                            BorderLayout.CENTER);
                    projectWrap.revalidate();
                    projectWrap.repaint();
                }
            }
        };
        w.execute();
    }

    private void renderProjectResult(File dir, List<Path> files) {
        projectWrap.removeAll();
        if (files.isEmpty()) {
            projectWrap.add(Theme.notice("在 " + dir.getAbsolutePath()
                            + " 下（含子目录）没有找到任何可识别的依赖声明文件。\n"
                            + "支持：requirements.txt / environment.yml / pyproject.toml / Pipfile / "
                            + "package.json / pom.xml / build.gradle(.kts) / go.mod / Cargo.toml / "
                            + "composer.json / Gemfile / pubspec.yaml / *.csproj / packages.config",
                    Theme.WARN, Theme.WARN_BG), BorderLayout.CENTER);
            projectWrap.revalidate();
            projectWrap.repaint();
            return;
        }
        JPanel body = Theme.vBox(10);
        JLabel head = new JLabel("在 " + dir.getName() + " 下找到 " + files.size() + " 个依赖声明文件");
        head.setFont(Theme.F_H2);
        head.setForeground(Theme.TEXT_MAIN);
        head.setAlignmentX(LEFT_ALIGNMENT);
        body.add(Theme.fixed(head));

        for (Path f : files) {
            DepInspector.DepResult res = DepInspector.parseManifest(f);
            body.add(Theme.fixed(manifestCard(f, res)));
        }
        projectWrap.add(Theme.scroll(body), BorderLayout.CENTER);
        projectWrap.revalidate();
        projectWrap.repaint();
    }

    private JPanel manifestCard(Path file, DepInspector.DepResult res) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(0, 8));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JPanel hl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        hl.setOpaque(false);
        JLabel nm = new JLabel(file.getFileName().toString());
        nm.setFont(Theme.F_H3);
        nm.setForeground(Theme.TEXT_MAIN);
        hl.add(nm);
        hl.add(Theme.badge(res.ok ? (res.deps.size() + " 项依赖") : "解析受限",
                res.ok ? Theme.OK : Theme.WARN, res.ok ? Theme.OK_BG : Theme.WARN_BG));
        head.add(hl, BorderLayout.WEST);
        JPanel hr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        hr.setOpaque(false);
        JLabel rel = Theme.muted(relativeOf(file));
        rel.setFont(Theme.F_TINY);
        hr.add(rel);
        JButton open = Theme.ghostButton("打开文件");
        open.setPreferredSize(new Dimension(Theme.px(88), Theme.px(26)));
        open.addActionListener(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file.toFile());
                }
            } catch (Exception ex) {
                Theme.error(this, "打开失败", ex.getMessage());
            }
        });
        hr.add(open);
        JButton copy = Theme.ghostButton("复制清单");
        copy.setPreferredSize(new Dimension(Theme.px(88), Theme.px(26)));
        copy.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (DepInspector.Dep d : res.deps) {
                sb.append(d.name).append("\t").append(d.displayVersion()).append("\n");
            }
            Theme.copy(this, sb.toString(), res.deps.size() + " 项");
        });
        hr.add(copy);
        head.add(hr, BorderLayout.EAST);
        p.add(head, BorderLayout.NORTH);

        if (res.error != null && !res.error.isEmpty()) {
            p.add(Theme.notice(res.error, Theme.WARN, Theme.WARN_BG), BorderLayout.CENTER);
            return p;
        }
        if (res.deps.isEmpty()) {
            p.add(Theme.muted("文件里没有解析出依赖条目（可能是空的或格式特殊）。"), BorderLayout.CENTER);
            return p;
        }

        int show = Math.min(res.deps.size(), 400);
        Object[][] rows = new Object[show][4];
        for (int i = 0; i < show; i++) {
            DepInspector.Dep d = res.deps.get(i);
            rows[i][0] = d.name;
            rows[i][1] = d.version == null ? "—" : d.version;
            rows[i][2] = d.constraint == null ? "" : d.constraint;
            rows[i][3] = d.dev ? "dev" : "run";
        }
        DefaultTableModel tm = new DefaultTableModel(rows, new Object[]{"包名", "版本", "约束", "类型"}) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable t = new JTable(tm);
        configureTable(t, false);
        t.setPreferredScrollableViewportSize(new Dimension(0, Math.min(230, 26 * rows.length + 30)));
        JPanel sp = Theme.roundedScroll(t);
        JPanel tc = new JPanel(new BorderLayout(0, 4));
        tc.setOpaque(false);
        tc.add(sp, BorderLayout.CENTER);
        if (res.deps.size() > show) {
            tc.add(Theme.muted("仅显示前 " + show + " 项，共 " + res.deps.size() + " 项。"),
                    BorderLayout.SOUTH);
        }
        p.add(tc, BorderLayout.CENTER);
        return p;
    }

    private String relativeOf(Path file) {
        String dirText = projectDirField.getText() == null ? "" : projectDirField.getText().trim();
        if (dirText.isEmpty()) {
            return file.toString();
        }
        try {
            return new File(dirText).toPath().relativize(file).toString();
        } catch (IllegalArgumentException ex) {
            return file.toString();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…（已截断）";
    }
}
