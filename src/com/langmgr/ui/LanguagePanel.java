package com.langmgr.ui;

import com.langmgr.core.Detected;
import com.langmgr.core.EnvApplier;
import com.langmgr.core.EnvStore;
import com.langmgr.core.Os;
import com.langmgr.core.Proc;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语言与解释器页：左侧筛选 + 中间语言卡片列表 + 右侧详情。
 *
 * <p>回答四个问题：装了什么版本、装在哪、环境配置好没有、有几个可选版本。
 */
public class LanguagePanel extends JPanel {

    private final AppModel model;
    private final DefaultListModel<Detected.LangResult> listModel = new DefaultListModel<>();
    private final JList<Detected.LangResult> list = new JList<>(listModel);
    private final JTextField search = new JTextField();
    private final JComboBox<String> filter = new JComboBox<>(new String[]{"全部", "仅已安装", "仅未安装", "多版本"});
    private final JPanel detailWrap = new JPanel(new BorderLayout());
    private Detected.Report report;

    public LanguagePanel(AppModel model) {
        this.model = model;
        setLayout(new BorderLayout(12, 0));
        setBackground(Theme.BG_APP);
        setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 24));

        add(buildLeft(), BorderLayout.WEST);
        add(buildRight(), BorderLayout.CENTER);
    }

    /* ---------------------------------------------------------- 左栏 */

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setBackground(Theme.BG_APP);
        left.setPreferredSize(new Dimension(286, 0));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(Theme.h2("语言列表"), BorderLayout.NORTH);

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "搜索语言或路径…");
        search.setFont(Theme.F_SMALL);
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        searchRow.add(search, BorderLayout.CENTER);
        top.add(searchRow, BorderLayout.CENTER);

        filter.setFont(Theme.F_SMALL);
        filter.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        filter.setBackground(Color.WHITE);
        ((JLabel) filter.getRenderer()).setHorizontalAlignment(SwingConstants.LEFT);
        top.add(filter, BorderLayout.SOUTH);
        left.add(top, BorderLayout.NORTH);

        list.setCellRenderer(new LangCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.WHITE);
        list.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        list.setFixedCellHeight(-1);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail(list.getSelectedValue());
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        left.add(sp, BorderLayout.CENTER);

        JLabel hint = Theme.muted("<html>点击左侧任一项查看详情<br>右键可复制路径或打开所在目录</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        left.add(hint, BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(new SimpleDoc(() -> applyFilter()));
        filter.addActionListener(e -> applyFilter());
        return left;
    }

    /* ---------------------------------------------------------- 右栏 */

    private JComponent buildRight() {
        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Theme.BG_APP);
        detailWrap.setBackground(Theme.BG_APP);
        detailWrap.add(Theme.muted("请先执行一次扫描，然后从左侧选择一个语言。"), BorderLayout.CENTER);
        right.add(Theme.scroll(detailWrap), BorderLayout.CENTER);
        return right;
    }

    /* ---------------------------------------------------------- 刷新 */

    public void refresh(Detected.Report report) {
        this.report = report;
        applyFilter();
        Detected.LangResult sel = list.getSelectedValue();
        if (sel != null && report != null) {
            Detected.LangResult fresh = report.byId(sel.spec.id);
            if (fresh != null) {
                list.setSelectedValue(fresh, true);
                showDetail(fresh);
                return;
            }
        }
        if (listModel.size() > 0) {
            list.setSelectedIndex(0);
        } else {
            showDetail(null);
        }
    }

    private void applyFilter() {
        listModel.clear();
        if (report == null) {
            detailWrap.removeAll();
            detailWrap.add(Theme.muted("尚未扫描。点击右上角「重新扫描」开始检测。"), BorderLayout.CENTER);
            detailWrap.revalidate();
            detailWrap.repaint();
            return;
        }
        String kw = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        String mode = filter.getSelectedItem() == null ? "全部" : filter.getSelectedItem().toString();
        for (Detected.LangResult r : report.results) {
            if ("仅已安装".equals(mode) && !r.installed) {
                continue;
            }
            if ("仅未安装".equals(mode) && r.installed) {
                continue;
            }
            if ("多版本".equals(mode) && r.installs.size() <= 1) {
                continue;
            }
            if (!kw.isEmpty()) {
                String hay = (r.spec.displayName + " " + r.spec.id + " " + r.spec.category + " "
                        + r.activePath() + " " + r.activeVersion()).toLowerCase();
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            listModel.addElement(r);
        }
        if (listModel.size() > 0 && list.getSelectedIndex() < 0) {
            list.setSelectedIndex(0);
        } else if (listModel.size() == 0) {
            showDetail(null);
        }
    }

    /* ---------------------------------------------------------- 详情 */

    private void showDetail(Detected.LangResult r) {
        detailWrap.removeAll();
        if (r == null) {
            detailWrap.add(Theme.muted(report == null
                    ? "尚未扫描。点击右上角「重新扫描」开始检测本机语言环境。"
                    : "左侧没有匹配的语言，试试清空搜索框或切换筛选条件。"), BorderLayout.CENTER);
            detailWrap.revalidate();
            detailWrap.repaint();
            return;
        }
        JPanel body = Theme.vBox(12);

        // 标题条
        body.add(Theme.fixed(header(r)));

        // 关键信息
        body.add(Theme.fixed(keyValueCard(r)));

        // 全部安装实例
        if (r.installs.size() > 0) {
            body.add(Theme.fixed(installsCard(r)));
        }

        // 环境变量
        body.add(Theme.fixed(varsCard(r)));

        // 包管理器
        body.add(Theme.fixed(pkgCard(r)));

        // 官方下载网址
        if (!r.spec.downloads.isEmpty()) {
            body.add(Theme.fixed(downloadCard(r)));
        }

        // 备注 / 原始输出
        if (!r.notes.isEmpty() || (r.active != null && r.active.rawOutput != null)) {
            body.add(Theme.fixed(notesCard(r)));
        }

        detailWrap.add(body, BorderLayout.CENTER);
        detailWrap.revalidate();
        detailWrap.repaint();
    }

    private JPanel header(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(12, 0));

        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleRow.setOpaque(false);
        JLabel name = new JLabel(r.spec.displayName);
        name.setFont(Theme.F_TITLE);
        name.setForeground(Theme.TEXT_MAIN);
        titleRow.add(name);
        titleRow.add(Theme.badge(r.spec.category, Theme.TEXT_SUB, Theme.GRAY_BG));
        titleRow.add(Theme.badge(r.installed ? "已安装" : "未安装",
                r.installed ? Theme.OK : Theme.TEXT_MUTED,
                r.installed ? Theme.OK_BG : Theme.GRAY_BG));
        if (r.installed) {
            titleRow.add(Theme.badge(r.activeVersion(), Theme.INFO, Theme.INFO_BG));
        }
        left.add(titleRow, BorderLayout.NORTH);

        JLabel hint = Theme.sub(r.spec.hint == null || r.spec.hint.isEmpty() ? " " : r.spec.hint);
        hint.setBorder(BorderFactory.createEmptyBorder(6, 1, 0, 1));
        left.add(hint, BorderLayout.CENTER);
        p.add(left, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        Detected.Install primary = primaryInstall(r);
        if (primary != null && primary.exePath != null) {
            JButton openDir = Theme.ghostButton("打开所在目录");
            openDir.addActionListener(e -> openInExplorer(new File(primary.exePath).getParentFile()));
            btns.add(openDir);
            JButton copyPath = Theme.ghostButton("复制路径");
            copyPath.addActionListener(e -> Theme.copy(this, primary.exePath, "路径"));
            btns.add(copyPath);
        }
        if (r.switchableInstalls().size() > 1) {
            JButton sw = Theme.primaryButton("切换版本");
            sw.addActionListener(e -> {
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w instanceof MainFrame) {
                    ((MainFrame) w).gotoSwitchWith(r.spec.id);
                }
            });
            btns.add(sw);
        }
        JButton rescan = Theme.ghostButton("重新检测");
        rescan.addActionListener(e -> {
            rescan.setEnabled(false);
            rescan.setText("检测中…");
            model.rescanOne(r.spec.id, () -> SwingUtilities.invokeLater(() -> {
                rescan.setEnabled(true);
                rescan.setText("重新检测");
                Theme.toast(this, "已刷新 " + r.spec.displayName);
            }));
        });
        btns.add(rescan);
        p.add(btns, BorderLayout.EAST);
        return p;
    }

    private JPanel keyValueCard(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new GridLayout(0, 2, 18, 8));
        addKv(p, "当前生效版本", r.installed ? r.activeVersion() : "—");
        addKv(p, "可执行文件（实际在用）", exeCell(r));
        addKv(p, "推断安装根目录", r.active == null || r.active.rootDir == null ? "—" : r.active.rootDir);
        addKv(p, "发现方式", r.active == null ? "—" : r.active.source);
        addKv(p, "PATH 中可用", r.installed && isActiveInPath(r) ? "是" : "否");
        addKv(p, "环境配置状态", r.envLabel());
        addKv(p, "已发现的安装数", r.installs.size() + " 个");
        addKv(p, "架构信息", r.active == null || r.active.arch == null ? "—" : r.active.arch);
        return p;
    }

    /**
     * "可执行文件"单元格：第一行是系统实际在用的那个；PATH 命中转发器时补第二行，
     * 说明命中的是转发器——这属于"命中与使用不一致"，是切换不生效的原因。
     */
    private static String exeCell(Detected.LangResult r) {
        String running = r.activePath();
        if (r.forwarderPath == null) {
            return running;
        }
        return running + "\n↳ PATH 命中 " + r.forwarderPath + "（转发器，切换会被它拦截）";
    }

    private boolean isActiveInPath(Detected.LangResult r) {
        if (r.active == null || r.active.exePath == null) {
            return false;
        }
        File dir = new File(r.active.exePath).getParentFile();
        return dir != null && EnvStore.pathContains(dir.getAbsolutePath()) != 0;
    }

    private void addKv(JPanel p, String k, String v) {
        JLabel key = new JLabel(k);
        key.setFont(Theme.F_SMALL);
        key.setForeground(Theme.TEXT_SUB);
        key.setVerticalAlignment(SwingConstants.TOP);
        p.add(key);

        JTextArea val = new JTextArea(v == null ? "—" : v);
        val.setLineWrap(true);
        val.setWrapStyleWord(true);
        val.setEditable(false);
        val.setFocusable(false);  // 纯展示：点击不出现光标
        val.setFont(Theme.F_MONO_SM);
        val.setForeground(Theme.TEXT_MAIN);
        val.setOpaque(false);
        val.setBorder(null);
        val.setToolTipText(v);
        val.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && v != null && !v.isEmpty() && !v.equals("—")) {
                    Theme.copy(p, v, "");
                }
            }
        });
        p.add(val);
    }

    /**
     * 取用于展示的主要安装实例。
     * PATH 中没有生效项时（例如语言装在非 PATH 目录），退回第一个已发现的安装，
     * 这样界面仍能提供"打开目录/复制路径"，而不是让按钮凭空消失。
     */
    private static Detected.Install primaryInstall(Detected.LangResult r) {
        if (r == null || r.installs.isEmpty()) {
            return null;
        }
        if (r.active != null) {
            return r.active;
        }
        return r.installs.get(0);
    }

    private JPanel installsCard(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(0, 8));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Theme.h2("已发现的安装（" + r.installs.size() + "）"), BorderLayout.WEST);
        if (r.switchableInstalls().size() > 1) {
            JButton sw = Theme.ghostButton("去切换 →");
            sw.addActionListener(e -> {
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w instanceof MainFrame) {
                    ((MainFrame) w).gotoSwitchWith(r.spec.id);
                }
            });
            head.add(sw, BorderLayout.EAST);
        }
        p.add(head, BorderLayout.NORTH);

        Object[][] rows = new Object[r.installs.size()][5];
        for (int i = 0; i < r.installs.size(); i++) {
            Detected.Install in = r.installs.get(i);
            String status;
            if (in.active) {
                status = "当前生效";            // 系统实际在用的
            } else if (in.blocking) {
                status = "转发器·拦截";        // 卡在 PATH 最前面、让切换失效的那条
            } else if (in.stale) {
                status = "转发器残留";         // 已不在 PATH，只是文件还在
            } else if (!in.switchable) {
                status = "不可切换";
            } else {
                status = "";
            }
            rows[i][0] = status;
            rows[i][1] = in.version;
            rows[i][2] = in.exePath == null ? "—" : in.exePath;
            rows[i][3] = in.rootDir == null ? "—" : in.rootDir;
            rows[i][4] = in.source;
        }
        JTable t = Theme.table(rows, new Object[]{"状态", "版本", "可执行文件", "安装根目录", "发现方式"});
        // 列宽按比例压缩到可视宽度内：窄窗口下五列全部可见（长路径截断显示），
        // 而不是整表撑出卡片被裁掉
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        t.getColumnModel().getColumn(0).setPreferredWidth(96);
        t.getColumnModel().getColumn(1).setPreferredWidth(110);
        t.getColumnModel().getColumn(2).setPreferredWidth(260);
        t.getColumnModel().getColumn(3).setPreferredWidth(240);
        t.getColumnModel().getColumn(4).setPreferredWidth(110);
        t.getColumnModel().getColumn(0).setCellRenderer(new StatusRenderer());
        t.setPreferredScrollableViewportSize(new Dimension(0, Math.min(190, 34 * rows.length + 34)));
        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { maybePopup(e); }

            @Override
            public void mouseReleased(MouseEvent e) { maybePopup(e); }

            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = t.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                t.setRowSelectionInterval(row, row);
                Detected.Install in = r.installs.get(row);
                JPopupMenu menu = new JPopupMenu();
                JMenuItem copy = new JMenuItem("复制可执行文件路径");
                copy.addActionListener(a -> Theme.copy(t, in.exePath, "路径"));
                menu.add(copy);
                JMenuItem open = new JMenuItem("在资源管理器中打开");
                open.setEnabled(in.exePath != null && new File(in.exePath).getParentFile() != null);
                open.addActionListener(a -> openInExplorer(new File(in.exePath).getParentFile()));
                menu.add(open);
                JMenuItem raw = new JMenuItem("查看原始版本输出");
                raw.setEnabled(in.rawOutput != null && !in.rawOutput.isEmpty());
                raw.addActionListener(a -> Theme.info(t, in.version + " 原始输出", in.rawOutput));
                menu.add(raw);
                menu.show(t, e.getX(), e.getY());
            }
        });
        p.add(Theme.roundedScroll(t), BorderLayout.CENTER);
        return p;
    }

    private JPanel varsCard(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(0, 8));
        p.add(Theme.h2("关联环境变量"), BorderLayout.NORTH);

        if (r.vars.isEmpty()) {
            p.add(Theme.muted("该语言无需专门的环境变量，装在 PATH 里即可使用。"), BorderLayout.CENTER);
            return p;
        }
        JPanel grid = new JPanel(new GridLayout(0, 1, 0, 6));
        grid.setOpaque(false);
        for (Detected.VarState v : r.vars.values()) {
            grid.add(varRow(r, v));
        }
        p.add(grid, BorderLayout.CENTER);
        return p;
    }

    private JPanel varRow(Detected.LangResult r, Detected.VarState v) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        Color dot = v.healthy() ? Theme.OK
                : (v.effectiveValue == null || v.effectiveValue.trim().isEmpty()) ? Theme.TEXT_MUTED
                : Theme.ERR;
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(Theme.badge(v.healthy() ? "正常" : (dot == Theme.ERR ? "异常" : "未设置"),
                dot, dot == Theme.OK ? Theme.OK_BG : (dot == Theme.ERR ? Theme.ERR_BG : Theme.GRAY_BG)));
        JLabel nm = new JLabel(v.name);
        nm.setFont(Theme.F_H3);
        nm.setForeground(Theme.TEXT_MAIN);
        left.add(nm);
        row.add(left, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        JTextArea val = new JTextArea(v.effectiveValue == null || v.effectiveValue.trim().isEmpty()
                ? "（未设置）" : v.effectiveValue);
        val.setLineWrap(true);
        val.setWrapStyleWord(true);
        val.setEditable(false);
        val.setFocusable(false);  // 纯展示：点击不出现光标
        val.setFont(Theme.F_MONO_SM);
        val.setForeground(v.healthy() ? Theme.TEXT_MAIN : Theme.TEXT_SUB);
        val.setOpaque(false);
        val.setBorder(null);
        center.add(val, BorderLayout.CENTER);
        if (v.note != null && !v.note.isEmpty() && !"未设置".equals(v.note)) {
            JLabel note = Theme.sub("· " + v.note);
            note.setForeground(v.healthy() ? Theme.TEXT_MUTED : Theme.WARN);
            note.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
            center.add(note, BorderLayout.SOUTH);
        }
        row.add(center, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        if (v.rawValue != null && !v.rawValue.equals(v.effectiveValue)) {
            JButton rawBtn = Theme.ghostButton("原始值");
            rawBtn.setPreferredSize(new Dimension(Theme.px(70), Theme.px(26)));
            rawBtn.addActionListener(e -> Theme.info(this, v.name + " 注册表原始值", v.rawValue));
            right.add(rawBtn);
        }
        if (r.active != null && r.active.rootDir != null && !v.healthy()) {
            JButton fix = Theme.primaryButton("设为当前版本");
            fix.setPreferredSize(new Dimension(Theme.px(124), Theme.px(26)));
            fix.addActionListener(e -> quickSetVar(r, v));
            right.add(fix);
        } else if (v.effectiveValue != null && !v.effectiveValue.trim().isEmpty()) {
            JButton clr = Theme.ghostButton("复制值");
            clr.setPreferredSize(new Dimension(Theme.px(74), Theme.px(26)));
            clr.addActionListener(e -> Theme.copy(this, v.effectiveValue, ""));
            right.add(clr);
        }
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /** 单个变量的一键修正（走完整计划流程，含备份）。 */
    private void quickSetVar(Detected.LangResult r, Detected.VarState v) {
        if (r.active == null) {
            return;
        }
        String value = com.langmgr.core.EnvApplier.suggestEnvFor(r.spec, r.active).get(v.name);
        if (value == null) {
            Theme.error(this, "无法自动推断", "不能为该变量推断出合适的值，请到「环境变量」页手动设置。");
            return;
        }
        // 危险变量（PYTHONHOME/GOROOT/DOTNET_ROOT）的建议是"不要设置"——
        // 对应动作是删除变量，而不是把占位符字符串原样写进注册表
        boolean leaveEmpty = EnvApplier.isLeaveEmptyHint(value);
        String confirmText = leaveEmpty
                ? "「" + v.name + "」设了反而会干扰解释器/虚拟环境的自动定位，建议不设置。\n\n"
                        + "将删除用户环境变量 " + v.name
                        + "（当前值：" + (v.effectiveValue == null ? "（未设置）" : v.effectiveValue) + "）"
                : "将把用户环境变量改为：\n\n" + v.name + " = " + value
                        + "\n\n当前值：" + (v.effectiveValue == null ? "（未设置）" : v.effectiveValue);
        boolean ok = Theme.confirm(this, leaveEmpty ? "删除 " + v.name : "设置 " + v.name,
                confirmText
                        + "\n\n执行前会自动备份到 backup 目录，可随时回滚。\n"
                        + "只影响当前用户，不改系统级变量；已打开的终端需重开才生效。");
        if (!ok) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<String, Void> w = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    EnvStore.backupSingle(v.name);
                } catch (Exception ex) {
                    return "备份失败，已中止（未做任何修改）：" + ex.getMessage();
                }
                String err = leaveEmpty ? EnvStore.delete(v.name)
                        : EnvStore.write(v.name, value, value.contains("%"));
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
                    Theme.error(LanguagePanel.this, "写入失败", err);
                    return;
                }
                Theme.toast(LanguagePanel.this, v.name + " 已更新");
                model.rescanOne(r.spec.id, () -> SwingUtilities.invokeLater(() -> showDetail(
                        model.report() == null ? r : model.report().byId(r.spec.id))));
            }
        };
        w.execute();
    }

    private JPanel pkgCard(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(0, 8));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Theme.h2("包管理器"), BorderLayout.WEST);
        if (r.installed) {
            JButton b = Theme.ghostButton("查看依赖 →");
            b.addActionListener(e -> {
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w instanceof MainFrame) {
                    ((MainFrame) w).gotoDepsWith(r.spec.id);
                }
            });
            head.add(b, BorderLayout.EAST);
        }
        p.add(head, BorderLayout.NORTH);

        if (r.pkgManagers.isEmpty()) {
            p.add(Theme.muted("该语言未登记专门的包管理器。"), BorderLayout.CENTER);
            return p;
        }
        // 两列排布、行数自动扩展：窄窗口时卡片随视口收缩（内容各自换行），
        // 而不是一整行摊开被窗口右缘裁掉
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
        grid.setOpaque(false);
        for (Detected.PkgState s : r.pkgManagers) {
            JPanel cell = new JPanel(new BorderLayout(0, 4));
            cell.setOpaque(false);
            cell.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(s.installed ? Theme.LINE : new Color(0xEE, 0xEE, 0xEE)),
                    BorderFactory.createEmptyBorder(9, 11, 9, 11)));
            JPanel t = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            t.setOpaque(false);
            JLabel nm = new JLabel(s.displayName);
            nm.setFont(Theme.F_H3);
            nm.setForeground(s.installed ? Theme.TEXT_MAIN : Theme.TEXT_MUTED);
            t.add(nm);
            t.add(Theme.badge(s.installed ? "已安装" : "未安装",
                    s.installed ? Theme.OK : Theme.TEXT_MUTED,
                    s.installed ? Theme.OK_BG : Theme.GRAY_BG));
            cell.add(t, BorderLayout.NORTH);
            JTextArea ver = new JTextArea(s.installed
                    ? ("版本 " + s.version + (s.exePath == null ? "" : ("\n" + s.exePath)))
                    : (s.error == null ? "未检测到" : s.error));
            ver.setLineWrap(true);
            ver.setWrapStyleWord(true);
            ver.setEditable(false);
            ver.setFocusable(false);  // 纯展示：点击不出现光标
            ver.setFont(Theme.F_TINY);
            ver.setForeground(Theme.TEXT_SUB);
            ver.setOpaque(false);
            ver.setBorder(null);
            cell.add(ver, BorderLayout.CENTER);
            grid.add(cell);
        }
        p.add(grid, BorderLayout.CENTER);
        return p;
    }

    /**
     * 官方下载网址卡片。
     * 未安装时提示"照着网址去装"；已安装时可用于补装其它版本。
     * 单击链接用默认浏览器打开；每行附复制按钮。
     */
    private JPanel downloadCard(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(0, 8));
        p.add(Theme.h2("官方下载网址"), BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 1, 0, 6));
        grid.setOpaque(false);
        for (Map.Entry<String, String> d : r.spec.downloads.entrySet()) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.LINE),
                    BorderFactory.createEmptyBorder(7, 10, 7, 10)));

            JLabel lbl = new JLabel(d.getKey());
            lbl.setFont(Theme.F_SMALL);
            lbl.setForeground(Theme.TEXT_SUB);
            row.add(lbl, BorderLayout.WEST);

            JLabel url = new JLabel(d.getValue());
            url.setFont(Theme.F_MONO_SM);
            url.setForeground(Theme.ACCENT_DARK);
            url.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            url.setToolTipText("单击在浏览器中打开：" + d.getValue());
            url.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openBrowser(LanguagePanel.this, d.getValue());
                }
            });
            row.add(url, BorderLayout.CENTER);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            JButton copy = Theme.ghostButton("复制");
            copy.setPreferredSize(new Dimension(Theme.px(64), Theme.px(24)));
            copy.addActionListener(e -> Theme.copy(this, d.getValue(), "网址"));
            right.add(copy);
            row.add(right, BorderLayout.EAST);
            grid.add(row);
        }
        p.add(grid, BorderLayout.CENTER);

        JLabel tip = Theme.muted(r.installed
                ? "想补装其它版本？从上面的历史版本页下载后，可在「版本切换」页管理。"
                : "本机未检测到该语言，可从上面的官方地址下载安装。");
        tip.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        p.add(tip, BorderLayout.SOUTH);
        return p;
    }

    /** 用系统默认浏览器打开网址（失败时弹窗提示并复制链接）。 */
    static void openBrowser(Component parent, String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            } else if (Os.isWindows()) {
                Proc.run("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                Theme.copy(parent, url, "网址");
                return;
            }
            Theme.toast(parent, "已在浏览器中打开");
        } catch (Exception ex) {
            Theme.copy(parent, url, "网址");
        }
    }

    private JPanel notesCard(Detected.LangResult r) {
        JPanel p = Theme.card(null);
        p.setLayout(new BorderLayout(0, 8));
        p.add(Theme.h2("探测细节"), BorderLayout.NORTH);
        StringBuilder sb = new StringBuilder();
        if (r.active != null && r.active.rawOutput != null && !r.active.rawOutput.isEmpty()) {
            sb.append("【版本命令原始输出】\n").append(r.active.rawOutput).append("\n\n");
        }
        if (!r.notes.isEmpty()) {
            sb.append("【扫描备注】\n");
            for (String n : r.notes) {
                sb.append("· ").append(n).append("\n");
            }
        }
        sb.append("\n【探测命令】\n");
        for (com.langmgr.core.LangSpec.Probe pr : r.spec.probes) {
            sb.append("· ").append(String.join(" ", pr.command)).append("\n");
        }
        sb.append("\n扫描耗时：").append(r.scanMillis).append(" ms");
        p.add(Theme.textArea(sb.toString(), 8), BorderLayout.CENTER);
        return p;
    }

    /* ---------------------------------------------------------- 工具 */

    static void openInExplorer(File dir) {
        if (dir == null) {
            return;
        }
        try {
            if (Os.isWindows() && dir.isDirectory()) {
                Proc.run("explorer.exe", dir.getAbsolutePath());
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception ex) {
            Theme.error(null, "打开失败", ex.getMessage());
        }
    }

    /** 列表单元格渲染：语言名 + 版本 + 状态点。 */
    private static final class LangCellRenderer extends JPanel implements ListCellRenderer<Detected.LangResult> {
        private final JLabel name = new JLabel();
        private final JLabel meta = new JLabel();
        private final JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        LangCellRenderer() {
            setLayout(new BorderLayout(0, 3));
            setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 9));
            name.setFont(Theme.F_H3);
            meta.setFont(Theme.F_TINY);
            badges.setOpaque(false);
            add(name, BorderLayout.NORTH);
            add(badges, BorderLayout.CENTER);
            add(meta, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Detected.LangResult> list,
                                                     Detected.LangResult value, int index,
                                                     boolean isSelected, boolean cellHasFocus) {
            name.setText(value.spec.displayName);
            meta.setText(value.installed
                    ? DashboardPanel.shorten(value.activePath())
                    : "未检测到");
            meta.setToolTipText(value.installed ? value.activePath() : null);
            badges.removeAll();
            if (value.installed) {
                badges.add(Theme.badge(value.activeVersion(), Theme.TEXT_MAIN, Theme.GRAY_BG));
                if (value.installs.size() > 1) {
                    badges.add(Theme.badge(value.installs.size() + "版本", Theme.INFO, Theme.INFO_BG));
                }
                int lv = value.envLevel();
                if (lv == 2) {
                    badges.add(Theme.badge("异常", Theme.ERR, Theme.ERR_BG));
                } else if (lv == 1) {
                    badges.add(Theme.badge("就绪", Theme.OK, Theme.OK_BG));
                }
            } else {
                badges.add(Theme.badge("未安装", Theme.TEXT_MUTED, Theme.GRAY_BG));
            }

            if (isSelected) {
                setBackground(new Color(0xDD, 0xE9, 0xFC));
                name.setForeground(Theme.ACCENT_DARK);
                meta.setForeground(Theme.TEXT_SUB);
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : new Color(0xFA, 0xFB, 0xFD));
                name.setForeground(value.installed ? Theme.TEXT_MAIN : Theme.TEXT_MUTED);
                meta.setForeground(Theme.TEXT_MUTED);
            }
            setOpaque(true);
            return this;
        }
    }

    /** "● 当前" 的着色渲染。 */
    private static final class StatusRenderer extends JLabel implements javax.swing.table.TableCellRenderer {
        StatusRenderer() {
            setOpaque(true);
            setFont(Theme.F_SMALL);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            String v = value == null ? "" : value.toString();
            setText(v);
            if (isSelected) {
                setBackground(new Color(0xDD, 0xE9, 0xFC));
            } else {
                setBackground(row % 2 == 1 ? new Color(0xFA, 0xFB, 0xFD) : Color.WHITE);
            }
            if (v.contains("当前")) {
                setForeground(Theme.OK);
            } else if (v.contains("拦截") || v.contains("转发器")) {
                setForeground(Theme.WARN);
            } else {
                setForeground(Theme.TEXT_MUTED);
            }
            return this;
        }
    }

    /** 简化版 DocumentListener。 */
    static final class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable action;

        SimpleDoc(Runnable action) {
            this.action = action;
        }

        private void fire() {
            SwingUtilities.invokeLater(action);
        }

        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fire(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fire(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fire(); }
    }
}
