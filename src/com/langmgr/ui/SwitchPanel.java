package com.langmgr.ui;

import com.langmgr.core.Detected;
import com.langmgr.core.EnvApplier;
import com.langmgr.core.EnvStore;
import com.langmgr.core.LangSpec;
import com.langmgr.core.Scanner;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 版本切换页。
 *
 * <p>交互流程刻意做成三步，因为改 PATH 出错会直接让用户的终端瘫痪：
 * <ol>
 *   <li>选语言、选目标版本；</li>
 *   <li>生成<b>变更计划预览</b>——逐条列出将写哪个变量、PATH 怎么动，此时还没碰任何配置；</li>
 *   <li>用户确认后才执行，执行前自动备份，执行后给出日志，并提供一键回滚。</li>
 * </ol>
 */
public class SwitchPanel extends JPanel {

    private final AppModel model;
    private final JComboBox<LangOption> langCombo = new JComboBox<>();
    private final DefaultListModel<Detected.Install> versionModel = new DefaultListModel<>();
    private final JList<Detected.Install> versionList = new JList<>(versionModel);
    private final JPanel planWrap = new JPanel(new BorderLayout());
    private final JPanel logWrap = new JPanel(new BorderLayout());
    private final JCheckBox managePath = new JCheckBox("同时调整 PATH", true);
    private final JCheckBox cleanOthers = new JCheckBox("移除其他版本的 PATH 条目", true);
    private final JButton previewButton = Theme.primaryButton("生成变更计划");
    private final JButton applyButton = Theme.ghostButton("确认并应用");
    private final JButton scriptButton = Theme.ghostButton("导出脚本");
    private final JButton removeButton = Theme.ghostButton("删除此版本…");
    private JButton rescanButton;

    private Detected.Report report;
    private Detected.LangResult currentLang;
    private EnvApplier.Plan pendingPlan;

    /** 下拉项包装，显示"语言名（当前版本）"。 */
    private static final class LangOption {
        final Detected.LangResult result;
        final String label;
        final int switchableCount;

        LangOption(Detected.LangResult r) {
            this.result = r;
            this.switchableCount = r.switchableInstalls().size();
            this.label = r.spec.displayName + "  ·  当前 " + r.activeVersion()
                    + (switchableCount > 1 ? ("  ·  共 " + switchableCount + " 个可切换版本") : "");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public SwitchPanel(AppModel model) {
        this.model = model;
        setLayout(new BorderLayout(12, 0));
        setBackground(Theme.BG_APP);
        setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 24));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        langCombo.addActionListener(e -> onLangChanged());
        versionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            pendingPlan = null;
            Detected.Install sel = versionList.getSelectedValue();
            if (sel != null && sel.stale) {
                // 已不在 PATH 的残留：不是拦截问题，只是文件还在，引导去删除
                showStaleNotice(sel);
                previewButton.setEnabled(false);
            } else if (sel != null && sel.blocking) {
                // 真转发器（javapath 这类中间层）：先把"它是什么、为什么切换不生效"讲清楚
                showBlockingNotice(sel);
                previewButton.setEnabled(false);
            } else if (sel != null && !sel.switchable) {
                // 由安装器/宿主管理的版本（MSVC 工具集、.NET Framework csc、Roslyn 等）：
                // 它们不是转发器，只是版本不由环境变量选择，给对应的解释
                showManagedNotice(sel);
                previewButton.setEnabled(false);
            } else {
                renderPlan(null);
                previewButton.setEnabled(currentLang != null
                        && currentLang.switchableInstalls().size() > 1);
            }
            updateRemoveEnabled();
        });
        managePath.addActionListener(e -> invalidatePlan());
        cleanOthers.addActionListener(e -> invalidatePlan());
        previewButton.addActionListener(e -> doPreview());
        applyButton.addActionListener(e -> doApply());
        scriptButton.addActionListener(e -> doExportScript());
        removeButton.addActionListener(e -> doRemoveVersion());
        applyButton.setEnabled(false);
        scriptButton.setEnabled(false);
    }

    /* ---------------------------------------------------------- 布局 */

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(Theme.title("版本切换"), BorderLayout.NORTH);
        JLabel sub = Theme.sub("在本机已安装的多个版本之间切换默认解释器/编译器，"
                + "并同步更新对应的环境变量与 PATH");
        sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        left.add(sub, BorderLayout.CENTER);
        p.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton back = Theme.ghostButton("回滚上一次变更");
        back.addActionListener(e -> doRollback());
        right.add(back);
        JButton rescan = Theme.ghostButton("刷新版本列表");
        rescanButton = rescan;
        rescan.addActionListener(e -> {
            rescan.setEnabled(false);
            rescan.setText("扫描中…");
            model.rescan();
        });
        right.add(rescan);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JComponent buildBody() {
        JPanel split = new JPanel(new GridLayout(1, 2, 12, 0));
        split.setOpaque(false);

        // ---- 左：选择 ----
        JPanel pick = Theme.card(null);
        pick.setLayout(new BorderLayout(0, 10));
        pick.add(Theme.h2("① 选择语言与目标版本"), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        langCombo.setFont(Theme.F_BODY);
        langCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        langCombo.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        langCombo.setBackground(Color.WHITE);
        center.add(langCombo, BorderLayout.NORTH);

        versionList.setCellRenderer(new VersionRenderer());
        versionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        versionList.setBackground(Color.WHITE);
        versionList.setFixedCellHeight(-1);
        JScrollPane sp = new JScrollPane(versionList);
        sp.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        center.add(sp, BorderLayout.CENTER);

        JPanel opts = new JPanel(new GridLayout(0, 1, 0, 4));
        opts.setOpaque(false);
        opts.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)));
        managePath.setFont(Theme.F_SMALL);
        managePath.setOpaque(false);
        managePath.setForeground(Theme.TEXT_MAIN);
        cleanOthers.setFont(Theme.F_SMALL);
        cleanOthers.setOpaque(false);
        cleanOthers.setForeground(Theme.TEXT_MAIN);
        opts.add(managePath);
        opts.add(cleanOthers);
        opts.add(Theme.muted("取消勾选则只改 HOME 变量，PATH 保持原样。"));
        center.add(opts, BorderLayout.SOUTH);
        pick.add(center, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.add(previewButton);
        removeButton.setEnabled(false);
        removeButton.setToolTipText("仅清理用户环境里指向该版本的引用；可选连带删除安装目录");
        btnRow.add(removeButton);
        pick.add(btnRow, BorderLayout.SOUTH);

        // ---- 右：计划 + 日志 ----
        JPanel rightCol = new JPanel(new BorderLayout(0, 12));
        rightCol.setOpaque(false);

        JPanel planCard = Theme.card(null);
        planCard.setLayout(new BorderLayout(0, 8));
        JPanel ph = new JPanel(new BorderLayout());
        ph.setOpaque(false);
        ph.add(Theme.h2("② 变更计划预览"), BorderLayout.WEST);
        JPanel pbtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        pbtns.setOpaque(false);
        pbtns.add(scriptButton);
        pbtns.add(applyButton);
        ph.add(pbtns, BorderLayout.EAST);
        planCard.add(ph, BorderLayout.NORTH);
        planWrap.setOpaque(false);
        planWrap.add(Theme.muted("选择目标版本后点「生成变更计划」，这里会列出将要执行的每一项改动。"
                + "在点「确认并应用」之前，不会修改任何配置。"), BorderLayout.CENTER);
        planCard.add(planWrap, BorderLayout.CENTER);

        JPanel logCard = Theme.card(null);
        logCard.setLayout(new BorderLayout(0, 8));
        logCard.add(Theme.h2("③ 执行日志"), BorderLayout.NORTH);
        logWrap.setOpaque(false);
        JLabel logHint = Theme.muted("执行记录会显示在这里。每次应用前都会自动备份，可随时回滚。");
        logHint.setHorizontalAlignment(SwingConstants.CENTER);
        logWrap.add(logHint, BorderLayout.CENTER);
        logCard.add(logWrap, BorderLayout.CENTER);

        JSplitPane vSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, planCard, logCard);
        Theme.flatSplit(vSplit);
        vSplit.setResizeWeight(0.62);
        vSplit.setBorder(null);
        vSplit.setOpaque(false);
        vSplit.setBackground(Theme.BG_APP);
        rightCol.add(vSplit, BorderLayout.CENTER);

        split.add(pick);
        split.add(rightCol);
        return split;
    }

    /* ---------------------------------------------------------- 数据 */

    public void refresh(Detected.Report report) {
        this.report = report;
        if (rescanButton != null) {
            rescanButton.setEnabled(true);
            rescanButton.setText("刷新版本列表");
        }
        String keepId = currentLang == null ? null : currentLang.spec.id;
        langCombo.removeAllItems();
        versionModel.clear();
        pendingPlan = null;
        applyButton.setEnabled(false);
        scriptButton.setEnabled(false);
        renderPlan(null);

        if (report == null) {
            planWrap.removeAll();
            planWrap.add(Theme.notice("尚未扫描，无法列出可切换的版本。\n请先在右上角点「重新扫描」。",
                    Theme.TEXT_SUB, Theme.GRAY_BG), BorderLayout.CENTER);
            planWrap.revalidate();
            planWrap.repaint();
            return;
        }
        List<LangOption> opts = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            // 收录口径：已安装且有安装实例即可进列表——包括 MSVC/C# 这种
            // 所有安装都不可切换的语言。切换页同样是"解释为什么切不动"的地方，
            // 完全藏掉反而让人以为没检测到（选中后会给出版本由谁管理的说明）。
            if (r.installed && !r.installs.isEmpty()) {
                opts.add(new LangOption(r));
            }
        }
        // 多版本的排前面
        opts.sort((a, b) -> Integer.compare(b.switchableCount, a.switchableCount));
        for (LangOption o : opts) {
            langCombo.addItem(o);
        }
        if (keepId != null) {
            for (int i = 0; i < langCombo.getItemCount(); i++) {
                if (langCombo.getItemAt(i).result.spec.id.equals(keepId)) {
                    langCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        if (langCombo.getItemCount() == 0) {
            planWrap.removeAll();
            planWrap.add(Theme.notice("本机没有检测到任何可用的语言安装，因此没有可切换的版本。",
                    Theme.WARN, Theme.WARN_BG), BorderLayout.CENTER);
            planWrap.revalidate();
            planWrap.repaint();
        } else {
            onLangChanged();
        }
    }

    /** 从其他页面跳转过来时定位到某个语言。 */
    public void focusOn(String langId) {
        if (langId == null) {
            return;
        }
        for (int i = 0; i < langCombo.getItemCount(); i++) {
            if (langCombo.getItemAt(i).result.spec.id.equals(langId)) {
                langCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    /**
     * 跳到"版本切换"并选中该语言的转发器条目。
     *
     * <p>「PATH 转发器拦截」这类问题软件没法自动修（要改系统级 PATH），
     * 所以概览页的「处理」把用户带到这里：右侧变更计划预览区会展示
     * 「它是什么 / 为什么切换不生效 / 怎么解决」，而不是丢一个切不动的按钮。
     */
    public void selectForwarder(String langId) {
        if (langId == null) {
            return;
        }
        focusOn(langId);
        for (int i = 0; i < versionModel.size(); i++) {
            if (versionModel.get(i).blocking) {
                versionList.setSelectedIndex(i);
                versionList.ensureIndexIsVisible(i);
                return;
            }
        }
        // 没有转发器条目（例如用户已经把它从系统 PATH 移除）：
        // 停在实际在用的版本上，右侧保持常规的"选择目标版本"提示
    }

    private void onLangChanged() {
        LangOption opt = (LangOption) langCombo.getSelectedItem();
        versionModel.clear();
        pendingPlan = null;
        applyButton.setEnabled(false);
        scriptButton.setEnabled(false);
        renderPlan(null);
        if (opt == null) {
            currentLang = null;
            return;
        }
        currentLang = opt.result;
        int switchableCount = opt.result.switchableInstalls().size();
        // 可切换版本 + 拦截切换的转发器一起进列表，并按"当前生效 → 版本从新到旧"排；
        // 转发器用 blocking 做同版本内的次序键，保证它紧跟在自己镜像的那个版本后面，
        // 而不是被甩到列表末尾让人对不上号。
        // 全部安装都进列表：可切换的、拦截切换的转发器、以及 MSVC/C# 这类
        // 不可切换的条目（后者有专门徽标与说明）。只显示部分会让用户以为
        // "其他版本没检测到"，尤其在一键处理把某个安装加入 PATH 之后——
        // 那个条目解决了"找不到命令"，但并不因此变成可切换版本。
        List<Detected.Install> shown = new ArrayList<>(opt.result.installs);
        shown.sort((a, b) -> {
            if (a.active != b.active) {
                return a.active ? -1 : 1;
            }
            int c = Scanner.compareVersion(b.version, a.version);
            if (c != 0) {
                return c;
            }
            return Boolean.compare(a.blocking || a.stale, b.blocking || b.stale);
        });
        for (Detected.Install i : shown) {
            versionModel.addElement(i);
        }
        // 默认选中实际在用的那个
        Detected.Install running = opt.result.active;
        for (int i = 0; i < versionModel.size(); i++) {
            Detected.Install cand = versionModel.get(i);
            if (running != null && cand.exePath != null
                    && EnvStore.samePath(cand.exePath, running.exePath)) {
                versionList.setSelectedIndex(i);
                versionList.ensureIndexIsVisible(i);
                break;
            }
        }
        // 上面 setSelectedIndex 已触发选择监听器：选中的若是安装器/宿主管理的
        // 条目，监听器已渲染出带徽标释义的专属说明——这里不能再被通用提示覆盖，
        // 否则首次选语言时永远只能看到笼统文案，点走再点回才会"变对"。
        Detected.Install selNow = versionList.getSelectedValue();
        if (selNow != null && !selNow.switchable && !selNow.blocking && !selNow.stale) {
            previewButton.setEnabled(false);
            return;
        }
        if (switchableCount <= 1) {
            int hidden = opt.result.installs.size() - switchableCount;
            String extra;
            if (switchableCount == 0 && hidden > 0) {
                // MSVC / C# 这类：所有版本都由安装器或宿主管理
                extra = "\n\n这 " + hidden + " 个条目的版本由 Visual Studio 安装器或 .NET 宿主自动管理，"
                        + "不通过环境变量选择，因此本工具不提供切换操作；"
                        + "如需增删版本，请使用 Visual Studio Installer 或 .NET 官方安装包。";
            } else {
                extra = hidden > 0
                        ? ("\n另外 " + hidden + " 个条目（系统 PATH 转发器、或由宿主/global.json 选择）无法通过环境变量切换。")
                        : "";
            }
            planWrap.removeAll();
            String head = switchableCount == 0
                    ? "该语言的版本不通过环境变量选择，因此不提供切换操作。"
                    : "该语言只有 " + switchableCount + " 个可切换版本，没有其他版本可选。";
            planWrap.add(Theme.notice(head + extra
                            + "\n如果确实装了多个版本但没被识别，可在「环境变量」页手动指定路径。",
                    Theme.TEXT_SUB, Theme.GRAY_BG), BorderLayout.CENTER);
            planWrap.revalidate();
            planWrap.repaint();
            previewButton.setEnabled(false);
        } else {
            previewButton.setEnabled(true);
            planWrap.removeAll();
            JLabel planHint = Theme.muted("选择目标版本后点「生成变更计划」。");
            planHint.setHorizontalAlignment(SwingConstants.CENTER);
            planWrap.add(planHint, BorderLayout.CENTER);
            planWrap.revalidate();
            planWrap.repaint();
        }
        updateRemoveEnabled();
    }

    /**
     * 选中"由安装器/宿主管理"的版本（MSVC 工具集、.NET Framework csc、
     * Roslyn 编译器等）时的说明：它们能被检测到、也可能正被 PATH 命中，
     * 但"用哪个版本"不是由环境变量决定的，所以本页不提供切换操作。
     * 之前这类条目被误路由到转发器说明（JAVA_HOME/javapath 文案），完全对不上号。
     */
    private void showManagedNotice(Detected.Install sel) {
        String specName = currentLang == null ? "该语言" : currentLang.spec.displayName;
        boolean isMsvc = currentLang != null && "msvc".equals(currentLang.spec.id);
        JPanel box = new JPanel(new BorderLayout(0, 8));
        box.setOpaque(false);
        String where = sel.active ? "PATH 当前命中的" : "已发现的";
        String path = sel.exePath != null ? sel.exePath
                : (sel.rootDir != null ? sel.rootDir : "（路径未知）");
        box.add(Theme.notice("「" + path + "」是" + where + " " + specName + " 条目，"
                        + (sel.active ? "新终端可直接调用。" : "不在 PATH 中。"),
                Theme.INFO, Theme.INFO_BG), BorderLayout.NORTH);
        // 徽标释义按所选条目自身携带的徽标动态生成：
        // 点"PATH 命中·可直接调用 + 不可切换"的条目解释两者；
        // 点不在 PATH 的纯"不可切换"条目只解释后者，避免张冠李戴。
        StringBuilder legend = new StringBuilder();
        if (sel.active) {
            legend.append("「PATH 命中 · 可直接调用」：新开一个终端敲命令（")
                    .append(currentLang == null ? "" : String.join(" / ", currentLang.spec.exeNames))
                    .append("）时，实际运行的就是这个文件。\n");
        } else {
            legend.append("该条目不在 PATH 中：新终端敲命令不会用到它。\n");
        }
        legend.append("「不可切换」：用哪个版本不由环境变量决定，所以本页不提供切换按钮。")
              .append(sel.active ? "能调用 ≠ 能切换版本。" : "");
        String extra = legend + "\n\n"
                + (isMsvc
                ? "MSVC 的版本由 Visual Studio Installer 统一安装与管理，"
                        + "cl.exe 平时也不在 PATH 里（开发者命令行通过 vcvars64.bat 临时设置）。\n\n"
                        + "· 想增删工具集版本：请使用 Visual Studio Installer。\n"
                        + "· 想编译 C++：建议使用「x64 Native Tools Command Prompt」——\n"
                        + "  仅把 cl.exe 目录加入 PATH 并不能编译，还缺 INCLUDE/LIB 等变量。"
                : "C# 编译器的版本随 .NET Framework / Visual Studio 一起安装，"
                        + "不由环境变量选择。\n\n"
                        + "· 想用新编译器：安装新版 .NET SDK 或 Visual Studio。\n"
                        + "· 新式 .NET 项目请使用 dotnet CLI（见 .NET SDK 条目）。");
        box.add(wrapArea(extra), BorderLayout.CENTER);
        planWrap.removeAll();
        planWrap.add(box, BorderLayout.NORTH);
        planWrap.revalidate();
        planWrap.repaint();
    }

    /**
     * 选中转发器时的说明面板：它不是可选版本，而是卡在 PATH 最前面的中间层。
     * 这里必须给出"接下来该做什么"，否则用户只会看到一堆不能点的按钮。
     */
    private void showBlockingNotice(Detected.Install fwd) {
        String running = currentLang == null ? null : currentLang.runningPath();
        // 文案不要写死 JAVA_HOME：虽然目前只有 Java 会产生转发器条目，
        // 但按语言的 HOME 变量取值更稳，避免将来别的语言出现转发器时再次错位
        String homeVar = currentLang != null && !currentLang.spec.homeVars.isEmpty()
                ? currentLang.spec.homeVars.get(0) : "HOME 类环境变量";
        JPanel box = new JPanel(new BorderLayout(0, 8));
        box.setOpaque(false);
        box.add(Theme.notice("「" + fwd.exePath + "」不是版本，是系统 PATH 里的转发器。",
                Theme.WARN, Theme.WARN_BG), BorderLayout.NORTH);
        box.add(wrapArea("新终端输入命令时先命中它，它再转发到实际在用的版本：\n"
                + (running == null ? "（未解析到）" : running) + "\n\n"
                + "转发器基本都写在系统级 PATH 里，而系统级永远排在用户级之前——"
                + "所以在本页做任何切换都不会改变实际使用的版本。\n\n"
                + "要真正切换，需要把这条转发器从系统 PATH 移除（管理员权限），"
                + "或者改用 " + homeVar + " / 绝对路径调用。"), BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        JButton how = Theme.primaryButton("怎么解决…");
        how.addActionListener(e -> ForwarderHelp.show(SwitchPanel.this,
                currentLang == null ? null : currentLang.spec, fwd.exePath, running, null));
        row.add(how);
        box.add(row, BorderLayout.SOUTH);

        planWrap.removeAll();
        planWrap.add(box, BorderLayout.NORTH);
        planWrap.revalidate();
        planWrap.repaint();
    }

    /**
     * 选中"转发器残留"时的说明：它已经不在 PATH 里，不会再拦截任何命令；
     * 剩下的只是安装器留在磁盘上的文件。
     */
    private void showStaleNotice(Detected.Install ghost) {
        String dir = ghost.rootDir == null ? ghost.exePath : ghost.rootDir;
        JPanel box = new JPanel(new BorderLayout(0, 8));
        box.setOpaque(false);
        box.add(Theme.notice("「" + dir + "」已不在 PATH 中，命令不会再命中它。",
                Theme.INFO, Theme.INFO_BG), BorderLayout.NORTH);
        box.add(wrapArea("它是 Oracle 安装器留在磁盘上的文件（转发器目录，以及它指向的 "
                + "javapath_target_* 目录），PATH 里已经没有它，\n"
                + "所以命令行不会走到这里。它不属于任何已安装的 JDK 版本。\n\n"
                + "清理方式：点左侧底部的「删除此版本…」，勾选「同时删除安装目录」。\n"
                + "删除前建议先在概览页的「转发器目录残留」卡片点「处理」——\n"
                + "那里会检查是否还有程序按绝对路径引用它。\n\n"
                + "注意：以后重装或修复 Oracle Java 时，安装器可能重新创建它们。"), BorderLayout.CENTER);
        planWrap.removeAll();
        planWrap.add(box, BorderLayout.NORTH);
        planWrap.revalidate();
        planWrap.repaint();
    }

    private void invalidatePlan() {
        pendingPlan = null;
        applyButton.setEnabled(false);
        scriptButton.setEnabled(false);
        renderPlan(null);
    }

    /* ---------------------------------------------------------- 预览 */

    private void doPreview() {
        Detected.Install target = versionList.getSelectedValue();
        if (currentLang == null || target == null) {
            Theme.info(this, "请先选择", "请在左侧选择一个语言和目标版本。");
            return;
        }
        EnvApplier.Plan plan = EnvApplier.buildSwitchPlan(
                currentLang.spec, currentLang, target,
                managePath.isSelected(), cleanOthers.isSelected());
        pendingPlan = plan;
        renderPlan(plan);
        boolean canApply = plan != null && !plan.isEmpty();
        applyButton.setEnabled(canApply);
        scriptButton.setEnabled(plan != null && !plan.steps.isEmpty());
        if (!canApply) {
            applyButton.setToolTipText("当前无需任何变更");
        }
    }

    private void renderPlan(EnvApplier.Plan plan) {
        planWrap.removeAll();
        if (plan == null) {
            JLabel noPlan = Theme.muted("尚无计划。");
            noPlan.setHorizontalAlignment(SwingConstants.CENTER);
            planWrap.add(noPlan, BorderLayout.CENTER);
        } else {
            JPanel body = Theme.vBox(7);
            JLabel t = new JLabel(plan.title);
            t.setFont(Theme.F_H2);
            t.setForeground(Theme.TEXT_MAIN);
            t.setAlignmentX(LEFT_ALIGNMENT);
            body.add(Theme.fixed(t));

            if (plan.isEmpty()) {
                body.add(Theme.fixed(Theme.notice("当前配置已经与目标版本一致，无需任何变更。",
                        Theme.OK, Theme.OK_BG)));
            } else {
                int idx = 0;
                for (EnvApplier.Step s : plan.steps) {
                    body.add(Theme.fixed(stepRow(++idx, s)));
                }
            }
            // 该版本推荐的完整环境
            if (plan.target != null && plan.spec != null) {
                Map<String, String> suggest = EnvApplier.suggestEnvFor(plan.spec, plan.target);
                if (!suggest.isEmpty()) {
                    StringBuilder sb = new StringBuilder("该版本推荐的完整环境配置：\n");
                    for (Map.Entry<String, String> e : suggest.entrySet()) {
                        sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
                    }
                    sb.append("\n（上面只列出与当前配置有差异的项，未列出说明已经正确）");
                    JTextArea ta = new JTextArea(sb.toString());
                    ta.setEditable(false);
                    ta.setLineWrap(true);
                    ta.setWrapStyleWord(true);
                    ta.setFont(Theme.F_MONO_SM);
                    ta.setForeground(Theme.TEXT_SUB);
                    ta.setOpaque(false);
                    ta.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
                    ta.setAlignmentX(LEFT_ALIGNMENT);
                    body.add(Theme.fixed(ta));
                }
            }
            for (String w : plan.warnings) {
                body.add(Theme.fixed(Theme.notice("※ " + w, Theme.WARN, Theme.WARN_BG)));
            }
            planWrap.add(Theme.scroll(body), BorderLayout.CENTER);
        }
        planWrap.revalidate();
        planWrap.repaint();
    }

    private JPanel stepRow(int idx, EnvApplier.Step s) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.px(46)));

        JLabel num = new JLabel(String.valueOf(idx), SwingConstants.CENTER);
        num.setFont(Theme.F_H3);
        num.setForeground(Color.WHITE);
        num.setPreferredSize(new Dimension(22, 22));
        num.setOpaque(true);
        num.setBackground(kindColor(s.kind));
        row.add(num, BorderLayout.WEST);

        JTextArea d = new JTextArea(s.describe);
        d.setLineWrap(true);
        d.setWrapStyleWord(true);
        d.setEditable(false);
        d.setFocusable(false);  // 纯展示：点击不出现光标
        d.setFont(Theme.F_SMALL);
        d.setForeground(Theme.TEXT_MAIN);
        d.setOpaque(false);
        d.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        row.add(d, BorderLayout.CENTER);

        JLabel kind = new JLabel(kindLabel(s.kind));
        kind.setFont(Theme.F_TINY);
        kind.setForeground(kindColor(s.kind));
        row.add(kind, BorderLayout.EAST);
        return row;
    }

    private static Color kindColor(EnvApplier.Step.Kind k) {
        switch (k) {
            case PATH_REMOVE: return Theme.ERR;
            case PATH_ADD:
            case PATH_REORDER: return Theme.WARN;
            case DELETE_VAR: return Theme.TEXT_SUB;
            default: return Theme.ACCENT;
        }
    }

    private static String kindLabel(EnvApplier.Step.Kind k) {
        switch (k) {
            case SET_VAR: return "写变量";
            case DELETE_VAR: return "删变量";
            case PATH_ADD: return "加 PATH";
            case PATH_REMOVE: return "移 PATH";
            case PATH_REORDER: return "调序";
            default: return "";
        }
    }

    /* ---------------------------------------------------------- 执行 */

    private void doApply() {
        if (pendingPlan == null || pendingPlan.isEmpty()) {
            Theme.info(this, "无可执行内容", "请先生成一份非空的变更计划。");
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("即将对「当前用户」的环境变量做如下修改：\n\n");
        int i = 0;
        for (EnvApplier.Step s : pendingPlan.steps) {
            msg.append(++i).append(". ").append(s.describe).append("\n");
        }
        msg.append("\n执行前会自动备份到 backup 目录，可在本页随时回滚。\n");
        msg.append("不修改系统级变量，不需要管理员权限。\n");
        msg.append("已打开的终端和 IDE 需要重启才能读到新值。\n\n确认执行吗？");

        if (!Theme.confirm(this, "确认应用环境变更", msg.toString())) {
            return;
        }
        applyButton.setEnabled(false);
        scriptButton.setEnabled(false);
        removeButton.setEnabled(false);
        applyButton.setText("执行中…");

        final EnvApplier.Plan plan = pendingPlan;
        SwingWorker<List<String>, Void> w = new SwingWorker<List<String>, Void>() {
            /** 0=通过；null 且 ran=true 表示未验证；非空表示失败原因。 */
            private String verdict;
            private boolean verified;

            @Override
            protected List<String> doInBackground() {
                List<String> log = EnvApplier.apply(plan);
                boolean failed = false;
                for (String l : log) {
                    if (l.startsWith("×")) {
                        failed = true;
                        break;
                    }
                }
                // 写成功不等于切换成功：转发器、系统级 PATH 都可能让新终端命中的
                // 仍是旧版本。这里按"新开终端会命中什么"复核一次。
                if (!failed && plan.target != null && currentLang != null) {
                    verdict = verifySwitch(currentLang.spec, plan.target.exePath);
                    verified = true;
                    if (verdict == null) {
                        log.add("√ 验证通过：新终端命中的就是 " + plan.target.exePath);
                    } else {
                        log.add("× 切换未生效：" + verdict);
                    }
                }
                return log;
            }

            @Override
            protected void done() {
                applyButton.setText("确认并应用");
                try {
                    List<String> log = get();
                    renderLog(log, plan.backupFile);
                    boolean failed = false;
                    for (String l : log) {
                        if (l.startsWith("×")) {
                            failed = true;
                            break;
                        }
                    }
                    if (failed) {
                        Theme.toast(SwitchPanel.this, "部分变更失败，详见日志");
                    } else if (verified && verdict != null) {
                        showSwitchFailed(plan.spec, plan.target, verdict);
                    } else {
                        Theme.toast(SwitchPanel.this, "切换成功，已验证生效");
                    }
                    pendingPlan = null;
                    // 重扫该语言以反映新状态
                    if (currentLang != null) {
                        model.rescanOne(currentLang.spec.id, () -> SwingUtilities.invokeLater(() -> {
                            refresh(model.report());
                            if (currentLang != null) {
                                focusOn(currentLang.spec.id);
                            }
                        }));
                    } else {
                        refresh(model.report());
                    }
                } catch (Exception ex) {
                    renderLog(java.util.Collections.singletonList("× 执行异常：" + ex.getMessage()), null);
                }
            }
        };
        w.execute();
    }

    /**
     * 切换未生效的提示：先讲清事实（实际在用的是什么），再给"怎么做"的入口。
     * 只弹一句"失败"没有任何价值——用户下一步该干什么才是重点。
     */
    private void showSwitchFailed(LangSpec spec, Detected.Install target, String verdict) {
        String forwarder = currentLang == null ? null : currentLang.forwarderPath;
        String running = currentLang == null ? null : currentLang.runningPath();
        StringBuilder sb = new StringBuilder();
        sb.append("环境变量已经按计划写好了，并已备份（可在本页回滚），")
                .append("但新开的终端仍然不会用到目标版本。\n\n")
                .append("实际在用：").append(running == null ? "（未找到）" : running).append("\n");
        if (forwarder != null) {
            sb.append("PATH 先命中：").append(forwarder).append("（转发器）\n");
        }
        if (target != null) {
            sb.append("目标版本：").append(target.exePath).append("\n");
        }
        sb.append("\n").append(verdict);

        Object[] options = {"怎么解决…", "知道了"};
        int r = JOptionPane.showOptionDialog(this, wrapArea(sb.toString()),
                "切换未生效", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
                null, options, options[1]);
        if (r == 0) {
            ForwarderHelp.show(this, spec, forwarder, running,
                    target == null ? null : target.exePath);
        }
    }

    /**
     * 按"新开一个终端会命中什么"复核操作结果。
     *
     * @param expectExe 期望生效的可执行文件
     * @return 符合预期返回 null；否则返回一句可直接展示给用户的原因
     */
    private static String verifySwitch(LangSpec spec, String expectExe) {
        String hit = Scanner.hitPath(spec);
        String eff = Scanner.effectiveExePath(spec);
        if (expectExe == null) {
            return null;
        }
        if (eff == null) {
            return spec.displayName + " 已经不在 PATH 里，新终端找不到它的可执行文件。";
        }
        if (EnvStore.samePath(eff, expectExe)) {
            return null;
        }
        boolean forwarded = hit != null && !EnvStore.samePath(hit, eff);
        if (forwarded) {
            return "新终端先命中转发器 " + hit + "，它按注册表指向 " + eff
                    + "，而不是目标 " + expectExe + "。\n"
                    + "转发器通常写在系统级 PATH 里，而系统级永远排在用户级之前——"
                    + "把版本目录加到用户 PATH 首位也压不过它。\n"
                    + "要真正切换，需管理员把该条转发器从系统 PATH 移除（或改用读 "
                    + "JAVA_HOME / 绝对路径调用）。";
        }
        return "新终端命中的是 " + eff + "，目标 " + expectExe
                + " 排在它后面。\n"
                + "PATH 里先出现的那个可执行文件永远优先，而系统级条目排在用户级之前。";
    }

    private void renderLog(List<String> log, Path backupFile) {
        logWrap.removeAll();
        JPanel body = Theme.vBox(6);
        for (String l : log) {
            Color fg = l.startsWith("×") ? Theme.ERR
                    : l.startsWith("√") ? Theme.OK
                    : l.startsWith("※") ? Theme.WARN : Theme.TEXT_SUB;
            JLabel lab = new JLabel(l);
            lab.setFont(Theme.F_SMALL);
            lab.setForeground(fg);
            lab.setAlignmentX(LEFT_ALIGNMENT);
            body.add(Theme.fixed(lab));
        }
        if (backupFile != null) {
            JPanel brow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            brow.setOpaque(false);
            brow.setAlignmentX(LEFT_ALIGNMENT);
            JButton undo = Theme.dangerButton("回滚到此次变更前");
            undo.addActionListener(e -> rollbackTo(backupFile));
            JButton open = Theme.ghostButton("打开备份文件");
            open.addActionListener(e -> {
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(backupFile.toFile());
                    }
                } catch (Exception ex) {
                    Theme.error(this, "打开失败", ex.getMessage());
                }
            });
            brow.add(undo);
            brow.add(open);
            body.add(Theme.fixed(brow));
        }
        logWrap.add(Theme.scroll(body), BorderLayout.CENTER);
        logWrap.revalidate();
        logWrap.repaint();
    }

    private void rollbackTo(Path backup) {
        if (!Theme.confirm(this, "确认回滚",
                "将用备份文件还原环境变量与 PATH：\n\n" + backup
                        + "\n\n当前值会被覆盖，回滚前会先自动做一份安全备份。确认继续吗？")) {
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
                renderLog(log, null);
                Theme.toast(SwitchPanel.this, "回滚完成");
                model.rescan();
            }
        };
        w.execute();
    }

    private void doRollback() {
        List<Path> backups = EnvApplier.listBackups();
        if (backups.isEmpty()) {
            Theme.info(this, "没有备份", "backup 目录下还没有任何备份文件，说明尚未执行过变更。");
            return;
        }
        Path latest = backups.get(0);
        String[] options = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) {
            options[i] = backups.get(i).getFileName().toString();
        }
        String sel = (String) JOptionPane.showInputDialog(this,
                "共 " + backups.size() + " 份备份，选择要回滚到哪一份：",
                "回滚环境变更", JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (sel == null) {
            return;
        }
        Path target = null;
        for (int i = 0; i < backups.size(); i++) {
            if (options[i].equals(sel)) {
                target = backups.get(i);
                break;
            }
        }
        if (target == null) {
            target = latest;
        }
        rollbackTo(target);
    }

    private void doExportScript() {
        if (pendingPlan == null || pendingPlan.isEmpty()) {
            return;
        }
        String script = EnvApplier.exportScript(pendingPlan);
        JTextArea ta = new JTextArea(script);
        ta.setFont(Theme.F_MONO_SM);
        ta.setRows(12);
        ta.setColumns(70);
        JScrollPane sp = new JScrollPane(ta);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(Theme.sub("下面是等价的脚本片段，可复制到 CI 或其他机器使用。"
                + "Windows 部分使用 reg add 保留注册表类型；PATH 变更不会写进静态脚本，"
                + "长 PATH 建议仍用本工具的图形界面执行。"),
                BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        String[] opts = {"复制全部", "关闭"};
        int r = JOptionPane.showOptionDialog(this, p, "导出脚本", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
        if (r == 0) {
            Theme.copy(this, script, "脚本");
        }
    }

    /* ---------------------------------------------------------- 删除版本 */

    private void updateRemoveEnabled() {
        Detected.Install sel = versionList.getSelectedValue();
        boolean ok = sel != null && currentLang != null && sel.switchable;
        removeButton.setEnabled(ok);
        if (ok) {
            removeButton.setToolTipText(sel.active
                    ? "当前生效的版本：只清理环境引用（不删安装目录）"
                    : "清理用户环境里指向该版本的 PATH/变量，可选删除安装目录");
        }
    }

    /**
     * 删除一个版本：清理环境引用（可回滚），可选连带删除安装目录。
     *
     * <p>清理口径是"用户环境里所有指向它的路径"——用户 PATH 中的每个目录、
     * PATH 里的重复条目、以及指向它的 HOME 类变量，一次清干净，不留死指针。
     * 系统级 PATH 只做提示，不擅自修改。
     */
    private void doRemoveVersion() {
        final Detected.Install inst = versionList.getSelectedValue();
        if (currentLang == null || inst == null) {
            return;
        }
        final LangSpec spec = currentLang.spec;
        final EnvApplier.Plan plan = EnvApplier.buildRemovePlan(spec, inst, currentLang);
        final String root = inst.rootDir;
        final boolean dirExists = root != null && new File(root).isDirectory();
        final boolean canDeleteDir = dirExists && !inst.active;

        StringBuilder sb = new StringBuilder();
        sb.append("从「当前用户」的环境里移除 ").append(spec.displayName).append(" ")
                .append(inst.label()).append("：\n\n");
        if (plan.steps.isEmpty()) {
            sb.append("（没有检测到需要清理的环境引用）\n");
        } else {
            int i = 0;
            for (EnvApplier.Step s : plan.steps) {
                sb.append(++i).append(". ").append(s.describe).append("\n");
            }
        }
        for (String w : plan.warnings) {
            sb.append("\n※ ").append(w);
        }
        sb.append("\n执行前会自动备份，可在本页回滚；系统级 PATH 不会被修改。");

        final JCheckBox del = new JCheckBox("同时删除磁盘上的安装目录（不可恢复）：" + root);
        del.setSelected(false);
        del.setEnabled(canDeleteDir);
        if (!canDeleteDir) {
            del.setToolTipText(inst.active
                    ? "当前生效的版本不能删除安装目录，请先切换到其它版本"
                    : "未定位到安装目录");
        }
        JPanel box = new JPanel(new BorderLayout(0, 10));
        box.add(wrapArea(sb.toString()), BorderLayout.CENTER);
        box.add(del, BorderLayout.SOUTH);
        String[] options = {dirExists ? "确认删除" : "确认清理", "取消"};
        int r = JOptionPane.showOptionDialog(this, box, "删除版本",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (r != 0) {
            return;
        }

        removeButton.setEnabled(false);
        SwingWorker<List<String>, Void> w = new SwingWorker<List<String>, Void>() {
            private String verdict;

            @Override
            protected List<String> doInBackground() {
                List<String> log = new ArrayList<>();
                if (plan.isEmpty()) {
                    log.add("○ 没有需要清理的环境引用。");
                } else {
                    log.addAll(EnvApplier.apply(plan));
                }
                if (del.isSelected()) {
                    log.add("");
                    log.add("—— 删除安装目录 ——");
                    log.addAll(EnvApplier.deleteInstallDir(new File(root)));
                }
                // 复核：该版本是否还占着"当前生效"
                String eff = Scanner.effectiveExePath(spec);
                if (eff != null && inst.exePath != null && EnvStore.samePath(eff, inst.exePath)) {
                    String hit = Scanner.hitPath(spec);
                    verdict = "新终端仍会命中 " + eff
                            + (hit != null && !EnvStore.samePath(hit, eff)
                            ? "（经转发器 " + hit + "）" : "")
                            + "，说明系统级 PATH 里还有指向它的条目，需要管理员移除。";
                }
                return log;
            }

            @Override
            protected void done() {
                try {
                    List<String> log = get();
                    if (verdict != null) {
                        log.add("× 清理未彻底：" + verdict);
                    }
                    renderLog(log, plan.backupFile);
                    if (verdict != null) {
                        Theme.error(SwitchPanel.this, "清理未彻底", verdict);
                    } else {
                        Theme.toast(SwitchPanel.this, "已清理该版本的环境引用");
                    }
                    model.rescanOne(spec.id, () -> SwingUtilities.invokeLater(() -> {
                        refresh(model.report());
                        focusOn(spec.id);
                    }));
                } catch (Exception ex) {
                    renderLog(java.util.Collections.singletonList("× 删除异常：" + ex.getMessage()), null);
                }
            }
        };
        w.execute();
    }

    /**
     * 可换行的透明文本区：HTML JLabel 没有宽度约束时永不换行，首选宽度是整行，
     * 窄窗口下会把对话框/面板撑破；JTextArea 自己折行，高度也算得准。
     */
    private static JTextArea wrapArea(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setFocusable(false);
        ta.setOpaque(false);
        ta.setBackground(new Color(0, 0, 0, 0));
        ta.setBorder(null);
        ta.setFont(Theme.F_BODY);
        return ta;
    }

    /* ---------------------------------------------------------- 渲染器 */

    /**
     * 版本列表单元格。
     *
     * <p>徽标含义：active 且语言本身可由环境变量选择 → "当前生效"；
     * active 但版本由安装器/宿主管理（MSVC/C#）→ "PATH 命中"——它确实是
     * 新终端会调用的那个文件，但称"生效版本"会让人误以为存在切换关系。
     */
    private final class VersionRenderer extends JPanel implements ListCellRenderer<Detected.Install> {
        private final JLabel ver = new JLabel();
        private final JLabel path = new JLabel();
        private final JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        VersionRenderer() {
            setLayout(new BorderLayout(0, 3));
            setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 9));
            ver.setFont(Theme.F_H3);
            path.setFont(Theme.F_TINY);
            badges.setOpaque(false);
            add(ver, BorderLayout.NORTH);
            add(badges, BorderLayout.CENTER);
            add(path, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Detected.Install> list,
                                                     Detected.Install value, int index,
                                                     boolean isSelected, boolean cellHasFocus) {
            ver.setText(value.label());
            path.setText(DashboardPanel.shorten(value.exePath == null ? value.rootDir : value.exePath));
            path.setToolTipText(Theme.wrapTip(value.exePath == null ? value.rootDir : value.exePath));
            badges.removeAll();
            if (value.active) {
                boolean envSelectable = currentLang == null || currentLang.spec.envSwitchable;
                badges.add(Theme.badge(envSelectable ? "当前生效" : "PATH 命中 · 可直接调用",
                        Theme.OK, Theme.OK_BG));
            } else if (value.blocking) {
                badges.add(Theme.badge("转发器 · 拦截切换", Theme.WARN, Theme.WARN_BG));
            } else if (value.stale) {
                // 已不在 PATH：拦截问题解决了，只剩磁盘上的文件
                badges.add(Theme.badge("转发器残留 · 可删除", Theme.INFO, Theme.INFO_BG));
            }
            if (!value.switchable && !value.blocking && !value.stale) {
                // MSVC 工具集 / .NET 运行时这类由安装器或宿主管理的条目
                badges.add(Theme.badge("不可切换 · 由安装器/宿主管理", Theme.INFO, Theme.INFO_BG));
            }
            badges.add(Theme.badge(value.source, Theme.TEXT_SUB, Theme.GRAY_BG));
            if (value.arch != null && !value.arch.isEmpty()) {
                badges.add(Theme.badge(value.arch, Theme.INFO, Theme.INFO_BG));
            }
            if (value.rootDir != null && !new java.io.File(value.rootDir).isDirectory()) {
                badges.add(Theme.badge("目录已失效", Theme.ERR, Theme.ERR_BG));
            }

            if (isSelected) {
                setBackground(new Color(0xDD, 0xE9, 0xFC));
                ver.setForeground(Theme.ACCENT_DARK);
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : new Color(0xFA, 0xFB, 0xFD));
                ver.setForeground(Theme.TEXT_MAIN);
            }
            path.setForeground(Theme.TEXT_MUTED);
            setOpaque(true);
            return this;
        }
    }
}
