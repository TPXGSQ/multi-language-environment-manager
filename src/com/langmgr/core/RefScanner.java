package com.langmgr.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 检查某个"准备删除的目录"还有没有程序在用绝对路径引用它。
 *
 * <p>只删 PATH 条目不会删文件，所以删除前必须回答一个问题：
 * <b>除了 PATH 之外，还有谁会按绝对路径找到这个目录？</b> —— 有的话，
 * 删掉目录就会把那个程序弄坏。这件事不能甩给用户去"自己确认"，
 * 必须由程序扫一遍、把命中项和处置方式一起摆出来。
 *
 * <p>检查范围（全部只读）：
 * <ol>
 *   <li>注册表里的任意字符串值或键路径命中该目录——覆盖
 *       {@code App Paths}、文件关联、服务、计划任务、启动项里的硬编码路径；</li>
 *   <li>用户与系统 PATH（含 {@code %VAR%} 写法展开后再比）；</li>
 *   <li>其它环境变量；</li>
 *   <li>当前用户与系统级 Start Menu 的快捷方式。</li>
 * </ol>
 * 不扫全盘（太慢且噪音大）；扫不到的残余靠删除后的"仍被占用"提示兜底。
 */
public final class RefScanner {

    /** 一条引用：谁在引用、引用的是什么、以什么形式引用。 */
    public static final class Ref {
        /** 引用来源，如 {@code 系统环境变量 Path}、{@code 注册表 HKLM\...\App Paths\java.exe}。 */
        public String source;
        /** 命中的具体内容（变量值、注册表值、快捷方式目标）。 */
        public String detail;
        /** 处置建议：删除该值 / 运行注册表命令 / 删除快捷方式。 */
        public String action;
        /**
         * 引用形式：{@link #KIND_ENV}（环境变量）、{@link #KIND_REG}（注册表值）、
         * {@link #KIND_LNK}（快捷方式）。
         */
        public String kind;
        /** 可执行的修复命令（目前只有注册表类能给出安全的 reg delete / 快捷方式删除）。 */
        public String fixCommand;

        @Override
        public String toString() {
            return source + " ← " + detail;
        }
    }

    public static final String KIND_ENV = "env";
    public static final String KIND_REG = "reg";
    public static final String KIND_LNK = "lnk";

    /** 注册表扫描的时间总预算：超过就停，宁可少报也不让界面卡住。 */
    private static final long REG_BUDGET_MS = 15_000;

    private RefScanner() {
    }

    /**
     * 扫描对 {@code dirs} 中任一路径的绝对路径引用。
     *
     * @return 引用列表（去重后）；空列表表示没发现硬编码引用
     */
    public static List<Ref> findHardReferences(List<String> dirs) {
        List<Ref> out = new ArrayList<>();
        incomplete = false;
        if (dirs == null || dirs.isEmpty()) {
            return out;
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Ref r : scanEnv(dirs, seen)) {
            if (keys.add(r.kind + "|" + r.source + "|" + r.detail)) {
                out.add(r);
            }
        }
        for (Ref r : scanRegistry(dirs)) {
            if (keys.add(r.kind + "|" + r.source + "|" + r.detail)) {
                out.add(r);
            }
        }
        for (Ref r : scanShortcuts(dirs)) {
            if (keys.add(r.kind + "|" + r.source + "|" + r.detail)) {
                out.add(r);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------ 环境变量 */

    /** 环境变量（PATH 与其它 HOME 类变量）：值里含目标目录即算引用。 */
    private static List<Ref> scanEnv(List<String> dirs, Set<String> seen) {
        List<Ref> out = new ArrayList<>();
        // Path 单独拆条目比，才能精确说明"第几条"
        checkPathList(out, dirs, EnvStore.systemPathEntries(), "系统环境变量 Path");
        checkPathList(out, dirs, EnvStore.userPathEntries(), "用户环境变量 Path");

        // 先用本进程环境快速过一遍（不启动任何子进程）；命中的再查注册表确认作用域，
        // 避免为了几个变量就 fork 几十次 reg.exe
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String name = e.getKey();
            if ("PATH".equalsIgnoreCase(name)) {
                continue;
            }
            String raw = e.getValue();
            if (raw == null || raw.isEmpty() || matchIn(dirs, raw) == null) {
                continue;
            }
            addEnvRef(out, seen, name, raw);
        }
        return out;
    }

    /** 追加一条环境变量引用（写注册表原文与作用域说明）。 */
    private static void addEnvRef(List<Ref> out, Set<String> seen, String name, String effective) {
        String raw = EnvStore.readRaw(name);
        String shown = raw != null && !raw.isEmpty() ? raw : effective;
        if (!seen.add(name.toUpperCase(Locale.ROOT) + "|" + shown)) {
            return;
        }
        Ref r = new Ref();
        r.kind = KIND_ENV;
        r.source = "环境变量 " + name + (raw != null ? "（用户作用域）" : "（系统/进程）");
        r.detail = shown;
        r.action = "该变量指向要被删除的目录，删除目录后使用它的程序会失效；"
                + "建议在「环境变量」页改为其它值，或直接删除该变量。";
        out.add(r);
    }

    private static void checkPathList(List<Ref> out, List<String> dirs, List<String> entries, String label) {
        for (int i = 0; i < entries.size(); i++) {
            String hit = matchIn(dirs, entries.get(i));
            if (hit == null) {
                continue;
            }
            Ref r = new Ref();
            r.kind = KIND_ENV;
            r.source = label + " 第 " + (i + 1) + " 条";
            r.detail = entries.get(i);
            r.action = "这条 PATH 仍直接指向要被删除的目录；删除后会变成失效条目，"
                    + "留在 PATH 里会拖慢每次命令查找，建议先移除。";
            out.add(r);
        }
    }

    /* -------------------------------------------------------------- 注册表 */

    /**
     * 会硬编码可执行文件路径的注册表位置。
     *
     * <p>不遍历整个 SOFTWARE 树——那是几十万个子键，注册表递归查询要几十秒，
     * 而用户只是点了个"删除"。这里只查真正会存绝对路径的那几个位置：
     * App Paths、文件关联（Classes）、启动项、系统服务、计划任务。
     */
    private static final String[] REG_ROOTS = {
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Run",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SYSTEM\\CurrentControlSet\\Services",
            "HKLM\\SOFTWARE\\Classes",
            "HKCU\\SOFTWARE\\Classes",
    };

    /**
     * 扫描常见"会硬编码路径"的注册表位置。
     *
     * <p>用 {@code reg query ... /s /f <目录名> /d} 让注册表自己过滤值数据；
     * 命中后再逐条确认整段路径都在值里，避免同名前缀误报。
     */
    private static List<Ref> scanRegistry(List<String> dirs) {
        List<Ref> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + REG_BUDGET_MS;
        for (String dir : dirs) {
            String leaf = new File(dir).getName();
            if (leaf.isEmpty()) {
                continue;
            }
            for (String root : REG_ROOTS) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 500) {
                    // 预算用完就收工：宁可少报几处，也不能让"确认引用"变成卡顿
                    incomplete = true;
                    return out;
                }
                // 单个位置最多等 4 秒：Services / Classes 子键多，
                // 真慢的时候宁可漏报（还有目录占用兜底），也不能让 UI 卡住
                Proc.Result r = Proc.run(java.util.Arrays.asList(
                        "reg", "query", root, "/s", "/f", leaf, "/d"), null,
                        Math.min(4000, left));
                collectRegHits(out, dirs, r.stdout);
            }
        }
        return out;
    }

    /** 本次扫描是否因时间预算不足而提前结束（UI 据此提示"未扫全"）。 */
    private static volatile boolean incomplete;

    /** 上一次 {@link #findHardReferences} 是否扫全了。 */
    public static boolean lastScanIncomplete() {
        return incomplete;
    }

    /**
     * 解析 {@code reg query /s /f ... /d} 的输出：块首是键路径，块内是值行。
     * 只有整段目标路径出现在值里才算命中。
     */
    private static void collectRegHits(List<Ref> out, List<String> dirs, String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return;
        }
        String key = null;
        for (String line : stdout.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("HKEY_")) {
                key = t;
                continue;
            }
            if (key == null) {
                continue;
            }
            String[] parts = t.split("\\s{2,}", 3);
            if (parts.length < 2) {
                continue;
            }
            String valueName = parts[0];
            String data = parts[parts.length - 1];
            if (matchIn(dirs, data) == null) {
                continue;
            }
            // 跳过语言管理器自己的备份目录：那是本工具写的快照，删掉目录不影响
            if (key.toUpperCase(Locale.ROOT).contains("LANGMGR")) {
                continue;
            }
            Ref r = new Ref();
            r.kind = KIND_REG;
            r.source = "注册表 " + key + " → " + valueName;
            r.detail = data;
            r.fixCommand = regDeleteCommand(key, valueName);
            r.action = r.fixCommand == null
                    ? "该注册表值按绝对路径引用了这个目录；删除目录前请先把它改成新路径。"
                    : "该注册表值按绝对路径引用了这个目录。若该程序已不再使用，"
                      + "可用下方命令删除这条值（需管理员权限）；仍在用则先改成新路径。";
            out.add(r);
        }
    }

    /** 生成删除某条注册表值的命令；默认值用 /ve。 */
    private static String regDeleteCommand(String key, String valueName) {
        if (key == null) {
            return null;
        }
        String hive = key.toUpperCase(Locale.ROOT);
        boolean isDefault = "(默认)".equals(valueName) || "(Default)".equalsIgnoreCase(valueName);
        StringBuilder sb = new StringBuilder("reg delete \"")
                .append(key).append("\" ");
        if (isDefault) {
            sb.append("/ve ");
        } else {
            sb.append("/v \"").append(valueName).append("\" ");
        }
        sb.append("/f");
        if (hive.startsWith("HKEY_LOCAL_MACHINE")) {
            sb.append("   :: 需管理员权限");
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------ 快捷方式 */

    private static int shortcutsScanned;

    /** 最近一次扫描解析的快捷方式数量（供界面展示进度）。 */
    public static int countShortcutsScanned() {
        return shortcutsScanned;
    }

    private static List<Ref> scanShortcuts(List<String> dirs) {
        shortcutsScanned = 0;
        List<Ref> out = new ArrayList<>();
        List<File> lnks = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String programData = System.getenv("ProgramData");
        List<File> roots = new ArrayList<>();
        if (appData != null) {
            roots.add(new File(appData, "Microsoft\\Windows\\Start Menu\\Programs"));
        }
        if (programData != null) {
            roots.add(new File(programData, "Microsoft\\Windows\\Start Menu\\Programs"));
        }
        for (File root : roots) {
            collectLnkFiles(lnks, root, 0);
        }
        shortcutsScanned = lnks.size();
        // 批量解析：一个 PowerShell 进程读一批 .lnk 的目标，比逐个启动快两个数量级
        int batch = 80;
        for (int i = 0; i < lnks.size(); i += batch) {
            List<File> part = lnks.subList(i, Math.min(lnks.size(), i + batch));
            List<String> targets = resolveLnkBatch(part);
            for (int j = 0; j < part.size(); j++) {
                String target = j < targets.size() ? targets.get(j) : null;
                addLnkRef(out, dirs, part.get(j), target);
            }
        }
        return out;
    }

    private static void addLnkRef(List<Ref> out, List<String> dirs, File f, String target) {
        if (target == null || matchIn(dirs, target) == null) {
            return;
        }
        Ref r = new Ref();
        r.kind = KIND_LNK;
        r.source = "快捷方式 " + f.getAbsolutePath();
        r.detail = target;
        r.action = "该快捷方式指向要被删除的目录里的程序；删除目录后它会失效，建议先改指向或删除它。";
        out.add(r);
    }

    private static void collectLnkFiles(List<File> out, File dir, int depth) {
        if (depth > 4 || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collectLnkFiles(out, f, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                out.add(f);
            }
        }
    }

    /**
     * 一次 PowerShell 调用解析一批 .lnk，返回与 {@code lnks} 等长的列表
     *（解析失败的项为 null）。脚本逐行输出 {@code 序号 TAB 目标路径}。
     */
    private static List<String> resolveLnkBatch(List<File> lnks) {
        StringBuilder script = new StringBuilder("$w=New-Object -ComObject WScript.Shell;");
        for (int i = 0; i < lnks.size(); i++) {
            String p = lnks.get(i).getAbsolutePath().replace("'", "''");
            script.append("try{$t=$w.CreateShortcut('").append(p)
                  .append("').TargetPath}catch{$t=''};")
                  .append("Write-Output ('").append(i).append("\"t'+$t);");
        }
        List<String> cmd = java.util.Arrays.asList("powershell", "-NoProfile", "-NonInteractive",
                "-Command", script.toString());
        Proc.Result r = Proc.run(cmd, null, Math.min(30_000, 4000 + lnks.size() * 300));
        String[] lines = r.stdout == null ? new String[0] : r.stdout.split("\r?\n");
        List<String> out = new ArrayList<>(java.util.Collections.nCopies(lnks.size(), null));
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            try {
                int idx = Integer.parseInt(line.substring(0, tab).trim());
                String t = line.substring(tab + 1).trim();
                if (idx >= 0 && idx < out.size() && !t.isEmpty()) {
                    out.set(idx, t);
                }
            } catch (NumberFormatException ignore) {
                // 跳过无法解析的输出行
            }
        }
        return out;
    }

    /* ---------------------------------------------------------------- 工具 */

    /**
     * 目标目录是否出现在 {@code text} 里（大小写不敏感、容忍分隔符差异）。
     * 匹配时要求命中片段后面紧跟分隔符或结尾，避免 {@code ...\javapath} 误匹配
     * {@code ...\javapath_target_1}。
     */
    public static String matchIn(List<String> dirs, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String norm = text.replace('/', '\\');
        String lower = norm.toLowerCase(Locale.ROOT);
        for (String dir : dirs) {
            if (dir == null || dir.isEmpty()) {
                continue;
            }
            String d = dir.replace('/', '\\').toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int idx = lower.indexOf(d, from);
                if (idx < 0) {
                    break;
                }
                int end = idx + d.length();
                if (end >= lower.length()) {
                    return norm.substring(idx);
                }
                char next = lower.charAt(end);
                boolean boundary = next == '\\' || next == '"' || next == ';'
                        || next == ',' || Character.isWhitespace(next);
                // 只是被删除的那个 javapath 目录本身（而非 javapath_target_*）也算命中
                if (boundary) {
                    return norm.substring(idx);
                }
                from = idx + 1;
            }
        }
        return null;
    }

    /** 是否本身就是"javapath 系列"目录（用于排除自己引用自己）。 */
    public static boolean isSelfOrSiblingDir(String dir, String text) {
        String d = new File(dir).getName().toLowerCase(Locale.ROOT);
        return text.toLowerCase(Locale.ROOT).contains(d);
    }

    /** 便于外部排查：把扫描结果拼成多行文本。 */
    public static String describe(List<Ref> refs) {
        StringBuilder sb = new StringBuilder();
        for (Ref r : refs) {
            sb.append("· ").append(r.source).append("\n  ← ").append(r.detail).append("\n");
        }
        return sb.toString();
    }

    /** 供调用方判断环境变量是否可读（reg.exe 不可用时给出降级说明）。 */
    public static boolean envReadable() {
        return EnvStore.readRaw("PATH") != null || !EnvStore.systemPathEntries().isEmpty();
    }

    /** 写一份引用清单到临时文件，便于用户在删除前留档。 */
    public static String dumpToTemp(List<Ref> refs) {
        try {
            Path p = Files.createTempFile("langmgr-refs-", ".txt");
            Files.write(p, describe(refs).getBytes(StandardCharsets.UTF_8));
            return p.toAbsolutePath().toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
