package com.langmgr.ui;

import com.langmgr.core.Detected;
import com.langmgr.core.Os;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主窗口：左侧导航栏 + 右侧内容区（CardLayout 切换页面）。
 */
public class MainFrame extends JFrame implements AppModel.Listener {

    /** 导航项定义。图标用 {@link NavIcons} 按功能矢量绘制，不依赖字体字形。 */
    private static final class NavItem {
        final String key;
        final String label;

        NavItem(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static final NavItem[] NAV = {
            new NavItem("dashboard", "环境概览"),
            new NavItem("languages", "语言与解释器"),
            new NavItem("switch",    "版本切换"),
            new NavItem("deps",      "依赖管理"),
            new NavItem("env",       "环境变量"),
            new NavItem("report",    "诊断报告"),
    };

    /**
     * 按功能矢量绘制的导航小图标（14px，2px 圆头线条）。
     * 不再使用 Unicode 字符——雅黑里缺字形的符号会显示成方块 □。
     */
    private static final class NavIcons {
        private NavIcons() {
        }

        /** 在 (x, y) 处绘制 key 对应的 size 见方图标，颜色随选中/悬停变化由调用方传入。 */
        static void paint(Graphics2D g, String key, int x, int y, int s, Color c) {
            g.setColor(c);
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = x + s / 2;
            int cy = y + s / 2;
            switch (key) {
                case "dashboard": // 概览：2×2 仪表格
                    int cell = (s - 4) / 2;
                    g.fillRoundRect(x + 1, y + 1, cell, cell, 3, 3);
                    g.fillRoundRect(x + s - cell - 1, y + 1, cell, cell, 3, 3);
                    g.fillRoundRect(x + 1, y + s - cell - 1, cell, cell, 3, 3);
                    g.drawRoundRect(x + s - cell - 1, y + s - cell - 1, cell, cell, 3, 3);
                    break;
                case "languages": // 语言与解释器：< / > 代码符号
                    g.drawLine(x + 5, y + 4, x + 1, cy);
                    g.drawLine(x + 1, cy, x + 5, y + s - 4);
                    g.drawLine(x + s - 5, y + 4, x + s - 1, cy);
                    g.drawLine(x + s - 1, cy, x + s - 5, y + s - 4);
                    g.drawLine(cx - 2, y + 3, cx + 2, y + s - 3);
                    break;
                case "switch": // 版本切换：上下双向箭头
                    g.drawLine(x + 5, y + s - 2, x + 5, y + 2);
                    g.drawLine(x + 2, y + 5, x + 5, y + 2);
                    g.drawLine(x + 5, y + 2, x + 8, y + 5);
                    g.drawLine(x + s - 5, y + 2, x + s - 5, y + s - 2);
                    g.drawLine(x + s - 8, y + s - 5, x + s - 5, y + s - 2);
                    g.drawLine(x + s - 5, y + s - 2, x + s - 2, y + s - 5);
                    break;
                case "deps": // 依赖管理：包裹箱
                    g.drawRoundRect(x + 1, y + 2, s - 2, s - 4, 3, 3);
                    g.drawLine(x + 1, y + 5, x + s - 1, y + 5);
                    g.drawLine(cx, y + 5, cx, y + 8);
                    break;
                case "env": // 环境变量：齿轮
                    g.drawOval(x + 3, y + 3, s - 6, s - 6);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        g.drawLine((int) Math.round(cx + Math.cos(a) * (s / 2.0 - 1)),
                                (int) Math.round(cy + Math.sin(a) * (s / 2.0 - 1)),
                                (int) Math.round(cx + Math.cos(a) * (s / 2.0 - 3)),
                                (int) Math.round(cy + Math.sin(a) * (s / 2.0 - 3)));
                    }
                    break;
                case "report": // 诊断报告：柱状图
                    g.drawLine(x + 1, y + s - 2, x + s - 1, y + s - 2);
                    g.drawLine(x + 4, y + s - 2, x + 4, y + s - 8);
                    g.drawLine(cx, y + s - 2, cx, y + 3);
                    g.drawLine(x + s - 4, y + s - 2, x + s - 4, y + s - 6);
                    break;
                default: // 兜底：圆点
                    g.fillOval(x + 4, y + 4, s - 8, s - 8);
                    break;
            }
            g.setStroke(old);
        }
    }

    private final AppModel model;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    /** 1px 表面重建期间忽略自身触发的 resize，避免 timer 自激。 */
    private long ignoreResizeUntil;
    /** resize 去抖：连续尺寸变化停 140ms 后整窗重绘一次（清除切换期间的旧帧残段） */
    private final Timer resizeRepaintTimer = new Timer(140, e -> {
        content.revalidate();
        repaint();
        // 强制 AWT/DWM 重建窗口表面：窗口化状态下把高度微调 1px 再恢复。
        // 现象“残段不在脏区内、repaint 无效”说明问题在 Swing 交付给 DWM 之后，
        // 仅靠 repaint 不够，需要触发一次表面重建。
        if (isShowing() && (getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            ignoreResizeUntil = System.currentTimeMillis() + 400;
            Dimension size = getSize();
            setSize(size.width, size.height + 1);
            setSize(size);
        }
    });
    private final Map<String, NavButton> navButtons = new LinkedHashMap<>();
    private final JLabel statusLabel = new JLabel("");
    private final JProgressBar statusProgress = new JProgressBar(0, 100);
    private final JButton rescanButton = Theme.primaryButton("重新扫描");

    private final DashboardPanel dashboardPanel;
    private final LanguagePanel languagePanel;
    private final SwitchPanel switchPanel;
    private final DependencyPanel dependencyPanel;
    private final EnvPanel envPanel;
    private final ReportPanel reportPanel;

    public MainFrame(AppModel model) {
        super("多语言环境管理器");
        this.model = model;
        model.addListener(this);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 700));
        setSize(1340, 840);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG_APP);
        setLayout(new BorderLayout());
        // 标题栏 / 任务栏图标：默认自绘，支持用户自定义（界面设置里改）
        setIconImages(AppIcon.icons());

        dashboardPanel  = new DashboardPanel(model, this::showPage, this::gotoSwitchForwarder);
        languagePanel   = new LanguagePanel(model);
        switchPanel     = new SwitchPanel(model);
        dependencyPanel = new DependencyPanel(model);
        envPanel        = new EnvPanel(model);
        reportPanel     = new ReportPanel(model);

        add(buildSidebar(), BorderLayout.WEST);
        add(buildMain(), BorderLayout.CENTER);

        // 尺寸变化（最大化/还原/拖拽边框）后整窗重绘：
        // Nimbus + 自绘卡片边框在 resize 布局切换时可能以旧边界绘制并残留旧帧像素
        //（表现为页签/页眉区出现卡片边框色的细竖线残段），布局稳定后全量重绘即可清除。
        resizeRepaintTimer.setRepeats(false);
        resizeRepaintTimer.setCoalesce(true);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // timer 自己 setSize(h+1)/setSize(h) 也会触发这里；
                // 不屏蔽的话会不断 restart，形成 140ms 一次的自激循环。
                if (System.currentTimeMillis() >= ignoreResizeUntil) {
                    resizeRepaintTimer.restart();
                }
            }
        });
        // 最大化/还原/最小化切换瞬间立即整窗重绘：切换过程中布局短暂处于中间态，
        // 可能以旧边界画出卡片边框并残留为 DWM 旧帧
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowStateChanged(java.awt.event.WindowEvent e) {
                content.revalidate();
                repaint();
            }
        });

        showPage("dashboard");
    }

    /* ---------------------------------------------------------- 侧栏 */

    private JComponent buildSidebar() {
        JPanel side = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.BG_SIDEBAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        side.setOpaque(false);
        side.setPreferredSize(new Dimension(212, 0));
        // 右侧竖分隔线，与内容区区分（浅色侧栏 + 白色分隔线）
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));

        // 品牌区：图标用程序自绘的 AppIcon（与窗口标题栏一致），不再依赖字体字形
        JPanel brand = new JPanel(new BorderLayout());
        brand.setOpaque(false);
        brand.setBorder(BorderFactory.createEmptyBorder(22, 20, 18, 16));
        JLabel logo = new JLabel("多语言环境管理器",
                new ImageIcon(com.langmgr.ui.AppIcon.current(20)), SwingConstants.LEFT);
        logo.setFont(Theme.bold(14.5f));
        logo.setForeground(Theme.TEXT_MAIN);
        logo.setIconTextGap(8);
        brand.add(logo, BorderLayout.NORTH);
        JLabel sub = new JLabel("检测 · 切换 · 依赖 · 环境");
        sub.setFont(Theme.F_TINY);
        sub.setForeground(Theme.TEXT_MUTED);
        sub.setBorder(BorderFactory.createEmptyBorder(6, 1, 0, 0));
        brand.add(sub, BorderLayout.CENTER);
        side.add(brand, BorderLayout.NORTH);

        // 导航项
        JPanel navPanel = new JPanel();
        navPanel.setLayout(new BoxLayout(navPanel, BoxLayout.Y_AXIS));
        navPanel.setOpaque(false);
        navPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        for (NavItem item : NAV) {
            NavButton b = new NavButton(item);
            navButtons.put(item.key, b);
            navPanel.add(b);
            navPanel.add(Box.createRigidArea(new Dimension(0, 3)));
        }
        side.add(navPanel, BorderLayout.CENTER);

        // 底部系统信息
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createEmptyBorder(12, 20, 16, 16));
        JTextArea ta = new JTextArea(Os.label()
                + "\nJava " + System.getProperty("java.version")
                + "\n扫描注册语言 " + com.langmgr.core.LangSpec.all().size() + " 种");
        ta.setEditable(false);
        ta.setFocusable(false);  // 纯展示：点击不出现光标
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(Theme.F_TINY);
        ta.setForeground(Theme.TEXT_MUTED);
        ta.setOpaque(false);
        ta.setBackground(new Color(0, 0, 0, 0));
        ta.setBorder(null);
        foot.add(ta, BorderLayout.CENTER);
        side.add(foot, BorderLayout.SOUTH);

        return side;
    }

    /** 侧栏导航按钮（自绘，带选中态）。 */
    private final class NavButton extends JPanel {
        private final NavItem item;
        private boolean selected;
        private boolean hover;

        NavButton(NavItem item) {
            this.item = item;
            setLayout(new BorderLayout());
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.px(40)));
            setPreferredSize(new Dimension(192, Theme.px(40)));
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { showPage(item.key); }
            });
        }

        void setSelected(boolean s) {
            this.selected = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 浅色风格：选中浅蓝圆角，悬停浅灰圆角
            if (selected) {
                g2.setColor(Theme.BG_SIDEBAR_SEL);
                g2.fillRoundRect(2, 2, getWidth() - 6, getHeight() - 4, 10, 10);
            } else if (hover) {
                g2.setColor(Theme.BG_SIDEBAR_HOVER);
                g2.fillRoundRect(2, 2, getWidth() - 6, getHeight() - 4, 10, 10);
            }
            // 文字与图标都以行高中心对齐，避免图标偏上
            g2.setFont(Theme.font(13));
            FontMetrics fm = g2.getFontMetrics();
            int textBaseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int iconCenterY = getHeight() / 2;
            Color iconColor = selected ? Theme.ACCENT : (hover ? Theme.TEXT_MAIN : Theme.TEXT_SUB);
            NavIcons.paint(g2, item.key, 14, iconCenterY - 7, 14, iconColor);
            g2.setFont(selected ? Theme.bold(13) : Theme.font(13));
            g2.setColor(selected ? Theme.ACCENT_DARK : (hover ? Theme.TEXT_MAIN : Theme.TEXT_SUB));
            g2.drawString(item.label, 36, textBaseline);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /* ---------------------------------------------------------- 主区 */

    private JComponent buildMain() {
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Theme.BG_APP);
        main.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        // 顶栏
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Theme.BG_APP);
        top.setBorder(BorderFactory.createEmptyBorder(16, 24, 10, 24));
        top.setPreferredSize(new Dimension(0, 62));

        JLabel appName = new JLabel("本机开发环境一站式管理");
        appName.setFont(Theme.bold(15));
        appName.setForeground(Theme.TEXT_SUB);
        top.add(appName, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        statusProgress.setPreferredSize(new Dimension(150, 6));
        statusProgress.setStringPainted(false);
        statusProgress.setBorderPainted(false);
        statusProgress.setBackground(Theme.GRAY_BG);
        statusProgress.setForeground(Theme.ACCENT);
        statusProgress.setVisible(false);
        right.add(statusProgress);
        statusLabel.setFont(Theme.F_SMALL);
        statusLabel.setForeground(Theme.TEXT_SUB);
        right.add(statusLabel);
        JButton settings = Theme.ghostButton("界面设置");
        settings.setToolTipText("切换界面字体、自定义窗口标题图标");
        settings.addActionListener(e -> new SettingsDialog(this).setVisible(true));
        right.add(settings);
        rescanButton.addActionListener(e -> {
            rescanButton.setEnabled(false);
            model.rescan();
        });
        right.add(rescanButton);
        top.add(right, BorderLayout.EAST);
        main.add(top, BorderLayout.NORTH);

        content.setBackground(Theme.BG_APP);
        content.add(dashboardPanel, "dashboard");
        content.add(languagePanel, "languages");
        content.add(switchPanel, "switch");
        content.add(dependencyPanel, "deps");
        content.add(envPanel, "env");
        content.add(reportPanel, "report");
        main.add(content, BorderLayout.CENTER);
        return main;
    }

    /** 切换页面。 */
    public void showPage(String key) {
        for (Map.Entry<String, NavButton> e : navButtons.entrySet()) {
            e.getValue().setSelected(e.getKey().equals(key));
        }
        cards.show(content, key);
        // 同理：卡片切换后整体重绘，避免上一页自绘边线的像素残留
        content.repaint();
        // 各面板按需刷新
        Detected.Report r = model.report();
        switch (key) {
            case "dashboard": dashboardPanel.refresh(r); break;
            case "languages": languagePanel.refresh(r); break;
            case "switch":    switchPanel.refresh(r); break;
            case "deps":      dependencyPanel.refresh(r); break;
            case "env":       envPanel.refresh(r); break;
            case "report":    reportPanel.refresh(r); break;
            default: break;
        }
        content.revalidate();
        content.repaint();
    }

    /* --------------------------------------------------- 扫描状态回调 */

    @Override
    public void onScanning(String currentLang, int done, int total) {
        SwingUtilities.invokeLater(() -> {
            rescanButton.setEnabled(false);
            statusProgress.setVisible(true);
            statusProgress.setMaximum(Math.max(1, total));
            statusProgress.setValue(done);
            statusLabel.setText("正在检测 " + currentLang + "（" + done + "/" + total + "）");
        });
    }

    @Override
    public void onScanned(Detected.Report report) {
        SwingUtilities.invokeLater(() -> {
            rescanButton.setEnabled(true);
            statusProgress.setVisible(false);
            if (report == null) {
                statusLabel.setText("扫描失败：" + model.lastError());
            } else {
                statusLabel.setText("扫描完成 · " + report.installedCount() + " 种语言可用 · 耗时 "
                        + (report.totalMillis / 1000.0) + "s");
                // 刷新当前页面
                String current = currentCard();
                if (current != null) {
                    showPage(current);
                }
            }
        });
    }

    private String currentCard() {
        for (Map.Entry<String, NavButton> e : navButtons.entrySet()) {
            if (e.getValue().selected) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 供各面板请求"跳转到某页并带上参数"。 */
    public void gotoSwitchWith(String langId) {
        // 先 refresh 目标页（填充列表），再定位语言；顺序反过来首次点击会 no-op
        showPage("switch");
        switchPanel.focusOn(langId);
    }

    /**
     * 跳到"版本切换"并选中该语言的转发器条目。
     * 用于"PATH 转发器拦截"这类问题：软件无法自动处理，
     * 就把用户带到变更计划预览的说明面板——那里解释原因并提供解决方案。
     */
    public void gotoSwitchForwarder(String langId) {
        showPage("switch");
        switchPanel.selectForwarder(langId);
    }

    /**
     * 重建主窗口：字体 / 图标设置应用后调用。
     * 现有组件仍持有旧的 Font 对象，逐个替换不可靠，直接换一个新窗口最干净。
     * 扫描状态保存在共享的 AppModel 里，不会丢失。
     */
    public void recreate() {
        final AppModel m = model;
        m.removeListener(this);
        dispose();
        SwingUtilities.invokeLater(() -> {
            MainFrame f = new MainFrame(m);
            f.setVisible(true);
            Theme.toast(f, "界面设置已应用");
        });
    }

    public void gotoDepsWith(String langId) {
        showPage("deps");
        dependencyPanel.focusOn(langId);
    }

    public List<String> navKeys() {
        return new ArrayList<>(navButtons.keySet());
    }
}
