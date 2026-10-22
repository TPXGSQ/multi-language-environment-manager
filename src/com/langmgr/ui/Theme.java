package com.langmgr.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 界面主题：配色、字体与通用 Swing 组件。
 *
 * <p>Swing 默认外观偏"上世纪"，这里用自绘组件统一成扁平深色侧栏 + 浅色内容区的现代风格，
 * 且不引入任何第三方外观库，保证只用 JDK 就能编译运行。
 */
public final class Theme {

    /* ------------------------------------------------------------ 配色 */

    public static final Color BG_APP      = new Color(0xF4, 0xF6, 0xF9);
    public static final Color BG_CARD     = Color.WHITE;
    /** 侧栏：浅色背景（深色文字），与内容区用一条竖分隔线区分。 */
    public static final Color BG_SIDEBAR  = new Color(0xF9, 0xFA, 0xFC);
    public static final Color BG_SIDEBAR_SEL = new Color(0xE2, 0xEB, 0xFB);
    public static final Color BG_SIDEBAR_HOVER = new Color(0xEF, 0xF2, 0xF7);
    public static final Color BG_HOVER    = new Color(0xEC, 0xF0, 0xF6);
    public static final Color BG_CODE     = new Color(0xF7, 0xF8, 0xFA);

    public static final Color TEXT_MAIN   = new Color(0x1F, 0x26, 0x30);
    public static final Color TEXT_SUB    = new Color(0x6B, 0x76, 0x84);
    public static final Color TEXT_MUTED  = new Color(0x9A, 0xA4, 0xB2);
    public static final Color TEXT_ON_DARK= new Color(0xE6, 0xEA, 0xF0);
    public static final Color TEXT_DARK_SUB = new Color(0x8C, 0x99, 0xA8);

    public static final Color LINE        = new Color(0xE2, 0xE7, 0xEE);
    public static final Color ACCENT      = new Color(0x2F, 0x6F, 0xED);
    public static final Color ACCENT_DARK = new Color(0x1F, 0x55, 0xC4);

    public static final Color OK          = new Color(0x1F, 0xA8, 0x68);
    public static final Color OK_BG       = new Color(0xE6, 0xF6, 0xEE);
    public static final Color WARN        = new Color(0xE0, 0x8A, 0x00);
    public static final Color WARN_BG     = new Color(0xFD, 0xF3, 0xDF);
    public static final Color ERR         = new Color(0xD9, 0x3B, 0x3B);
    public static final Color ERR_BG      = new Color(0xFB, 0xEA, 0xEA);
    public static final Color INFO        = new Color(0x2F, 0x6F, 0xED);
    public static final Color INFO_BG     = new Color(0xE9, 0xF0, 0xFE);
    public static final Color GRAY_BG     = new Color(0xEE, 0xF1, 0xF5);

    /* ------------------------------------------------------------ 字体 */

    /**
     * 当前界面字体族。
     * 初始值 = 用户偏好里保存的选择（若已卸载则忽略）→ 自动挑选中文友好字体。
     * 可在「界面设置」中切换，保存在用户偏好里，重启后仍然生效。
     */
    private static String fontFamily = initialFontFamily();

    /**
     * 全局字体缩放系数（0.85 ~ 1.35），保存在用户偏好里。
     * font()/bold()/mono() 统一乘以该系数；固定像素的组件尺寸经 {@link #px(int)} 同步缩放。
     * 注意：必须声明在 static 初始化块之前——Java 按文本顺序执行静态初始化，
     * 否则 refreshFonts() 运行时本字段还是默认值 0，所有字号常量会被算成 0。
     */
    private static float fontScale = Math.max(0.8f, Math.min(1.4f, prefs().getFloat("fontScale", 1.0f)));

    /** 界面字号常量。切换字体后由 {@link #refreshFonts()} 重建。 */
    public static Font F_TITLE;
    public static Font F_H2;
    public static Font F_H3;
    public static Font F_BODY;
    public static Font F_SMALL;
    public static Font F_TINY;
    public static Font F_MONO;
    public static Font F_MONO_SM;

    static {
        refreshFonts();
    }

    private static java.util.prefs.Preferences prefs() {
        return java.util.prefs.Preferences.userNodeForPackage(Theme.class);
    }

    /** 持久化偏好的读取入口（供 AppIcon 等共用同一节点）。 */
    public static java.util.prefs.Preferences uiPrefs() {
        return prefs();
    }

    private static String initialFontFamily() {
        String saved = prefs().get("fontFamily", null);
        if (saved != null && isInstalledFamily(saved)) {
            return saved;
        }
        return pickFontFamily();
    }

    /** 字体族列表在进程内不会变，缓存避免每次创建组件都全量枚举。 */
    private static volatile String[] FAMILIES;
    private static volatile String MONO_FAMILY;
    private static volatile java.util.List<String> CHINESE_FAMILIES;

    private static String[] families() {
        String[] f = FAMILIES;
        if (f == null) {
            f = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            FAMILIES = f;
        }
        return f;
    }

    static boolean isInstalledFamily(String name) {
        if (name == null) {
            return false;
        }
        for (String a : families()) {
            if (a.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static String fontFamily() {
        return fontFamily;
    }

    /**
     * 切换界面字体族：保存偏好并重建字号常量与 UIManager 字体默认值。
     * 已显示的组件仍持有旧 Font 对象，调用方应随后重建窗口（MainFrame.recreate()）。
     */
    public static void applyFontFamily(String name) {
        if (name == null || !isInstalledFamily(name)) {
            return;
        }
        fontFamily = name;
        prefs().put("fontFamily", name);
        refreshFonts();
        pushUiFonts();
    }

    private static void refreshFonts() {
        F_TITLE   = bold(22);
        F_H2      = bold(16);
        F_H3      = bold(13);
        F_BODY    = font(13);
        F_SMALL   = font(11.5f);
        F_TINY    = font(10.5f);
        F_MONO    = mono(12);
        F_MONO_SM = mono(11);
    }

    /** 列出系统已安装且能显示中文的字体族，常见中文 UI 字体排在前面。 */
    public static java.util.List<String> chineseCapableFamilies() {
        java.util.List<String> cached = CHINESE_FAMILIES;
        if (cached != null) {
            return new java.util.ArrayList<>(cached);
        }
        String[] curated = {"Microsoft YaHei UI", "Microsoft YaHei", "DengXian", "SimHei",
                "Microsoft JhengHei UI", "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC",
                "Segoe UI", "SimSun", "NSimSun", "KaiTi", "FangSong", "Dialog"};
        java.util.List<String> installed = new java.util.ArrayList<>();
        for (String a : families()) {
            if (!installed.contains(a) && new Font(a, Font.PLAIN, 12).canDisplay('语')) {
                installed.add(a);
            }
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String c : curated) {
            for (String a : installed) {
                if (a.equalsIgnoreCase(c)) {
                    out.add(a);
                    break;
                }
            }
        }
        for (String a : installed) {
            if (!out.contains(a)) {
                out.add(a);
            }
        }
        CHINESE_FAMILIES = out;
        return new java.util.ArrayList<>(out);
    }

    /**
     * 从候选符号中挑一个指定字体能显示的（避免界面出现豆腐块 □）。
     * 都显示不了时返回空串，调用方应省略图标。
     */
    public static String pickIcon(Font font, String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && font.canDisplayUpTo(c) < 0) {
                return c;
            }
        }
        return "";
    }

    private static String pickFontFamily() {
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
                "Noto Sans CJK SC", "Source Han Sans SC", "Dialog"};
        String[] available = families();
        for (String c : candidates) {
            for (String a : available) {
                if (a.equalsIgnoreCase(c)) {
                    return a;
                }
            }
        }
        return Font.SANS_SERIF;
    }

    /**
     * fontScale 的声明已前移到类首部（静态初始化顺序要求），此处只保留操作方法。
     */
    public static float fontScale() {
        return fontScale;
    }

    /** 切换字体缩放：保存偏好并重建字号常量与 UIManager 默认值（随后应重建窗口）。 */
    public static void applyFontScale(float scale) {
        fontScale = Math.max(0.8f, Math.min(1.4f, scale));
        prefs().putFloat("fontScale", fontScale);
        refreshFonts();
        pushUiFonts();
    }

    /** 按字体缩放换算像素尺寸——按钮高度、徽章高度、表格行高等固定尺寸都经这里。 */
    public static int px(int base) {
        return Math.round(base * fontScale);
    }

    public static Font font(float size) {
        return new Font(fontFamily, Font.PLAIN, Math.round(size * fontScale));
    }

    public static Font bold(float size) {
        return new Font(fontFamily, Font.BOLD, Math.round(size * fontScale));
    }

    /**
     * 等宽字体（用于路径、日志、报告预览）。
     *
     * <p>注意：Consolas、JetBrains Mono 等纯西文等宽字体不含中文字形，
     * Swing 对物理字体不做跨字体回退，中文会渲染成豆腐块 □。
     * 因此逐个候选做 canDisplay('语') 检查，不满足就直接落到
     * 逻辑字体 Monospaced——它由 JDK 字体配置合成，中西文都能正常显示。
     */
    public static Font mono(float size) {
        String family = MONO_FAMILY;
        if (family == null) {
            String[] candidates = {"Cascadia Mono", "Consolas", "JetBrains Mono", "Menlo", "Monospaced"};
            family = Font.MONOSPACED;
            for (String c : candidates) {
                boolean match = false;
                for (String a : families()) {
                    if (a.equalsIgnoreCase(c)) {
                        match = true;
                        break;
                    }
                }
                if (match && new Font(c, Font.PLAIN, 12).canDisplay('语')) {
                    family = c;
                    break;
                }
            }
            MONO_FAMILY = family;
        }
        return new Font(family, Font.PLAIN, Math.round(size * fontScale));
    }

    private Theme() {
    }

    /* ---------------------------------------------------------- 通用组件 */

    /** 圆角卡片容器，带浅色描边与内边距。 */
    public static JPanel card(LayoutManager lm) {
        JPanel p = new JPanel(lm == null ? new BorderLayout() : lm) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_CARD);
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
                g2.setColor(LINE);
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 2, getHeight() - 2, 14, 14));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        return p;
    }

    /** 圆角状态徽章。 */
    public static JComponent badge(String text, Color fg, Color bg) {
        JLabel l = new JLabel(text, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setOpaque(false);
        l.setForeground(fg);
        l.setFont(F_TINY);
        l.setBorder(BorderFactory.createEmptyBorder(2, 9, 3, 9));
        // 同样按实际字体测量，避免中文字符宽度被低估导致截断
        int w = l.getFontMetrics(F_TINY).stringWidth(text) + px(24);
        l.setPreferredSize(new Dimension(Math.max(px(46), w), px(20)));
        return l;
    }

    /** 主色按钮。 */
    public static JButton primaryButton(String text) {
        return styledButton(text, ACCENT, Color.WHITE, ACCENT_DARK);
    }

    /** 次级按钮（白底描边）。 */
    public static JButton ghostButton(String text) {
        JButton b = styledButton(text, Color.WHITE, TEXT_MAIN, new Color(0xE4, 0xE9, 0xF0));
        b.setBorderPainted(true);
        return b;
    }

    /** 危险按钮。 */
    public static JButton dangerButton(String text) {
        return styledButton(text, new Color(0xFB, 0xEC, 0xEC), ERR, new Color(0xF5, 0xDD, 0xDD));
    }

    private static JButton styledButton(String text, Color bg, Color fg, Color hover) {
        final Color baseBg = bg;
        final Color baseFg = fg;
        final Color hoverBg = hover;
        JButton b = new JButton(text) {
            private boolean over;
            private boolean down;

            {
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) { over = true; repaint(); }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) { over = false; down = false; repaint(); }
                    @Override public void mousePressed(java.awt.event.MouseEvent e) { down = true; repaint(); }
                    @Override public void mouseReleased(java.awt.event.MouseEvent e) { down = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                // 完全自绘：不能调 super——Nimbus 的 ButtonPainter 会在悬停时
                // 叠加一层蓝色立体背景，把我们要的浅色 hover 盖掉
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color c = down || over ? hoverBg : baseBg;
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                if (isEnabled()) {
                    g2.setColor(over ? hoverBg : new Color(0xD8, 0xDF, 0xE8));
                    g2.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 2, getHeight() - 2, 10, 10));
                }
                FontMetrics fm = g2.getFontMetrics(getFont());
                int tx = (getWidth() - fm.stringWidth(getText())) / 2;
                int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(isEnabled() ? baseFg : TEXT_MUTED);
                g2.drawString(getText(), tx, ty);
                g2.dispose();
            }
        };
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFont(F_BODY);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(px(7), px(16), px(8), px(16)));
        // 用实际字体测量宽度——中文在不同字体/字号下宽度差异很大，
        // 之前按 text.length()*9 估算会把"查看全部"这类按钮截断成"查看全…"。
        FontMetrics fm = b.getFontMetrics(F_BODY);
        b.setPreferredSize(new Dimension(Math.max(px(88), fm.stringWidth(text) + px(36)), px(32)));
        return b;
    }

    /** 只读多行文本区，等宽字体，用于日志与原始输出。 */
    public static JScrollPane textArea(String content, int rows) {
        JTextArea ta = new JTextArea(content == null ? "" : content);
        ta.setEditable(false);
        ta.setFocusable(false);  // 纯展示：点击不出现光标
        ta.setFont(F_MONO_SM);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(false);
        ta.setBackground(BG_CODE);
        ta.setForeground(TEXT_MAIN);
        ta.setCaretColor(ACCENT);
        ta.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        ta.setRows(rows);
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(BorderFactory.createLineBorder(LINE));
        sp.getViewport().setBackground(BG_CODE);
        return sp;
    }

    public static JTextArea areaOf(JScrollPane sp) {
        Component vp = sp.getViewport().getView();
        return vp instanceof JTextArea ? (JTextArea) vp : null;
    }

    /** 统一风格的表格。 */
    public static JTable table(Object[][] data, Object[] columns) {
        JTable t = new JTable(data, columns) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (c instanceof JComponent) {
                    ((JComponent) c).setOpaque(true);
                }
                c.setFont(F_SMALL);
                if (isRowSelected(row)) {
                    c.setBackground(new Color(0xDD, 0xE9, 0xFC));
                    c.setForeground(TABLE_CELL_FG);
                } else if (row % 2 == 1) {
                    c.setBackground(new Color(0xFA, 0xFB, 0xFD));
                    c.setForeground(TABLE_CELL_FG);
                } else {
                    c.setBackground(Color.WHITE);
                    c.setForeground(TABLE_CELL_FG);
                }
                return c;
            }

            @Override
            public int getRowHeight() {
                return px(26);
            }
        };
        t.setFillsViewportHeight(true);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setFont(F_H3);
        t.getTableHeader().setPreferredSize(new Dimension(0, px(32)));
        t.getTableHeader().setBackground(TABLE_HEADER_BG);
        t.getTableHeader().setForeground(TABLE_HEADER_FG);
        flatTableHeader(t);
        centerCells(t);
        t.setGridColor(LINE);
        return t;
    }

    /** 标题标签。 */
    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_TITLE);
        l.setForeground(TEXT_MAIN);
        return l;
    }

    /** 分区标题：左侧竖向强调条 + 大字，用于报告等长页面的分节。 */
    public static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT);
                g2.fillRoundRect(0, px(3), px(4), getHeight() - px(6), px(4), px(4));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(F_H2);
        l.setForeground(TEXT_MAIN);
        l.setBorder(BorderFactory.createEmptyBorder(0, px(14), 0, 0));
        return l;
    }

    public static JLabel h2(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_H2);
        l.setForeground(TEXT_MAIN);
        return l;
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_BODY);
        l.setForeground(TEXT_MAIN);
        return l;
    }

    public static JLabel sub(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_SMALL);
        l.setForeground(TEXT_SUB);
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_SMALL);
        l.setForeground(TEXT_MUTED);
        return l;
    }

    /** 更小的灰色说明文字。 */
    public static JLabel tiny(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_TINY);
        l.setForeground(TEXT_MUTED);
        return l;
    }

    /** 圆角描边。 */
    public static AbstractBorder roundLine(Color c) {
        return new AbstractBorder() {
            @Override
            public void paintBorder(Component comp, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 2, h - 2, 10, 10));
                g2.dispose();
            }

            @Override
            public Insets getBorderInsets(Component c) {
                return new Insets(6, 8, 6, 8);
            }
        };
    }

    /** 顶部提示条。 */
    public static JPanel notice(String text, Color fg, Color bg) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 9, 12));
        // 用可换行的 JTextArea：JLabel 永不换行，窗口一窄长行（长路径、长说明）就被裁掉。
        // 此前担心 Nimbus 白底问题——用 setOpaque(false)+透明背景+无边框即可根治，
        // 行高与自动换行由 JTextArea 自己算，比 HTML JLabel 的首选高度更可靠。
        JTextArea ta = new JTextArea(text);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setFocusable(false);  // 纯展示：点击不出现光标
        ta.setOpaque(false);
        ta.setBackground(new Color(0, 0, 0, 0));
        ta.setBorder(null);
        ta.setFont(F_SMALL);
        ta.setForeground(fg);
        p.add(ta, BorderLayout.CENTER);
        return p;
    }

    /**
     * 自动换行、且首选高度正确的纯文本块（无背景、无边框、不可选中）。
     *
     * <p>普通 {@code JTextArea} 在宽度未知时，首选高度只按「一行」计算，
     * 放进 BoxLayout 后行高就定死了一行——文本被裁成半截，这正是
     * 「窗口化下说明文字显示不全」的根因。这里改成按当前可用宽度实测行数，
     * 让父容器每次都拿到真实高度。
     */
    public static JTextArea wrapText(String text, Font font, Color fg) {
        JTextArea ta = new WrapTextArea(text == null ? "" : text);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setFocusable(false);             // 纯展示：点击不出现光标
        ta.setOpaque(false);
        ta.setBackground(new Color(0, 0, 0, 0));  // Windows LAF 仍会自绘白底
        ta.setBorder(null);
        ta.setFont(font);
        ta.setForeground(fg);
        return ta;
    }

    /** 首选高度按实际宽度实测的 JTextArea（详见 {@link #wrapText}）。 */
    private static final class WrapTextArea extends JTextArea {
        /** 不在组件树里的影子文本区：用来按任意宽度试排文字。 */
        private final JTextArea shadow = new JTextArea();
        private int lastWidth = -1;

        WrapTextArea(String text) {
            super(text);
            shadow.setLineWrap(true);
            shadow.setWrapStyleWord(true);
            shadow.setBorder(null);
        }

        /**
         * 在影子文本区上按指定宽度试排，返回实际需要的高度。
         * 不能直接 setSize(this)——布局过程中改自身尺寸会让 BoxLayout 的
         * 缓存请求数组失效，抛 NPE；影子组件不在任何容器里，随便改。
         */
        private int measuredHeight(int width) {
            shadow.setFont(getFont());
            shadow.setText(getText());
            shadow.setSize(Math.max(1, width), Short.MAX_VALUE);
            return shadow.getPreferredSize().height;
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getWidth() > 0 ? getWidth() : 260;
            return new Dimension(w, measuredHeight(w) + 2);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(1, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            // 宽度必须放开：若跟着 preferred 走，BoxLayout 会把组件钉死在
            // 首次估算的宽度上，之后宽度再也变不过来，行高也就永远不更新
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void setBounds(int x, int y, int w, int h) {
            super.setBounds(x, y, w, h);
            // 宽度变了 → 折行行数可能变 → 让父容器重新询问首选高度。
            // 宽度不变则什么都不做，避免 setBounds/revalidate 互相抖动。
            if (w > 0 && w != lastWidth) {
                lastWidth = w;
                Container p = getParent();
                if (p != null) {
                    p.revalidate();
                }
            }
        }
    }

    /**
     * 单行标签：宽度不够时中间用 … 省略（路径的尾段更重要），悬停显示全文。
     * 用于把长路径塞进固定宽度的卡片而不撑破布局。
     */
    public static JLabel elide(String text) {
        final String full = text == null ? "" : text;
        JLabel l = new JLabel(full) {
            @Override
            protected void paintComponent(Graphics g) {
                Insets in = getInsets();
                int avail = getWidth() - in.left - in.right;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                FontMetrics fm = g2.getFontMetrics(getFont());
                String t = fm.stringWidth(full) > avail ? middleElide(full, fm, avail) : full;
                g2.setColor(getForeground());
                int ty = in.top + (getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(t, in.left, ty);
                g2.dispose();
            }
        };
        l.setFont(F_MONO_SM);
        l.setForeground(TEXT_MAIN);
        l.setToolTipText(full);
        l.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return l;
    }

    /** 中间省略：保留头部 30% + 尾部 70%（文件名在末尾，更该留住）。 */
    private static String middleElide(String text, FontMetrics fm, int avail) {
        String dots = "…";
        int keep = avail - fm.stringWidth(dots);
        if (keep <= 0) {
            return dots;
        }
        int tailW = Math.round(keep * 0.7f);
        int headW = keep - tailW;
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (fm.stringWidth(head.toString() + text.charAt(i)) > headW) {
                break;
            }
            head.append(text.charAt(i));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = text.length() - 1; i >= 0; i--) {
            if (fm.stringWidth(text.charAt(i) + tail.toString()) > tailW) {
                break;
            }
            tail.insert(0, text.charAt(i));
        }
        return head + dots + tail;
    }

    /**
     * 空状态提示卡：白底圆角 + 左侧强调条 + 标题 + 说明文字，
     * 与统计卡片同风格，用于「暂无数据」「一切正常」类占位。
     */
    public static JPanel emptyCard(String title, String body, Color accent) {
        JPanel p = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_CARD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(LINE);
                g2.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 14, 14);
                g2.setColor(accent);
                g2.fillRoundRect(0, 12, 3, getHeight() - 24, 3, 3);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 14));
        JLabel t = new JLabel(title);
        t.setFont(F_H3);
        t.setForeground(TEXT_MAIN);
        p.add(t, BorderLayout.NORTH);
        JPanel lines = new JPanel();
        lines.setOpaque(false);
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        for (String line : body.split("\n")) {
            JLabel l = new JLabel(line);
            l.setFont(F_SMALL);
            l.setForeground(TEXT_SUB);
            l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            lines.add(l);
        }
        p.add(lines, BorderLayout.CENTER);
        return p;
    }

    /** 纵向 Box 布局的便捷容器。 */
    public static JPanel vBox(int gap) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        if (gap > 0) {
            p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        }
        return p;
    }

    /**
     * 纵向 Box 布局 + 跟踪视口宽度：用于放进 {@link #scroll(Component)} 的内容面板。
     * 普通 JPanel 不实现 Scrollable，内含长文本/长路径时首选宽度会被撑到数万像素，
     * 视口不约束其宽度，内容横向溢出被裁剪；实现跟踪后宽度恒等于视口宽度。
     */
    /** 跟踪视口宽度的纵向面板（匿名类无法追加接口，故用具名内部类）。 */
    private static final class TrackingVBox extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return Math.max(100, r.height - 20);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    public static JPanel scrollableVBox() {
        JPanel p = new TrackingVBox();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    public static Component vGap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    public static Component hGap(int w) {
        return Box.createRigidArea(new Dimension(w, 0));
    }

    /** 让组件在 BoxLayout 下不被纵向拉伸。 */
    public static Component fixed(Component c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        // 统一左锚定：JLabel 默认文字居中、组件默认 alignmentX 居中，
        // 垂直滚动条出现/消失会改变视口宽度，居中内容随之左右漂移
        //（诊断报告页「生成时间/总览」停留后左移即此原因）；全部固定为左对齐
        if (c instanceof JLabel) {
            ((JLabel) c).setHorizontalAlignment(SwingConstants.LEFT);
        }
        if (c instanceof JComponent) {
            ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return c;
    }

    private static boolean installed;

    /** 全局 Swing 外观设置。 */
    public static void install() {
        if (installed) {
            return; // 监听器只能挂一次，重复 install 会重复替换滚动条 UI
        }
        installed = true;
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        UIManager.put("OptionPane.background", BG_CARD);
        UIManager.put("Panel.background", BG_APP);
        pushUiFonts();
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.background", BG_APP);
        UIManager.put("ScrollBar.thumb", new Color(0xC3, 0xCC, 0xD8));
        UIManager.put("ScrollBar.track", BG_APP);
        UIManager.put("ScrollPane.background", BG_CARD);
        UIManager.put("Viewport.background", BG_CARD);
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("MenuItem.font", F_BODY);
        UIManager.put("Menu.font", F_BODY);
        UIManager.put("CheckBox.font", F_BODY);
        UIManager.put("RadioButton.font", F_BODY);
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        // Nimbus 覆盖回我们要的配色
        UIManager.put("nimbusBase", ACCENT);
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusSelectionBackground", ACCENT);
        UIManager.put("control", BG_APP);
        UIManager.put("text", TEXT_MAIN);
        UIManager.put("nimbusLightBackground", BG_CARD);
        UIManager.put("Table.alternateRowColor", new Color(0xFA, 0xFB, 0xFD));
        UIManager.put("Table.selectionBackground", new Color(0xDD, 0xE9, 0xFC));
        UIManager.put("Table.selectionForeground", TEXT_MAIN);
        UIManager.put("Table.gridColor", LINE);
        UIManager.put("nimbusBorder", LINE);

        /* ---------- 页签（依赖管理 / 环境变量 / 诊断报告等页的 TabbedPane） ----------
         * 注意：Nimbus 不理会 UIManager.put 的 "TabbedPane:..." painter 键（实测无效），
         * 必须通过组件的 "Nimbus.Overrides" 客户端属性注入，见下方 flatTabs()。 */

        /* ---------- 滚动条 ----------
         * 同理，Nimbus 也会无视 UIManager.put 的 "ScrollBarUI"（实测仍会实例化
         * 裸 BasicScrollBarUI，导致出现方块箭头）。改为全局监听组件创建，
         * 每个 JScrollBar 一出现就换成我们的 ModernScrollBarUI。 */
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (ev instanceof java.awt.event.ContainerEvent
                    && ev.getID() == java.awt.event.ContainerEvent.COMPONENT_ADDED) {
                Component c = ((java.awt.event.ContainerEvent) ev).getChild();
                if (c instanceof JScrollBar) {
                    // 延迟到队列尾：组件仍在构造中，立即 setUI 有初始化顺序风险
                    SwingUtilities.invokeLater(() -> {
                        try {
                            JScrollBar sb = (JScrollBar) c;
                            if (!(sb.getUI() instanceof ModernScrollBarUI)) {
                                sb.setUI(new ModernScrollBarUI());
                            }
                        } catch (RuntimeException ignored) {
                            // 布局与替换竞态的兜底，不影响程序运行
                        }
                    });
                }
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    /**
     * 把 TabbedPane 换成白/灰扁平样式（选中白底、未选浅灰、悬停稍深）。
     * Nimbus 的 painter 只认组件级 "Nimbus.Overrides" 客户端属性，全局 put 无效；
     * 且状态键必须用 Nimbus 注册的精确组合名（如 [Enabled+MouseOver]、[Focused+Selected]），
     * 写 [MouseOver]、[Selected+Focused] 这类不存在的组合是不生效的。
     */
    public static void flatTabs(JTabbedPane t) {
        UIDefaults ov = new UIDefaults();
        ov.put("TabbedPane:TabbedPaneTab.contentMargins", new Insets(6, 14, 6, 14));
        // —— 未选中：浅灰、悬停加深、按下再深（键名须与 NimbusDefaults 注册的一致）
        ov.put("TabbedPane:TabbedPaneTab[Enabled].backgroundPainter", flatPainter(new Color(0xF1, 0xF5, 0xFA)));
        ov.put("TabbedPane:TabbedPaneTab[Enabled+MouseOver].backgroundPainter", flatPainter(new Color(0xE7, 0xF0, 0xFC)));
        ov.put("TabbedPane:TabbedPaneTab[Enabled+Pressed].backgroundPainter", flatPainter(new Color(0xDC, 0xE9, 0xFA)));
        ov.put("TabbedPane:TabbedPaneTab[Disabled].backgroundPainter", flatPainter(new Color(0xF3, 0xF5, 0xF8)));
        // —— 选中：白底（含带焦点/悬停/按下的全部组合，漏一个就会露出默认蓝色）
        ov.put("TabbedPane:TabbedPaneTab[Selected].backgroundPainter", flatPainter(BG_CARD));
        ov.put("TabbedPane:TabbedPaneTab[MouseOver+Selected].backgroundPainter", flatPainter(BG_CARD));
        ov.put("TabbedPane:TabbedPaneTab[Pressed+Selected].backgroundPainter", flatPainter(BG_CARD));
        ov.put("TabbedPane:TabbedPaneTab[Focused+Selected].backgroundPainter", flatPainter(BG_CARD));
        ov.put("TabbedPane:TabbedPaneTab[Focused+MouseOver+Selected].backgroundPainter", flatPainter(BG_CARD));
        ov.put("TabbedPane:TabbedPaneTab[Focused+Pressed+Selected].backgroundPainter", flatPainter(BG_CARD));
        ov.put("TabbedPane:TabbedPaneTab[Disabled+Selected].backgroundPainter", flatPainter(BG_CARD));
        // —— 文字颜色
        ov.put("TabbedPane:TabbedPaneTab.textForeground", TEXT_SUB);
        ov.put("TabbedPane:TabbedPaneTab[Selected].textForeground", TEXT_MAIN);
        ov.put("TabbedPane:TabbedPaneTab[Pressed+Selected].textForeground", TEXT_MAIN);
        ov.put("TabbedPane:TabbedPaneTab[Focused+Pressed+Selected].textForeground", TEXT_MAIN);
        ov.put("TabbedPane:TabbedPaneTab[Disabled].textForeground", TEXT_MUTED);
        // —— 页签条本身：悬停/按下时默认会画出蓝色双横线，一并压平
        ov.put("TabbedPane:TabbedPaneTabArea[Enabled].backgroundPainter", flatPainter(BG_APP));
        ov.put("TabbedPane:TabbedPaneTabArea[Enabled+MouseOver].backgroundPainter", flatPainter(BG_APP));
        ov.put("TabbedPane:TabbedPaneTabArea[Enabled+Pressed].backgroundPainter", flatPainter(BG_APP));
        ov.put("TabbedPane:TabbedPaneTabArea[Disabled].backgroundPainter", flatPainter(BG_APP));
        ov.put("TabbedPane:TabbedPaneContent.backgroundPainter", flatPainter(BG_APP));
        ov.put("TabbedPane:TabbedPaneContent.borderPainter", flatPainter(BG_APP));
        ov.put("TabbedPane.font", F_BODY);
        t.putClientProperty("Nimbus.Overrides", ov);
        t.setFont(F_BODY);
        t.setBackground(BG_APP);
        // 去掉选中页签外围的蓝色焦点框（键盘导航对鼠标操作无影响）
        t.setFocusable(false);
        // 切换页签后整体重绘：实测（Robot 屏幕捕获逐像素比对）切换页签后，
        // 上一页自绘组件的描边、文字像素会残留在页签组件之外的边距带里，
        // tabs.repaint() 的脏区范围不够；必须重绘整个顶层窗口才能根除
        t.addChangeListener(e -> SwingUtilities.invokeLater(() -> {
            Container root = t.getTopLevelAncestor();
            if (root != null) {
                root.repaint();
            } else {
                t.repaint();
            }
        }));
    }

    /** 表头浅蓝色（按参考样式取 #EFF6FF）。 */
    public static final Color TABLE_HEADER_BG = new Color(0xEF, 0xF6, 0xFF);
    /** 表头文字：柔和灰黑色。 */
    public static final Color TABLE_HEADER_FG = new Color(0x3C, 0x44, 0x50);
    /** 表格正文文字：比 TEXT_MAIN 更深的近黑色。 */
    public static final Color TABLE_CELL_FG = new Color(0x11, 0x15, 0x1C);
    /** 表格圆角描边：细黑灰线。 */
    public static final Color TABLE_EDGE = new Color(0x4E, 0x55, 0x60);

    /** 单元格文字居中（原始 new JTable 的表调用，Theme.table 已内置）。 */
    public static void centerCells(JTable t) {
        javax.swing.table.DefaultTableCellRenderer r = new javax.swing.table.DefaultTableCellRenderer();
        r.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        t.setDefaultRenderer(Object.class, r);
    }

    /**
     * 表头改成浅蓝色。实测 Nimbus 下表头背景既不吃 setBackground 也不吃
     * Nimbus.Overrides（renderer 样式链独立），最可靠的是换成自绘 renderer。
     */
    public static void flatTableHeader(JTable t) {
        t.getTableHeader().setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            private boolean lastColumn;

            {
                setOpaque(false);
                setHorizontalAlignment(CENTER);
                setFont(F_H3);
            }

            @Override
            public java.awt.Component getTableCellRendererComponent(JTable tab, Object v,
                    boolean sel, boolean foc, int row, int col) {
                lastColumn = tab != null && col == tab.getColumnCount() - 1;
                setText(v == null ? "" : v.toString());
                return this;
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(TABLE_HEADER_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // 最后一列不画右分隔线：那条线会贴在表头最右缘，
                // 看起来像多出一格/多了一条灰竖线。
                if (!lastColumn) {
                    g2.setColor(new Color(0xE0, 0xEA, 0xF8));
                    g2.fillRect(getWidth() - 1, 0, 1, getHeight()); // 列间细线
                }
                // 表头下边框画在每个单元格内部，左/右两端都能贴到单元格边界，
                // 不会像 JTableHeader 的 MatteBorder 那样在两端各少 1px。
                g2.setColor(LINE);
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
                super.paintComponent(g); // 只画文字（不透明背景已关）
            }
        });
        // 下边框由 renderer 负责；清掉旧 MatteBorder，避免双重描边/内缩
        t.getTableHeader().setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        t.getTableHeader().setBackground(TABLE_HEADER_BG);
        t.getTableHeader().setForeground(TABLE_HEADER_FG);
    }

    /**
     * 表格外框：把滚动面板包在一个自绘边框的容器里。
     *
     * <p>之前直接在 JScrollPane 上画边框有两个顽疾：
     * <ul>
     *   <li>滚动条/表头重绘会把边框局部盖掉，只剩右/下段；</li>
     *   <li>圆角弧线在右下角天然留 1-2px 白点，补丁又容易画到框外。</li>
     * </ul>
     * 现在边框由外层容器绘制，滚动面板整体缩进 2px，任何子组件重绘
     * 都碰不到边框；外层 opaque，父卡片重绘也不会擦掉它。
     */
    public static JPanel roundedScroll(JTable t) {
        JScrollPane inner = new JScrollPane(t);
        inner.setOpaque(false);
        inner.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        inner.setViewportBorder(null);
        inner.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        // Nimbus 自带的右上角块是蓝紫色渐变，和表头颜色不一致。
        // 这里显式覆盖成表头同色；它位于滚动面板内部，不会碰到外框。
        JLabel headerCorner = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(TABLE_HEADER_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                // 表头的下边框只覆盖到列头宽度，右上角这一小块要自己补上，
                // 否则最右列下方的灰线到角落会断掉 5-12px。
                g.setColor(LINE);
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        headerCorner.setOpaque(false);
        inner.setCorner(JScrollPane.UPPER_RIGHT_CORNER, headerCorner);
        JLabel lowerCorner = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(BG_CARD);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        lowerCorner.setOpaque(false);
        inner.setCorner(JScrollPane.LOWER_RIGHT_CORNER, lowerCorner);

        JPanel outer = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(BG_CARD);
                g.fillRect(0, 0, getWidth(), getHeight());
                Graphics2D g2 = (Graphics2D) g.create();
                // 直接用 fillRect 画四条 1px 实心边：drawRect/BasicStroke 在
                // 高 DPI 缩放或窗口重绘时会把边线摊成浅灰，右下角尤其明显。
                g2.setColor(TABLE_EDGE);
                int w = getWidth();
                int h = getHeight();
                g2.fillRect(0, 0, w, 1);       // top
                g2.fillRect(0, h - 1, w, 1);   // bottom
                g2.fillRect(0, 0, 1, h);       // left
                g2.fillRect(w - 1, 0, 1, h);   // right
                g2.dispose();
            }
        };
        outer.setOpaque(true);
        outer.setBackground(BG_CARD);
        // 内层从 (1,1) 开始：边框独占最外圈，表头下边框可以紧贴外框，
        // 不会再在左右两端各留 1px 白缝。
        outer.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        outer.add(inner, BorderLayout.CENTER);
        attachScrollSettleRepaint(inner);
        return outer;
    }

    /** 把 JSplitPane 的分隔条改成参考样式的浅蓝色。 */
    public static void flatSplit(JSplitPane sp) {
        UIDefaults ov = new UIDefaults();
        ov.put("SplitPane:SplitPaneDivider[Enabled].backgroundPainter", flatPainter(new Color(0xE8, 0xF1, 0xFD)));
        ov.put("SplitPane:SplitPaneDivider[Enabled].foregroundPainter", flatPainter(new Color(0xE8, 0xF1, 0xFD)));
        ov.put("SplitPane:SplitPaneDivider[Disabled].backgroundPainter", flatPainter(new Color(0xE8, 0xF1, 0xFD)));
        ov.put("SplitPane:SplitPaneDivider[Disabled].foregroundPainter", flatPainter(new Color(0xE8, 0xF1, 0xFD)));
        sp.putClientProperty("Nimbus.Overrides", ov);
        sp.setDividerSize(8);
    }

    /** 递归把容器内所有滚动条换成现代样式（兜底用，正常由全局监听器覆盖）。 */
    public static void applyModernScrollBar(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JScrollBar) {
                JScrollBar sb = (JScrollBar) c;
                if (!(sb.getUI() instanceof ModernScrollBarUI)) {
                    sb.setUI(new ModernScrollBarUI());
                }
            } else if (c instanceof Container) {
                applyModernScrollBar((Container) c);
            }
        }
    }

    /** 纯色 Painter（用于覆盖 Nimbus 的立体页签绘制）。 */
    private static javax.swing.Painter<JComponent> flatPainter(Color c) {
        return (g, obj, w, h) -> {
            g.setColor(c);
            g.fillRect(0, 0, w, h);
        };
    }

    /** 把当前字体族写入 UIManager 默认值（新建组件生效）。 */
    private static void pushUiFonts() {
        UIManager.put("OptionPane.messageFont", F_BODY);
        UIManager.put("OptionPane.buttonFont", F_BODY);
        UIManager.put("Button.font", F_BODY);
        UIManager.put("Label.font", F_BODY);
        UIManager.put("ComboBox.font", F_BODY);
        UIManager.put("TextField.font", F_BODY);
        UIManager.put("TextArea.font", F_BODY);
        UIManager.put("Table.font", F_SMALL);
        UIManager.put("TableHeader.font", F_H3);
        UIManager.put("TabbedPane.font", F_BODY);
        UIManager.put("TitledBorder.font", F_H3);
        UIManager.put("MenuItem.font", F_BODY);
        UIManager.put("Menu.font", F_BODY);
        UIManager.put("CheckBox.font", F_BODY);
        UIManager.put("RadioButton.font", F_BODY);
    }

    /** 居中显示窗口。 */
    public static void center(Window w) {
        w.setLocationRelativeTo(null);
    }    /** 复制到剪贴板并给出气泡提示。 */
    public static void copy(Component owner, String text, String what) {
        if (text == null) {
            return;
        }
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        toast(owner, "已复制" + (what == null ? "" : what));
    }

    /** 轻量气泡提示（不打断操作）。 */
    public static void toast(Component owner, String msg) {
        if (owner == null) {
            return;
        }
        Window root = SwingUtilities.getWindowAncestor(owner);
        if (root == null) {
            return;
        }
        JWindow w = new JWindow(root);
        JLabel l = new JLabel(msg, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(40, 46, 56, 235));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setOpaque(false);
        l.setForeground(Color.WHITE);
        l.setFont(F_SMALL);
        l.setBorder(BorderFactory.createEmptyBorder(8, 18, 9, 18));
        w.getContentPane().setLayout(new BorderLayout());
        w.getContentPane().add(l, BorderLayout.CENTER);
        w.setBackground(new Color(0, 0, 0, 0));
        w.pack();
        Point loc = root.getLocationOnScreen();
        w.setLocation(loc.x + (root.getWidth() - w.getWidth()) / 2,
                loc.y + root.getHeight() - w.getHeight() - 40);
        w.setAlwaysOnTop(true);
        w.setVisible(true);
        Timer t = new Timer(1600, e -> w.dispose());
        t.setRepeats(false);
        t.start();
    }

    /** 统一样式的确认对话框。 */
    public static boolean confirm(Component owner, String title, String message) {
        JTextArea ta = new JTextArea(message);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setFont(F_BODY);
        ta.setBackground(BG_CARD);
        ta.setOpaque(true);
        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG_CARD);
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_CARD);
        p.add(sp, BorderLayout.CENTER);
        // 固定高度只作为上限；长单行会换行，靠滚动条查看，不会被裁掉
        p.setPreferredSize(new Dimension(560,
                Math.min(px(360), Math.max(px(120), message.split("\n").length * px(22) + px(40)))));
        int r = JOptionPane.showConfirmDialog(owner, p, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.OK_OPTION;
    }

    /** 信息对话框。 */
    public static void info(Component owner, String title, String message) {
        JOptionPane.showMessageDialog(owner, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    /** 错误对话框。 */
    public static void error(Component owner, String title, String message) {
        JOptionPane.showMessageDialog(owner, message, title, JOptionPane.ERROR_MESSAGE);
    }

    /** 可横向滚动的面板包装。 */
    public static JScrollPane scroll(Component view) {
        JScrollPane sp = new JScrollPane(trackViewportWidth(view),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG_APP);
        sp.setOpaque(false);
        // 禁用 blit 位块复制：它会把旧像素搬进视口，产生滚动后的黑线伪影
        sp.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        attachScrollSettleRepaint(sp);
        return sp;
    }

    /**
     * 让滚动视图的宽度始终跟随视口。
     *
     * <p>scroll() 禁用了横向滚动条——这意味着内容比窗口宽时不是"给你滚动条"，
     * 而是直接被裁掉。让视图实现 {@link javax.swing.Scrollable} 并声明宽度跟随视口后，
     * 窄窗口下子组件会拿到真实宽度去收缩重排（表格压缩列宽、文本换行），
     * 而不是保持"设计宽度"被静默截断。
     */
    private static JComponent trackViewportWidth(Component view) {
        JPanel p = new ViewportTrackPanel();
        p.setOpaque(false);
        p.add(view, BorderLayout.CENTER);
        return p;
    }

    /** 实现 Scrollable 的纵向容器：宽度跟随视口，高度按内容。 */
    private static final class ViewportTrackPanel extends JPanel implements Scrollable {
        ViewportTrackPanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * 滚动停止 150ms 后整窗重绘一次（轻量保险）。
     * 越界描边的根因（paintChildren 中 setClip(null)）修复后，此防线仅作兜底，
     * 静止与滚动期间均无持续开销。
     */
    public static void attachScrollSettleRepaint(final JScrollPane sp) {
        final javax.swing.Timer[] timer = new javax.swing.Timer[1];
        java.awt.event.AdjustmentListener l = new java.awt.event.AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(java.awt.event.AdjustmentEvent e) {
                if (timer[0] == null) {
                    timer[0] = new javax.swing.Timer(150, new java.awt.event.ActionListener() {
                        @Override
                        public void actionPerformed(java.awt.event.ActionEvent ev) {
                            java.awt.Window w = SwingUtilities.getWindowAncestor(sp);
                            if (w != null) {
                                w.repaint();
                            }
                        }
                    });
                    timer[0].setRepeats(false);
                    timer[0].setCoalesce(true);
                }
                timer[0].restart();
            }
        };
        sp.getVerticalScrollBar().addAdjustmentListener(l);
        sp.getHorizontalScrollBar().addAdjustmentListener(l);
    }

    /**
     * 递归强制容器内所有滚动面板的视口使用 {@link JViewport#SIMPLE_SCROLL_MODE}。
     * 嵌套视口若遗留 BLIT_SCROLL_MODE，边缘像素对齐异常时会把旧缓冲搬进可视区，
     * 形成滚动后的残影。表格视图重建后调用一次即可。
     */
    public static void forceSimpleScrollMode(java.awt.Container c) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JScrollPane) {
                ((JScrollPane) comp).getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            }
            if (comp instanceof java.awt.Container) {
                forceSimpleScrollMode((java.awt.Container) comp);
            }
        }
    }

    /* ---------------------------------------------------------- 现代滚动条 */

    /**
     * 现代风格滚动条：白色轨道、长椭圆胶囊滑块、小巧的三角箭头按钮。
     */
    public static final class ModernScrollBarUI extends BasicScrollBarUI {

        private boolean hover;

        @Override
        protected void installListeners() {
            super.installListeners();
            scrollbar.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    hover = true;
                    scrollbar.repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    scrollbar.repaint();
                }
            });
            // 值变化时整体重绘滚动条：BasicScrollBarUI 默认只重绘滑块新位置，
            // 滑块腾出的区域残留旧像素，快速滚动后在端部堆积成黑色横条
            scrollbar.addAdjustmentListener(e -> scrollbar.repaint());
        }

        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0xC5, 0xCE, 0xD9);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return new ArrowButton(orientation);
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return new ArrowButton(orientation);
        }

        /** 小巧的三角箭头按钮：白底、灰色小三角，悬停加深。 */
        private static final class ArrowButton extends JButton {
            private final int dir;
            private boolean over;

            ArrowButton(int dir) {
                this.dir = dir;
                setFocusable(false);
                setBorder(null);
                setOpaque(false);
                setContentAreaFilled(false);
                setBorderPainted(false);
                setPreferredSize(new Dimension(Theme.px(10), Theme.px(14)));
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) { over = true; repaint(); }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) { over = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 必须填充背景：track 只覆盖两按钮之间的区域，按钮区若透明会露出
                // 旧帧像素（滚动后在端部堆积成黑色横条）
                g2.setColor(BG_CARD);
                g2.fillRect(0, 0, getWidth(), getHeight());
                int size = 5;
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.setColor(over ? new Color(0x6B, 0x76, 0x84) : new Color(0x9A, 0xA4, 0xB2));
                switch (dir) {
                    case SwingConstants.NORTH:
                        g2.fillPolygon(new int[]{cx - size, cx + size, cx},
                                new int[]{cy + 2, cy + 2, cy - 2}, 3);
                        break;
                    case SwingConstants.SOUTH:
                        g2.fillPolygon(new int[]{cx - size, cx + size, cx},
                                new int[]{cy - 2, cy - 2, cy + 2}, 3);
                        break;
                    case SwingConstants.WEST:
                        g2.fillPolygon(new int[]{cx + 2, cx + 2, cx - 2},
                                new int[]{cy - size, cy + size, cy}, 3);
                        break;
                    default:
                        g2.fillPolygon(new int[]{cx - 2, cx - 2, cx + 2},
                                new int[]{cy - size, cy + size, cy}, 3);
                        break;
                }
                g2.dispose();
            }
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(BG_CARD);
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.dispose();
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.width <= 0 || r.height <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hover || isDragging ? new Color(0xA9, 0xB4, 0xC2) : new Color(0xC5, 0xCE, 0xD9));
            // 把滑块夹取到两个箭头按钮之间：滑块矩形在端部可能与按钮边界重叠
            // 1-2px，重叠部分会被按钮（后画）盖住，表现为端部圆头缺损
            if (scrollbar.getOrientation() == Adjustable.VERTICAL) {
                int w = Math.max(6, r.width - 4);
                int top = decrButton.getY() + decrButton.getHeight();
                int bot = incrButton.getY();
                int y = Math.max(r.y, top);
                int h = Math.min(r.y + r.height, bot) - y;
                if (h <= 0) {
                    g2.dispose();
                    return;
                }
                int arc = Math.min(w, h);
                g2.fillRoundRect(r.x + (r.width - w) / 2, y, w, h, arc, arc);
            } else {
                int h = Math.max(6, r.height - 4);
                int left = decrButton.getX() + decrButton.getWidth();
                int right = incrButton.getX();
                int x = Math.max(r.x, left);
                int wid = Math.min(r.x + r.width, right) - x;
                if (wid <= 0) {
                    g2.dispose();
                    return;
                }
                int arc = Math.min(h, wid);
                g2.fillRoundRect(x, r.y + (r.height - h) / 2, wid, h, arc, arc);
            }
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return scrollbar.getOrientation() == Adjustable.VERTICAL
                    ? new Dimension(12, 48) : new Dimension(48, 12);
        }
    }
}
