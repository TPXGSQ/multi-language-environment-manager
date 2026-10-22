package com.langmgr.core;

import java.io.File;

/** 平台与架构判断。 */
public final class Os {

    public enum Family { WINDOWS, MAC, LINUX, OTHER }

    private static final Family FAMILY;
    private static final String ARCH;
    private static final boolean X64;

    static {
        String n = System.getProperty("os.name", "").toLowerCase();
        if (n.contains("win")) {
            FAMILY = Family.WINDOWS;
        } else if (n.contains("mac") || n.contains("darwin")) {
            FAMILY = Family.MAC;
        } else if (n.contains("nux") || n.contains("nix") || n.contains("aix")) {
            FAMILY = Family.LINUX;
        } else {
            FAMILY = Family.OTHER;
        }
        String a = System.getProperty("os.arch", "").toLowerCase();
        ARCH = a;
        X64 = a.contains("64") || a.contains("amd64") || a.contains("x86_64") || a.contains("aarch64") || a.contains("arm64");
    }

    private Os() {
    }

    public static Family family() {
        return FAMILY;
    }

    public static boolean isWindows() {
        return FAMILY == Family.WINDOWS;
    }

    public static boolean isMac() {
        return FAMILY == Family.MAC;
    }

    public static boolean isLinux() {
        return FAMILY == Family.LINUX;
    }

    public static String arch() {
        return ARCH;
    }

    public static boolean is64Bit() {
        return X64;
    }

    public static String label() {
        String os = System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "");
        return os + " · " + ARCH + (X64 ? " (64位)" : " (32位)");
    }

    /** 用户主目录。 */
    public static File home() {
        String h = System.getProperty("user.home");
        return h == null ? new File(".") : new File(h);
    }

    /** 展开 %VAR%（Windows）或 ${VAR}（Unix）形式的环境变量引用。 */
    public static String expand(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (int pass = 0; pass < 3; pass++) {
            String next = isWindows() ? expandWindows(out) : expandUnix(out);
            if (next.equals(out)) {
                break;
            }
            out = next;
        }
        return out;
    }

    private static String expandWindows(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '%') {
                int end = text.indexOf('%', i + 1);
                if (end > i + 1) {
                    String name = text.substring(i + 1, end);
                    String val = System.getenv(name);
                    if (val != null) {
                        sb.append(val);
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String expandUnix(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '$') {
                int j = i + 1;
                boolean braced = j < text.length() && text.charAt(j) == '{';
                if (braced) {
                    j++;
                }
                int start = j;
                while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                if (j > start && (!braced || (j < text.length() && text.charAt(j) == '}'))) {
                    String name = text.substring(start, j);
                    String val = System.getenv(name);
                    if (val != null) {
                        sb.append(val);
                        i = braced ? j + 1 : j;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 判断路径 p 是否等于或位于 root 之下（忽略大小写，Windows 友好）。 */
    public static boolean isUnder(String p, String root) {
        if (p == null || root == null || root.isEmpty()) {
            return false;
        }
        String a = norm(p);
        String b = norm(root);
        if (b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return a.startsWith(b.endsWith(File.separator) ? b : b + File.separator);
    }

    private static String norm(String p) {
        String s = p.trim();
        try {
            s = new File(s).getCanonicalPath();
        } catch (Exception ignored) {
            s = new File(s).getAbsolutePath();
        }
        if (isWindows()) {
            s = s.toLowerCase();
        }
        while (s.endsWith(File.separator) && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
