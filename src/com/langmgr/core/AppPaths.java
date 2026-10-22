package com.langmgr.core;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;

/**
 * 应用目录解析。
 *
 * <p>之前备份、报告、图标都相对 {@code user.dir}，从开始菜单/快捷方式启动时
 * “起始位置”不同，文件会散落到别处，图标也可能读不到。这里统一解析：
 * <ul>
 *   <li>jpackage 生成的 exe：{@code jpackage.app-path} 的父目录；</li>
 *   <li>普通 jar：jar 所在目录；</li>
 *   <li>源码/classes 运行：当前工作目录。</li>
 * </ul>
 *
 * <p>数据目录优先用应用目录（便携）；若应用目录不可写（例如装在
 * {@code C:\Program Files}），退回 {@code %LOCALAPPDATA%\langmgr}。
 */
public final class AppPaths {

    private static volatile File home;

    private AppPaths() {
    }

    /** 应用主目录（jar/exe 所在目录）。 */
    public static File home() {
        File h = home;
        if (h == null) {
            synchronized (AppPaths.class) {
                h = home;
                if (h == null) {
                    h = resolveHome();
                    home = h;
                }
            }
        }
        return h;
    }

    /** 备份目录：应用目录可写时用 {@code <home>/backup}，否则退回本地数据目录。 */
    public static File backupDir() {
        return new File(dataHome(), "backup");
    }

    /** 报告导出默认目录。 */
    public static File reportsDir() {
        return new File(dataHome(), "reports");
    }

    /** 图标目录。 */
    public static File iconsDir() {
        return new File(home(), "icons");
    }

    /** 可写的数据根目录。 */
    public static File dataHome() {
        File h = home();
        try {
            if (h.isDirectory() && Files.isWritable(h.toPath())) {
                return h;
            }
        } catch (RuntimeException ignored) {
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.trim().isEmpty()) {
            return new File(local.trim(), "langmgr");
        }
        return new File(Os.home(), ".langmgr");
    }

    private static File resolveHome() {
        // jpackage 启动器会设置该属性，指向 exe 本身。
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.trim().isEmpty()) {
            File exe = new File(appPath.trim()).getAbsoluteFile();
            File dir = exe.getParentFile();
            if (dir != null) {
                return dir;
            }
        }
        try {
            URL loc = AppPaths.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                File f = new File(loc.toURI()).getAbsoluteFile();
                if (f.isFile()) {
                    File dir = f.getParentFile();
                    if (dir != null) {
                        return dir;
                    }
                }
            }
        } catch (Exception ignored) {
            // 取不到 code source 时退回工作目录
        }
        return new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }
}
