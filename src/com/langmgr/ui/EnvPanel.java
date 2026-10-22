package com.langmgr.ui;

import com.langmgr.core.Detected;
import com.langmgr.core.EnvApplier;
import com.langmgr.core.EnvStore;
import com.langmgr.core.Os;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 环境变量页：用户级变量管理 + PATH 可视化编辑 + 备份回滚。
 *
 * <p>安全约束（这一页能改配置，所以限制很明确）：
 * <ul>
 *   <li>只操作 HKCU（当前用户），系统级变量只读展示，改它需要管理员权限且影响所有用户；</li>
 *   <li>所有写入走注册表，不用 setx，避免 PATH 被截断到 1024 字符、%VAR% 被永久展开；</li>
 *   <li>任何写操作前先把受影响变量备份成 JSON，可一键回滚；</li>
 *   <li>PATH 支持排序、上移下移、去重、清理失效条目，每步都先在表格里改，最后统一提交。</li>
 * </ul>
 */
public class EnvPanel extends JPanel {

    private final AppModel model;
    private final DefaultTableModel varModel = new DefaultTableModel(
            new Object[]{"变量名", "值（原始）", "展开后", "状态"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable varTable = new JTable(varModel);
    private final DefaultTableModel pathModel = new DefaultTableModel(
            new Object[]{"序号", "目录", "归属", "存在", "原始条目"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable pathTable = new JTable(pathModel);
    private final JLabel varSummary = Theme.muted("—");
    private final JLabel pathSummary = Theme.muted("—");
    private final List<String> pendingPath = new ArrayList<>();
    private final JLabel pathDirtyLabel = Theme.muted("");
    private Detected.Report report;

    public EnvPanel(AppModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG_APP);
        setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 24));

        add(buildHeader(), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        Theme.flatTabs(tabs);
        tabs.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        tabs.addTab("环境变量", buildVarTab());
        tabs.addTab("PATH 管理", buildPathTab());
        tabs.addTab("备份与回滚", buildBackupTab());
        tabs.addTab("系统级变量（只读）", buildSystemTab());
        add(tabs, BorderLayout.CENTER);
    }

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(Theme.title("环境变量"), BorderLayout.NORTH);
        JLabel sub = Theme.sub("仅管理当前用户作用域的变量；写入使用注册表方式，不会像 setx 那样截断长 PATH");
        sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        left.add(sub, BorderLayout.CENTER);
        p.add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton reload = Theme.ghostButton("重新读取");
        reload.addActionListener(e -> refresh(model.report()));
        right.add(reload);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    /* ------------------------------------------------------ 变量表 */

    private JComponent buildVarTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JButton add = Theme.ghostButton("新增变量");
        add.addActionListener(e -> addVar());
        JButton edit = Theme.ghostButton("编辑选中");
        edit.addActionListener(e -> editVar());
        JButton del = Theme.ghostButton("删除选中");
        del.addActionListener(e -> deleteVar());
        JButton copy = Theme.ghostButton("复制值");
        copy.addActionListener(e -> copySelectedValue());
        JButton openDir = Theme.ghostButton("打开目录");
        openDir.addActionListener(e -> openSelectedDir());
        bar.add(add);
        bar.add(edit);
        bar.add(del);
        bar.add(copy);
        bar.add(openDir);
        bar.add(varSummary);
        p.add(bar, BorderLayout.NORTH);

        configureTable(varTable);
        varTable.getColumnModel().getColumn(0).setPreferredWidth(170);
        varTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        varTable.getColumnModel().getColumn(2).setPreferredWidth(330);
        varTable.getColumnModel().getColumn(3).setPreferredWidth(110);
        varTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        varTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editVar();
                }
            }
        });
        JPanel sp = Theme.roundedScroll(varTable);
        p.add(sp, BorderLayout.CENTER);

        p.add(Theme.notice("这里的「原始」列是注册表中未展开的值（可能含 %USERPROFILE% 这类引用），"
                        + "「展开后」是实际生效的路径。两者不一致是正常现象。\n"
                        + "所有改动只作用于当前用户，执行前自动备份，可在「备份与回滚」页还原。",
                Theme.TEXT_SUB, Theme.GRAY_BG), BorderLayout.SOUTH);
        return p;
    }

    private void configureTable(JTable t) {
        t.setFont(Theme.F_SMALL);
        t.setRowHeight(25);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setFont(Theme.F_H3);
        t.getTableHeader().setPreferredSize(new Dimension(0, 30));
        t.getTableHeader().setBackground(Theme.TABLE_HEADER_BG);
        t.getTableHeader().setForeground(Theme.TEXT_MAIN);
        Theme.flatTableHeader(t);
        t.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                          boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                c.setFont(Theme.F_SMALL);
                ((javax.swing.table.DefaultTableCellRenderer) c).setHorizontalAlignment(CENTER);
                if (isSelected) {
                    c.setBackground(new Color(0xDD, 0xE9, 0xFC));
                    c.setForeground(Theme.TABLE_CELL_FG);
                } else {
                    c.setBackground(row % 2 == 1 ? new Color(0xFA, 0xFB, 0xFD) : Color.WHITE);
                    c.setForeground(Theme.TABLE_CELL_FG);
                }
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                return c;
            }
        });
    }

    private void loadVars() {
        varModel.setRowCount(0);
        Map<String, String> all = EnvStore.listAll();
        int missing = 0;
        for (Map.Entry<String, String> e : all.entrySet()) {
            String raw = e.getValue();
            String exp = Os.expand(raw);
            String target = EnvStore.strip(exp);
            String status;
            if (raw == null || raw.trim().isEmpty()) {
                status = "空值";
            } else if ("PATH".equalsIgnoreCase(e.getKey())) {
                status = "列表（见 PATH 管理）";
            } else if (looksLikePath(target) && target.indexOf(File.pathSeparator) >= 0) {
                status = "多路径列表";
            } else if (!raw.equals(exp) && new File(target).exists()) {
                status = "正常（含引用）";
            } else if (looksLikePath(target) && !new File(target).exists()) {
                status = "目标不存在";
                missing++;
            } else {
                status = "正常";
            }
            varModel.addRow(new Object[]{e.getKey(), raw == null ? "" : raw, exp, status});
        }
        varSummary.setText("共 " + all.size() + " 个用户变量"
                + (missing > 0 ? ("，其中 " + missing + " 个指向不存在的路径") : ""));
    }

    private static boolean looksLikePath(String s) {
        if (s == null || s.length() < 3) {
            return false;
        }
        String t = s.trim();
        return t.contains(File.separator) || t.startsWith("\\") || t.startsWith("/")
                || (t.length() > 2 && t.charAt(1) == ':');
    }

    private void addVar() {
        JTextField name = new JTextField();
        JTextArea value = new JTextArea(3, 34);
        value.setLineWrap(true);
        value.setFont(Theme.F_MONO_SM);
        JPanel form = formPanel("变量名", name, "值", value);
        int r = JOptionPane.showConfirmDialog(this, form, "新增用户环境变量",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        String n = name.getText().trim();
        if (n.isEmpty()) {
            Theme.error(this, "变量名为空", "请填写变量名。");
            return;
        }
        if (EnvStore.readRaw(n) != null) {
            if (!Theme.confirm(this, "变量已存在",
                    n + " 已经存在，当前值为：\n" + EnvStore.readRaw(n) + "\n\n确定要覆盖吗？")) {
                return;
            }
        }
        commitVar(n, value.getText(), null);
    }

    private void editVar() {
        int row = varTable.getSelectedRow();
        if (row < 0) {
            Theme.info(this, "未选择", "请先在表格里选中一个变量。");
            return;
        }
        String name = String.valueOf(varModel.getValueAt(row, 0));
        String raw = EnvStore.readRaw(name);
        if (raw == null) {
            raw = String.valueOf(varModel.getValueAt(row, 1));
        }
        JTextField nameField = new JTextField(name);
        nameField.setEditable(false);
        JTextArea value = new JTextArea(raw == null ? "" : raw, 3, 34);
        value.setFont(Theme.F_MONO_SM);
        value.setLineWrap(true);
        JPanel form = formPanel("变量名", nameField, "值", value);
        int r = JOptionPane.showConfirmDialog(this, form, "编辑 " + name,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        commitVar(name, value.getText(), raw);
    }

    private JPanel formPanel(String k1, JComponent v1, String k2, JComponent v2) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 6, 6, 6);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0;
        g.gridy = 0;
        p.add(new JLabel(k1), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        v1.setPreferredSize(new Dimension(380, 28));
        p.add(v1, g);
        g.gridx = 0;
        g.gridy = 1;
        g.fill = GridBagConstraints.NONE;
        g.weightx = 0;
        p.add(new JLabel(k2), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.BOTH;
        g.weightx = 1;
        g.weighty = 1;
        p.add(new JScrollPane(v2), g);
        return p;
    }

    /** 统一的变量写入：备份 → 写 → 广播 → 刷新。 */
    private void commitVar(String name, String newValue, String oldValue) {
        boolean same = oldValue != null && oldValue.equals(newValue);
        if (same) {
            Theme.toast(this, "值未变化，未做修改");
            return;
        }
        if (newValue.indexOf('\n') >= 0 || newValue.indexOf('\r') >= 0) {
            Theme.error(this, "值不合法", "环境变量值不能包含换行；请改成单行后再写入。");
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append(name).append("\n\n");
        msg.append("原值：").append(oldValue == null ? "（未设置）" : oldValue).append("\n");
        msg.append("新值：").append(newValue.isEmpty() ? "（空）" : newValue).append("\n\n");
        String check = EnvStore.strip(Os.expand(newValue));
        if (looksLikePath(check) && !newValue.isEmpty()
                && !new File(check).exists()) {
            msg.append("※ 注意：新值指向的目录当前不存在。\n");
        }
        if (name.equalsIgnoreCase("PATH")) {
            msg.append("※ 你正在直接编辑 PATH，建议改用「PATH 管理」页，那里有去重和失效清理。");
        }
        msg.append("\n执行前会先备份，可在「备份与回滚」页还原。确认写入吗？");
        if (!Theme.confirm(this, "确认修改变量", msg.toString())) {
            return;
        }
        runWrite("修改变量 " + name, () -> {
            try {
                EnvStore.backupSingle(name);
            } catch (Exception ex) {
                return "备份失败，已中止（不会做任何修改）：" + ex.getMessage();
            }
            return EnvStore.write(name, newValue, newValue.contains("%"));
        }, () -> {
            loadVars();
            if (name.equalsIgnoreCase("PATH")) {
                loadPath();
            }
            model.rescan();
        });
    }

    /**
     * 把“备份 + 注册表写入 + 广播”放到后台线程，避免 EDT 被 reg.exe /
     * PowerShell 卡住；完成后回 EDT 刷新界面。返回错误信息（null=成功）。
     */
    private void runWrite(String title, Supplier<String> work, Runnable after) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<String, Void> w = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String err = work.get();
                if (err == null) {
                    EnvStore.broadcastChange();
                }
                return err;
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                String err;
                try {
                    err = get();
                } catch (Exception ex) {
                    err = "执行异常：" + ex.getMessage();
                }
                if (err != null) {
                    Theme.error(EnvPanel.this, "操作失败", err);
                } else {
                    Theme.toast(EnvPanel.this, "已更新：" + title);
                }
                if (after != null) {
                    after.run();
                }
            }
        };
        w.execute();
    }

    private void deleteVar() {
        int[] rows = varTable.getSelectedRows();
        if (rows.length == 0) {
            Theme.info(this, "未选择", "请先选中要删除的变量。");
            return;
        }
        List<String> names = new ArrayList<>();
        for (int r : rows) {
            names.add(String.valueOf(varModel.getValueAt(r, 0)));
        }
        if (names.stream().anyMatch(n -> n.equalsIgnoreCase("PATH"))) {
            Theme.error(this, "禁止删除 PATH",
                    "删除用户 PATH 会让当前用户的所有命令行工具失效，风险过高。\n"
                            + "如确需清空，请到「PATH 管理」页逐条移除。");
            return;
        }
        if (!Theme.confirm(this, "确认删除",
                "将从注册表 HKCU\\Environment 删除以下变量：\n\n"
                        + String.join("\n", names)
                        + "\n\n删除前会自动备份，可在「备份与回滚」页还原。确认吗？")) {
            return;
        }
        runWrite("删除 " + names.size() + " 个变量", () -> {
            try {
                EnvStore.backupMany(names);
            } catch (Exception ex) {
                return "备份失败，已中止：" + ex.getMessage();
            }
            List<String> failed = new ArrayList<>();
            for (String n : names) {
                String err = EnvStore.delete(n);
                if (err != null) {
                    failed.add(n + "：" + err);
                }
            }
            return failed.isEmpty() ? null : "部分删除失败：\n" + String.join("\n", failed);
        }, () -> {
            loadVars();
            model.rescan();
        });
    }

    private void copySelectedValue() {
        int row = varTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        String raw = String.valueOf(varModel.getValueAt(row, 1));
        String exp = String.valueOf(varModel.getValueAt(row, 2));
        Object[] opts = {"原始值", "展开后的值"};
        Object sel = JOptionPane.showInputDialog(this, "复制哪个值？", "复制",
                JOptionPane.PLAIN_MESSAGE, null, opts, raw.equals(exp) ? opts[0] : opts[1]);
        if (sel == null) {
            return;
        }
        Theme.copy(this, opts[0].equals(sel) ? raw : exp, "");
    }

    private void openSelectedDir() {
        int row = varTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        String exp = String.valueOf(varModel.getValueAt(row, 2));
        File f = new File(EnvStore.strip(exp));
        if (!f.exists()) {
            Theme.error(this, "目录不存在", exp + "\n该路径在本机上不存在。");
            return;
        }
        LanguagePanel.openInExplorer(f.isDirectory() ? f : f.getParentFile());
    }

    /* ------------------------------------------------------ PATH 表 */

    private JComponent buildPathTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JButton up = Theme.ghostButton("上移");
        up.addActionListener(e -> moveRow(-1));
        JButton down = Theme.ghostButton("下移");
        down.addActionListener(e -> moveRow(1));
        JButton top = Theme.ghostButton("置顶");
        top.addActionListener(e -> moveTo(0));
        JButton addBtn = Theme.ghostButton("添加目录");
        addBtn.addActionListener(e -> addPathEntry());
        JButton editBtn = Theme.ghostButton("编辑");
        editBtn.addActionListener(e -> editPathEntry());
        JButton delBtn = Theme.ghostButton("移除选中");
        delBtn.addActionListener(e -> removeSelectedPath());
        JButton dedupe = Theme.ghostButton("去重");
        dedupe.addActionListener(e -> dedupePath());
        JButton clean = Theme.dangerButton("清理失效条目");
        clean.addActionListener(e -> cleanDeadEntries());
        JButton sortBtn = Theme.ghostButton("按字母排序");
        sortBtn.addActionListener(e -> sortPath());
        bar.add(up);
        bar.add(down);
        bar.add(top);
        bar.add(addBtn);
        bar.add(editBtn);
        bar.add(delBtn);
        bar.add(dedupe);
        bar.add(clean);
        bar.add(sortBtn);
        p.add(bar, BorderLayout.NORTH);

        configureTable(pathTable);
        pathTable.getColumnModel().getColumn(0).setPreferredWidth(38);
        pathTable.getColumnModel().getColumn(1).setPreferredWidth(440);
        pathTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        pathTable.getColumnModel().getColumn(3).setPreferredWidth(56);
        pathTable.getColumnModel().getColumn(4).setPreferredWidth(340);
        pathTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        pathTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                          boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                c.setFont(column == 1 || column == 4 ? Theme.F_MONO_SM : Theme.F_SMALL);
                boolean dead = column == 3 && "否".equals(String.valueOf(value));
                if (isSelected) {
                    c.setBackground(new Color(0xDD, 0xE9, 0xFC));
                    c.setForeground(Theme.TABLE_CELL_FG);
                } else {
                    c.setBackground(dead ? Theme.ERR_BG : (row % 2 == 1 ? new Color(0xFA, 0xFB, 0xFD) : Color.WHITE));
                    c.setForeground(dead ? Theme.ERR : Theme.TEXT_MAIN);
                }
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                if (column == 0 || column == 3) {
                    setHorizontalAlignment(SwingConstants.CENTER);
                } else {
                    setHorizontalAlignment(SwingConstants.LEFT);
                }
                return c;
            }
        });
        JPanel sp = Theme.roundedScroll(pathTable);
        p.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setOpaque(false);
        JPanel noteLeft = new JPanel(new BorderLayout());
        noteLeft.setOpaque(false);
        pathSummary.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));
        noteLeft.add(pathSummary, BorderLayout.NORTH);
        noteLeft.add(Theme.notice("上面的排序、去重、移除都只改内存中的列表，必须点右侧「提交 PATH 变更」才会写入注册表。\n"
                + "写入前会自动备份；提交后新开的终端才会生效，已打开的终端和 IDE 需要重启。",
                Theme.TEXT_SUB, Theme.GRAY_BG), BorderLayout.CENTER);
        bottom.add(noteLeft, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        JButton discard = Theme.ghostButton("放弃改动");
        discard.addActionListener(e -> {
            pendingPath.clear();
            loadPath();
            Theme.toast(this, "已放弃未提交的改动");
        });
        JButton commit = Theme.ghostButton("提交 PATH 变更");
        commit.addActionListener(e -> commitPath());
        btns.add(pathDirtyLabel);
        btns.add(discard);
        btns.add(commit);
        bottom.add(btns, BorderLayout.EAST);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    /** 从注册表重新读取 PATH，覆盖待提交列表。 */
    private void loadPath() {
        pendingPath.clear();
        pendingPath.addAll(EnvStore.userPathEntries());
        renderPath();
    }

    /** 把 pendingPath 渲染到表格，不写注册表。 */
    private void renderPath() {
        pathModel.setRowCount(0);
        int dead = 0;
        for (int i = 0; i < pendingPath.size(); i++) {
            String raw = pendingPath.get(i);
            String exp = Os.expand(raw);
            boolean exists = new File(EnvStore.strip(exp)).isDirectory();
            if (!exists) {
                dead++;
            }
            String owner = EnvApplier.annotateDir(raw);
            pathModel.addRow(new Object[]{
                    i + 1,
                    exp,
                    owner == null ? "" : owner,
                    exists ? "是" : "否",
                    raw});
        }
        String rawAll = String.join(File.pathSeparator, pendingPath);
        StringBuilder sb = new StringBuilder();
        sb.append("用户 PATH：").append(pendingPath.size()).append(" 条，共 ")
                .append(rawAll.length()).append(" 字符");
        if (dead > 0) {
            sb.append("，其中 ").append(dead).append(" 条指向不存在的目录");
        }
        if (rawAll.length() > EnvStore.SETX_LIMIT) {
            sb.append("（已超过 setx 的 1024 上限，本工具用注册表写入不受影响）");
        }
        pathSummary.setText(sb.toString());
        boolean dirty = !pendingPath.equals(EnvStore.userPathEntries());
        pathDirtyLabel.setText(dirty ? "● 有未提交的改动" : "");
        pathDirtyLabel.setForeground(dirty ? Theme.WARN : Theme.TEXT_MUTED);
    }

    private void moveRow(int delta) {
        int row = pathTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        moveTo(row + delta, row);
    }

    private void moveTo(int target) {
        int row = pathTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        moveTo(target, row);
    }

    private void moveTo(int target, int from) {
        if (from < 0 || from >= pendingPath.size()) {
            return;
        }
        int t = Math.max(0, Math.min(pendingPath.size() - 1, target));
        if (t == from) {
            return;
        }
        String v = pendingPath.remove(from);
        pendingPath.add(t, v);
        renderPath();
        pathTable.setRowSelectionInterval(t, t);
    }

    private void addPathEntry() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择要加入 PATH 的目录");
        int r = fc.showOpenDialog(this);
        String dir = null;
        if (r == JFileChooser.APPROVE_OPTION) {
            dir = fc.getSelectedFile().getAbsolutePath();
        } else {
            dir = JOptionPane.showInputDialog(this, "或直接输入目录路径：", "添加 PATH 条目",
                    JOptionPane.PLAIN_MESSAGE);
        }
        if (dir == null || dir.trim().isEmpty()) {
            return;
        }
        dir = dir.trim();
        for (String p : pendingPath) {
            if (sameDir(Os.expand(p), dir)) {
                Theme.info(this, "已存在", dir + " 已经在 PATH 中（第 "
                        + (pendingPath.indexOf(p) + 1) + " 位）。");
                return;
            }
        }
        if (!new File(dir).isDirectory()) {
            if (!Theme.confirm(this, "目录不存在",
                    dir + "\n\n该目录当前不存在，仍要加入 PATH 吗？\n（失效条目会拖慢每次命令查找）")) {
                return;
            }
        }
        Object[] pos = {"加到最前（优先生效）", "加到最后"};
        Object sel = JOptionPane.showInputDialog(this, "插入位置：", "添加 PATH 条目",
                JOptionPane.PLAIN_MESSAGE, null, pos, pos[0]);
        if (sel == null) {
            return;
        }
        if (pos[0].equals(sel)) {
            pendingPath.add(0, dir);
        } else {
            pendingPath.add(dir);
        }
        renderPath();
    }

    private void editPathEntry() {
        int row = pathTable.getSelectedRow();
        if (row < 0 || row >= pendingPath.size()) {
            Theme.info(this, "未选择", "请先选中一个 PATH 条目。");
            return;
        }
        String old = pendingPath.get(row);
        Object input = JOptionPane.showInputDialog(this, "编辑 PATH 条目（可含 %VAR% 引用）：",
                "编辑", JOptionPane.PLAIN_MESSAGE, null, null, old);
        if (input == null) {
            return;
        }
        String nv = String.valueOf(input);
        if (nv.trim().isEmpty() || nv.equals(old)) {
            return;
        }
        pendingPath.set(row, nv.trim());
        renderPath();
    }

    private void removeSelectedPath() {
        int[] rows = pathTable.getSelectedRows();
        if (rows.length == 0) {
            Theme.info(this, "未选择", "请先选中要移除的条目（可多选）。");
            return;
        }
        List<String> removing = new ArrayList<>();
        for (int r : rows) {
            if (r < pendingPath.size()) {
                removing.add(pendingPath.get(r));
            }
        }
        if (!Theme.confirm(this, "确认移除",
                "将从用户 PATH 移除 " + removing.size() + " 个条目：\n\n"
                        + String.join("\n", removing)
                        + "\n\n此操作只改内存列表，点「提交 PATH 变更」后才写入注册表。")) {
            return;
        }
        pendingPath.removeAll(removing);
        renderPath();
    }

    private void dedupePath() {
        int before = pendingPath.size();
        List<String> dedup = EnvStore.dedupePath(pendingPath);
        pendingPath.clear();
        pendingPath.addAll(dedup);
        renderPath();
        Theme.toast(this, before == dedup.size() ? "没有重复条目" : ("去重：" + before + " → " + dedup.size()));
    }

    private void cleanDeadEntries() {
        List<String> dead = new ArrayList<>();
        for (String p : pendingPath) {
            if (!new File(EnvStore.strip(Os.expand(p))).isDirectory()) {
                dead.add(p);
            }
        }
        if (dead.isEmpty()) {
            Theme.info(this, "无需清理", "所有 PATH 条目指向的目录都存在。");
            return;
        }
        if (!Theme.confirm(this, "清理失效条目",
                "以下 " + dead.size() + " 个条目指向的目录不存在：\n\n"
                        + String.join("\n", dead)
                        + "\n\n移除它们可以加快命令行启动与查找速度。\n"
                        + "（只改内存列表，提交后才写入注册表）")) {
            return;
        }
        pendingPath.removeAll(dead);
        renderPath();
        Theme.toast(this, "已移除 " + dead.size() + " 个失效条目");
    }

    private void sortPath() {
        List<String> sorted = new ArrayList<>(pendingPath);
        sorted.sort((a, b) -> Os.expand(a).compareToIgnoreCase(Os.expand(b)));
        pendingPath.clear();
        pendingPath.addAll(sorted);
        renderPath();
    }

    private void commitPath() {
        List<String> current = EnvStore.userPathEntries();
        if (pendingPath.equals(current)) {
            Theme.info(this, "无改动", "PATH 与注册表中的值一致，无需提交。");
            return;
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String p : pendingPath) {
            if (noneMatch(current, p)) {
                added.add(p);
            }
        }
        for (String p : current) {
            if (noneMatch(pendingPath, p)) {
                removed.add(p);
            }
        }
        StringBuilder msg = new StringBuilder();
        msg.append("将把用户 PATH 从 ").append(current.size()).append(" 条改为 ")
                .append(pendingPath.size()).append(" 条：\n\n");
        if (!added.isEmpty()) {
            msg.append("新增（").append(added.size()).append("）：\n");
            for (String a : added) {
                msg.append("  + ").append(a).append("\n");
            }
            msg.append("\n");
        }
        if (!removed.isEmpty()) {
            msg.append("移除（").append(removed.size()).append("）：\n");
            for (String r : removed) {
                msg.append("  - ").append(r).append("\n");
            }
            msg.append("\n");
        }
        if (added.isEmpty() && removed.isEmpty()) {
            msg.append("仅顺序发生变化。\n\n");
        }
        msg.append("※ 顺序很重要：靠前的目录优先级更高，同名命令会用先找到的那个。\n");
        msg.append("写入前会自动备份，可在「备份与回滚」页一键还原。\n");
        msg.append("提交后需要重开终端才生效。确认写入吗？");

        if (!Theme.confirm(this, "确认提交 PATH 变更", msg.toString())) {
            return;
        }
        final List<String> pathSnapshot = new ArrayList<>(pendingPath);
        runWrite("PATH 变更", () -> {
            try {
                EnvStore.backupMany(java.util.Collections.singletonList("PATH"));
            } catch (Exception ex) {
                return "备份失败，已中止（PATH 未被修改）：" + ex.getMessage();
            }
            return EnvApplier.writePath(pathSnapshot);
        }, () -> {
            loadPath();
            loadVars();
            model.rescan();
        });
    }

    private static boolean noneMatch(List<String> list, String item) {
        for (String p : list) {
            if (sameDir(Os.expand(p), Os.expand(item))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameDir(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = strip(a), y = strip(b);
        return Os.isWindows() ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    private static String strip(String p) {
        String s = p.trim();
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        while (s.length() > 1 && (s.endsWith("\\") || s.endsWith("/"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /* ------------------------------------------------------ 备份 */

    private final JPanel backupListWrap = new JPanel(new BorderLayout());

    private JComponent buildBackupTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        JButton reload = Theme.ghostButton("刷新列表");
        reload.addActionListener(e -> loadBackups());
        JButton rollback = Theme.ghostButton("回滚选中备份");
        rollback.addActionListener(e -> rollbackSelected());
        JButton openDir = Theme.ghostButton("打开备份目录");
        openDir.addActionListener(e -> {
            File d = EnvStore.backupDir();
            if (d.isDirectory()) {
                LanguagePanel.openInExplorer(d);
            } else {
                Theme.info(this, "还没有备份", "尚未产生任何备份文件，目录会在首次变更时创建。");
            }
        });
        JButton del = Theme.dangerButton("删除选中备份");
        del.addActionListener(e -> deleteSelectedBackup());
        bar.add(reload);
        bar.add(rollback);
        bar.add(openDir);
        bar.add(del);
        p.add(bar, BorderLayout.NORTH);
        backupListWrap.setBackground(Theme.BG_APP);
        p.add(Theme.scroll(backupListWrap), BorderLayout.CENTER);
        p.add(Theme.notice("备份文件是纯 JSON，记录了变更前的用户 PATH 与相关变量原始值。\n"
                + "即使本工具无法启动，也可以手动打开这些文件，把里面的值复制回注册表编辑器还原。",
                Theme.TEXT_SUB, Theme.GRAY_BG), BorderLayout.SOUTH);
        loadBackups();
        return p;
    }

    private List<Path> backupFiles = new ArrayList<>();

    private void loadBackups() {
        backupFiles = EnvApplier.listBackups();
        backupListWrap.removeAll();
        if (backupFiles.isEmpty()) {
            // 白色圆角空状态卡，置顶显示（原来是一行灰字悬在灰色滚动区中央）
            JPanel card = Theme.emptyCard("还没有任何备份",
                    "执行版本切换或修改环境变量时会自动生成。\n"
                            + "备份文件可在下方说明中了解格式，随时可回滚还原。",
                    Theme.TEXT_SUB);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            top.add(card, BorderLayout.NORTH);
            backupListWrap.add(top, BorderLayout.CENTER);
        } else {
            JPanel body = Theme.vBox(7);
            for (Path p : backupFiles) {
                body.add(Theme.fixed(backupRow(p)));
            }
            backupListWrap.add(body, BorderLayout.CENTER);
        }
        backupListWrap.revalidate();
        backupListWrap.repaint();
    }

    private JPanel backupRow(Path p) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(8, 11, 8, 11)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.px(46)));
        JLabel nm = new JLabel(p.getFileName().toString());
        nm.setFont(Theme.F_MONO_SM);
        nm.setForeground(Theme.TEXT_MAIN);
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(nm, BorderLayout.NORTH);
        String size;
        try {
            size = (java.nio.file.Files.size(p) / 1024.0) + " KB";
        } catch (Exception ex) {
            size = "?";
        }
        left.add(Theme.tiny(size + " · " + p.toAbsolutePath()), BorderLayout.CENTER);
        row.add(left, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        JButton view = Theme.ghostButton("查看");
        view.setPreferredSize(new Dimension(Theme.px(64), Theme.px(26)));
        view.addActionListener(e -> viewBackup(p));
        JButton rb = Theme.ghostButton("回滚");
        rb.setPreferredSize(new Dimension(Theme.px(64), Theme.px(26)));
        rb.addActionListener(e -> rollbackTo(p));
        btns.add(view);
        btns.add(rb);
        row.add(btns, BorderLayout.EAST);
        return row;
    }

    private void viewBackup(Path p) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            JTextArea ta = new JTextArea(text);
            ta.setFont(Theme.F_MONO_SM);
            ta.setEditable(false);
            JScrollPane sp = new JScrollPane(ta);
            sp.setPreferredSize(new Dimension(620, 380));
            JPanel wrap = new JPanel(new BorderLayout(0, 6));
            wrap.add(Theme.sub(p.getFileName().toString()), BorderLayout.NORTH);
            wrap.add(sp, BorderLayout.CENTER);
            String[] opts = {"复制内容", "关闭"};
            int r = JOptionPane.showOptionDialog(this, wrap, "备份内容", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, opts, opts[1]);
            if (r == 0) {
                Theme.copy(this, text, "备份内容");
            }
        } catch (Exception ex) {
            Theme.error(this, "读取失败", ex.getMessage());
        }
    }

    private void rollbackSelected() {
        if (backupFiles.isEmpty()) {
            Theme.info(this, "没有备份", "还没有任何备份文件可供回滚。");
            return;
        }
        String[] names = new String[backupFiles.size()];
        for (int i = 0; i < backupFiles.size(); i++) {
            names[i] = backupFiles.get(i).getFileName().toString();
        }
        String sel = (String) JOptionPane.showInputDialog(this,
                "选择要回滚到的备份（列表按时间倒序，第一项最新）：", "回滚",
                JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (sel == null) {
            return;
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(sel)) {
                rollbackTo(backupFiles.get(i));
                return;
            }
        }
    }

    private void rollbackTo(Path backup) {
        if (!Theme.confirm(this, "确认回滚",
                "将用该备份还原用户 PATH 与相关变量：\n\n" + backup.getFileName()
                        + "\n\n当前的值会被覆盖。回滚前也会先做一份安全备份，"
                        + "万一回滚本身出错还能再救回来。\n"
                        + "已打开的终端需重开才生效。确认继续吗？")) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<List<String>, Void> w = new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return EnvApplier.rollback(backup);
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                List<String> log;
                try {
                    log = get();
                } catch (Exception ex) {
                    log = java.util.Collections.singletonList("× 回滚异常：" + ex.getMessage());
                }
                StringBuilder sb = new StringBuilder();
                for (String l : log) {
                    sb.append(l).append("\n");
                }
                boolean failed = sb.toString().contains("×");
                if (failed) {
                    Theme.error(EnvPanel.this, "回滚结果（含失败项）", sb.toString());
                } else {
                    Theme.info(EnvPanel.this, "回滚完成", sb.toString());
                }
                loadVars();
                loadPath();
                model.rescan();
            }
        };
        w.execute();
    }

    private void deleteSelectedBackup() {
        if (backupFiles.isEmpty()) {
            return;
        }
        String[] names = new String[backupFiles.size()];
        for (int i = 0; i < backupFiles.size(); i++) {
            names[i] = backupFiles.get(i).getFileName().toString();
        }
        String sel = (String) JOptionPane.showInputDialog(this, "选择要删除的备份文件：",
                "删除备份", JOptionPane.WARNING_MESSAGE, null, names, names[0]);
        if (sel == null) {
            return;
        }
        Path target = null;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(sel)) {
                target = backupFiles.get(i);
                break;
            }
        }
        if (target == null) {
            return;
        }
        if (!Theme.confirm(this, "确认删除备份",
                "将永久删除备份文件：\n\n" + target
                        + "\n\n删除后无法再用它回滚。确认吗？")) {
            return;
        }
        String err = EnvApplier.deleteBackup(target);
        if (err != null) {
            Theme.error(this, "删除失败", err);
        } else {
            Theme.toast(this, "已删除备份");
        }
        loadBackups();
    }

    /* ------------------------------------------------------ 系统变量（只读） */

    private JComponent buildSystemTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_APP);
        p.add(Theme.notice("系统级变量（HKLM）影响本机所有用户，修改需要管理员权限，风险面大。\n"
                        + "本工具只做只读展示，不提供写入。确需修改请用系统设置或注册表编辑器，并先自行备份。",
                Theme.WARN, Theme.WARN_BG), BorderLayout.NORTH);

        DefaultTableModel tm = new DefaultTableModel(new Object[]{"变量名", "值（展开后）", "原始值"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable t = new JTable(tm);
        configureTable(t);
        t.getColumnModel().getColumn(0).setPreferredWidth(220);
        t.getColumnModel().getColumn(1).setPreferredWidth(600);
        t.getColumnModel().getColumn(2).setPreferredWidth(320);
        // 真正读 HKLM，而不是 System.getenv() 的合并快照
        Map<String, String> system = EnvStore.systemVars();
        java.util.Set<String> userKeys = new java.util.HashSet<>(EnvStore.listAll().keySet());
        List<String> keys = new ArrayList<>(system.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        int overridden = 0;
        for (String k : keys) {
            String raw = system.get(k);
            String exp = Os.expand(raw);
            boolean isOverridden = userKeys.contains(k);
            if (isOverridden) {
                overridden++;
            }
            tm.addRow(new Object[]{k, exp + (isOverridden ? "  ← 用户级已覆盖" : ""), raw});
        }
        JPanel sp = Theme.roundedScroll(t);
        p.add(sp, BorderLayout.CENTER);
        p.add(Theme.muted(keys.isEmpty()
                ? "未读取到系统级变量（需要 Windows 且当前用户有读取权限）。"
                : "共 " + keys.size() + " 项系统级变量，其中 " + overridden
                        + " 项被用户级变量覆盖。修改系统级变量需要管理员权限。"), BorderLayout.SOUTH);
        return p;
    }

    /* ------------------------------------------------------ 刷新 */

    public void refresh(Detected.Report report) {
        this.report = report;
        loadVars();
        loadPath();
        loadBackups();
    }
}
