package com.langmgr.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户级环境变量的读取与持久化。
 *
 * <p><b>为什么不用 setx：</b>setx 有两个会破坏用户环境的已知问题——
 * 一是它把值截断到 1024 字符，长 PATH 会被直接砍掉后半段；
 * 二是它写入前展开 %VAR%，导致 %JAVA_HOME% 之类的引用被永久固化为字面路径。
 * 因此这里统一走注册表 HKCU\Environment，读写都保留原始 REG_EXPAND_SZ 类型。
 *
 * <p>作用域仅当前用户（HKCU），不触碰系统级变量，无需管理员权限。
 *
 * <p>性能：{@code reg.exe} 单次约 50-150ms，而扫描/界面刷新会反复读同一批值。
 * 这里对“原始值/类型”做 5 秒的短缓存，写操作会立即失效；一次扫描通常只会
 * 真正启动个位数个 reg 进程。需要强制刷新时调用 {@link #invalidateCaches()}。
 */
public final class EnvStore {

    private static final String HKCU_ENV = "HKCU\\Environment";
    /** 系统级环境变量键（只读，不写）。 */
    private static final String HKLM_ENV =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";
    /** setx 的截断阈值，超过此长度的 PATH 用 setx 会丢数据。 */
    public static final int SETX_LIMIT = 1024;
    /** 注册表单个值的安全长度上限（Windows 实际约 32K）。 */
    public static final int REG_VALUE_LIMIT = 30_000;

    /** 短缓存：避免 UI/扫描在几毫秒内反复启动 reg.exe。 */
    private static final long CACHE_TTL_MS = 5_000L;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * 解析 reg query 的值行。
     * 形如 {@code     Path    REG_EXPAND_SZ    C:\...}；
     * 值内部可以含空格，只有“名称/类型/数据”之间是 2 个以上空格。
     */
    private static final Pattern REG_LINE = Pattern.compile(
            "^\\s*(.+?)\\s{2,}(REG_[A-Z_]+)(?:\\s{2,}(.*))?$");

    private static final class Cached {
        final String value;
        final long expiresAt;

        Cached(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static final class RegEntry {
        final String name;
        final String type;
        final String value;

        RegEntry(String name, String type, String value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }
    }

    private EnvStore() {
    }

    /** 清空注册表短缓存（外部修改、测试或需要立即重读时调用）。 */
    public static void invalidateCaches() {
        CACHE.clear();
    }

    /* ------------------------------------------------------------ 读取 */

    /** 从注册表读取用户环境变量的原始值（保留未展开的 %VAR%）。 */
    public static String readRaw(String name) {
        return readRawAt(HKCU_ENV, name);
    }

    /** 从指定注册表键读取环境变量的原始值（保留未展开的 %VAR%）。 */
    public static String readRawAt(String regKey, String name) {
        if (!Os.isWindows()) {
            return readShellProfile(name);
        }
        if (regKey == null || name == null) {
            return null;
        }
        String key = "raw|" + regKey + "|" + name.toLowerCase(Locale.ROOT);
        Cached cached = cacheGet(key);
        if (cached != null) {
            return cached.value;
        }
        RegEntry e = queryRegValue(regKey, name);
        if (e == null) {
            // 连“不存在”也缓存：扫描会检查大量未设置的 HOME 变量，
            // 不缓存的话每个变量都要启动一次 reg.exe。
            cachePut(key, null);
            return null;
        }
        cachePut(key, e.value);
        return e.value;
    }

    /** 读取用户环境变量的注册表类型（REG_SZ / REG_EXPAND_SZ ...）。 */
    public static String readType(String name) {
        return readTypeAt(HKCU_ENV, name);
    }

    public static String readTypeAt(String regKey, String name) {
        if (!Os.isWindows() || regKey == null || name == null) {
            return null;
        }
        String key = "type|" + regKey + "|" + name.toLowerCase(Locale.ROOT);
        Cached cached = cacheGet(key);
        if (cached != null) {
            return cached.value;
        }
        RegEntry e = queryRegValue(regKey, name);
        if (e == null) {
            cachePut(key, null);
            return null;
        }
        cachePut(key, e.type);
        return e.type;
    }

    /** 读取已展开的当前进程值。 */
    public static String readEffective(String name) {
        String v = System.getenv(name);
        if (v != null) {
            return v;
        }
        String raw = readRaw(name);
        return raw == null ? null : Os.expand(raw);
    }

    /** 列出全部用户级环境变量（原始值，未展开）。 */
    public static Map<String, String> listAll() {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Os.isWindows()) {
            for (Map.Entry<String, String> e : System.getenv().entrySet()) {
                map.put(e.getKey(), e.getValue());
            }
            return map;
        }
        for (RegEntry e : queryReg(HKCU_ENV)) {
            map.put(e.name, e.value);
        }
        return map;
    }

    /** 列出全部系统级环境变量（HKLM，只读）。 */
    public static Map<String, String> systemVars() {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Os.isWindows()) {
            return map;
        }
        for (RegEntry e : queryReg(HKLM_ENV)) {
            map.put(e.name, e.value);
        }
        return map;
    }

    private static List<RegEntry> queryReg(String key) {
        Proc.Result r = Proc.run(Arrays.asList("reg", "query", key), null, 8000);
        return r.ok() ? parseRegOutput(r.stdout) : Collections.emptyList();
    }

    private static RegEntry queryRegValue(String key, String name) {
        Proc.Result r = Proc.run(Arrays.asList("reg", "query", key, "/v", name), null, 8000);
        if (!r.ok()) {
            return null;
        }
        for (RegEntry e : parseRegOutput(r.stdout)) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<RegEntry> parseRegOutput(String stdout) {
        List<RegEntry> list = new ArrayList<>();
        if (stdout == null || stdout.isEmpty()) {
            return list;
        }
        for (String line : stdout.split("\\r?\\n")) {
            Matcher m = REG_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).trim();
            if (name.isEmpty()) {
                continue;
            }
            String value = m.group(3) == null ? "" : m.group(3).trim();
            list.add(new RegEntry(name, m.group(2), value));
        }
        return list;
    }

    private static Cached cacheGet(String key) {
        Cached c = CACHE.get(key);
        if (c == null) {
            return null;
        }
        if (c.expiresAt < System.currentTimeMillis()) {
            CACHE.remove(key, c);
            return null;
        }
        return c;
    }

    private static void cachePut(String key, String value) {
        if (key != null) {
            CACHE.put(key, new Cached(value, System.currentTimeMillis() + CACHE_TTL_MS));
        }
    }

    /* ------------------------------------------------------------ 写入 */

    /**
     * 写入用户环境变量。
     * @return 失败时返回错误信息，成功返回 null
     */
    public static String write(String name, String value, boolean expandable) {
        return writeTyped(name, value, expandable ? "REG_EXPAND_SZ" : "REG_SZ");
    }

    /**
     * 按指定注册表类型写入用户环境变量。
     * @return 失败时返回错误信息，成功返回 null
     */
    public static String writeTyped(String name, String value, String type) {
        if (name == null || name.trim().isEmpty()) {
            return "变量名为空";
        }
        String data = value == null ? "" : value;
        if (data.indexOf('\n') >= 0 || data.indexOf('\r') >= 0) {
            return "环境变量值不能包含换行";
        }
        if (!Os.isWindows()) {
            return writeShellProfile(name, data);
        }
        boolean adjusted = false;
        // reg.exe 的值经 MSVCRT 引号解析：以反斜杠结尾且被引号包住时，
        // 末尾的 \" 会被当成转义引号。仅在确实会被引号包裹时补一个反斜杠。
        if (data.endsWith("\\") && needsQuoting(data)) {
            data = data + "\\";
            adjusted = true;
        }
        Proc.Result r = Proc.run(Arrays.asList(
                "reg", "add", HKCU_ENV, "/v", name, "/t", normalizeType(type),
                "/d", data, "/f"), null, 8000);
        if (!r.ok()) {
            String msg = Proc.oneLine(r.effective());
            return "注册表写入失败：" + (msg.isEmpty() ? ("exit=" + r.exitCode) : msg);
        }
        invalidateCaches();
        if (adjusted) {
            // 补反斜杠是 CRT 引号规则下的 workaround，写后读回确认没有多写一个
            String actual = readRawAt(HKCU_ENV, name);
            if (actual == null || !actual.equals(value)) {
                return "注册表写入校验失败：期望 [" + value + "]，实际 [" + actual + "]";
            }
        }
        return null;
    }

    private static boolean needsQuoting(String data) {
        return data.isEmpty() || data.indexOf(' ') >= 0 || data.indexOf('\t') >= 0
                || data.indexOf('"') >= 0;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "REG_SZ";
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        switch (t) {
            case "REG_SZ":
            case "REG_EXPAND_SZ":
            case "REG_MULTI_SZ":
            case "REG_DWORD":
            case "REG_QWORD":
            case "REG_BINARY":
            case "REG_NONE":
                return t;
            default:
                return "REG_SZ";
        }
    }

    /** 删除用户环境变量。 */
    public static String delete(String name) {
        if (!Os.isWindows()) {
            return "当前平台不支持删除，请手动编辑 shell 配置文件";
        }
        Proc.Result r = Proc.run(Arrays.asList(
                "reg", "delete", HKCU_ENV, "/v", name, "/f"), null, 8000);
        if (!r.ok()) {
            String msg = Proc.oneLine(r.effective());
            if (msg.contains("找不到") || msg.toLowerCase(Locale.ROOT).contains("cannot find")
                    || msg.toLowerCase(Locale.ROOT).contains("unable")) {
                invalidateCaches();
                return null; // 本就不存在，视为成功
            }
            return "注册表删除失败：" + msg;
        }
        invalidateCaches();
        return null;
    }

    /* ------------------------------------------------------------ PATH 操作 */

    /** 返回当前用户 PATH 的条目列表（原始，可能含 %VAR%）。 */
    public static List<String> userPathEntries() {
        // 返回可变副本：调用方会 add/remove，不能污染缓存
        return new ArrayList<>(splitPath(readRaw("PATH")));
    }

    /** 返回系统 PATH 的条目列表（原始，可能含 %VAR%；只读 HKLM，不修改）。 */
    public static List<String> systemPathEntries() {
        return splitPath(readRawAt(HKLM_ENV, "Path"));
    }

    /** 系统 PATH 的原始字符串（只读 HKLM）。 */
    public static String systemPathRaw() {
        return readRawAt(HKLM_ENV, "Path");
    }

    /**
     * 注册表里的全部 PATH 目录：系统级在前、用户级在后，展开 %VAR%、去重、保序。
     * 与进程环境变量无关——即使程序是在 PATH 变更前启动的（进程内 PATH 是
     * 启动时的快照），这里拿到的也是磁盘上的真实状态。
     */
    public static List<String> allPathDirs() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String e : systemPathEntries()) {
            String exp = Os.expand(e).trim();
            String key = normalizePath(exp);
            if (!exp.isEmpty() && seen.add(key)) {
                out.add(exp);
            }
        }
        for (String e : userPathEntries()) {
            String exp = Os.expand(e).trim();
            String key = normalizePath(exp);
            if (!exp.isEmpty() && seen.add(key)) {
                out.add(exp);
            }
        }
        return out;
    }

    /**
     * 注册表 PATH 兜底查找。{@link Proc#which} 只看进程环境变量（启动时快照），
     * 若程序在 PATH 变更前启动、或环境变更广播未达资源管理器，新加入的目录
     * 会被漏判；此方法直接按注册表（系统+用户）解析，与刷新状态无关。
     * @return 可执行文件绝对路径，找不到返回 null
     */
    public static String whichFromRegistry(String exe) {
        if (exe == null || exe.trim().isEmpty()) {
            return null;
        }
        List<String> suffixes = Os.isWindows()
                ? Arrays.asList(".exe", ".cmd", ".bat", ".com", "")
                : Arrays.asList("", ".sh");
        for (String dir : allPathDirs()) {
            for (String suf : suffixes) {
                File f = new File(strip(dir), exe + suf);
                if (f.isFile() && f.canRead()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    public static List<String> splitPath(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return list;
        }
        for (String p : raw.split(File.pathSeparator)) {
            if (!p.trim().isEmpty()) {
                list.add(p.trim());
            }
        }
        return list;
    }

    /**
     * 判断某目录是否在 PATH 中（用户级或系统级）。
     * @return 0=不在, 1=在用户级, 2=在系统级(或已展开后命中)
     */
    public static int pathContains(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            return 0;
        }
        String target = normalizePath(dir);
        if (target.isEmpty()) {
            return 0;
        }
        for (String p : splitPath(System.getenv("PATH"))) {
            if (samePath(p, target)) {
                return userPathContains(target) ? 1 : 2;
            }
        }
        return userPathContains(target) ? 1 : 0;
    }

    /** 用户 PATH 中第一次出现的位置，找不到返回 -1。 */
    public static int userPathIndex(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            return -1;
        }
        String target = normalizePath(dir);
        List<String> entries = userPathEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (samePath(entries.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean userPathContains(String normalizedTarget) {
        for (String p : userPathEntries()) {
            if (samePath(p, normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化路径：去引号/尾斜杠、消除 . 和 ..、Windows 下忽略大小写。 */
    public static String normalizePath(String p) {
        if (p == null) {
            return "";
        }
        String s = strip(p);
        if (s.isEmpty()) {
            return "";
        }
        // 只用 Path.normalize 消除 . / ..，不做 getCanonicalPath：
        // 后者会访问磁盘/解析 Junction，PATH 比较会被拖慢。
        try {
            s = new File(s).getAbsoluteFile().toPath().normalize().toString();
        } catch (RuntimeException ignored) {
        }
        if (Os.isWindows()) {
            s = s.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    /** 去引号与结尾路径分隔符，保留原始大小写。 */
    public static String strip(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim();
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        while (s.length() > 1 && (s.endsWith("\\") || s.endsWith("/"))
                && !(s.length() <= 3 && s.length() > 1 && s.charAt(1) == ':')) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 两个路径是否指向同一目录（忽略引号/尾斜杠/大小写/..）。 */
    public static boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = normalizePath(a);
        String y = normalizePath(b);
        return !x.isEmpty() && x.equals(y);
    }

    /**
     * 把目录加入用户 PATH 末尾。已存在则不动。
     * @return 错误信息，成功返回 null
     */
    public static String addToPath(String dir) {
        List<String> entries = userPathEntries();
        for (String e : entries) {
            if (samePath(Os.expand(e), dir)) {
                return null;
            }
        }
        entries.add(dir);
        String joined = String.join(File.pathSeparator, entries);
        if (joined.length() > REG_VALUE_LIMIT) {
            return "PATH 过长（" + joined.length() + " 字符，上限约 " + REG_VALUE_LIMIT + "），请手动精简后再试";
        }
        return write("PATH", joined, true);
    }

    /**
     * 从用户 PATH 移除目录（所有重复项）。
     * @return 错误信息，成功返回 null
     */
    public static String removeFromPath(String dir) {
        List<String> entries = userPathEntries();
        boolean removed = entries.removeIf(e -> samePath(Os.expand(e), dir));
        if (!removed) {
            return null;
        }
        String joined = String.join(File.pathSeparator, entries);
        return write("PATH", joined, true);
    }

    /** 去重用户 PATH（保留首次出现顺序）。返回新列表，不写入。 */
    public static List<String> dedupePath(List<String> entries) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String e : entries) {
            // 归一化后再判重：去引号/尾斜杠/..、Windows 下忽略大小写，
            // 否则 "C:\a" 与 "C:\a\" 会被当成两条，重复条目统计不到
            String key = normalizePath(Os.expand(e));
            if (!key.isEmpty() && seen.add(key)) {
                out.add(e);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------ 备份 */

    /**
     * 备份单个变量到 backup 目录，返回备份文件路径。
     * 失败时抛 IOException，调用方据此中止写入——先备份再改，避免写坏后无从还原。
     */
    public static Path backupSingle(String name) throws IOException {
        return backup(Collections.singletonList(name), "PATH".equalsIgnoreCase(name));
    }

    /**
     * 备份多个变量（含 PATH）到 backup 目录，返回备份文件路径。
     * 输出格式与 EnvApplier.backup 兼容，可用同一套回滚逻辑还原。
     */
    public static Path backupMany(List<String> names) throws IOException {
        boolean includePath = false;
        if (names != null) {
            for (String n : names) {
                if (n != null && n.equalsIgnoreCase("PATH")) {
                    includePath = true;
                }
            }
        }
        return backup(names, includePath);
    }

    /**
     * 备份指定变量（可同时备份 PATH 的原始值）到 JSON。
     * 同时记录每个值的注册表类型，回滚时才能还原 REG_EXPAND_SZ 等类型。
     */
    public static Path backup(List<String> names, boolean includePath) throws IOException {
        File dir = AppPaths.backupDir();
        Files.createDirectories(dir.toPath());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> types = new LinkedHashMap<>();
        if (names != null) {
            for (String n : names) {
                if (n == null || n.trim().isEmpty()) {
                    continue;
                }
                vars.put(n, readRaw(n));
                types.put(n, readType(n));
            }
        }
        data.put("pathRaw", includePath ? readRaw("PATH") : null);
        data.put("vars", vars);
        data.put("types", types);
        data.put("os", Os.label());
        Path file = uniqueBackupFile(dir);
        Files.write(file, Json.write(data).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 备份目录（与 EnvApplier 共用）。 */
    public static File backupDir() {
        return AppPaths.backupDir();
    }

    private static Path uniqueBackupFile(File dir) {
        String base = "env-backup-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path file = dir.toPath().resolve(base + ".json");
        int n = 1;
        while (Files.exists(file)) {
            file = dir.toPath().resolve(base + "-" + n++ + ".json");
        }
        return file;
    }

    /**
     * 广播 WM_SETTINGCHANGE，让资源管理器/新终端立即感知变量变化。
     * 借助 PowerShell 的 Add-Type 调用 user32!SendMessageTimeout，无需第三方库。
     * 已存在的终端不会刷新——这是 Windows 的固有限制。
     *
     * <p>注意必须用<b>绝对路径</b>调用 PowerShell：Windows PowerShell 装在
     * {@code System32\WindowsPowerShell\v1.0\}，这个子目录默认不在 PATH 里，
     * 用裸命令名 "powershell" 会启动失败并让广播静默失效。
     */
    public static void broadcastChange() {
        if (!Os.isWindows()) {
            return;
        }
        String shell = findPowerShell();
        if (shell == null) {
            return;
        }
        String ps = "$sig='[DllImport(\"user32.dll\",SetLastError=true,CharSet=CharSet.Auto)]"
                + "public static extern IntPtr SendMessageTimeout(IntPtr hWnd,uint Msg,UIntPtr wParam,"
                + "string lParam,uint fuFlags,uint uTimeout,out UIntPtr lpdwResult);';"
                + "$t=Add-Type -MemberDefinition $sig -Name 'N' -Namespace 'W' -PassThru;"
                + "$r=[UIntPtr]::Zero;"
                + "[void]$t::SendMessageTimeout([IntPtr]0xffff,0x001A,[UIntPtr]::Zero,'Environment',2,5000,[ref]$r)";
        Proc.run(Arrays.asList(shell, "-NoProfile", "-NonInteractive", "-Command", ps), null, 10_000);
    }

    /**
     * 定位 PowerShell 可执行文件。优先 pwsh（新版跨平台），
     * 其次 Windows PowerShell 的绝对路径，最后才回退到 PATH 查找。
     * @return 可用的绝对路径，全都找不到时返回 null
     */
    public static String findPowerShell() {
        String pwsh = Proc.which("pwsh");
        if (pwsh != null) {
            return pwsh;
        }
        String sysRoot = System.getenv("SystemRoot");
        if (sysRoot == null || sysRoot.trim().isEmpty()) {
            sysRoot = System.getenv("windir");
        }
        if (sysRoot == null || sysRoot.trim().isEmpty()) {
            sysRoot = "C:\\Windows";
        }
        File candidate = new File(sysRoot,
                "System32" + File.separator + "WindowsPowerShell" + File.separator
                        + "v1.0" + File.separator + "powershell.exe");
        if (candidate.isFile()) {
            return candidate.getAbsolutePath();
        }
        return Proc.which("powershell");
    }

    /* -------------------------------------------------- 非 Windows 平台降级 */

    private static String readShellProfile(String name) {
        for (Path p : profileFiles()) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("export " + name + "=")) {
                        String v = t.substring(("export " + name + "=").length()).trim();
                        return unquote(v);
                    }
                    if (t.startsWith("setenv " + name + " ")) {
                        return unquote(t.substring(("setenv " + name + " ").length()).trim());
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return System.getenv(name);
    }

    private static String writeShellProfile(String name, String value) {
        List<Path> files = profileFiles();
        if (files.isEmpty()) {
            return "未找到可写的 shell 配置文件";
        }
        Path target = files.get(0);
        try {
            List<String> lines = Files.exists(target)
                    ? new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            String prefix = "export " + name + "=";
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith(prefix)) {
                    lines.set(i, prefix + quote(value));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(prefix + quote(value));
            }
            Files.write(target, lines, StandardCharsets.UTF_8);
            return null;
        } catch (IOException ex) {
            return "写入 " + target + " 失败：" + ex.getMessage();
        }
    }

    private static List<Path> profileFiles() {
        List<Path> list = new ArrayList<>();
        File home = Os.home();
        String shell = System.getenv("SHELL");
        if (shell != null && shell.contains("zsh")) {
            list.add(Paths.get(home.getAbsolutePath(), ".zshrc"));
        }
        list.add(Paths.get(home.getAbsolutePath(), ".bash_profile"));
        list.add(Paths.get(home.getAbsolutePath(), ".bashrc"));
        list.add(Paths.get(home.getAbsolutePath(), ".profile"));
        return list;
    }

    private static String quote(String v) {
        if (v == null) {
            return "\"\"";
        }
        // 写入 shell profile 时必须转义 $、反引号和反斜杠，
        // 否则值里的 $(...) 会在下次启动 shell 时被执行。
        return "\"" + v.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`") + "\"";
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
