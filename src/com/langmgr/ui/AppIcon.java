package com.langmgr.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 窗口标题栏 / 任务栏图标。
 *
 * <p>默认图标由代码自绘（圆角方块 + “语”字），不再使用 JDK 自带的咖啡杯图标；
 * 用户可在「界面设置」中选择一张本地图片（PNG / JPG / GIF）自定义，
 * 路径保存在用户偏好里，重启后仍然生效。图片损坏或路径失效时自动回退到默认图标。
 */
public final class AppIcon {

    private static final String PREF_KEY = "windowIcon";
    /** 标题栏 / 任务栏可能用到的尺寸。 */
    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128};

    private AppIcon() {
    }

    /** 返回多尺寸图标列表，用于 Frame.setIconImages()。 */
    public static List<Image> icons() {
        Image base = loadCustom();
        if (base == null) {
            base = loadBundled();
        }
        if (base == null) {
            base = drawDefault(128);
        }
        List<Image> out = new ArrayList<>();
        for (int s : SIZES) {
            out.add(scaleTo(base, s));
        }
        return out;
    }

    /** 当前生效图标的单尺寸版本（用于设置界面的预览）。 */
    public static Image current(int size) {
        Image base = loadCustom();
        if (base == null) {
            base = loadBundled();
        }
        if (base == null) {
            base = drawDefault(Math.max(64, size));
        }
        return scaleTo(base, size);
    }

    /** 用户自定义图片路径；未设置返回 null。 */
    public static String customPath() {
        String p = Theme.uiPrefs().get(PREF_KEY, null);
        return (p == null || p.trim().isEmpty()) ? null : p;
    }

    /**
     * 设置自定义图标路径；传 null 恢复默认图标。
     * 传入的路径必须可读且能解析为图片，否则返回 false 且不保存。
     */
    public static boolean setCustomPath(String pathOrNull) {
        if (pathOrNull == null || pathOrNull.trim().isEmpty()) {
            Theme.uiPrefs().remove(PREF_KEY);
            return true;
        }
        if (!new File(pathOrNull).isFile()) {
            return false;
        }
        Theme.uiPrefs().put(PREF_KEY, pathOrNull);
        return true;
    }

    private static Image loadCustom() {
        String p = customPath();
        if (p == null) {
            return null;
        }
        try {
            Image img = ImageIO.read(new File(p));
            if (img == null || img.getWidth(null) <= 0) {
                return null;
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 程序内置的默认图标（icons/head-pic.png）。
     * 依次查找：应用目录/icons、当前工作目录/icons、应用目录的上一级/icons，
     * 最后读 jar 内资源；都没有才自绘，避免从不同入口启动时退回“语”字图标。
     */
    private static Image loadBundled() {
        File home = com.langmgr.core.AppPaths.home();
        File parent = home.getParentFile();
        File[] candidates = {
                new File(new File(home, "icons"), "head-pic.png"),
                new File(new File(System.getProperty("user.dir", ".")), "icons/head-pic.png"),
                parent == null ? null : new File(new File(parent, "icons"), "head-pic.png")
        };
        for (File f : candidates) {
            Image img = loadFile(f);
            if (img != null) {
                return img;
            }
        }
        try {
            java.io.InputStream in = AppIcon.class.getResourceAsStream("/icons/head-pic.png");
            if (in != null) {
                try {
                    Image img = ImageIO.read(in);
                    if (img != null && img.getWidth(null) > 0) {
                        return img;
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Image loadFile(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            Image img = ImageIO.read(f);
            return img != null && img.getWidth(null) > 0 ? img : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 等比缩放到正方形（带平滑与透明支持）。 */
    private static Image scaleTo(Image src, int size) {
        int w = src.getWidth(null);
        int h = src.getHeight(null);
        if (w <= 0 || h <= 0) {
            return drawDefault(size);
        }
        // 等比缩放后居中放进正方形画布，保持比例不拉伸
        double scale = Math.min((double) size / w, (double) size / h);
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(src, (size - dw) / 2, (size - dh) / 2, dw, dh, null);
        g2.dispose();
        return out;
    }

    /** 自绘默认图标：品牌色圆角方块 + 白色“语”字。 */
    private static Image drawDefault(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        float r = size * 0.22f;
        GradientPaint gp = new GradientPaint(0, 0, Theme.ACCENT, size, size, Theme.ACCENT_DARK);
        g2.setPaint(gp);
        g2.fill(new RoundRectangle2D.Double(0, 0, size - 1, size - 1, r, r));

        // 中央白字“语”，字号随图标大小自适应
        Font f = Theme.bold(size * 0.52f);
        g2.setFont(f);
        g2.setColor(Color.WHITE);
        FontMetrics fm = g2.getFontMetrics();
        String ch = "语";
        int x = (size - fm.stringWidth(ch)) / 2;
        int y = (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(ch, x, y);
        g2.dispose();
        return img;
    }
}
