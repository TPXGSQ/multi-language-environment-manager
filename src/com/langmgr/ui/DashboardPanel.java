package com.langmgr.ui;

import com.langmgr.core.Detected;
import com.langmgr.core.EnvApplier;
import com.langmgr.core.EnvStore;
import com.langmgr.core.Os;
import com.langmgr.core.RefScanner;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 环境概览页：统计卡片、已安装语言速览、环境问题提示、快捷跳转。
 */
public class DashboardPanel extends JPanel {

    private final AppModel model;
    private final Consumer<String> navigate;
    /** 跳到"版本切换"并选中指定语言的转发器条目（用于转发器拦截类问题的处理入口）。 */
    private final Consumer<String> gotoForwarder;

    private final JPanel statsRow = new JPanel(new GridLayout(1, 4, 14, 0));
    /*
     * 这两个面板装进 JScrollPane 里：必须实现 Scrollable 并跟踪视口宽度，
     * 否则"未检测到：…"这类长文本会把面板首选宽度撑到上万像素，
     * 视口不约束宽度，整行内容横向溢出被裁剪。
     */
    private final JPanel installedWrap = Theme.scrollableVBox();
    private final JPanel issueWrap = Theme.scrollableVBox();
    /** 底部"未检测到"清单（自动换行，最多两行半高度）。 */
    private final JTextArea missingStrip = new JTextArea();

    public DashboardPanel(AppModel model, Consumer<String> navigate, Consumer<String> gotoForwarder) {
        this.model = model;
        this.navigate = navigate;
        this.gotoForwarder = gotoForwarder;
        setLayout(new BorderLayout());
        setBackground(Theme.BG_APP);
        setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 24));

        JPanel body = Theme.vBox(14);
        body.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        // 标题
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JPanel headLeft = new JPanel(new BorderLayout());
        headLeft.setOpaque(false);
        headLeft.add(Theme.title("环境概览"), BorderLayout.NORTH);
        JLabel sub = Theme.sub("自动检测本机已安装的编程语言解释器与编译器，给出环境配置健康度评估");
        sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        headLeft.add(sub, BorderLayout.CENTER);
        head.add(headLeft, BorderLayout.WEST);
        // 扫描进度只保留主窗口顶栏的一套（状态条），此处不再重复显示
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.px(52)));
        body.add(head);

        statsRow.setOpaque(false);
        statsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.px(96)));
        body.add(statsRow);

        // 两栏：已安装语言 / 待处理问题
        JPanel cols = new JPanel(new GridLayout(1, 2, 14, 0));
        cols.setOpaque(false);

        JPanel leftCard = Theme.card(null);
        leftCard.setLayout(new BorderLayout());
        JPanel leftHead = new JPanel(new BorderLayout());
        leftHead.setOpaque(false);
        leftHead.add(Theme.h2("已检测到的语言"), BorderLayout.WEST);
        JButton toLang = Theme.ghostButton("查看全部 →");
        toLang.addActionListener(e -> navigate.accept("languages"));
        leftHead.add(toLang, BorderLayout.EAST);
        leftCard.add(leftHead, BorderLayout.NORTH);
        leftCard.add(Theme.scroll(installedWrap), BorderLayout.CENTER);
        // 未检测到清单放卡片底部：BorderLayout 保证满宽，不会像 BoxLayout 那样被撑爆或居中偏移
        missingStrip.setOpaque(false);
        missingStrip.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));
        missingStrip.setVisible(false);
        leftCard.add(missingStrip, BorderLayout.SOUTH);

        JPanel rightCard = Theme.card(null);
        rightCard.setLayout(new BorderLayout());
        JPanel rightHead = new JPanel(new BorderLayout());
        rightHead.setOpaque(false);
        rightHead.add(Theme.h2("需要关注"), BorderLayout.WEST);
        JButton toEnv = Theme.ghostButton("环境变量 →");
        toEnv.addActionListener(e -> navigate.accept("env"));
        rightHead.add(toEnv, BorderLayout.EAST);
        rightCard.add(rightHead, BorderLayout.NORTH);
        rightCard.add(Theme.scroll(issueWrap), BorderLayout.CENTER);

        cols.add(leftCard);
        cols.add(rightCard);
        cols.setPreferredSize(new Dimension(0, 420));
        body.add(cols);

        add(Theme.scroll(body), BorderLayout.CENTER);
        buildStats(0, 0, 0, 0, false);
    }

    /* ------------------------------------------------------ 统计卡片 */

    private void buildStats(int installed, int total, int multiVer, int issues, boolean scanned) {
        statsRow.removeAll();
        if (scanned) {
            statsRow.add(statCard("可用语言", String.valueOf(installed), "共检测 " + total + " 种", Theme.ACCENT, Theme.INFO_BG));
            statsCard(statsRow, "多版本共存", String.valueOf(multiVer), "可进行版本切换", Theme.OK, Theme.OK_BG);
            statsCard(statsRow, "环境配置异常", String.valueOf(issues), issues == 0 ? "无异常" : "建议检查 HOME 变量与 PATH",
                    issues == 0 ? Theme.OK : Theme.WARN, issues == 0 ? Theme.OK_BG : Theme.WARN_BG);
            int pathCount = EnvStore.userPathEntries().size();
            statsCard(statsRow, "用户 PATH 条目", String.valueOf(pathCount),
                    EnvStore.readRaw("PATH") == null ? "未设置用户级 PATH" : "当前用户作用域",
                    Theme.TEXT_SUB, Theme.GRAY_BG);
        } else {
            // 尚未扫描完成：四张卡统一显示占位横杠，扫描完成后由 refresh(report) 刷新真实值
            statsCard(statsRow, "可用语言", "—", "扫描完成后统计", Theme.TEXT_SUB, Theme.GRAY_BG);
            statsCard(statsRow, "多版本共存", "—", "扫描完成后统计", Theme.TEXT_SUB, Theme.GRAY_BG);
            statsCard(statsRow, "环境配置异常", "—", "扫描完成后统计", Theme.TEXT_SUB, Theme.GRAY_BG);
            statsCard(statsRow, "用户 PATH 条目", "—", "扫描完成后统计", Theme.TEXT_SUB, Theme.GRAY_BG);
        }
        statsRow.revalidate();
        statsRow.repaint();
    }

    private void statsCard(JPanel parent, String title, String value, String sub, Color accent, Color bg) {
        parent.add(statCard(title, value, sub, accent, bg));
    }

    /** 与统计卡片同风格的提示卡（实现移至 Theme.emptyCard，供多模块复用）。 */
    private JPanel noticeCard(String title, String body, Color accent) {
        return Theme.emptyCard(title, body, accent);
    }

    private JPanel statCard(String title, String value, String sub, Color accent, Color bg) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.BG_CARD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(Theme.LINE);
                g2.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 14, 14);
                g2.setColor(accent);
                g2.fillRoundRect(0, 14, 3, getHeight() - 28, 3, 3);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 14));
        JLabel t = new JLabel(title);
        t.setFont(Theme.F_SMALL);
        t.setForeground(Theme.TEXT_SUB);
        t.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(t, BorderLayout.NORTH);

        JPanel mid = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        mid.setOpaque(false);
        JLabel v = new JLabel(value);
        v.setFont(Theme.bold(28));
        v.setForeground(accent);
        mid.add(v);
        p.add(mid, BorderLayout.CENTER);

        JLabel s = new JLabel(sub);
        s.setFont(Theme.F_TINY);
        s.setForeground(Theme.TEXT_MUTED);
        s.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(s, BorderLayout.SOUTH);
        return p;
    }

    /* ------------------------------------------------------ 刷新 */

    public void refresh(Detected.Report report) {
        installedWrap.removeAll();
        issueWrap.removeAll();

        if (report == null) {
            buildStats(0, com.langmgr.core.LangSpec.all().size(), 0, 0, false);
            // 空状态卡片：白底 + 标题 + 副文 + 右下角主按钮（不再用灰底提示条）
            JPanel emptyCard = Theme.card(null);
            emptyCard.setLayout(new BorderLayout(16, 0));
            JPanel left = Theme.vBox(6);
            JLabel t1 = new JLabel("尚未开始扫描");
            t1.setFont(Theme.bold(16));
            t1.setForeground(Theme.TEXT_MAIN);
            t1.setAlignmentX(Component.LEFT_ALIGNMENT);
            left.add(t1);
            // 副文用两个固定行高的 JLabel（JTextArea + lineWrap 的首选高度会被
            // maximumSize 压缩，字体放大后第二行会被截掉一半）
            JLabel d1 = new JLabel("点击「立即扫描」检测本机已安装的编程语言解释器与编译器。");
            d1.setFont(Theme.F_SMALL);
            d1.setForeground(Theme.TEXT_SUB);
            d1.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel d2 = new JLabel("扫描过程完全只读，不会修改任何配置。");
            d2.setFont(Theme.F_SMALL);
            d2.setForeground(Theme.TEXT_SUB);
            d2.setAlignmentX(Component.LEFT_ALIGNMENT);
            left.add(d1);
            left.add(d2);
            // 检测范围提示：放进卡片内与副文一起左对齐（放在卡片外会被 BoxLayout 居中截断）
            JLabel tip1 = new JLabel("支持检测 " + com.langmgr.core.LangSpec.all().size()
                    + " 种语言/工具链：Java、Python、Node.js、Go、Rust、.NET、MSVC、C#、GCC 等。");
            tip1.setFont(Theme.F_TINY);
            tip1.setForeground(Theme.TEXT_MUTED);
            tip1.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel tip2 = new JLabel("也可在右上角切换到命令行模式（--cli）。");
            tip2.setFont(Theme.F_TINY);
            tip2.setForeground(Theme.TEXT_MUTED);
            tip2.setAlignmentX(Component.LEFT_ALIGNMENT);
            left.add(tip1);
            left.add(tip2);
            emptyCard.add(left, BorderLayout.CENTER);
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
            btns.setOpaque(false);
            JButton scan = Theme.primaryButton("立即扫描");
            scan.addActionListener(e -> model.rescan());
            btns.add(scan);
            emptyCard.add(btns, BorderLayout.SOUTH);
            installedWrap.add(emptyCard);
            installedWrap.add(Theme.fixed(Theme.vGap(6)));
            issueWrap.add(Theme.fixed(noticeCard("暂无待处理的问题",
                    "开始扫描后，发现的环境问题会集中显示在这里。", Theme.TEXT_SUB)));
            revalidate();
            repaint();
            return;
        }

        buildStats(report.installedCount(), report.results.size(),
                report.multiVersionCount(), report.envIssueCount(), true);

        // 已安装语言列表
        List<Detected.LangResult> installed = new ArrayList<>();
        List<Detected.LangResult> missing = new ArrayList<>();
        for (Detected.LangResult r : report.results) {
            (r.installed ? installed : missing).add(r);
        }
        for (Detected.LangResult r : installed) {
            installedWrap.add(Theme.fixed(langRow(r)));
        }
        // 未检测到清单：最多列 10 个名字，按 360px 手工断行（卡片内容区约 386px，留余量）
        if (missing.isEmpty()) {
            missingStrip.setVisible(false);
        } else {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < missing.size() && i < 10; i++) {
                names.add(missing.get(i).spec.displayName);
            }
            String suffix = missing.size() > 10 ? " 等 " + missing.size() + " 种" : "";
            String text = "未检测到：" + String.join("、", names) + suffix;
            FontMetrics fm = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    .createGraphics().getFontMetrics(Theme.F_SMALL);
            StringBuilder wrapped = new StringBuilder();
            int lineW = 0;
            // 按顿号边界断行（保留分隔符在上一行末尾），避免把 "Lua" 这类单词拆开
            for (String seg : text.split("(?<=、)")) {
                int w = fm.stringWidth(seg);
                if (lineW > 0 && lineW + w > 360) {
                    wrapped.append('\n');
                    lineW = 0;
                    if (seg.startsWith("、")) {
                        seg = seg.substring(1);
                    }
                }
                wrapped.append(seg);
                lineW += fm.stringWidth(seg);
            }
            missingStrip.setText(wrapped.toString());
            missingStrip.setEditable(false);
            missingStrip.setFocusable(false);  // 纯展示：点击不出现光标
            missingStrip.setOpaque(false);
            missingStrip.setFont(Theme.F_SMALL);
            missingStrip.setForeground(Theme.TEXT_MUTED);
            missingStrip.setRows(3);
            missingStrip.setVisible(true);
        }

        // 待关注问题
        int issues = 0;
        issues += addIssueRows(report);
        if (issues == 0) {
            issueWrap.add(Theme.fixed(noticeCard("没有发现明显的环境配置问题",
                    "各项 HOME 变量指向的目录都存在，\nPATH 中也没有失效条目。", Theme.OK)));
        }
        issueWrap.add(Theme.fixed(Theme.vGap(8)));
        issueWrap.add(Theme.fixed(quickActions(report)));

        revalidate();
        repaint();
    }

    private JPanel langRow(Detected.LangResult r) {
        JPanel row = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(Theme.BG_CARD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.setColor(Theme.LINE);
                g2.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.px(62)));

        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        JLabel name = new JLabel(r.spec.displayName);
        name.setFont(Theme.F_H3);
        name.setForeground(Theme.TEXT_MAIN);
        left.add(name, BorderLayout.NORTH);
        JLabel path = new JLabel(shorten(r.activePath()));
        path.setFont(Theme.F_TINY);
        path.setForeground(Theme.TEXT_MUTED);
        path.setToolTipText(Theme.wrapTip(r.activePath()));
        path.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        left.add(path, BorderLayout.CENTER);
        row.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(Theme.badge(r.activeVersion(), Theme.TEXT_MAIN, Theme.GRAY_BG));
        if (r.installs.size() > 1) {
            right.add(Theme.badge(r.installs.size() + " 个安装", Theme.INFO, Theme.INFO_BG));
        }
        if (!r.inPath() && r.installed) {
            right.add(Theme.badge("不在 PATH", Theme.WARN, Theme.WARN_BG));
        }
        int lv = r.envLevel();
        if (lv == 1) {
            right.add(Theme.badge("环境就绪", Theme.OK, Theme.OK_BG));
        } else if (lv == 2) {
            right.add(Theme.badge("环境异常", Theme.ERR, Theme.ERR_BG));
        } else if (lv == 4) {
            right.add(Theme.badge("可选未配", Theme.WARN, Theme.WARN_BG));
        }
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private int addIssueRows(Detected.Report report) {
        int n = 0;
        // 0. PATH 命中转发器：命中与使用不一致，用户级 PATH 的切换全部失效
        for (Detected.LangResult r : report.results) {
            if (!r.blockedByForwarder()) {
                continue;
            }
            issueWrap.add(forwarderRow(r));
            n++;
        }
        // 0b. 转发器目录残留：已不在 PATH 里（不影响命中），但磁盘上还在
        for (Detected.LangResult r : report.results) {
            if (r.staleForwarderDirs.isEmpty()) {
                continue;
            }
            issueWrap.add(staleForwarderRow(r));
            n++;
        }
        // 1. HOME 变量异常
        for (Detected.LangResult r : report.results) {
            for (Detected.VarState v : r.vars.values()) {
                boolean set = v.effectiveValue != null && !v.effectiveValue.trim().isEmpty();
                if (set && !v.targetExists) {
                    issueWrap.add(issueRow(Theme.ERR, Theme.ERR_BG,
                            r.spec.displayName + " · " + v.name,
                            "指向的目录不存在：" + v.effectiveValue,
                            fixVarToCurrent(r, v), "env"));
                    n++;
                } else if (set && v.note != null && v.note.contains("不一致")) {
                    issueWrap.add(issueRow(Theme.WARN, Theme.WARN_BG,
                            r.spec.displayName + " · " + v.name,
                            v.note + "（当前生效：" + r.activePath() + "）",
                            fixVarToCurrent(r, v), "switch"));
                    n++;
                }
            }
        }
        // 2. 装了但不在 PATH
        for (Detected.LangResult r : report.results) {
            if (!r.installed) {
                continue;
            }
            boolean anyActive = false;
            for (Detected.Install i : r.installs) {
                if (i.active) {
                    anyActive = true;
                    break;
                }
            }
            if (!anyActive) {
                issueWrap.add(issueRow(Theme.WARN, Theme.WARN_BG,
                        r.spec.displayName,
                        "发现 " + r.installs.size() + " 个安装，但都不在 PATH 中，命令行无法直接调用",
                        fixAddToPath(r), "switch"));
                n++;
            }
        }
        // 3. PATH 中失效的条目
        List<String> dead = new ArrayList<>();
        for (String p : EnvStore.userPathEntries()) {
            String exp = EnvStore.strip(Os.expand(p));
            if (!new File(exp).isDirectory()) {
                dead.add(p);
            }
        }
        if (!dead.isEmpty()) {
            issueWrap.add(issueRow(Theme.WARN, Theme.WARN_BG,
                    "用户 PATH",
                    "有 " + dead.size() + " 个条目指向不存在的目录（拖慢每次命令查找）："
                            + shorten(String.join(" ; ", dead)),
                    fixDeadPathEntries(dead), "env"));
            n++;
        }
        // 4. 用户 PATH 过长
        String raw = EnvStore.readRaw("PATH");
        if (raw != null && raw.length() > EnvStore.SETX_LIMIT) {
            List<String> all = EnvStore.userPathEntries();
            List<String> dedup = EnvStore.dedupePath(all);
            issueWrap.add(issueRow(Theme.INFO, Theme.INFO_BG,
                    "用户 PATH 长度",
                    "共 " + raw.length() + " 字符，已超过 setx 的 1024 上限；"
                            + "本工具用注册表方式读写，不会截断，但其他脚本调用 setx 时仍有风险",
                    dedup.size() < all.size() ? fixDedupePath(all, dedup) : null, "env"));
            n++;
        }
        return n;
    }

    /* ------------------------------------------------------ 一键自动处理 */

    /**
     * HOME 变量异常/不一致的一键修复：把变量改写成当前生效版本的推导值。
     * 无法推导（没有生效安装或该变量不在推导范围内）时返回 null，退回页面跳转。
     */
    private Runnable fixVarToCurrent(Detected.LangResult r, Detected.VarState v) {
        if (r.active == null || r.active.rootDir == null) {
            return null;
        }
        String value = EnvApplier.suggestEnvFor(r.spec, r.active).get(v.name);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        // 危险变量（PYTHONHOME/GOROOT/DOTNET_ROOT）的建议是"不要设置"：
        // 动作必须是删除变量。若把"（建议留空）"占位符原样写进注册表，
        // 检查器会把它判成"指向的目录不存在"，一键处理后又原样写回，问题永远消不掉。
        boolean leaveEmpty = EnvApplier.isLeaveEmptyHint(value);
        String confirmText = leaveEmpty
                ? "「" + v.name + "」设了反而会干扰解释器/虚拟环境的自动定位，建议不设置。\n\n"
                        + "将删除用户环境变量 " + v.name + "（当前值：" + v.effectiveValue + "）"
                : "将把该变量改为当前生效版本 " + r.active.label() + " 的推导值：\n\n"
                        + v.name + " = " + value + "\n\n当前值：" + v.effectiveValue;
        return () -> autoFix(r.spec.displayName + " · " + v.name,
                new String[]{v.name},
                confirmText,
                leaveEmpty
                        ? () -> EnvStore.delete(v.name)
                        : () -> EnvStore.write(v.name, value, value.contains("%")));
    }

    /**
     * "装了但不在 PATH" 的一键修复：把主安装实例的可执行目录加入用户 PATH。
     */
    private Runnable fixAddToPath(Detected.LangResult r) {
        Detected.Install primary = r.active != null ? r.active
                : (r.installs.isEmpty() ? null : r.installs.get(0));
        if (primary == null || primary.exePath == null) {
            return null;
        }
        File dir = new File(primary.exePath).getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        String dirPath = dir.getAbsolutePath();
        return () -> autoFix(r.spec.displayName + " 加入 PATH",
                new String[]{"PATH"},
                "将把该目录追加到用户 PATH 末尾：\n\n" + dirPath
                        + "\n\n追加后新开的终端即可直接调用 " + r.spec.displayName + " 命令。",
                () -> EnvStore.addToPath(dirPath));
    }

    /** 失效 PATH 条目的一键清理：备份后重写用户 PATH，仅保留仍然存在的目录。 */
    private Runnable fixDeadPathEntries(List<String> dead) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < dead.size() && i < 8; i++) {
            list.append("  - ").append(Os.expand(dead.get(i))).append("\n");
        }
        if (dead.size() > 8) {
            list.append("  … 共 ").append(dead.size()).append(" 条\n");
        }
        return () -> autoFix("清理失效 PATH 条目",
                new String[]{"PATH"},
                "将从用户 PATH 中移除以下失效条目（其余条目与顺序保持不变）：\n\n" + list,
                () -> {
                    // 点击时才重新计算保留列表，避免扫描后 PATH 又有变动
                    List<String> kept = new ArrayList<>();
                    for (String p : EnvStore.userPathEntries()) {
                        if (new File(EnvStore.strip(Os.expand(p))).isDirectory()) {
                            kept.add(p);
                        }
                    }
                    return EnvStore.write("PATH", String.join(File.pathSeparator, kept), true);
                });
    }

    /** PATH 过长且存在重复时的一键去重。 */
    private Runnable fixDedupePath(List<String> all, List<String> dedup) {
        return () -> autoFix("PATH 去重",
                new String[]{"PATH"},
                "将移除用户 PATH 中 " + (all.size() - dedup.size()) + " 个重复条目（保留首次出现的顺序）：\n\n"
                        + all.size() + " 条 → " + dedup.size() + " 条",
                () -> EnvStore.write("PATH", String.join(File.pathSeparator, dedup), true));
    }

    /**
     * 一键处理的统一执行流程：确认 → 备份 → 执行 → 广播变更 → 重扫刷新。
     * action 返回错误信息，返回 null 表示成功。
     */
    private void autoFix(String title, String[] backupVars, String confirmText, Supplier<String> action) {
        boolean ok = Theme.confirm(this, "一键处理 · " + title,
                confirmText + "\n\n只修改当前用户作用域（HKCU），不动系统级变量；"
                        + "执行前自动备份到 backup 目录，可在「环境变量 → 备份与回滚」中一键还原。\n"
                        + "已打开的终端需重新打开才能读到新值。");
        if (!ok) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<String, Void> w = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    EnvStore.backupMany(Arrays.asList(backupVars));
                } catch (Exception ex) {
                    return "备份失败，已中止（未做任何修改）：" + ex.getMessage();
                }
                String err = action.get();
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
                    Theme.error(DashboardPanel.this, "处理失败", err);
                    return;
                }
                Theme.toast(DashboardPanel.this, "已处理：" + title);
                // 全量重扫，让概览/语言/环境变量各页都拿到最新状态
                model.rescan();
            }
        };
        w.execute();
    }

    private JPanel issueRow(Color fg, Color bg, String title, String detail,
                            Runnable autoFix, String fallbackPage) {
        Runnable nav = fallbackPage == null ? null : () -> navigate.accept(fallbackPage);
        return issueRow(fg, bg, title, Theme.wrapText(detail, Theme.F_SMALL, Theme.TEXT_MAIN),
                autoFix, nav);
    }

    /**
     * 问题行的统一骨架：左侧标题 + 明细块（可为多行组合），右侧操作按钮。
     * detail 传 {@link Theme#wrapText} 生成的文本块时高度会按实际宽度实测，
     * 窄窗口下不会再把说明文字裁掉半行。
     */
    private JPanel issueRow(Color fg, Color bg, String title, JComponent detail,
                            Runnable autoFix, Runnable fallback) {
        JPanel row = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        // BoxLayout 默认按 alignmentX 居中，宽度变化时内容会左右漂移，统一左锚
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel t = new JLabel(title);
        t.setFont(Theme.F_H3);
        t.setForeground(fg);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        Theme.autoTooltip(t);
        left.add(t);
        if (detail != null) {
            detail.setAlignmentX(Component.LEFT_ALIGNMENT);
            left.add(Theme.vGap(4));
            left.add(detail);
        }
        row.add(left, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        if (autoFix != null) {
            // 有自动修复方案：主按钮直接处理，次级按钮可跳到页面查看细节
            JButton fix = Theme.primaryButton("一键处理");
            fix.setToolTipText("自动修复：确认后先备份再修改，可随时回滚");
            fix.addActionListener(e -> autoFix.run());
            btns.add(fix);
            if (fallback != null) {
                JButton more = Theme.ghostButton("详情");
                more.addActionListener(e -> fallback.run());
                btns.add(more);
            }
        } else if (fallback != null) {
            // 软件无法自动处理（如要改系统级 PATH / 需要人工选目录）：
            // 「处理」跳到对应页面的操作面板，把"下一步该做什么"直接摆到用户面前
            JButton b = Theme.primaryButton("处理");
            b.addActionListener(e -> fallback.run());
            btns.add(b);
        }
        // 按钮贴卡片顶部，与标题对齐；多行明细时不会被拉到中间
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.add(btns, BorderLayout.NORTH);
        row.add(east, BorderLayout.EAST);
        return row;
    }

    /** 明细里的一行「标签 + 值」：值用等宽字体、过长中间省略，悬停看全文。 */
    private JPanel kvLine(String key, String value, Color keyColor) {
        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel k = new JLabel(key);
        k.setFont(Theme.F_TINY);
        k.setForeground(keyColor);
        k.setPreferredSize(new Dimension(Theme.px(70), Theme.px(17)));
        line.add(k, BorderLayout.WEST);
        line.add(Theme.elide(value == null || value.isEmpty() ? "（未解析到）" : value),
                BorderLayout.CENTER);
        return line;
    }

    /**
     * 「转发器目录残留」问题行：PATH 已经不指向它了，但目录还在磁盘上。
     * 点「处理」后由程序自己检查还有没有程序按绝对路径引用它，
     * 再按检查结果给出可执行的处置选项——不需要用户自己去"确认"。
     */
    private JPanel staleForwarderRow(Detected.LangResult r) {
        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String dir : r.staleForwarderDirs) {
            block.add(Theme.fixed(kvLine("残留目录", dir, Theme.TEXT_SUB)));
            block.add(Theme.vGap(2));
        }
        block.add(Theme.vGap(4));
        block.add(Theme.wrapText("这些目录已不在任何 PATH 中，因此不会再被命令命中的。"
                        + "它们只是安装器留在磁盘上的文件；点「处理」会先检查是否还有程序"
                        + "按绝对路径引用它们，再给出清理或保留的方案。",
                Theme.F_SMALL, Theme.TEXT_SUB));
        return issueRow(Theme.INFO, Theme.INFO_BG,
                r.spec.displayName + " · 转发器目录残留", block,
                () -> doHandleStale(r), () -> navigate.accept("languages"));
    }

    /** 处理残留目录：后台扫描绝对路径引用 → 按结果给出选项 → 执行。 */
    private void doHandleStale(Detected.LangResult r) {
        List<String> dirs = r.staleForwarderDirs;
        if (dirs.isEmpty()) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // 扫描注册表 + 快捷方式可能要几十秒，给一个模态进度提示，避免用户以为没反应
        JDialog wait = new JDialog(SwingUtilities.getWindowAncestor(this) instanceof java.awt.Frame
                ? (java.awt.Frame) SwingUtilities.getWindowAncestor(this) : null,
                "正在检查引用", Dialog.ModalityType.APPLICATION_MODAL);
        javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
        bar.setIndeterminate(true);
        JPanel wp = new JPanel(new BorderLayout(0, Theme.px(8)));
        wp.setBorder(BorderFactory.createEmptyBorder(Theme.px(18), Theme.px(24),
                Theme.px(18), Theme.px(24)));
        wp.add(Theme.wrapText("正在检查是否有程序按绝对路径引用这些目录……"
                + "（会扫描环境变量、注册表和开始菜单快捷方式，可能需要一点时间）",
                Theme.F_SMALL, Theme.TEXT_MAIN), BorderLayout.NORTH);
        wp.add(bar, BorderLayout.SOUTH);
        wait.setContentPane(wp);
        wait.setResizable(false);
        wait.pack();
        wait.setLocationRelativeTo(this);
        SwingWorker<List<RefScanner.Ref>, Void> w = new SwingWorker<List<RefScanner.Ref>, Void>() {
            @Override
            protected List<RefScanner.Ref> doInBackground() {
                return RefScanner.findHardReferences(dirs);
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                wait.setVisible(false);
                wait.dispose();
                List<RefScanner.Ref> refs;
                try {
                    refs = get();
                } catch (Exception ex) {
                    Theme.error(DashboardPanel.this, "检查失败",
                            "无法完成引用检查：" + ex.getMessage());
                    return;
                }
                showStaleOptions(r, refs, RefScanner.lastScanIncomplete());
            }
        };
        w.execute();
        wait.setVisible(true); // 模态阻塞 EDT，但事件循环仍在泵，done() 里会关闭它
    }

    /**
     * 展示检查结果并让用户选一个动作。
     *
     * <p>三种情况分开处理：
     * <ul>
     *   <li>没有引用 → 直接给「删除目录」；</li>
     *   <li>有引用 → 列出引用者 + 逐条处置办法，按钮改为「先处理引用」；</li>
     *   <li>扫描超时（注册表太大）→ 明说没扫全，仍允许删除，但提示可能残留。</li>
     * </ul>
     */
    private void showStaleOptions(Detected.LangResult r, List<RefScanner.Ref> refs, boolean incomplete) {
        List<String> dirs = r.staleForwarderDirs;
        StringBuilder sb = new StringBuilder();
        sb.append("检查范围：系统/用户 PATH、其它环境变量、注册表（App Paths、启动项、"
                + "卸载项、服务、文件关联）、开始菜单快捷方式。\n\n");
        for (String d : dirs) {
            sb.append("  ").append(d).append("\n");
        }
        sb.append("\n");

        boolean hasRefs = !refs.isEmpty();
        if (hasRefs) {
            sb.append("发现 ").append(refs.size()).append(" 处按绝对路径引用这些目录：\n\n");
            int shown = 0;
            for (RefScanner.Ref ref : refs) {
                if (shown++ >= 8) {
                    sb.append("  …共 ").append(refs.size()).append(" 处\n");
                    break;
                }
                sb.append("· ").append(ref.source).append("\n");
                sb.append("    ").append(ref.detail).append("\n");
                sb.append("    → ").append(ref.action).append("\n\n");
            }
            sb.append("建议先处理上面的引用，否则删除目录后这些程序会失效。\n");
        } else if (incomplete) {
            sb.append("未发现绝对路径引用（注意：注册表扫描超时提前结束，可能没扫全）。\n");
        } else {
            sb.append("未发现任何程序按绝对路径引用这些目录，可以安全删除。\n");
        }
        sb.append("\n删除后不影响命令行使用；重装或修复 Oracle Java 时安装器可能重新创建。");

        JTextArea ta = Theme.wrapText(sb.toString(), Theme.F_SMALL, Theme.TEXT_MAIN);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(Theme.px(620), Theme.px(360)));
        sp.setBorder(BorderFactory.createLineBorder(Theme.LINE));

        Object[] opts = hasRefs
                ? new Object[]{"复制处置命令", "仍然删除", "取消"}
                : new Object[]{"删除目录", "取消"};
        int choice = JOptionPane.showOptionDialog(this, sp,
                hasRefs ? "这些目录仍被引用 · 先处理引用" : "可以安全删除",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                opts, opts[opts.length - 1]);

        if (hasRefs) {
            if (choice == 0) {
                copyRefCommands(refs);
                return;
            }
            if (choice != 1) {
                return;
            }
            boolean ok = Theme.confirm(this, "确认删除",
                    "仍有 " + refs.size() + " 处引用指向这些目录，删除后相关程序可能失效。\n\n"
                            + "确定继续删除吗？");
            if (!ok) {
                return;
            }
        } else if (choice != 0) {
            return;
        }
        deleteStaleNow(dirs);
    }

    /** 把引用清单与可执行的处置命令一起复制到剪贴板（无命令的只给说明）。 */
    private void copyRefCommands(List<RefScanner.Ref> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 指向转发器残留目录的引用清单（langmgr 生成）\n\n");
        for (RefScanner.Ref ref : refs) {
            sb.append("# ").append(ref.source).append("\n");
            sb.append("#   ").append(ref.detail).append("\n");
            if (ref.fixCommand != null) {
                sb.append(ref.fixCommand).append("\n");
            } else {
                sb.append("#   ").append(ref.action).append("\n");
            }
            sb.append("\n");
        }
        Theme.copy(this, sb.toString(), "引用清单与处置命令");
    }

    /** 真正执行删除（白名单校验在 EnvApplier 内）。 */
    private void deleteStaleNow(List<String> dirs) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<String, Void> w = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return EnvApplier.deleteStaleForwarderDirs(dirs);
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                String err;
                try {
                    err = get();
                } catch (Exception ex) {
                    err = "删除异常：" + ex.getMessage();
                }
                if (err != null) {
                    Theme.error(DashboardPanel.this, "删除失败", err);
                    return;
                }
                Theme.toast(DashboardPanel.this, "已删除转发器残留目录");
                model.rescan();
            }
        };
        w.execute();
    }

    /**
     * 「PATH 转发器拦截」问题行：把"先命中的"和"实际在用的"两条路径并排摆出来。
     * 一整段散文里塞两个长路径既难读、换行也很难看，拆成两行对照更清楚。
     */
    private JPanel forwarderRow(Detected.LangResult r) {
        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.add(Theme.fixed(kvLine("PATH 先命中", r.forwarderPath, Theme.ERR)));
        block.add(Theme.vGap(2));
        block.add(Theme.fixed(kvLine("实际在用", r.runningPath(), Theme.OK)));
        block.add(Theme.vGap(6));
        block.add(Theme.wrapText("转发器写在系统级 PATH 里，系统级永远排在用户级之前，"
                        + "所以在用户级做任何切换都不会生效。点「处理」看移除步骤。",
                Theme.F_SMALL, Theme.TEXT_SUB));
        return issueRow(Theme.ERR, Theme.ERR_BG,
                r.spec.displayName + " · PATH 转发器拦截", block, null,
                () -> gotoForwarder.accept(r.spec.id));
    }

    private JPanel quickActions(Detected.Report report) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JButton b1 = Theme.primaryButton("重新扫描环境");
        b1.addActionListener(e -> model.rescan());
        JButton b2 = Theme.ghostButton("版本切换");
        b2.addActionListener(e -> navigate.accept("switch"));
        JButton b3 = Theme.ghostButton("依赖管理");
        b3.addActionListener(e -> navigate.accept("deps"));
        JButton b4 = Theme.ghostButton("生成诊断报告");
        b4.addActionListener(e -> navigate.accept("report"));
        p.add(b1);
        p.add(b2);
        p.add(b3);
        p.add(b4);
        return p;
    }

    /** 把长路径显示成 …\a\b\c 形式。 */
    static String shorten(String path) {
        if (path == null || path.isEmpty()) {
            return "—";
        }
        if (path.length() <= 62) {
            return path;
        }
        return "…" + path.substring(path.length() - 60);
    }
}
