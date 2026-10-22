package com.langmgr.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * 界面设置对话框：界面字体切换 + 窗口标题图标自定义。
 *
 * <p>两项设置都保存在用户偏好（java.util.prefs）里，重启后仍然生效。
 * 点「应用并重建界面」后：写偏好 → 重建字体常量与 UIManager 默认值 →
 * 关闭本对话框 → 重建主窗口，因此新字体与新图标立即生效，无需重启。
 */
public final class SettingsDialog extends JDialog {

    private final MainFrame owner;
    private final JComboBox<String> fontCombo;
    private final JComboBox<String> sizeCombo;
    private final JLabel iconPreview = new JLabel();
    private final JLabel iconPathLabel;
    private String pendingIconPath;   // 用户在对话框里新选但尚未应用的路径
    private boolean iconReset;        // 用户点了"恢复默认图标"

    /** 字号档位：标签 → 缩放系数。 */
    private static final String[] SIZE_LABELS = {"较小（85%）", "标准（100%）", "较大（115%）", "特大（130%）"};
    private static final float[] SIZE_SCALES = {0.85f, 1.0f, 1.15f, 1.3f};

    public SettingsDialog(MainFrame owner) {
        super(owner, "界面设置", true);
        this.owner = owner;
        setBackground(Theme.BG_APP);

        JPanel body = Theme.vBox(14);
        body.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        body.setBackground(Theme.BG_APP);

        /* ---------------- 字体 ---------------- */
        JPanel fontCard = Theme.card(null);
        fontCard.setLayout(new BorderLayout(0, 10));
        fontCard.add(Theme.h2("界面字体"), BorderLayout.NORTH);
        fontCard.add(Theme.sub("从系统已安装的字体中选择（仅列出支持中文的字体），应用后立即生效并记住选择。"),
                BorderLayout.CENTER);

        java.util.List<String> families = Theme.chineseCapableFamilies();
        fontCombo = new JComboBox<>(families.toArray(new String[0]));
        fontCombo.setSelectedItem(Theme.fontFamily());
        fontCombo.setFont(Theme.F_BODY);
        fontCombo.setBackground(Color.WHITE);
        fontCombo.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        // 每个字体名用它自己的字体渲染，所见即所得
        fontCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value != null) {
                    setFont(new Font(value.toString(), Font.PLAIN, 14));
                    setText(value + "　— 多语言环境管理器 Abc 123");
                }
                return c;
            }
        });
        JPanel fontRow = new JPanel(new BorderLayout(8, 0));
        fontRow.setOpaque(false);
        fontRow.add(fontCombo, BorderLayout.CENTER);
        JButton resetFont = Theme.ghostButton("恢复默认");
        resetFont.addActionListener(e -> {
            String def = Theme.chineseCapableFamilies().isEmpty() ? null
                    : Theme.chineseCapableFamilies().get(0);
            if (def != null) {
                fontCombo.setSelectedItem(def);
            }
        });
        fontRow.add(resetFont, BorderLayout.EAST);
        fontCard.add(fontRow, BorderLayout.SOUTH);

        // 字号档位
        JPanel sizeRow = new JPanel(new BorderLayout(8, 0));
        sizeRow.setOpaque(false);
        sizeRow.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        sizeRow.add(Theme.label("字体大小"), BorderLayout.WEST);
        sizeCombo = new JComboBox<>(SIZE_LABELS);
        float cur = Theme.fontScale();
        int sel = 1;
        for (int i = 0; i < SIZE_SCALES.length; i++) {
            if (Math.abs(SIZE_SCALES[i] - cur) < 0.01f) {
                sel = i;
            }
        }
        sizeCombo.setSelectedIndex(sel);
        sizeCombo.setFont(Theme.F_BODY);
        sizeCombo.setBackground(Color.WHITE);
        sizeCombo.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        sizeRow.add(sizeCombo, BorderLayout.CENTER);
        JLabel sizeTip = Theme.muted("按钮、表格行高等尺寸会同步缩放");
        sizeRow.add(sizeTip, BorderLayout.EAST);
        JPanel fontBottom = new JPanel(new BorderLayout());
        fontBottom.setOpaque(false);
        fontBottom.add(fontRow, BorderLayout.NORTH);
        fontBottom.add(sizeRow, BorderLayout.CENTER);
        fontCard.add(fontBottom, BorderLayout.SOUTH);
        body.add(fontCard);

        /* ---------------- 窗口图标 ---------------- */
        JPanel iconCard = Theme.card(null);
        iconCard.setLayout(new BorderLayout(0, 10));
        iconCard.add(Theme.h2("窗口标题图标"), BorderLayout.NORTH);
        iconCard.add(Theme.sub("显示在窗口左上角和任务栏。支持 PNG / JPG / GIF，建议正方形图片，程序会自动缩放。"),
                BorderLayout.CENTER);

        JPanel iconRow = new JPanel(new BorderLayout(12, 0));
        iconRow.setOpaque(false);
        iconPreview.setPreferredSize(new Dimension(56, 56));
        iconPreview.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        iconRow.add(iconPreview, BorderLayout.WEST);

        JPanel iconCenter = new JPanel(new BorderLayout(0, 6));
        iconCenter.setOpaque(false);
        iconPathLabel = Theme.sub(currentIconLabel());
        iconCenter.add(iconPathLabel, BorderLayout.CENTER);
        JPanel iconBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        iconBtns.setOpaque(false);
        JButton choose = Theme.ghostButton("选择图片…");
        choose.addActionListener(e -> chooseIcon());
        JButton resetIcon = Theme.ghostButton("恢复默认图标");
        resetIcon.addActionListener(e -> {
            pendingIconPath = null;
            iconReset = true;
            iconPathLabel.setText("默认图标（自绘）");
            refreshPreview(null);
        });
        iconBtns.add(choose);
        iconBtns.add(resetIcon);
        iconCenter.add(iconBtns, BorderLayout.SOUTH);
        iconRow.add(iconCenter, BorderLayout.CENTER);
        iconCard.add(iconRow, BorderLayout.SOUTH);
        body.add(iconCard);

        refreshPreview(AppIcon.customPath());

        /* ---------------- 底部按钮 ---------------- */
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        foot.setOpaque(false);
        JButton cancel = Theme.ghostButton("取消");
        cancel.addActionListener(e -> dispose());
        JButton apply = Theme.primaryButton("应用并重建界面");
        apply.addActionListener(e -> apply());
        foot.add(cancel);
        foot.add(apply);
        body.add(foot);

        setContentPane(body);
        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(owner);
    }

    private String currentIconLabel() {
        String p = AppIcon.customPath();
        return p == null ? "默认图标（自绘）" : p;
    }

    private void refreshPreview(String path) {
        Image img;
        if (path == null) {
            img = AppIcon.current(48);
        } else {
            try {
                img = javax.imageio.ImageIO.read(new File(path));
            } catch (Exception ex) {
                img = null;
            }
            if (img == null) {
                img = AppIcon.current(48);
            }
        }
        iconPreview.setIcon(new ImageIcon(img.getScaledInstance(48, 48, Image.SCALE_SMOOTH)));
    }

    private void chooseIcon() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("选择窗口图标图片");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "图片文件 (*.png, *.jpg, *.jpeg, *.gif)", "png", "jpg", "jpeg", "gif"));
        String cur = AppIcon.customPath();
        if (cur != null) {
            File f = new File(cur);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                fc.setCurrentDirectory(f.getParentFile());
            }
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File sel = fc.getSelectedFile();
        // 预检：必须能解析为图片
        try {
            Image test = javax.imageio.ImageIO.read(sel);
            if (test == null || test.getWidth(null) <= 0) {
                Theme.error(this, "不是有效图片", "所选文件无法解析为图片，请换一张 PNG / JPG / GIF。");
                return;
            }
        } catch (Exception ex) {
            Theme.error(this, "读取失败", ex.getMessage());
            return;
        }
        pendingIconPath = sel.getAbsolutePath();
        iconReset = false;
        iconPathLabel.setText(pendingIconPath);
        refreshPreview(pendingIconPath);
    }

    private void apply() {
        // 字体与字号
        Object sel = fontCombo.getSelectedItem();
        if (sel != null) {
            Theme.applyFontFamily(sel.toString());
        }
        int sizeIdx = sizeCombo.getSelectedIndex();
        if (sizeIdx >= 0 && sizeIdx < SIZE_SCALES.length) {
            Theme.applyFontScale(SIZE_SCALES[sizeIdx]);
        }
        // 图标：只有用户在本次会话中改过才写偏好
        if (iconReset) {
            AppIcon.setCustomPath(null);
        } else if (pendingIconPath != null) {
            AppIcon.setCustomPath(pendingIconPath);
        }
        dispose();
        owner.recreate();
    }
}
