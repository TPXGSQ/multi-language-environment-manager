package com.langmgr.ui;

import com.langmgr.core.AppPaths;
import com.langmgr.core.Detected;
import com.langmgr.core.EnvApplier;
import com.langmgr.core.EnvStore;
import com.langmgr.core.Json;
import com.langmgr.core.Os;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断报告页：把整次扫描结果汇总成 Markdown / JSON 报告，可导出留档或发给同事排查环境问题。
 */
public class ReportPanel extends JPanel {

    private final AppModel model;
    private final JTextArea preview = new JTextArea();
    private final JComboBox<String> formatCombo = new JComboBox<>(new String[]{"Markdown", "JSON"});
    private final JPanel tableWrap = Theme.scrollableVBox();
    private Detected.Report report;
    private String lastGenerated;

    public ReportPanel(AppModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG_APP);
        setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 24));

        add(buildHeader(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBackground(Theme.BG_APP);
        preview.setEditable(false);
        preview.setFont(Theme.F_MONO_SM);
        preview.setBackground(Theme.BG_CODE);
        preview.setForeground(Theme.TEXT_MAIN);
        preview.setLineWrap(false);
        preview.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JScrollPane sp = new JScrollPane(preview);
        sp.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getHorizontalScrollBar().setUnitIncrement(18);

        // 表格视图：总览 / 包管理器 / 下载网址 三张表；文本视图：原始 Markdown/JSON
        JTabbedPane tabs = new JTabbedPane();
        Theme.flatTabs(tabs);
        tableWrap.setBackground(Theme.BG_APP);
        tableWrap.setBorder(BorderFactory.createEmptyBorder(10, 2, 10, 2));
        JScrollPane tableScroll = Theme.scroll(tableWrap);
        tableScroll.setBorder(null);
        tabs.addTab("表格视图", tableScroll);
        tabs.addTab("文本视图", sp);
        center.add(tabs, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
    }

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(Theme.title("诊断报告"), BorderLayout.NORTH);
        JLabel sub = Theme.sub("表格视图直读本机环境概况；文本视图可导出为报告文件，便于留档、对比环境差异或提交问题");
        sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        left.add(sub, BorderLayout.CENTER);
        p.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(Theme.label("格式"));
        formatCombo.setPreferredSize(new Dimension(110, 28));
        formatCombo.setFont(Theme.F_SMALL);
        formatCombo.setBackground(Color.WHITE);
        formatCombo.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        formatCombo.addActionListener(e -> generate());
        right.add(formatCombo);
        JButton gen = Theme.ghostButton("重新生成");
        gen.addActionListener(e -> generate());
        JButton copy = Theme.ghostButton("复制全文");
        copy.addActionListener(e -> Theme.copy(this, preview.getText(), "报告"));
        JButton save = Theme.primaryButton("导出文件");
        save.addActionListener(e -> export());
        right.add(gen);
        right.add(copy);
        right.add(save);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    /* ------------------------------------------------------ 生成 */

    public void refresh(Detected.Report report) {
        this.report = report;
        generate();
    }

    private void generate() {
        if (report == null) {
            lastGenerated = null;
            preview.setText("尚未扫描，没有可导出的报告。\n请先点右上角「重新扫描」。");
            preview.setCaretPosition(0);
            rebuildTables();
            return;
        }
        lastGenerated = "JSON".equals(formatCombo.getSelectedItem())
                ? buildJson()
                : buildMarkdown();
        preview.setText(lastGenerated);
        preview.setCaretPosition(0);
        rebuildTables();
    }

    /* ------------------------------------------------------ 表格视图 */

    /** 重建表格视图的三张表。 */
    private void rebuildTables() {
        tableWrap.removeAll();
        if (report == null) {
            tableWrap.add(Theme.fixed(Theme.notice(
                    "尚未扫描，没有数据。请先点右上角「重新扫描」。", Theme.TEXT_SUB, Theme.GRAY_BG)));
            tableWrap.revalidate();
            tableWrap.repaint();
            return;
        }
        tableWrap.add(Theme.fixed(Theme.muted(
                "生成时间：" + report.scannedAt + "　·　系统：" + report.osLabel
                        + "　·　已安装 " + report.installedCount() + " / " + report.results.size() + " 种")));

        // 总览（与上方生成时间一行拉开距离）
        tableWrap.add(Theme.fixed(Theme.vGap(14)));
        tableWrap.add(Theme.fixed(Theme.sectionTitle("总览")));
        tableWrap.add(Theme.fixed(Theme.vGap(6)));
        Object[][] overview = new Object[report.results.size()][6];
        for (int i = 0; i < report.results.size(); i++) {
            Detected.LangResult r = report.results.get(i);
            overview[i][0] = r.spec.displayName;
            overview[i][1] = r.installed ? "已安装" : "未安装";
            overview[i][2] = r.installed ? r.activeVersion() : "—";
            overview[i][3] = r.installed ? r.activePath() : "—";
            overview[i][4] = r.installs.size();
            overview[i][5] = r.installed ? r.envLabel() : "—";
        }
        tableWrap.add(Theme.fixed(tableCard(overview,
                new Object[]{"语言", "状态", "当前版本", "可执行文件", "安装数", "环境配置"},
                new int[]{130, 70, 130, 320, 60, 90}, null)));

        // 二、包管理器
        List<Object[]> pmRows = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            for (Detected.PkgState s : r.pkgManagers) {
                pmRows.add(new Object[]{r.spec.displayName, s.displayName,
                        s.installed ? "已安装" : "未安装", s.installed ? s.version : nz(s.error)});
            }
        }
        if (!pmRows.isEmpty()) {
            tableWrap.add(Theme.fixed(Theme.vGap(14)));
            tableWrap.add(Theme.fixed(Theme.sectionTitle("包管理器")));
            tableWrap.add(Theme.fixed(Theme.vGap(6)));
            tableWrap.add(Theme.fixed(tableCard(pmRows.toArray(new Object[0][]),
                    new Object[]{"语言", "包管理器", "状态", "版本"},
                    new int[]{130, 110, 80, 220}, null)));
        }

        // 三、官方下载网址（双击打开浏览器）
        List<Object[]> dlRows = new ArrayList<>();
        List<String> dlUrls = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            for (Map.Entry<String, String> d : r.spec.downloads.entrySet()) {
                dlRows.add(new Object[]{r.spec.displayName, d.getKey(), d.getValue()});
                dlUrls.add(d.getValue());
            }
        }
        if (!dlRows.isEmpty()) {
            tableWrap.add(Theme.fixed(Theme.vGap(14)));
            tableWrap.add(Theme.fixed(Theme.sectionTitle("官方下载网址（双击打开）")));
            tableWrap.add(Theme.fixed(Theme.vGap(6)));
            tableWrap.add(Theme.fixed(tableCard(dlRows.toArray(new Object[0][]),
                    new Object[]{"语言", "用途", "网址"},
                    new int[]{130, 210, 400}, dlUrls)));
        }
        // 兜底：递归把视图内所有视口强制为 SIMPLE_SCROLL_MODE，
        // 防止嵌套视口遗留 blit 滚动把旧像素搬进可视区（滚动残影防线）
        Theme.forceSimpleScrollMode(tableWrap);
        tableWrap.revalidate();
        tableWrap.repaint();
    }

    /**
     * 一张带白色卡片衬底的表格。
     * urls 非空时，双击该行用默认浏览器打开对应网址。
     */
    private JPanel tableCard(Object[][] rows, Object[] cols, int[] widths, List<String> urls) {
        JPanel card = Theme.card(null);
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JTable t = Theme.table(rows, cols);
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        int h = Theme.px(26) * rows.length + Theme.px(34);
        t.setPreferredScrollableViewportSize(new Dimension(0, Math.min(Theme.px(320), h)));
        if (urls != null) {
            t.setToolTipText("双击在浏览器中打开该行网址");
            t.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        int row = t.rowAtPoint(e.getPoint());
                        if (row >= 0 && row < urls.size()) {
                            LanguagePanel.openBrowser(ReportPanel.this, urls.get(row));
                        }
                    }
                }
            });
        }
        card.add(Theme.roundedScroll(t), BorderLayout.CENTER);
        return card;
    }

    /* ------------------------------------------------------ Markdown */

    private String buildMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 本机开发环境诊断报告\n\n");
        sb.append("- 生成时间：").append(report.scannedAt).append("\n");
        sb.append("- 操作系统：").append(report.osLabel).append("\n");
        sb.append("- 检测工具：多语言环境管理器（Java ").append(System.getProperty("java.version")).append("）\n");
        sb.append("- 扫描耗时：").append(String.format("%.1f", report.totalMillis / 1000.0)).append(" 秒\n");
        sb.append("- 语言覆盖：").append(report.results.size()).append(" 种，其中 ")
                .append(report.installedCount()).append(" 种已安装\n\n");

        // 概览表
        sb.append("## 一、总览\n\n");
        sb.append("| 语言 | 状态 | 当前版本 | 可执行文件 | 安装数 | 环境配置 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Detected.LangResult r : report.results) {
            sb.append("| ").append(r.spec.displayName)
                    .append(" | ").append(r.installed ? "已安装" : "未安装")
                    .append(" | ").append(esc(r.installed ? r.activeVersion() : "—"))
                    .append(" | ").append(esc(r.installed ? r.activePath() : "—"))
                    .append(" | ").append(r.installs.size())
                    .append(" | ").append(r.installed ? r.envLabel() : "—")
                    .append(" |\n");
        }
        sb.append("\n");

        // 多版本
        List<Detected.LangResult> multi = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            if (r.installs.size() > 1) {
                multi.add(r);
            }
        }
        if (!multi.isEmpty()) {
            sb.append("## 二、存在多版本的语言\n\n");
            sb.append("这些语言本机装了多个版本，可以在「版本切换」页调整默认版本。\n\n");
            for (Detected.LangResult r : multi) {
                sb.append("### ").append(r.spec.displayName).append("（").append(r.installs.size()).append(" 个）\n\n");
                sb.append("| 状态 | 版本 | 可执行文件 | 安装根目录 | 发现方式 |\n");
                sb.append("|---|---|---|---|---|\n");
                for (Detected.Install i : r.installs) {
                    sb.append("| ").append(i.active ? "● 当前" : "")
                            .append(" | ").append(esc(i.version))
                            .append(" | ").append(esc(nz(i.exePath)))
                            .append(" | ").append(esc(nz(i.rootDir)))
                            .append(" | ").append(esc(i.source))
                            .append(" |\n");
                }
                sb.append("\n");
            }
        }

        // 环境变量
        sb.append("## ").append(multi.isEmpty() ? "二" : "三").append("、环境变量\n\n");
        boolean anyVar = false;
        for (Detected.LangResult r : report.results) {
            if (r.vars.isEmpty()) {
                continue;
            }
            anyVar = true;
            sb.append("### ").append(r.spec.displayName).append("\n\n");
            sb.append("| 变量 | 生效值 | 目录存在 | 在 PATH 中 | 备注 |\n");
            sb.append("|---|---|---|---|---|\n");
            for (Detected.VarState v : r.vars.values()) {
                sb.append("| ").append(v.name)
                        .append(" | ").append(esc(nz(v.effectiveValue)))
                        .append(" | ").append(v.effectiveValue == null || v.effectiveValue.trim().isEmpty()
                                ? "—" : (v.targetExists ? "是" : "否"))
                        .append(" | ").append(v.inPath ? "是" : "否")
                        .append(" | ").append(esc(nz(v.note)))
                        .append(" |\n");
            }
            sb.append("\n");
        }
        if (!anyVar) {
            sb.append("已安装的语言都没有关联的 HOME 类环境变量。\n\n");
        }

        // 包管理器
        sb.append("## ").append(nextSection(multi.isEmpty(), 1)).append("、包管理器\n\n");
        sb.append("| 语言 | 包管理器 | 状态 | 版本 |\n");
        sb.append("|---|---|---|---|\n");
        boolean anyPm = false;
        for (Detected.LangResult r : report.results) {
            for (Detected.PkgState s : r.pkgManagers) {
                anyPm = true;
                sb.append("| ").append(r.spec.displayName)
                        .append(" | ").append(s.displayName)
                        .append(" | ").append(s.installed ? "已安装" : "未安装")
                        .append(" | ").append(esc(s.installed ? s.version : nz(s.error)))
                        .append(" |\n");
            }
        }
        if (!anyPm) {
            sb.append("| — | — | — | 未检测到任何包管理器 |\n");
        }
        sb.append("\n");

        // 问题清单
        sb.append("## ").append(nextSection(multi.isEmpty(), 2)).append("、发现的问题\n\n");
        List<String> problems = collectProblems();
        if (problems.isEmpty()) {
            sb.append("未发现问题，环境配置整体健康。\n\n");
        } else {
            for (int i = 0; i < problems.size(); i++) {
                sb.append(i + 1).append(". ").append(problems.get(i)).append("\n");
            }
            sb.append("\n");
        }

        // 官方下载网址
        sb.append("## ").append(nextSection(multi.isEmpty(), 3)).append("、官方下载网址\n\n");
        sb.append("需要安装或补装其它版本时，从对应官方地址下载。\n\n");
        sb.append("| 语言 | 用途 | 网址 |\n");
        sb.append("|---|---|---|\n");
        for (Detected.LangResult r : report.results) {
            for (Map.Entry<String, String> d : r.spec.downloads.entrySet()) {
                sb.append("| ").append(r.spec.displayName)
                        .append(" | ").append(esc(d.getKey()))
                        .append(" | ").append(d.getValue())
                        .append(" |\n");
            }
        }
        sb.append("\n");

        // PATH
        sb.append("## ").append(nextSection(multi.isEmpty(), 4)).append("、用户 PATH\n\n");
        List<String> entries = EnvStore.userPathEntries();
        if (entries.isEmpty()) {
            sb.append("未设置用户级 PATH。\n\n");
        } else {
            String raw = String.join(File.pathSeparator, entries);
            sb.append("共 ").append(entries.size()).append(" 条，").append(raw.length()).append(" 字符");
            if (raw.length() > EnvStore.SETX_LIMIT) {
                sb.append("（超过 setx 的 1024 上限，用 setx 修改会截断）");
            }
            sb.append("。\n\n");
            sb.append("| # | 目录 | 归属 | 存在 |\n|---|---|---|---|\n");
            int i = 0;
            for (String e : entries) {
                String exp = Os.expand(e);
                String owner = EnvApplier.annotateDir(e);
                sb.append("| ").append(++i)
                        .append(" | ").append(esc(exp))
                        .append(" | ").append(owner == null ? "" : owner)
                        .append(" | ").append(new File(EnvStore.strip(exp)).isDirectory() ? "是" : "否")
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("---\n\n");
        sb.append("报告由多语言环境管理器自动生成。所有检测均为只读，未修改任何系统配置。\n");
        return sb.toString();
    }

    private String nextSection(boolean multiEmpty, int offset) {
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八"};
        int base = multiEmpty ? 2 : 3;
        int idx = base + offset - 1;
        return idx < cn.length ? cn[idx] : String.valueOf(idx + 1);
    }

    private List<String> collectProblems() {
        List<String> out = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            for (Detected.VarState v : r.vars.values()) {
                boolean set = v.effectiveValue != null && !v.effectiveValue.trim().isEmpty();
                if (set && !v.targetExists) {
                    out.add("【高】" + r.spec.displayName + " 的 " + v.name + " 指向不存在的目录："
                            + v.effectiveValue + "。建议重新指向有效安装目录，或删除该变量。");
                } else if (set && v.note != null && v.note.contains("不一致")) {
                    out.add("【中】" + r.spec.displayName + " 的 " + v.name + " 与当前生效解释器不一致（"
                            + v.note + "）。当前生效：" + r.activePath());
                }
            }
            if (r.installed) {
                boolean anyActive = false;
                for (Detected.Install i : r.installs) {
                    if (i.active) {
                        anyActive = true;
                        break;
                    }
                }
                if (!anyActive) {
                    out.add("【中】发现 " + r.spec.displayName + " 的 " + r.installs.size()
                            + " 个安装，但都不在 PATH 中，命令行无法直接调用。");
                }
            }
        }
        List<String> dead = new ArrayList<>();
        for (String p : EnvStore.userPathEntries()) {
            if (!new File(EnvStore.strip(Os.expand(p))).isDirectory()) {
                dead.add(p);
            }
        }
        if (!dead.isEmpty()) {
            out.add("【低】用户 PATH 中有 " + dead.size() + " 个失效条目，会拖慢命令查找："
                    + String.join(" ; ", dead));
        }
        String raw = EnvStore.readRaw("PATH");
        if (raw != null && raw.length() > EnvStore.SETX_LIMIT) {
            out.add("【提示】用户 PATH 长 " + raw.length() + " 字符，超过 setx 的 1024 上限。"
                    + "不要用 setx 修改 PATH，否则会丢失后半段。");
        }
        // 重复条目
        List<String> all = EnvStore.userPathEntries();
        List<String> dedup = EnvStore.dedupePath(all);
        if (dedup.size() < all.size()) {
            out.add("【低】用户 PATH 存在 " + (all.size() - dedup.size()) + " 个重复条目，可在「PATH 管理」页一键去重。");
        }
        return out;
    }

    /* ------------------------------------------------------ JSON */

    private String buildJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", report.scannedAt);
        root.put("os", report.osLabel);
        root.put("toolJavaVersion", System.getProperty("java.version"));
        root.put("scanMillis", report.totalMillis);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("languagesScanned", report.results.size());
        summary.put("languagesInstalled", report.installedCount());
        summary.put("multiVersionLanguages", report.multiVersionCount());
        summary.put("envIssues", report.envIssueCount());
        root.put("summary", summary);

        List<Object> langs = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.spec.id);
            m.put("name", r.spec.displayName);
            m.put("category", r.spec.category);
            m.put("installed", r.installed);
            m.put("activeVersion", r.installed ? r.activeVersion() : null);
            m.put("activePath", r.installed ? r.activePath() : null);
            m.put("envLevel", r.envLabel());
            List<Object> insts = new ArrayList<>();
            for (Detected.Install i : r.installs) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("version", i.version);
                im.put("exePath", i.exePath);
                im.put("rootDir", i.rootDir);
                im.put("source", i.source);
                im.put("active", i.active);
                im.put("arch", i.arch);
                insts.add(im);
            }
            m.put("installs", insts);
            Map<String, Object> vars = new LinkedHashMap<>();
            for (Detected.VarState v : r.vars.values()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("raw", v.rawValue);
                vm.put("effective", v.effectiveValue);
                vm.put("targetExists", v.targetExists);
                vm.put("inPath", v.inPath);
                vm.put("note", v.note);
                vars.put(v.name, vm);
            }
            m.put("vars", vars);
            List<Object> pms = new ArrayList<>();
            for (Detected.PkgState s : r.pkgManagers) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", s.id);
                sm.put("name", s.displayName);
                sm.put("installed", s.installed);
                sm.put("version", s.version);
                sm.put("error", s.error);
                pms.add(sm);
            }
            m.put("packageManagers", pms);
            Map<String, String> dls = new LinkedHashMap<>();
            for (Map.Entry<String, String> d : r.spec.downloads.entrySet()) {
                dls.put(d.getKey(), d.getValue());
            }
            m.put("downloads", dls);
            m.put("notes", r.notes);
            m.put("scanMillis", r.scanMillis);
            langs.add(m);
        }
        root.put("languages", langs);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("userPathRaw", EnvStore.readRaw("PATH"));
        env.put("systemPathRaw", report.systemPathRaw);
        List<Object> pathEntries = new ArrayList<>();
        for (Map<String, String> row : EnvApplier.dumpUserPath()) {
            pathEntries.add(row);
        }
        env.put("userPathEntries", pathEntries);
        env.put("problems", collectProblems());
        root.put("environment", env);

        return Json.write(root);
    }

    /* ------------------------------------------------------ 导出 */

    private void export() {
        if (lastGenerated == null || lastGenerated.isEmpty()) {
            Theme.info(this, "没有内容", "请先生成报告（需要先完成一次扫描）。");
            return;
        }
        boolean json = "JSON".equals(formatCombo.getSelectedItem());
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String defaultName = "环境诊断报告-" + stamp + (json ? ".json" : ".md");

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("导出诊断报告");
        File def = AppPaths.reportsDir();
        if (def.isDirectory()) {
            fc.setCurrentDirectory(def);
        }
        fc.setSelectedFile(new File(defaultName));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                json ? "JSON 文件 (*.json)" : "Markdown 文件 (*.md)", json ? "json" : "md"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = fc.getSelectedFile();
        String name = target.getName().toLowerCase();
        if (json && !name.endsWith(".json")) {
            target = new File(target.getParentFile(), target.getName() + ".json");
        } else if (!json && !name.endsWith(".md")) {
            target = new File(target.getParentFile(), target.getName() + ".md");
        }
        if (target.exists() && !Theme.confirm(this, "文件已存在",
                target.getAbsolutePath() + "\n\n已存在同名文件，覆盖后原内容将丢失。确认覆盖吗？")) {
            return;
        }
        try {
            if (target.getParentFile() != null) {
                Files.createDirectories(target.getParentFile().toPath());
            }
            Files.write(target.toPath(), lastGenerated.getBytes(StandardCharsets.UTF_8));
            Theme.toast(this, "已导出");
            JPanel p = new JPanel(new BorderLayout(0, 8));
            p.add(Theme.label("报告已保存到："), BorderLayout.NORTH);
            JTextArea ta = new JTextArea(target.getAbsolutePath());
            ta.setFont(Theme.F_MONO_SM);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            p.add(ta, BorderLayout.CENTER);
            String[] opts = {"打开所在目录", "好的"};
            int r = JOptionPane.showOptionDialog(this, p, "导出成功", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (r == 0) {
                LanguagePanel.openInExplorer(target.getParentFile());
            }
        } catch (Exception ex) {
            Theme.error(this, "导出失败", ex.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** 转义 Markdown 表格中的竖线。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
