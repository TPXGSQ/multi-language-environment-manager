package com.langmgr.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语言环境扫描引擎。
 *
 * <p>扫描分三层，逐层补充信息：
 * <ol>
 *   <li><b>PATH 探测</b>——按 {@link LangSpec} 的命令跑一遍，拿到当前生效的版本与可执行文件路径；</li>
 *   <li><b>多版本发现</b>——扫描已知安装目录、注册表、以及语言自带的版本枚举命令
 *       （py launcher / conda env list / dotnet --list-sdks / rustup toolchains），
 *       找出装了但不在 PATH 里的版本，这是版本切换功能的数据来源；</li>
 *   <li><b>环境与依赖</b>——校验关联的 HOME 类变量是否指向有效目录、是否在 PATH 中，
 *       并探测各包管理器是否可用。</li>
 * </ol>
 *
 * <p>全程只读：不写注册表、不改文件、不动 PATH。
 */
public final class Scanner {

    /** 单条命令的超时，避免卡死的解释器拖慢整体扫描。 */
    private static final long CMD_TIMEOUT = 10_000;

    /**
     * PowerShell 专用超时。
     * Windows PowerShell 冷启动需要加载 .NET 运行时与内置模块，实测可接近 9 秒，
     * 沿用通用超时会让它卡在临界点上、检测结果时好时坏，因此单独放宽。
     */
    private static final long PS_CMD_TIMEOUT = 25_000;

    /**
     * py launcher 的 -0p 输出行。
     * 同时兼容新版 {@code -V:3.12-64 *  C:\path} 与旧版 {@code -3.9-64  C:\path}，
     * 也允许行首/行尾带 * 标记。
     * 分组：1=位数（32/64，可缺省） 2=可执行文件路径
     */
    private static final Pattern PY_LIST_LINE = Pattern.compile(
            "^\\*?\\s*(?:-V:|-)?\\s*(?:\\d+\\.\\d+)?\\s*(?:-(32|64))?\\s*\\*?\\s*(\\S.*?\\.exe)\\s*\\*?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** 进度回调，用于界面显示扫描状态。 */
    public interface Listener {
        void onProgress(String langName, int done, int total);
    }

    private final Listener listener;
    /**
     * 同一次扫描内缓存版本命令结果：PATH 探测与多版本发现经常对同一个
     * exe + 参数各跑一次，缓存后只启动一个进程。
     */
    private final Map<String, Proc.Result> probeCache = new ConcurrentHashMap<>();

    public Scanner(Listener listener) {
        this.listener = listener;
    }

    public Scanner() {
        this(null);
    }

    /* ------------------------------------------------------------ 主入口 */

    public Detected.Report scanAll() {
        long t0 = System.currentTimeMillis();
        Detected.Report report = new Detected.Report();
        List<LangSpec> specs = new ArrayList<>(LangSpec.all().values());
        int total = specs.size();

        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "langmgr-scan");
            t.setDaemon(true);
            return t;
        });

        try {
            CompletionService<Detected.LangResult> ecs = new ExecutorCompletionService<>(pool);
            Map<Future<Detected.LangResult>, LangSpec> pending = new LinkedHashMap<>();
            for (LangSpec spec : specs) {
                Future<Detected.LangResult> f = ecs.submit((Callable<Detected.LangResult>) () -> scanOne(spec));
                pending.put(f, spec);
            }
            int done = 0;
            while (!pending.isEmpty()) {
                Future<Detected.LangResult> f;
                try {
                    // 单个任务最多等 90 秒；Proc 自身已有 10-25 秒超时，这里只是兜底
                    f = ecs.poll(90, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    for (LangSpec spec : new ArrayList<>(pending.values())) {
                        report.results.add(failedResult(spec, "扫描被中断"));
                    }
                    pending.clear();
                    break;
                }
                if (f == null) {
                    for (LangSpec spec : new ArrayList<>(pending.values())) {
                        report.results.add(failedResult(spec, "扫描超时（90 秒）"));
                    }
                    pending.clear();
                    break;
                }
                LangSpec spec = pending.remove(f);
                if (spec == null) {
                    continue;
                }
                try {
                    Detected.LangResult r = f.get();
                    report.results.add(r);
                    if (listener != null) {
                        listener.onProgress(r.spec.displayName, ++done, total);
                    }
                } catch (Exception ex) {
                    Detected.LangResult r = failedResult(spec, "扫描异常：" + rootMessage(ex));
                    report.results.add(r);
                    if (listener != null) {
                        listener.onProgress(r.spec.displayName, ++done, total);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 按"已安装优先、再按注册顺序"稳定排序
        Map<String, Integer> order = new LinkedHashMap<>();
        int idx = 0;
        for (LangSpec s : specs) {
            order.put(s.id, idx++);
        }
        report.results.sort((a, b) -> {
            if (a.installed != b.installed) {
                return a.installed ? -1 : 1;
            }
            return Integer.compare(order.getOrDefault(a.spec.id, 999), order.getOrDefault(b.spec.id, 999));
        });

        report.systemPathRaw = EnvStore.systemPathRaw();
        report.totalMillis = System.currentTimeMillis() - t0;
        return report;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static Detected.LangResult failedResult(LangSpec spec, String note) {
        Detected.LangResult r = new Detected.LangResult();
        r.spec = spec;
        r.notes.add(note);
        return r;
    }

    /** 带缓存的命令执行：同一个 Scanner 内相同命令只跑一次。 */
    private Proc.Result runCached(List<String> command, long timeoutMillis) {
        String key = String.join("\u0000", command) + "|" + timeoutMillis;
        return probeCache.computeIfAbsent(key, k -> Proc.run(command, null, timeoutMillis));
    }

    /* -------------------------------------------------------- 单语言扫描 */

    public Detected.LangResult scanOne(LangSpec spec) {
        long t0 = System.currentTimeMillis();
        Detected.LangResult result = new Detected.LangResult();
        result.spec = spec;

        // ---- 1. PATH 中的可执行文件 ----
        Map<String, String> inPath = new LinkedHashMap<>();
        for (String exe : spec.exeNames) {
            // 先查注册表（系统级在前、用户级在后）：这才是新开终端命中的顺序。
            // 进程内的 PATH 是启动瞬间的快照——本工具改完变量后它仍是旧值，
            // 用它判定"当前生效"会让用户切完版本刷新后看到切换前的状态。
            String p = EnvStore.whichFromRegistry(exe);
            if (p == null) {
                p = Proc.which(exe);
            }
            if (p != null) {
                inPath.put(exe, p);
            }
        }

        // ---- 2. 逐个 probe 尝试，拿到生效版本 ----
        String pathExe = inPath.isEmpty() ? null : inPath.values().iterator().next();
        String pathExeName = inPath.isEmpty() ? null : inPath.keySet().iterator().next();
        if (pathExe != null) {
            for (LangSpec.Probe probe : spec.probes) {
                List<String> cmd = new ArrayList<>(probe.command);
                // 把命令首个 token 换成实际路径，避免 PATH 里同名程序的歧义
                if (!cmd.isEmpty() && cmd.get(0).equalsIgnoreCase(pathExeName)) {
                    cmd.set(0, pathExe);
                } else if (!cmd.isEmpty() && inPath.containsKey(cmd.get(0))) {
                    cmd.set(0, inPath.get(cmd.get(0)));
                }
                Proc.Result pr = runCached(cmd, CMD_TIMEOUT);
                String out = pr.effective();
                // 版本解析用 stdout+stderr 合并文本：有些工具把横幅打在 stderr、
                // 其余打在 stdout（如 cl /?——横幅在 stderr，帮助在 stdout），
                // 只取 effective() 会在这种"两边都有内容"的情况下漏掉版本横幅。
                String combined = pr.stdout + "\n" + pr.stderr;
                if (pr.timedOut || pr.error != null) {
                    result.notes.add(String.join(" ", cmd) + " → " + (pr.error != null ? pr.error : "超时"));
                    continue;
                }
                // 退出码非 0 说明命令并未正常完成（典型：本机只装了 .NET 运行时没装 SDK，
                // `dotnet --version` 会打印一段错误说明并以非 0 退出）。
                // 此时输出里的文字不是版本信息，采信会导致把错误文案误当版本号，必须跳过。
                if (pr.exitCode != 0) {
                    result.notes.add(String.join(" ", cmd) + " → 退出码 " + pr.exitCode
                            + "（" + Proc.firstLine(out) + "）");
                    continue;
                }
                String ver = probe.extract(combined);
                if (ver != null && !ver.isEmpty() && !ver.equals(combined)) {
                    Detected.Install inst = new Detected.Install();
                    inst.version = ver;
                    inst.exePath = pathExe;
                    inst.rootDir = guessRoot(pathExe, spec);
                    inst.source = "PATH";
                    inst.active = true;
                    inst.rawOutput = Proc.oneLine(out);
                    inst.arch = guessArch(spec.id, out);
                    result.active = inst;
                    result.installed = true;
                    break;
                } else if (!out.isEmpty() && pr.ok()) {
                    // 命令跑通了但没解析出版本，仍算已安装
                    Detected.Install inst = new Detected.Install();
                    inst.version = Proc.firstLine(out);
                    inst.exePath = pathExe;
                    inst.rootDir = guessRoot(pathExe, spec);
                    inst.source = "PATH";
                    inst.active = true;
                    inst.rawOutput = Proc.oneLine(out);
                    result.active = inst;
                    result.installed = true;
                    break;
                }
            }
            if (!result.installed && pathExe != null) {
                // 文件存在但所有 probe 都失败：仍标记为"存在但无法识别版本"
                Detected.Install inst = new Detected.Install();
                inst.version = "无法识别";
                inst.exePath = pathExe;
                inst.rootDir = guessRoot(pathExe, spec);
                inst.source = "PATH（版本探测失败）";
                inst.active = true;
                result.active = inst;
                result.installed = true;
                result.notes.add("可执行文件存在，但版本命令未返回可解析的输出。");
            }
        }

        // ---- 2a-2. 环境变量不可选版本的语言：PATH 命中也不构成"可切换" ----
        // MSVC/C# 这类语言的版本由安装器管理（见 LangSpec.envSwitchable）。
        // "一键加入 PATH"修复后命令能被调用了，但那只解决"找不到命令"，
        // 不解决"选哪个版本"——若仍标记 switchable，切换页会出现一个
        // 假装可切换的条目，且把真正需要解释的不可切换版本挤掉。
        if (result.active != null && !spec.envSwitchable) {
            result.active.switchable = false;
        }

        // ---- 2b. 转发器标注：命中 ≠ 实际使用 ----
        // Oracle 安装器会往系统 PATH 里塞 javapath\java.exe，它按注册表转发到某个 JDK。
        // 命中它只是"候选/配置"，真正跑起来的是另一个文件——当前生效必须按实际使用判定，
        // 所以 exePath 归位到真实目标；转发器本身另立一条记录（不可切换、标记为拦截），
        // 因为用户在版本列表里也需要看到它，否则无法理解"为什么切了没反应"。
        Detected.Install forwarderRow = null;
        if (result.active != null) {
            String hit = result.active.exePath;
            String real = realExeBehind(hit, spec);
            if (real != null) {
                result.notes.add("PATH 命中的 " + hit + " 是转发器，实际运行 " + real + "。");
                result.hitExePath = hit;
                result.forwarderPath = hit;

                forwarderRow = new Detected.Install();
                forwarderRow.version = result.active.version;
                forwarderRow.exePath = hit;
                File fdir = new File(hit).getParentFile();
                forwarderRow.rootDir = fdir == null ? null : fdir.getAbsolutePath();
                forwarderRow.arch = result.active.arch;
                forwarderRow.rawOutput = result.active.rawOutput;
                forwarderRow.source = "系统 PATH 转发器";
                forwarderRow.switchable = false;
                forwarderRow.blocking = true;
                forwarderRow.active = false;

                result.active.exePath = real;
                result.active.source = "PATH（经转发器）";
            }
        }

        // ---- 3. 发现其他安装实例 ----
        List<Detected.Install> extra = new ArrayList<>();
        try {
            extra.addAll(discoverInstalls(spec));
        } catch (RuntimeException ex) {
            result.notes.add("多版本发现失败：" + rootMessage(ex));
        }
        if (forwarderRow != null) {
            extra.add(forwarderRow);
        }
        mergeInstalls(result, extra);

        // ---- 3b. 转发器残留：已从 PATH 移出、但目录还躺在磁盘上 ----
        // 用户删掉系统 PATH 条目后，Oracle 的 javapath 目录（及其 javapath_target_*）
        // 并不会被一起删掉。它已不再影响命中，但仍是可执行文件的实体，
        // 这里主动报出来，免得用户对着"明明执行了命令"和"目录还在"反复怀疑。
        result.staleForwarderDirs.addAll(findStaleForwarderDirs(spec, result));
        // 残留目录也进安装列表（标 stale、不可切换），这样"版本切换"和
        // "语言与解释器"两个页面都能看到它，而不是只在概览页出现一次
        for (String dir : result.staleForwarderDirs) {
            Detected.Install ghost = new Detected.Install();
            ghost.version = "—";
            ghost.exePath = new File(dir, "java.exe").getAbsolutePath();
            ghost.rootDir = dir;
            ghost.source = "转发器残留（已不在 PATH）";
            ghost.switchable = false;
            ghost.stale = true;
            ghost.active = false;
            result.installs.add(ghost);
        }

        // ---- 4. 环境变量校验 ----
        for (String var : spec.homeVars) {
            Detected.VarState vs = new Detected.VarState();
            vs.name = var;
            vs.rawValue = EnvStore.readRaw(var);
            String eff = vs.rawValue == null ? System.getenv(var) : Os.expand(vs.rawValue);
            vs.effectiveValue = eff;
            if (eff != null && !eff.trim().isEmpty()) {
                File dir = new File(eff.trim());
                vs.targetExists = dir.exists();
                if (!vs.targetExists) {
                    vs.note = "指向的目录不存在";
                } else {
                    vs.inPath = EnvStore.pathContains(dir.getAbsolutePath()) != 0
                            || EnvStore.pathContains(new File(dir, "bin").getAbsolutePath()) != 0;
                    // HOME 类变量语义上指向「安装根目录」，所以必须拿 rootDir 比对，
                    // 不能拿 exePath：PATH 里的 java.exe 常是 Oracle 的 javapath 转发目录
                    // （Junction），用它比对会把配置正确的 JAVA_HOME 误报成不一致。
                    Detected.Install ref = result.active != null
                            ? result.active
                            : (result.installs.isEmpty() ? null : result.installs.get(0));
                    if (ref != null && ref.rootDir != null && !Os.isUnder(ref.rootDir, eff.trim())) {
                        vs.note = "与当前生效的安装位置不一致（实际：" + ref.rootDir + "）";
                    } else if (!vs.inPath) {
                        vs.note = "目录存在，但其 bin 未加入 PATH";
                    }
                }
            } else {
                vs.note = "未设置";
            }
            result.vars.put(var, vs);
        }

        // ---- 5. 包管理器 ----
        for (LangSpec.PkgManager pm : spec.pkgManagers) {
            Detected.PkgState ps = new Detected.PkgState();
            ps.id = pm.id;
            ps.displayName = pm.displayName;
            List<String> cmd = substituteForPm(pm.versionCmd, result, spec);
            if (cmd != null) {
                Proc.Result pr = runCached(cmd, CMD_TIMEOUT);
                String out = pr.effective();
                if (pr.error != null || pr.timedOut) {
                    ps.installed = false;
                    ps.error = pr.timedOut ? "执行超时" : pr.error;
                } else {
                    String ver = null;
                    if (pm.versionRegex != null) {
                        Matcher m = Pattern.compile(pm.versionRegex, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE)
                                .matcher(out);
                        if (m.find()) {
                            ver = m.group(1).trim();
                        }
                    }
                    if (ver == null) {
                        Matcher m = Pattern.compile("(\\d+\\.\\d+[\\w.\\-+]*)").matcher(out);
                        ver = m.find() ? m.group(1) : null;
                    }
                    if (ver != null || pr.ok()) {
                        ps.installed = true;
                        ps.version = ver != null ? ver : Proc.firstLine(out);
                        ps.exePath = Proc.which(cmd.get(0));
                        if (ps.exePath == null) {
                            ps.exePath = cmd.get(0);
                        }
                    } else {
                        ps.installed = false;
                        ps.error = Proc.firstLine(out);
                    }
                }
            } else {
                ps.installed = false;
                ps.error = "依赖的解释器不可用";
            }
            result.pkgManagers.add(ps);
        }

        result.scanMillis = System.currentTimeMillis() - t0;
        return result;
    }

    /**
     * 包管理器命令的解释器替换。
     * pip 这类需要挂在具体 python 上跑，若语言本身不可用则返回 null。
     */
    private static List<String> substituteForPm(List<String> cmd, Detected.LangResult result, LangSpec spec) {
        if (cmd == null || cmd.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>(cmd);
        String head = out.get(0);
        boolean headIsLangExe = spec.exeNames.stream().anyMatch(n -> n.equalsIgnoreCase(head));
        if (headIsLangExe) {
            if (result.active == null || result.active.exePath == null) {
                return null;
            }
            out.set(0, result.active.exePath);
        } else {
            String resolved = Proc.which(head);
            if (resolved == null) {
                return null;
            }
            out.set(0, resolved);
        }
        return out;
    }

    /** 从可执行文件路径反推安装根目录（一般是去掉末尾的 bin 段）。 */
    private static String guessRoot(String exePath, LangSpec spec) {
        if (exePath == null) {
            return null;
        }
        // Java 特殊处理：PATH 里的 java.exe 常是 Oracle 的 javapath 转发目录（Junction），
        // 从路径反推会得到转发目录而非真实 JDK。直接问 JVM 要 java.home 才是权威答案。
        //
        // 仅在 spec 非空（即处理 PATH 中的生效解释器）时才做这个查询：它需要额外启动一次
        // java 进程，而对目录扫描发现的 JDK 没有必要——调用方已经用真实目录覆盖了 rootDir，
        // 无条件查询会让扫描耗时成倍增长。
        if (spec != null && spec.id.equals("java")) {
            String home = queryJavaHome(exePath);
            if (home != null) {
                return home;
            }
        }
        File f = new File(exePath);
        File parent = f.getParentFile();
        if (parent == null) {
            return null;
        }
        String pn = parent.getName().toLowerCase();
        // 常见布局：root/bin/exe、root/exe、root/Scripts/exe(python venv)、root/Contents/Home/bin(java mac)
        if (pn.equals("bin") || pn.equals("sbin") || pn.equals("scripts") || pn.equals("cmd")) {
            File gp = parent.getParentFile();
            if (gp != null) {
                // macOS 的 .../Contents/Home 需要再上一层到 .jdk/Contents/Home
                if (gp.getName().equalsIgnoreCase("Home") && gp.getParentFile() != null
                        && gp.getParentFile().getName().equalsIgnoreCase("Contents")) {
                    return gp.getAbsolutePath();
                }
                return gp.getAbsolutePath();
            }
        }
        return parent.getAbsolutePath();
    }

    private static boolean isJavaExe(String exePath) {
        if (exePath == null) {
            return false;
        }
        String n = new File(exePath).getName().toLowerCase();
        return n.equals("java.exe") || n.equals("java");
    }

    /** java.home 查询结果缓存，避免对同一可执行文件重复启动 JVM。 */
    private static final Map<String, String> JAVA_HOME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 通过 -XshowSettings:properties 读取 java.home，得到真实 JDK/JRE 根目录。
     * 这样即使 PATH 里的是 javapath 之类的转发链接，也能定位到实际安装位置。
     */
    private static String queryJavaHome(String javaExe) {
        if (javaExe == null || !new File(javaExe).isFile()) {
            return null;
        }
        String key = Os.isWindows() ? javaExe.toLowerCase() : javaExe;
        String cached = JAVA_HOME_CACHE.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = null;
        Proc.Result r = Proc.run(Arrays.asList(javaExe, "-XshowSettings:properties", "-version"),
                null, CMD_TIMEOUT);
        String out = r.effective();
        if (!out.isEmpty()) {
            Matcher m = Pattern.compile("^\\s*java\\.home\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE).matcher(out);
            if (m.find()) {
                String home = m.group(1).trim();
                if (new File(home).isDirectory()) {
                    resolved = home;
                }
            }
        }
        JAVA_HOME_CACHE.put(key, resolved == null ? "" : resolved);
        return resolved;
    }

    /**
     * 把转发器路径还原成它实际启动的可执行文件；认不出来返回 null（保持原样）。
     *
     * <p>javapath 下的 java.exe 是按注册表转发的中间层，既不是符号链接，也没有
     * 可读的目标路径，单看文件系统无解；只能问 JVM 要 {@code java.home}，
     * 再拼回 {@code <home>/bin/<exe 名>}。
     *
     * <p>只对 Java 生效：其它语言（py launcher、npm 的 .cmd shim）没有同等可靠的
     * 权威出口，硬猜会把路径改错。
     */
    private static String realExeBehind(String exePath, LangSpec spec) {
        if (exePath == null || spec == null || !"java".equals(spec.id)) {
            return null;
        }
        File exe = new File(exePath);
        if (!exe.isFile()) {
            return null;
        }
        String homeStr = queryJavaHome(exePath);
        if (homeStr == null) {
            return null;
        }
        File home = new File(homeStr);
        // JDK 8 的 java.home 指向 <jdk>\jre，真正的启动器在 <jdk>\bin，
        // 归位到 jdk 根才能和目录扫描出来的安装合并成同一条。
        if (home.getName().equalsIgnoreCase("jre") && home.getParentFile() != null
                && new File(new File(home.getParentFile(), "bin"), exe.getName()).isFile()) {
            home = home.getParentFile();
        }
        File real = new File(new File(home, "bin"), exe.getName());
        if (!real.isFile()) {
            return null;
        }
        if (real.getAbsolutePath().equalsIgnoreCase(exe.getAbsolutePath())) {
            return null;
        }
        return real.getAbsolutePath();
    }

    /**
     * 新开一个终端时会命中的可执行文件（可能仍是转发器）。
     *
     * <p>顺序必须是"注册表 PATH（系统级在前、用户级在后）"——那正是 Windows 拼装
     * 新进程环境的规则，也是用户实际得到的结果；进程内 PATH 只是本进程启动时的快照。
     */
    public static String hitPath(LangSpec spec) {
        if (spec == null) {
            return null;
        }
        for (String exe : spec.exeNames) {
            String p = EnvStore.whichFromRegistry(exe);
            if (p == null) {
                p = Proc.which(exe);
            }
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * 实际运行的那个可执行文件：命中转发器时返回转发器指向的真实目标。
     * 切换/删除后用它验证"到底生效了没有"，避免把写变量成功误报成切换成功。
     */
    public static String effectiveExePath(LangSpec spec) {
        String hit = hitPath(spec);
        if (hit == null) {
            return null;
        }
        String real = realExeBehind(hit, spec);
        return real != null ? real : hit;
    }

    /** 从版本输出里猜架构信息。 */
    private static String guessArch(String langId, String output) {
        if (output == null) {
            return null;
        }
        String low = output.toLowerCase();
        String arch = null;
        if (low.contains("aarch64") || low.contains("arm64")) {
            arch = "arm64";
        } else if (low.contains("x86_64") || low.contains("amd64") || low.contains("x64")) {
            arch = "x86_64";
        } else if (low.contains("i386") || low.contains("i686") || low.contains("x86 ")) {
            arch = "x86";
        }
        if (langId.equals("java") && low.contains("64-bit")) {
            return arch == null ? "64-Bit VM" : arch;
        }
        return arch;
    }

    /** 把新发现的实例并入结果，按 rootDir+version 去重。 */
    private static void mergeInstalls(Detected.LangResult result, List<Detected.Install> extra) {
        Set<String> keys = new LinkedHashSet<>();
        if (result.active != null) {
            keys.add(key(result.active));
            result.installs.add(result.active);
        }
        for (Detected.Install e : extra) {
            String k = key(e);
            if (keys.add(k)) {
                result.installs.add(e);
            }
        }
        // 版本排序：新版本在前
        result.installs.sort((a, b) -> {
            if (a.active != b.active) {
                return a.active ? -1 : 1;
            }
            return compareVersion(b.version, a.version);
        });
        result.installed = !result.installs.isEmpty();
        if (result.active == null && !result.installs.isEmpty()) {
            // PATH 里没有，但发现了安装实例
            result.notes.add("发现 " + result.installs.size() + " 个安装，但都不在当前 PATH 中。");
        }
    }

    private static String key(Detected.Install i) {
        String root = i.rootDir == null ? "" : Os.isWindows() ? i.rootDir.toLowerCase() : i.rootDir;
        String exe = i.exePath == null ? "" : Os.isWindows() ? i.exePath.toLowerCase() : i.exePath;
        return root + "|" + exe + "|" + i.version;
    }

    /** 版本号比较，支持 1.8.0_291 / 21.0.2 / 3.12.1 等混合格式。 */
    public static int compareVersion(String a, String b) {
        List<String> pa = splitVersion(a);
        List<String> pb = splitVersion(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            String x = i < pa.size() ? pa.get(i) : "0";
            String y = i < pb.size() ? pb.get(i) : "0";
            try {
                int cmp = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
                if (cmp != 0) {
                    return cmp;
                }
            } catch (NumberFormatException ex) {
                int cmp = x.compareToIgnoreCase(y);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    private static List<String> splitVersion(String v) {
        if (v == null) {
            return Collections.emptyList();
        }
        String s = v.replaceAll("[^0-9a-zA-Z._\\-]", "");
        // Java 8 的 1.8.0_291 → 统一按分隔符切
        String[] parts = s.split("[._\\-+]");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /* -------------------------------------------------- 多版本发现（分语言） */

    private List<Detected.Install> discoverInstalls(LangSpec spec) {
        switch (spec.id) {
            case "java":   return discoverJava();
            case "python": return discoverPython();
            case "node":   return discoverNode();
            case "dotnet": return discoverDotnet();
            case "csharp": return discoverCsharp();
            case "msvc":   return discoverMsvc();
            case "gcc":    return discoverGcc();
            case "rust":   return discoverRust();
            case "go":     return discoverGo();
            case "ruby":   return discoverRuby();
            case "php":    return discoverPhp();
            case "flutter":return discoverFlutter();
            case "perl":   return discoverPerl();
            case "powershell": return discoverPowerShell();
            default:       return discoverGeneric(spec);
        }
    }

    /**
     * PowerShell 发现。
     * Windows PowerShell 5.1 装在 System32\WindowsPowerShell\v1.0\，该子目录默认不在 PATH 中，
     * 仅靠 which 查找会把它误判为未安装，因此直接按已知路径探测。
     */
    private List<Detected.Install> discoverPowerShell() {
        List<Detected.Install> out = new ArrayList<>();
        List<File> candidates = new ArrayList<>();
        String pwsh = Proc.which("pwsh");
        if (pwsh != null) {
            candidates.add(new File(pwsh));
        }
        String sysRoot = System.getenv("SystemRoot");
        if (sysRoot == null || sysRoot.trim().isEmpty()) {
            sysRoot = System.getenv("windir");
        }
        if (sysRoot == null || sysRoot.trim().isEmpty()) {
            sysRoot = "C:\\Windows";
        }
        candidates.add(new File(sysRoot,
                "System32" + File.separator + "WindowsPowerShell" + File.separator
                        + "v1.0" + File.separator + "powershell.exe"));
        // PowerShell 7+：%ProgramFiles%\PowerShell\7\pwsh.exe（目录名是版本号）
        File psRoot = new File(programFiles(), "PowerShell");
        File[] psKids = psRoot.listFiles(File::isDirectory);
        if (psKids != null) {
            for (File k : psKids) {
                candidates.add(new File(k, "pwsh.exe"));
            }
        }

        for (File exe : candidates) {
            if (!exe.isFile()) {
                continue;
            }
            boolean isCore = exe.getName().equalsIgnoreCase("pwsh.exe");
            // PATH 中生效的同一个 exe 已经由 scanOne 探测过，避免同一文件
            // 因注册表/进程两种版本来源被列成两条（版本号还会不一致）
            String active = Proc.which(isCore ? "pwsh" : "powershell");
            if (active != null && exe.getAbsoluteFile().equals(new File(active).getAbsoluteFile())) {
                continue;
            }
            String ver = null;
            String raw = null;
            String probeNote = null;

            if (!isCore) {
                // Windows PowerShell：优先读注册表 PowerShellEngine\PowerShellVersion。
                // 该值是毫秒级读取，不受 powershell.exe 冷启动（需加载 .NET 与模块，
                // 实测可长达数秒、且时快时慢）影响，因此作为首选，比进程探测稳定得多。
                String regVer = readWindowsPowerShellRegistryVersion();
                if (regVer != null) {
                    ver = regVer;
                    raw = "注册表 HKLM\\SOFTWARE\\Microsoft\\PowerShell\\3\\PowerShellEngine";
                }
            }

            if (ver == null) {
                // pwsh 直接 --version（本身很快）；Windows PowerShell 在注册表缺失时用进程兜底。
                List<List<String>> attempts = new ArrayList<>();
                attempts.add(Arrays.asList(exe.getAbsolutePath(), "-NoProfile", "-NonInteractive",
                        "-Command", "$PSVersionTable.PSVersion.ToString()"));
                attempts.add(Arrays.asList(exe.getAbsolutePath(), "--version"));
                for (List<String> cmd : attempts) {
                    Proc.Result r = runCached(cmd, PS_CMD_TIMEOUT);
                    String o = r.effective();
                    if (r.timedOut) {
                        probeNote = "版本探测超时（PowerShell 冷启动过慢）";
                        continue;
                    }
                    if (r.exitCode == 0 && !o.trim().isEmpty()) {
                        Matcher m = Pattern.compile("(\\d+\\.\\d+[\\w.\\-]*)").matcher(o);
                        if (m.find()) {
                            ver = m.group(1);
                            raw = Proc.oneLine(o);
                            probeNote = null;
                            break;
                        }
                    }
                }
            }

            Detected.Install i = new Detected.Install();
            i.exePath = exe.getAbsolutePath();
            i.rootDir = exe.getParentFile() == null ? null : exe.getParentFile().getAbsolutePath();
            i.source = isCore ? "PowerShell 7+" : "Windows PowerShell";
            i.rawOutput = raw;
            if (ver != null) {
                i.version = ver;
            } else {
                // 关键：可执行文件确实存在，就必须报告为「已安装」。
                // 仅仅因为版本没探到就整个丢弃，会让用户看到「未安装」的错误结论。
                i.version = "已安装（版本未获取）";
                i.rawOutput = probeNote == null
                        ? "可执行文件存在，但版本命令未返回可解析的输出"
                        : probeNote;
            }
            out.add(i);
        }
        return out;
    }

    /**
     * 从注册表读取 Windows PowerShell 版本。
     * 键：HKLM\SOFTWARE\Microsoft\PowerShell\3\PowerShellEngine，值名 PowerShellVersion。
     * @return 形如 "5.1.19041.1" 的版本串，读不到返回 null
     */
    private static String readWindowsPowerShellRegistryVersion() {
        if (!Os.isWindows()) {
            return null;
        }
        Proc.Result r = Proc.run(Arrays.asList("reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\PowerShell\\3\\PowerShellEngine",
                "/v", "PowerShellVersion"), null, 6000);
        if (!r.ok()) {
            return null;
        }
        for (String line : r.stdout.split("\\r?\\n")) {
            String t = line.trim();
            if (t.toLowerCase().startsWith("powershellversion")) {
                Matcher m = Pattern.compile("(\\d+\\.\\d+[\\w.\\-]*)").matcher(t);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /**
     * 找出"转发器目录还在磁盘上、但已经不在任何 PATH 里"的残留。
     *
     * <p>这是删完系统 PATH 条目后的常见后续状态：命中问题解决了，目录还在。
     * 只扫描已知的转发器容器目录，不做全盘搜索——目录名是固定的，
     * 扫固定位置既快又不会误判成用户的正常安装。
     *
     * <p>只报告"目录存在且不在 PATH 里"的情况；在 PATH 里的属于拦截问题，
     * 由 {@link Detected.LangResult#forwarderPath} 单独表达，不重复。
     */
    private static List<String> findStaleForwarderDirs(LangSpec spec, Detected.LangResult result) {
        List<String> out = new ArrayList<>();
        if (!Os.isWindows() || spec == null || !"java".equals(spec.id)) {
            return out;
        }
        // 已知容器：Oracle 安装器的 javapath 与它实际承载可执行文件的 javapath_target_*
        List<File> containers = new ArrayList<>();
        containers.add(new File(programFilesCommon(), "Oracle\\Java"));
        containers.add(new File(programFilesX86Common(), "Oracle\\Java"));
        for (File container : containers) {
            File[] kids = container.listFiles();
            if (kids == null) {
                continue;
            }
            for (File kid : kids) {
                if (!kid.isDirectory()) {
                    continue;
                }
                String n = kid.getName().toLowerCase();
                if (!n.startsWith("javapath") && !n.startsWith("java_path")) {
                    continue;
                }
                if (!new File(kid, "java.exe").isFile()) {
                    continue;
                }
                String dir = kid.getAbsolutePath();
                // 仍在 PATH 里 → 属于"拦截"，由 forwarderPath 表达
                if (EnvStore.pathContains(dir) != 0) {
                    continue;
                }
                if (result.hitExePath != null
                        && EnvStore.samePath(new File(result.hitExePath).getParent(), dir)) {
                    continue;
                }
                out.add(dir);
            }
        }
        return out;
    }

    /** Program Files\Common Files（不写死盘符）。 */
    private static File programFilesCommon() {
        return new File(programFiles(), "Common Files");
    }

    private static File programFilesX86Common() {
        return new File(programFilesX86(), "Common Files");
    }

    /* ---------------- Java ---------------- */

    private List<Detected.Install> discoverJava() {
        List<Detected.Install> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        addExisting(roots, new File(programFiles(), "Java").getAbsolutePath());
        addExisting(roots, new File(programFilesX86(), "Java").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "Eclipse Adoptium").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "Microsoft").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "Amazon Corretto").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "Zulu").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "BellSoft").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "JetBrains").getAbsolutePath());
        addExisting(roots, new File(programFiles(), "Android\\Android Studio\\jbr").getAbsolutePath());
        addExisting(roots, "D:\\Program Files\\Java");
        addExisting(roots, new File(Os.home(), ".jdks").getAbsolutePath());
        addExisting(roots, new File(Os.home(), ".sdkman\\candidates\\java").getAbsolutePath());
        addExisting(roots, new File(Os.home(), ".gradle\\jdks").getAbsolutePath());
        addExisting(roots, "/Library/Java/JavaVirtualMachines");
        addExisting(roots, "/usr/lib/jvm");

        for (File root : roots) {
            collectJdkDirs(root, out, 2);
        }

        // 注册表：Oracle / Adoptium 等会登记 JavaHome
        out.addAll(javaFromRegistry("HKLM\\SOFTWARE\\JavaSoft\\JDK"));
        out.addAll(javaFromRegistry("HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit"));
        out.addAll(javaFromRegistry("HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JDK"));
        out.addAll(javaFromRegistry("HKCU\\SOFTWARE\\JavaSoft\\JDK"));
        return out;
    }

    private void collectJdkDirs(File root, List<Detected.Install> out, int depth) {
        if (depth < 0 || root == null || !root.isDirectory()) {
            return;
        }
        File bin = new File(root, "bin");
        File javaExe = new File(bin, Os.isWindows() ? "java.exe" : "java");
        if (javaExe.isFile()) {
            Detected.Install i = probeExe(javaExe.getAbsolutePath(), "-version",
                    "version \"?([\\d._\\-ea+]+)\"?", "JDK 目录");
            if (i != null) {
                i.rootDir = root.getAbsolutePath();
                out.add(i);
            }
            return;
        }
        // macOS bundle: xxx.jdk/Contents/Home/bin/java
        File macHome = new File(root, "Contents/Home/bin/java");
        if (macHome.isFile()) {
            Detected.Install i = probeExe(macHome.getAbsolutePath(), "-version",
                    "version \"?([\\d._\\-ea+]+)\"?", "JDK 目录");
            if (i != null) {
                i.rootDir = new File(root, "Contents/Home").getAbsolutePath();
                out.add(i);
            }
            return;
        }
        File[] kids = root.listFiles(File::isDirectory);
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            String n = k.getName().toLowerCase();
            if (n.startsWith(".") && !n.equals(".jdks")) {
                continue;
            }
            collectJdkDirs(k, out, depth - 1);
        }
    }

    private List<Detected.Install> javaFromRegistry(String key) {
        List<Detected.Install> out = new ArrayList<>();
        if (!Os.isWindows()) {
            return out;
        }
        Proc.Result r = Proc.run(Arrays.asList("reg", "query", key, "/s", "/v", "JavaHome"), null, 8000);
        if (!r.ok()) {
            return out;
        }
        for (String line : r.stdout.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.startsWith("JavaHome")) {
                continue;
            }
            String[] parts = t.split("\\s{2,}");
            if (parts.length < 3) {
                continue;
            }
            String home = parts[parts.length - 1].trim();
            File exe = new File(new File(home, "bin"), "java.exe");
            if (exe.isFile()) {
                Detected.Install i = probeExe(exe.getAbsolutePath(), "-version",
                        "version \"?([\\d._\\-ea+]+)\"?", "注册表");
                if (i != null) {
                    i.rootDir = home;
                    out.add(i);
                }
            }
        }
        return out;
    }

    /* ---------------- Python ---------------- */

    private List<Detected.Install> discoverPython() {
        List<Detected.Install> out = new ArrayList<>();

        // py launcher 枚举。
        // 输出格式有两种，必须都支持，否则会漏掉整个解释器：
        //   新版：  -V:3.12-64 *        C:\...\python.exe
        //   旧版：  -3.9-64             C:\...\python.exe
        String pyLauncher = Proc.which("py");
        if (pyLauncher != null) {
            Proc.Result r = runCached(Arrays.asList(pyLauncher, "-0p"), CMD_TIMEOUT);
            String listing = (r.stdout == null || r.stdout.trim().isEmpty()) ? r.stderr : r.stdout;
            if (listing != null) {
                for (String line : listing.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.toLowerCase().contains("installed pythons")) {
                        continue; // 跳过 launcher 打印的标题行
                    }
                    Matcher m = PY_LIST_LINE.matcher(t);
                    if (!m.find()) {
                        continue;
                    }
                    String exe = m.group(2).trim();
                    if (exe.startsWith("\"") && exe.endsWith("\"") && exe.length() > 1) {
                        exe = exe.substring(1, exe.length() - 1);
                    }
                    if (!new File(exe).isFile()) {
                        continue;
                    }
                    Detected.Install i = probeExe(exe, "--version", "Python ([\\d.]+[\\w.]*)", "py launcher");
                    if (i != null) {
                        String bits = m.group(1);
                        if (bits != null && !bits.isEmpty()) {
                            i.arch = bits + "-bit";
                        }
                        out.add(i);
                    }
                }
            }
        }

        // conda 环境枚举
        String conda = Proc.which("conda");
        if (conda != null) {
            Proc.Result r = runCached(Arrays.asList(conda, "env", "list", "--json"), CMD_TIMEOUT);
            Map<String, Object> json = Json.asMap(Json.parse(r.stdout));
            List<Object> envs = json == null ? null : Json.asList(json.get("envs"));
            if (envs != null) {
                for (Object o : envs) {
                    String envDir = Json.asString(o);
                    if (envDir == null || envDir.trim().isEmpty()) {
                        continue;
                    }
                    File exe = pythonExeIn(envDir.trim());
                    if (exe != null) {
                        Detected.Install i = probeExe(exe.getAbsolutePath(), "--version",
                                "Python ([\\d.]+[\\w.]*)", "conda 环境");
                        if (i != null) {
                            i.rootDir = envDir.trim();
                            out.add(i);
                        }
                    }
                }
            }
        }

        // 常见目录
        List<File> roots = new ArrayList<>();
        addExisting(roots, "C:\\Python*");
        addExisting(roots, new File(programFiles(), "Python*").getAbsolutePath());
        addExisting(roots, "D:\\Python*");
        addExisting(roots, "D:\\Anaconda*");
        addExisting(roots, "C:\\Anaconda*");
        addExisting(roots, "C:\\ProgramData\\Anaconda*");
        addExisting(roots, "C:\\ProgramData\\miniconda*");
        addExisting(roots, new File(Os.home(), "AppData\\Local\\Programs\\Python").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "miniconda3").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "anaconda3").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "AppData\\Local\\Microsoft\\WindowsApps").getAbsolutePath());
        addExisting(roots, "/usr/bin");
        addExisting(roots, "/usr/local/bin");

        for (File root : roots) {
            if (root.isFile()) {
                continue;
            }
            File exe = pythonExeIn(root.getAbsolutePath());
            if (exe != null) {
                Detected.Install i = probeExe(exe.getAbsolutePath(), "--version",
                        "Python ([\\d.]+[\\w.]*)", "目录扫描");
                if (i != null) {
                    i.rootDir = root.getAbsolutePath();
                    out.add(i);
                }
            } else {
                // 容器型目录：Programs\Python\Python39\、scoop\apps\python\current\ 等，
                // 解释器在下一层子目录里，需要再遍历一层，否则会整体漏掉。
                File[] subKids = root.listFiles(File::isDirectory);
                if (subKids != null) {
                    for (File sub : subKids) {
                        File subExe = pythonExeIn(sub.getAbsolutePath());
                        if (subExe == null) {
                            continue;
                        }
                        Detected.Install i = probeExe(subExe.getAbsolutePath(), "--version",
                                "Python ([\\d.]+[\\w.]*)", "目录扫描");
                        if (i != null) {
                            i.rootDir = sub.getAbsolutePath();
                            out.add(i);
                        }
                    }
                }
            }
            // conda envs 子目录
            File envsDir = new File(root, "envs");
            File[] envKids = envsDir.listFiles(File::isDirectory);
            if (envKids != null) {
                for (File e : envKids) {
                    File exe2 = pythonExeIn(e.getAbsolutePath());
                    if (exe2 != null) {
                        Detected.Install i = probeExe(exe2.getAbsolutePath(), "--version",
                                "Python ([\\d.]+[\\w.]*)", "conda 环境");
                        if (i != null) {
                            i.rootDir = e.getAbsolutePath();
                            out.add(i);
                        }
                    }
                }
            }
            // WindowsApps 下的 python3.x.exe
            File[] kids = root.listFiles(f -> f.isFile()
                    && f.getName().toLowerCase().matches("python3?\\.?\\d*\\.exe"));
            if (kids != null) {
                for (File f : kids) {
                    if (f.length() < 1024) {
                        continue; // WindowsApps 的应用执行别名是 0 字节 stub，跳过
                    }
                    Detected.Install i = probeExe(f.getAbsolutePath(), "--version",
                            "Python ([\\d.]+[\\w.]*)", "目录扫描");
                    if (i != null) {
                        out.add(i);
                    }
                }
            }
        }
        return out;
    }

    private static File pythonExeIn(String dir) {
        if (dir == null) {
            return null;
        }
        File d = new File(dir);
        if (!d.isDirectory()) {
            return null;
        }
        for (String n : Os.isWindows()
                ? Arrays.asList("python.exe", "Scripts\\python.exe")
                : Arrays.asList("bin/python3", "bin/python")) {
            File f = new File(d, n);
            if (f.isFile() && f.length() > 1024) {
                return f;
            }
        }
        return null;
    }

    /* ---------------- Node ---------------- */

    private List<Detected.Install> discoverNode() {
        List<Detected.Install> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        addExisting(roots, new File(programFiles(), "nodejs").getAbsolutePath());
        addExisting(roots, new File(programFilesX86(), "nodejs").getAbsolutePath());
        addExisting(roots, "D:\\Program Files\\nodejs");
        addExisting(roots, new File(Os.home(), "AppData\\Local\\Programs\\nodejs").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "AppData\\Roaming\\nvm").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "AppData\\Local\\fnm_multishells").getAbsolutePath());
        addExisting(roots, new File(Os.home(), ".nvm\\versions\\node").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "AppData\\Local\\Volta\\tools\\image\\node").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "scoop\\apps\\nodejs").getAbsolutePath());
        addExisting(roots, "/usr/local/bin");
        addExisting(roots, "/usr/bin");

        for (File root : roots) {
            File exe = new File(root, Os.isWindows() ? "node.exe" : "node");
            if (exe.isFile()) {
                Detected.Install i = probeExe(exe.getAbsolutePath(), "--version", "v([\\d.]+)", "目录扫描");
                if (i != null) {
                    out.add(i);
                }
                continue;
            }
            // nvm / volta 布局：root/vX.Y.Z/node.exe
            File[] kids = root.listFiles(File::isDirectory);
            if (kids == null) {
                continue;
            }
            for (File k : kids) {
                File e2 = new File(k, Os.isWindows() ? "node.exe" : "bin/node");
                if (e2.isFile()) {
                    Detected.Install i = probeExe(e2.getAbsolutePath(), "--version", "v([\\d.]+)",
                            root.getName().toLowerCase().contains("nvm") ? "nvm" : "目录扫描");
                    if (i != null) {
                        i.rootDir = k.getAbsolutePath();
                        out.add(i);
                    }
                }
            }
        }
        return out;
    }

    /* ---------------- .NET ---------------- */

    /**
     * 定位 dotnet 宿主：优先 PATH，找不到再查标准安装位置。
     * 很多人装 .NET 时没勾"添加到 PATH"（或 PATH 被精简过），只按 which
     * 查找会把整套运行时误判成未安装——宿主文件的落点是固定的，直接探测即可。
     */
    private static String resolveDotnetExe() {
        if (!Os.isWindows()) {
            return Proc.which("dotnet");
        }
        String onPath = Proc.which("dotnet");
        if (onPath != null) {
            return onPath;
        }
        List<File> candidates = new ArrayList<>();
        String pf = System.getenv("ProgramW6432");
        if (pf != null && !pf.trim().isEmpty()) {
            candidates.add(new File(pf, "dotnet\\dotnet.exe"));
        }
        candidates.add(new File(programFiles(), "dotnet\\dotnet.exe"));
        candidates.add(new File(programFilesX86(), "dotnet\\dotnet.exe"));
        candidates.add(new File(Os.home(), ".dotnet\\dotnet.exe"));
        for (File f : candidates) {
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private List<Detected.Install> discoverDotnet() {
        List<Detected.Install> out = new ArrayList<>();
        String dotnet = resolveDotnetExe();
        if (dotnet == null) {
            return out;
        }
        String source = Proc.which("dotnet") != null ? "dotnet --list-sdks" : "dotnet 标准安装目录";
        Proc.Result r = runCached(Arrays.asList(dotnet, "--list-sdks"), CMD_TIMEOUT);
        for (String line : r.stdout.split("\\r?\\n")) {
            Matcher m = Pattern.compile("^\\s*([\\d.]+[\\w\\-.]*)\\s*\\[(.+?)\\]\\s*$").matcher(line);
            if (m.find()) {
                Detected.Install i = new Detected.Install();
                i.version = m.group(1) + " SDK";
                i.rootDir = m.group(2);
                i.exePath = dotnet;
                i.source = source;
                // SDK 版本由 global.json 决定，不能像 JDK 那样靠改环境变量切换；
                // 而且 --list-sdks 方括号里是 ...\sdk，写成 DOTNET_ROOT 会破坏宿主定位。
                i.switchable = false;
                out.add(i);
            }
        }
        Proc.Result r2 = runCached(Arrays.asList(dotnet, "--list-runtimes"), CMD_TIMEOUT);
        Set<String> seen = new LinkedHashSet<>();
        for (String line : r2.stdout.split("\\r?\\n")) {
            Matcher m = Pattern.compile("^\\s*(\\S+)\\s+([\\d.]+[\\w\\-.]*)\\s*\\[(.+?)\\]\\s*$").matcher(line);
            if (m.find()) {
                String rt = m.group(1) + " " + m.group(2);
                if (seen.add(rt)) {
                    Detected.Install i = new Detected.Install();
                    i.version = rt;
                    i.rootDir = m.group(3);
                    i.exePath = dotnet;
                    i.source = source;
                    // 运行时由 dotnet 宿主按项目自动选择，不能通过改 HOME 变量来切换。
                    // 标记为不可切换，避免在版本切换页里给出无效甚至有害的选项。
                    i.switchable = false;
                    out.add(i);
                }
            }
        }
        if (out.isEmpty()) {
            // 宿主存在但 SDK/运行时一个都没枚举出来（典型：--list-* 因缺 SDK 报错退出）。
            // 从 host\fxr 目录名兜底取宿主版本，仍要报告"已安装"，不能静默丢弃。
            File fxr = new File(new File(dotnet).getParentFile(), "host\\fxr");
            File[] kids = fxr.listFiles(File::isDirectory);
            String hostVer = null;
            if (kids != null) {
                for (File k : kids) {
                    if (hostVer == null || Scanner.compareVersion(k.getName(), hostVer) > 0) {
                        hostVer = k.getName();
                    }
                }
            }
            Detected.Install i = new Detected.Install();
            i.version = hostVer != null ? hostVer + "（仅运行时宿主）" : "已安装（版本未获取）";
            i.rootDir = new File(dotnet).getParent();
            i.exePath = dotnet;
            i.source = source;
            i.switchable = false;
            out.add(i);
        }
        return out;
    }

    /* ---------------- C# (.NET Framework / Roslyn) ---------------- */

    /**
     * 传统 C# 编译器发现。
     *
     * <p>csc.exe 基本从不进 PATH，只按 which 查会整体漏报。来源有两类：
     * <ol>
     *   <li>.NET Framework 自带编译器：C:\Windows\Microsoft.NET\Framework(64)\v*\csc.exe，
     *       版本可从横幅（"Visual C# Compiler version 4.8.x"）或目录名 v4.0.30319 取得；</li>
     *   <li>Visual Studio 的 Roslyn 编译器：&lt;VS&gt;\MSBuild\Current\Bin\Roslyn\csc.exe，
     *       版本形如 3.x，通常比 Framework 自带的新。</li>
     * </ol>
     * 两者都标记为不可切换：编译器是安装器落盘的固定文件，改环境变量不会影响哪份被用。
     */
    private List<Detected.Install> discoverCsharp() {
        List<Detected.Install> out = new ArrayList<>();
        if (!Os.isWindows()) {
            return out;
        }
        // 1) .NET Framework 目录（64 位与 32 位两套）
        String windir = System.getenv("SystemRoot");
        if (windir == null || windir.trim().isEmpty()) {
            windir = "C:\\Windows";
        }
        for (String sub : new String[]{"Microsoft.NET\\Framework64", "Microsoft.NET\\Framework"}) {
            File base = new File(windir, sub);
            File[] kids = base.listFiles(File::isDirectory);
            if (kids == null) {
                continue;
            }
            for (File k : kids) {
                String dirName = k.getName().toLowerCase();
                // 只认 v4+ 的 Framework 编译器：v2.0/v3.5 的上古 csc 报出来的
                // 版本号是 "8.00.50727" 这种编译器内部版本，排在 4.8 前面
                // 会被误读成 "C# 8"，徒增困惑；它们早已停止支持，不值得展示。
                if (!dirName.matches("v(4|5|\\d{2,})\\..*")) {
                    continue;
                }
                File csc = new File(k, "csc.exe");
                if (!csc.isFile()) {
                    continue;
                }
                Detected.Install i = probeExe(csc.getAbsolutePath(), "/?",
                        "Compiler version\\s+([\\d.]+)", ".NET Framework");
                if (i != null) {
                    if (i.version == null || i.version.startsWith("已安装")) {
                        // 横幅没解析出来时用目录名兜底（v4.0.30319 → 4.0.30319）
                        String dirVer = k.getName().substring(1);
                        if (dirVer.matches("[\\d.]+")) {
                            i.version = dirVer;
                            i.rawOutput = "目录名 " + k.getName();
                        }
                    }
                    i.rootDir = k.getAbsolutePath();
                    i.switchable = false;
                    out.add(i);
                }
            }
        }
        // 2) Visual Studio 的 Roslyn 编译器
        for (Detected.Install vs : discoverVsInstances()) {
            File roslyn = new File(vs.rootDir, "MSBuild\\Current\\Bin\\Roslyn\\csc.exe");
            if (!roslyn.isFile()) {
                continue;
            }
            Detected.Install i = probeExe(roslyn.getAbsolutePath(), "/?",
                    "Compiler version\\s+([\\d.]+)", "Visual Studio（Roslyn）");
            if (i != null) {
                i.rootDir = roslyn.getParent();
                i.switchable = false;
                out.add(i);
            }
        }
        return out;
    }

    /* ---------------- MSVC ---------------- */

    /**
     * 用 vswhere 枚举 Visual Studio / Build Tools 实例（VS2017+ 的官方出口）。
     * 返回的 Install 只填 rootDir（VS 实例根目录）与 source，供其它发现流程复用。
     */
    private List<Detected.Install> discoverVsInstances() {
        List<Detected.Install> out = new ArrayList<>();
        if (!Os.isWindows()) {
            return out;
        }
        File vswhere = new File(programFilesX86(),
                "Microsoft Visual Studio\\Installer\\vswhere.exe");
        if (!vswhere.isFile()) {
            return out;
        }
        Proc.Result r = runCached(Arrays.asList(vswhere.getAbsolutePath(),
                "-all", "-products", "*", "-property", "installationPath"), CMD_TIMEOUT);
        for (String line : r.stdout.split("\\r?\\n")) {
            String dir = line.trim();
            if (dir.isEmpty() || !new File(dir).isDirectory()) {
                continue;
            }
            Detected.Install i = new Detected.Install();
            i.rootDir = dir;
            i.source = "vswhere";
            out.add(i);
        }
        return out;
    }

    /**
     * MSVC（cl.exe）发现。
     *
     * <p>cl 默认不在 PATH（需要 vcvars64.bat 才可见），只按 which 查必然漏报，
     * 这正是"装了 C++ 却显示不出来"的主因。发现路径：
     * <ol>
     *   <li>vswhere 枚举出的每个 VS 实例，扫 VC\Tools\MSVC\&lt;版本&gt;\bin\Host*&;&lt;架构&gt;\cl.exe，
     *       版本号直接取目录名（14.29.30133），权威且不用启动 cl；</li>
     *   <li>按已知安装根目录兜底扫描（vswhere 缺失的旧版安装器/自定义安装）；</li>
     *   <li>VS2015 及更早的布局 VC\bin\cl.exe，版本从横幅解析。</li>
     * </ol>
     */
    private List<Detected.Install> discoverMsvc() {
        List<Detected.Install> out = new ArrayList<>();
        if (!Os.isWindows()) {
            return out;
        }
        Set<String> seenCl = new LinkedHashSet<>();

        // 1) vswhere 枚举的实例 + 2) 已知安装根目录兜底，统一走同一套扫描
        List<File> vsRoots = new ArrayList<>();
        for (Detected.Install vs : discoverVsInstances()) {
            if (vs.rootDir != null) {
                vsRoots.add(new File(vs.rootDir));
            }
        }
        File vsBaseX86 = new File(programFilesX86(), "Microsoft Visual Studio");
        File vsBaseX64 = new File(programFiles(), "Microsoft Visual Studio");
        for (File base : new File[]{vsBaseX86, vsBaseX64}) {
            File[] years = base.listFiles(File::isDirectory);
            if (years == null) {
                continue;
            }
            for (File year : years) {
                File[] editions = year.listFiles(File::isDirectory);
                if (editions == null) {
                    continue;
                }
                for (File ed : editions) {
                    if (!vsRoots.contains(ed)) {
                        vsRoots.add(ed);
                    }
                }
            }
        }

        for (File vs : vsRoots) {
            // VS2017+ 布局：VC\Tools\MSVC\<ver>\bin\Hostx64\x64\cl.exe
            File msvcRoot = new File(vs, "VC\\Tools\\MSVC");
            File[] versions = msvcRoot.listFiles(File::isDirectory);
            if (versions != null) {
                for (File v : versions) {
                    if (!v.getName().matches("\\d+\\.\\d+.*")) {
                        continue;
                    }
                    File bin = new File(v, "bin");
                    File[] hosts = bin.listFiles(File::isDirectory);
                    if (hosts == null) {
                        continue;
                    }
                    // 优先 Hostx64\x64，其次任意 Host*/x64，再退到任意组合
                    File chosen = pickHostCl(hosts);
                    if (chosen == null) {
                        continue;
                    }
                    if (seenCl.add(chosen.getAbsolutePath().toLowerCase())) {
                        Detected.Install i = new Detected.Install();
                        i.version = v.getName() + "（工具集）";
                        i.exePath = chosen.getAbsolutePath();
                        i.rootDir = v.getAbsolutePath();
                        i.source = "Visual Studio";
                        // MSVC 工具集是 VS 安装器管理的固定目录，改环境变量切不了版本
                        i.switchable = false;
                        i.rawOutput = "VS 实例：" + vs.getAbsolutePath();
                        out.add(i);
                    }
                }
            }
            // VS2015 及更早：VC\bin\cl.exe，版本只能从横幅解析
            File oldCl = new File(vs, "VC\\bin\\cl.exe");
            if (oldCl.isFile() && seenCl.add(oldCl.getAbsolutePath().toLowerCase())) {
                Detected.Install i = probeExe(oldCl.getAbsolutePath(), "/?",
                        "C/C\\+\\+.{0,60}?(\\d+\\.\\d+\\.\\d+)", "Visual Studio（旧版布局）");
                if (i != null) {
                    i.rootDir = oldCl.getParent();
                    i.switchable = false;
                    out.add(i);
                }
            }
        }
        return out;
    }

    /** 在 VC\Tools\MSVC\<ver>\bin 下的 Host* 目录里挑最合适的 cl.exe。 */
    private static File pickHostCl(File[] hosts) {
        File fallback = null;
        for (File h : hosts) {
            String hn = h.getName().toLowerCase();
            File x64 = new File(h, "x64\\cl.exe");
            if (x64.isFile()) {
                if (hn.equals("hostx64")) {
                    return x64;
                }
                if (fallback == null) {
                    fallback = x64;
                }
            }
            if (fallback == null) {
                File any = new File(h, "x86\\cl.exe");
                if (any.isFile()) {
                    fallback = any;
                }
            }
        }
        return fallback;
    }

    /* ---------------- GCC (MinGW 系目录兜底) ---------------- */

    /**
     * MinGW 系 GCC 发现。
     *
     * <p>GCC 装完不进 PATH 的情形很多（解压版 MinGW-w64、MSYS2 未做 profile 链接、
     * 绿色版 TDM-GCC），只按 which 查会漏。这里扫已知的常见落点，
     * 版本仍由 gcc/g++ 自己报告，不做任何猜测。
     */
    private List<Detected.Install> discoverGcc() {
        if (!Os.isWindows()) {
            return new ArrayList<>();
        }
        List<Detected.Install> out = new ArrayList<>();
        String[] regexes = {"gcc\\s.*?\\b(\\d+\\.\\d+(?:\\.\\d+)?)", "\\(GCC\\)\\s+(\\d+(?:\\.\\d+)+)"};
        String[][] targets = {
                {"C:\\mingw64\\bin", "gcc.exe"},
                {"C:\\MinGW\\bin", "gcc.exe"},
                {"C:\\msys64\\mingw64\\bin", "gcc.exe"},
                {"C:\\msys64\\ucrt64\\bin", "gcc.exe"},
                {"C:\\msys64\\clang64\\bin", "gcc.exe"},
                {"C:\\TDM-GCC-64\\bin", "gcc.exe"},
                {"C:\\Program Files\\mingw-w64", "*\\mingw64\\bin\\gcc.exe"},
                {"C:\\Program Files (x86)\\mingw-w64", "*\\mingw64\\bin\\gcc.exe"},
                {"D:\\mingw64\\bin", "gcc.exe"},
                {"D:\\msys64\\mingw64\\bin", "gcc.exe"},
                {"D:\\TDM-GCC-64\\bin", "gcc.exe"},
        };
        for (String[] t : targets) {
            File dir = new File(t[0]);
            if (!dir.isDirectory()) {
                continue;
            }
            List<File> exeFiles = new ArrayList<>();
            if (t[1].contains("*")) {
                // 嵌套通配：mingw-w64\<full-ver>\mingw64\bin\gcc.exe
                File[] kits = dir.listFiles(File::isDirectory);
                if (kits != null) {
                    for (File k : kits) {
                        File e = new File(k, t[1].replaceFirst("^[^\\\\]+\\\\", ""));
                        if (e.isFile()) {
                            exeFiles.add(e);
                        }
                    }
                }
            } else {
                File e = new File(dir, t[1]);
                if (e.isFile()) {
                    exeFiles.add(e);
                }
            }
            for (File e : exeFiles) {
                Detected.Install i = null;
                for (String rx : regexes) {
                    i = probeExe(e.getAbsolutePath(), "--version", rx, "MinGW 目录");
                    if (i != null && i.version != null && !i.version.startsWith("已安装")) {
                        break;
                    }
                }
                if (i != null) {
                    i.rootDir = e.getParentFile() == null ? null : e.getParentFile().getParentFile() == null
                            ? null : e.getParentFile().getParentFile().getAbsolutePath();
                    out.add(i);
                }
            }
        }
        return out;
    }

    /* ---------------- Rust ---------------- */

    private List<Detected.Install> discoverRust() {
        List<Detected.Install> out = new ArrayList<>();
        String rustupHome = System.getenv("RUSTUP_HOME");
        File toolchains = rustupHome != null && !rustupHome.isEmpty()
                ? new File(rustupHome, "toolchains")
                : new File(Os.home(), ".rustup\\toolchains");
        File[] kids = toolchains.listFiles(File::isDirectory);
        if (kids != null) {
            for (File k : kids) {
                File exe = new File(k, "bin" + File.separator + (Os.isWindows() ? "rustc.exe" : "rustc"));
                if (exe.isFile()) {
                    Detected.Install i = probeExe(exe.getAbsolutePath(), "--version",
                            "rustc ([\\d.]+[\\w.\\-+]*)", "rustup toolchain");
                    if (i != null) {
                        i.rootDir = k.getAbsolutePath();
                        i.version = i.version + " [" + k.getName() + "]";
                        out.add(i);
                    }
                }
            }
        }
        addExisting(out, new File(programFiles(), "Rust").getAbsolutePath(), "rustc.exe", "--version",
                "rustc ([\\d.]+[\\w.\\-+]*)", "目录扫描");
        return out;
    }

    /* ---------------- Go ---------------- */

    private List<Detected.Install> discoverGo() {
        List<Detected.Install> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        addExisting(roots, new File(programFiles(), "Go").getAbsolutePath());
        addExisting(roots, "C:\\Go");
        addExisting(roots, "D:\\Go");
        addExisting(roots, "D:\\Program Files\\Go");
        addExisting(roots, new File(Os.home(), "AppData\\Local\\Programs\\Go").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "sdk").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "scoop\\apps\\go").getAbsolutePath());
        addExisting(roots, "/usr/local/go");
        addExisting(roots, "/usr/lib/go");

        for (File root : roots) {
            File exe = new File(root, "bin" + File.separator + (Os.isWindows() ? "go.exe" : "go"));
            if (!exe.isFile()) {
                exe = new File(root, Os.isWindows() ? "go.exe" : "go");
            }
            if (exe.isFile()) {
                Detected.Install i = probeExe(exe.getAbsolutePath(), "version", "go([\\d.]+[\\w.]*)", "目录扫描");
                if (i != null) {
                    i.rootDir = root.getAbsolutePath();
                    out.add(i);
                }
                continue;
            }
            File[] kids = root.listFiles(File::isDirectory);
            if (kids != null) {
                for (File k : kids) {
                    File e2 = new File(k, "bin" + File.separator + (Os.isWindows() ? "go.exe" : "go"));
                    if (e2.isFile()) {
                        Detected.Install i = probeExe(e2.getAbsolutePath(), "version",
                                "go([\\d.]+[\\w.]*)", "目录扫描");
                        if (i != null) {
                            i.rootDir = k.getAbsolutePath();
                            out.add(i);
                        }
                    }
                }
            }
        }
        return out;
    }

    /* ---------------- Ruby / PHP / Flutter / Perl ---------------- */

    private List<Detected.Install> discoverRuby() {
        List<Detected.Install> out = new ArrayList<>();
        addExisting(out, "C:\\Ruby*", "bin\\ruby.exe", "--version", "ruby ([\\d.p]+)", "目录扫描");
        addExisting(out, new File(programFiles(), "Ruby*").getAbsolutePath(), "bin\\ruby.exe", "--version", "ruby ([\\d.p]+)", "目录扫描");
        File rbenv = new File(Os.home(), ".rbenv\\versions");
        File[] kids = rbenv.listFiles(File::isDirectory);
        if (kids != null) {
            for (File k : kids) {
                File exe = new File(k, "bin" + File.separator + (Os.isWindows() ? "ruby.exe" : "ruby"));
                if (exe.isFile()) {
                    Detected.Install i = probeExe(exe.getAbsolutePath(), "--version", "ruby ([\\d.p]+)", "rbenv");
                    if (i != null) {
                        i.rootDir = k.getAbsolutePath();
                        out.add(i);
                    }
                }
            }
        }
        return out;
    }

    private List<Detected.Install> discoverPhp() {
        List<Detected.Install> out = new ArrayList<>();
        addExisting(out, "C:\\php*", "php.exe", "--version", "PHP ([\\d.]+[\\w\\-.]*)", "目录扫描");
        addExisting(out, new File(programFiles(), "PHP*").getAbsolutePath(), "php.exe", "--version", "PHP ([\\d.]+[\\w\\-.]*)", "目录扫描");
        addExisting(out, new File(Os.home(), "AppData\\Local\\Programs\\php").getAbsolutePath(),
                "php.exe", "--version", "PHP ([\\d.]+[\\w\\-.]*)", "目录扫描");
        addExisting(out, new File(Os.home(), "scoop\\apps\\php").getAbsolutePath(),
                "current\\php.exe", "--version", "PHP ([\\d.]+[\\w\\-.]*)", "目录扫描");
        return out;
    }

    private List<Detected.Install> discoverFlutter() {
        List<Detected.Install> out = new ArrayList<>();
        String home = System.getenv("FLUTTER_ROOT");
        if (home == null || home.isEmpty()) {
            home = System.getenv("FLUTTER_HOME");
        }
        List<File> roots = new ArrayList<>();
        if (home != null && !home.isEmpty()) {
            addExisting(roots, home);
        }
        addExisting(roots, "C:\\flutter");
        addExisting(roots, "C:\\src\\flutter");
        addExisting(roots, "D:\\flutter");
        addExisting(roots, new File(Os.home(), "flutter").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "development\\flutter").getAbsolutePath());
        addExisting(roots, new File(Os.home(), "fvm\\versions").getAbsolutePath());
        for (File root : roots) {
            File exe = new File(root, "bin" + File.separator + (Os.isWindows() ? "flutter.bat" : "flutter"));
            if (exe.isFile()) {
                Detected.Install i = probeExe(exe.getAbsolutePath(), "--version", "Flutter ([\\d.]+)", "目录扫描");
                if (i != null) {
                    i.rootDir = root.getAbsolutePath();
                    out.add(i);
                }
            }
        }
        return out;
    }

    private List<Detected.Install> discoverPerl() {
        List<Detected.Install> out = new ArrayList<>();
        addExisting(out, "C:\\Strawberry\\perl", "bin\\perl.exe", "--version", "\\(v?([\\d.]+)\\)", "目录扫描");
        addExisting(out, "C:\\Perl*", "bin\\perl.exe", "--version", "\\(v?([\\d.]+)\\)", "目录扫描");
        return out;
    }

    /** 通用兜底：在若干标准前缀下找可执行名。 */
    private List<Detected.Install> discoverGeneric(LangSpec spec) {
        List<Detected.Install> out = new ArrayList<>();
        if (!Os.isWindows()) {
            for (String exe : spec.exeNames) {
                for (String dir : new String[]{"/usr/local/bin", "/usr/bin", "/opt/homebrew/bin"}) {
                    File f = new File(dir, exe);
                    if (f.isFile()) {
                        Detected.Install i = probeGeneric(f.getAbsolutePath(), spec, "目录扫描");
                        if (i != null) {
                            out.add(i);
                        }
                    }
                }
            }
        }
        return out;
    }

    private Detected.Install probeGeneric(String exePath, LangSpec spec, String source) {
        for (LangSpec.Probe p : spec.probes) {
            List<String> cmd = new ArrayList<>(p.command);
            if (!cmd.isEmpty()) {
                cmd.set(0, exePath);
            }
            Proc.Result r = runCached(cmd, CMD_TIMEOUT);
            String outTxt = r.effective();
            if (outTxt.isEmpty()) {
                continue;
            }
            String ver = p.extract(outTxt);
            if (ver != null && !ver.isEmpty()) {
                Detected.Install i = new Detected.Install();
                i.version = ver;
                i.exePath = exePath;
                i.rootDir = guessRoot(exePath, spec);
                i.source = source;
                i.rawOutput = Proc.oneLine(outTxt);
                return i;
            }
        }
        return null;
    }

    /* ---------------------------------------------------- 通用小工具 */

    /** 用绝对路径跑一次版本命令，构造 Install。 */
    private Detected.Install probeExe(String exePath, String arg, String regex, String source) {
        if (exePath == null || !new File(exePath).isFile()) {
            return null;
        }
        Detected.Install i = new Detected.Install();
        i.exePath = exePath;
        i.rootDir = guessRoot(exePath, null);
        i.source = source;

        Proc.Result r = runCached(Arrays.asList(exePath, arg), CMD_TIMEOUT);
        // 与 scanOne 一致：用 stdout+stderr 合并文本做版本解析，
        // 避免横幅在 stderr、正文在 stdout 的工具（如 cl /?）解析落空
        String out = r.effective();
        String combined = r.stdout + "\n" + r.stderr;
        if (r.timedOut || r.error != null) {
            // 文件存在但命令跑不起来：仍报告“已安装”，不要给出“未安装”的错误结论
            i.version = "已安装（版本探测失败）";
            i.rawOutput = r.timedOut ? "版本命令超时" : r.error;
            return i;
        }
        String ver = null;
        if (!combined.trim().isEmpty()) {
            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(combined);
            if (m.find()) {
                ver = m.group(1).trim();
            }
            if (ver == null) {
                Matcher m2 = Pattern.compile("(\\d+\\.\\d+[\\w.\\-+]*)").matcher(combined);
                ver = m2.find() ? m2.group(1) : null;
            }
        }
        if (ver == null) {
            i.version = "已安装（版本未获取）";
            i.rawOutput = out.isEmpty() ? "版本命令无输出" : Proc.oneLine(out);
        } else {
            i.version = ver;
            i.rawOutput = Proc.oneLine(out);
        }
        return i;
    }

    /** Program Files 目录（不写死 C 盘/英文系统路径）。 */
    private static File programFiles() {
        String pf = System.getenv("ProgramFiles");
        if (pf == null || pf.trim().isEmpty()) {
            String drive = System.getenv("SystemDrive");
            pf = (drive == null || drive.trim().isEmpty() ? "C:" : drive.trim())
                    + "\\Program Files";
        }
        return new File(pf);
    }

    /** 32 位 Program Files 目录。 */
    private static File programFilesX86() {
        String pf = System.getenv("ProgramFiles(x86)");
        if (pf == null || pf.trim().isEmpty()) {
            pf = new File(programFiles().getParentFile(), "Program Files (x86)").getAbsolutePath();
        }
        return new File(pf);
    }

    /** 支持通配符的路径收集，如 C:\Ruby*。 */
    private static void addExisting(List<File> sink, String pathPattern) {        if (pathPattern == null || pathPattern.isEmpty()) {
            return;
        }
        if (!pathPattern.contains("*")) {
            File f = new File(pathPattern);
            if (f.exists() && !sink.contains(f)) {
                sink.add(f);
            }
            return;
        }
        File parent = new File(pathPattern).getParentFile();
        String namePattern = new File(pathPattern).getName();
        if (parent == null || !parent.isDirectory()) {
            return;
        }
        String regex = namePattern.toLowerCase().replace(".", "\\.").replace("*", ".*");
        File[] kids = parent.listFiles(f -> f.isDirectory()
                && f.getName().toLowerCase().matches(regex));
        if (kids != null) {
            for (File k : kids) {
                if (!sink.contains(k)) {
                    sink.add(k);
                }
            }
        }
    }

    /** 在匹配通配符的目录下，用相对子路径找可执行文件并探测。 */
    private void addExisting(List<Detected.Install> out, String dirPattern, String relExe,
                             String arg, String regex, String source) {
        List<File> dirs = new ArrayList<>();
        addExisting(dirs, dirPattern);
        for (File d : dirs) {
            File target = d.isFile() && relExe.contains("\\") ? d : new File(d, relExe);
            // dirPattern 本身已指向含 exe 的目录时（如 C:\Strawberry\perl），relExe 相对其解析
            if (!target.isFile()) {
                continue;
            }
            Detected.Install i = probeExe(target.getAbsolutePath(), arg, regex, source);
            if (i != null) {
                if (relExe.contains(File.separator)) {
                    File parent = target.getParentFile();
                    File root = parent == null ? null : parent.getParentFile();
                    i.rootDir = root == null ? d.getAbsolutePath() : root.getAbsolutePath();
                } else {
                    i.rootDir = d.getAbsolutePath();
                }
                out.add(i);
            }
        }
    }
}
