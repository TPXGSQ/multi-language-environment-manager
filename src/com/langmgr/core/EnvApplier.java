package com.langmgr.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 环境切换执行器。
 *
 * <p>设计原则：<b>先出计划、再确认、后执行、可回滚</b>。
 * 修改用户 PATH 是有风险的操作——一旦写坏，用户新开的终端会找不到任何命令。
 * 因此这里强制：
 * <ol>
 *   <li>任何写操作前，把当前用户 PATH 和相关变量的原始值备份到 {@code backup/} 下的 JSON；</li>
 *   <li>只动 HKCU（当前用户），绝不触碰系统级变量，不需要管理员权限；</li>
 *   <li>写入用注册表 API 而非 setx，避免 1024 字符截断和 %VAR% 被永久展开；</li>
 *   <li>执行完广播 WM_SETTINGCHANGE，让新开的终端立即生效。</li>
 * </ol>
 *
 * <p>已存在的终端窗口不会刷新环境变量——这是 Windows 的固有行为，
 * 需要提示用户重开终端。
 */
public final class EnvApplier {

    private EnvApplier() {
    }

    /* ------------------------------------------------------- 计划结构 */

    /** 计划中的一条动作。 */
    public static final class Step {
        public enum Kind { SET_VAR, DELETE_VAR, PATH_ADD, PATH_REMOVE, PATH_REORDER }

        public final Kind kind;
        /** 目标变量名。 */
        public final String name;
        /** 新值（PATH_* 时为目录）。 */
        public final String value;
        /** 人类可读说明。 */
        public final String describe;
        /** 旧值，用于回滚与 diff 展示。 */
        public String oldValue;

        Step(Kind kind, String name, String value, String describe) {
            this.kind = kind;
            this.name = name;
            this.value = value;
            this.describe = describe;
        }

        @Override
        public String toString() {
            return describe;
        }
    }

    /** 一次完整的切换计划。 */
    public static final class Plan {
        public final String title;
        public final List<Step> steps = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        /** 备份文件路径，执行时写入。 */
        public Path backupFile;
        /** 语言规格，用于附带"该语言推荐的额外变量"。 */
        public LangSpec spec;
        /** 目标安装实例。 */
        public Detected.Install target;

        Plan(String title) {
            this.title = title;
        }

        public boolean isEmpty() {
            return steps.isEmpty();
        }

        public List<String> describeAll() {
            List<String> out = new ArrayList<>();
            for (Step s : steps) {
                out.add(s.describe);
            }
            return out;
        }
    }

    /* ------------------------------------------------------- 计划生成 */

    /**
     * 生成"把 lang 切换到 target"的计划。
     *
     * @param spec   语言规格
     * @param current 当前扫描结果（可能为 null）
     * @param target 目标安装实例
     * @param managePath 是否同时调整 PATH
     * @param cleanOtherVersions 是否把其他版本的 bin 目录从用户 PATH 移除
     */
    public static Plan buildSwitchPlan(LangSpec spec, Detected.LangResult current,
                                       Detected.Install target, boolean managePath,
                                       boolean cleanOtherVersions) {
        Plan plan = new Plan("切换 " + spec.displayName + " → " + target.label());
        plan.spec = spec;
        plan.target = target;

        if (target == null) {
            plan.warnings.add("未指定目标版本。");
            return plan;
        }
        String root = target.rootDir != null ? target.rootDir : guessRootFromExe(target.exePath);
        if (root == null) {
            plan.warnings.add("无法从可执行文件路径推断安装根目录，请手动指定。");
            return plan;
        }

        // ---- 1. 设置 HOME 类变量 ----
        // 只对"主变量"赋值；像 PYTHONHOME 这种会干扰虚拟环境的变量，默认不主动设置。
        for (String var : spec.homeVars) {
            if (isRiskyVar(spec.id, var)) {
                // 已经设了值（含误写成"（建议留空）"占位符的）才是真正的危害：
                // 生成删除步骤清掉；本来就没设，只提示即可
                String oldRaw = EnvStore.readRaw(var);
                if (oldRaw != null && !oldRaw.trim().isEmpty()) {
                    Step st = new Step(Step.Kind.DELETE_VAR, var, null,
                            "删除变量 " + var + "（原值 " + Os.expand(oldRaw)
                                    + "）：该变量设了反而会干扰虚拟环境或解释器自动定位");
                    st.oldValue = oldRaw;
                    plan.steps.add(st);
                } else {
                    plan.warnings.add("跳过 " + var + "：该变量在多数场景下不需要设置，"
                            + "设错反而会干扰虚拟环境或解释器自动定位。");
                }
                continue;
            }
            String wantValue = valueForVar(spec.id, var, root, target);
            if (wantValue == null) {
                continue;
            }
            String oldRaw = EnvStore.readRaw(var);
            String oldEff = oldRaw == null ? null : Os.expand(oldRaw);
            if (sameDir(oldEff, wantValue)) {
                continue; // 已经正确，不产生动作
            }
            Step st = new Step(Step.Kind.SET_VAR, var, wantValue,
                    "设置 " + var + " = " + wantValue
                            + (oldEff == null || oldEff.isEmpty() ? "（原先未设置）" : "（原为 " + oldEff + "）"));
            st.oldValue = oldRaw;
            plan.steps.add(st);
        }

        // ---- 2. PATH 调整 ----
        if (managePath) {
            List<File> binDirs = resolveBinDirs(root, spec, target);
            if (!binDirs.isEmpty()) {
                String primary = binDirs.get(0).getAbsolutePath();
                for (int i = 0; i < binDirs.size(); i++) {
                    String binPath = binDirs.get(i).getAbsolutePath();
                    int state = EnvStore.pathContains(binPath);
                    if (state == 0) {
                        Step st = new Step(Step.Kind.PATH_ADD, "PATH", binPath,
                                "把 " + binPath + " 加入用户 PATH 首位（确保优先于其他版本）");
                        plan.steps.add(st);
                    } else if (i == 0 && cleanOtherVersions && EnvStore.userPathIndex(binPath) > 0) {
                        Step st = new Step(Step.Kind.PATH_REORDER, "PATH", binPath,
                                "将 " + binPath + " 提到用户 PATH 首位");
                        plan.steps.add(st);
                    }
                }

                if (cleanOtherVersions && current != null) {
                    Set<String> plannedRemovals = new LinkedHashSet<>();
                    for (Detected.Install other : current.installs) {
                        if (other == target || other.rootDir == null) {
                            continue;
                        }
                        for (File otherBin : resolveBinDirs(other.rootDir, spec, other)) {
                            String otherPath = otherBin.getAbsolutePath();
                            if (EnvStore.samePath(otherPath, primary)) {
                                continue;
                            }
                            if (EnvStore.pathContains(otherPath) == 1
                                    && EnvStore.userPathIndex(otherPath) >= 0
                                    && plannedRemovals.add(EnvStore.normalizePath(otherPath))) {
                                Step st = new Step(Step.Kind.PATH_REMOVE, "PATH", otherPath,
                                        "从用户 PATH 移除旧版本目录 " + otherPath);
                                plan.steps.add(st);
                            }
                        }
                    }
                }
            } else {
                plan.warnings.add("未能定位该版本的 bin 目录，PATH 将不调整；"
                        + "若命令仍不可用，请手动把可执行文件所在目录加入 PATH。");
            }
        }

        // ---- 3. 一致性告警 ----
        if (current != null && current.active != null && target.exePath != null) {
            String running = current.runningPath();
            boolean forwarded = current.forwarderPath != null;
            boolean targetAlreadyRunning = running != null && sameFile(running, target.exePath);
            if (forwarded) {
                if (targetAlreadyRunning) {
                    plan.warnings.add("目标版本已经就是当前实际运行的版本（PATH 命中 "
                            + current.forwarderPath + "，它转发到 " + running + "）。"
                            + "但命中点仍是这个转发器——以后要切到别的版本，用户级 PATH 压不过它。");
                } else {
                    // 转发器几乎都躺在系统级 PATH 里，而系统 PATH 永远排在用户 PATH 之前，
                    // 所以往用户 PATH 首位插入新版本也压不过它——切换后新终端仍走转发器指向的
                    // 那个版本。这种情况必须说清楚，否则用户会以为切换失败是程序的 bug。
                    plan.warnings.add("系统 PATH 里的转发器 " + current.forwarderPath
                            + " 排在用户 PATH 之前，它当前转发到 " + running
                            + "。即使把 " + target.exePath + " 加入用户 PATH，新开的终端仍会走转发器；"
                            + "要真正切换，需管理员移除该系统 PATH 条目，或改由读 JAVA_HOME 的工具链生效。");
                }
            } else if (!targetAlreadyRunning) {
                plan.warnings.add("当前 PATH 生效的是 " + running
                        + "，切换后将改为 " + target.exePath + "。");
            }
        }
        if (!Os.isWindows()) {
            plan.warnings.add("当前为非 Windows 平台，变量将写入 shell 配置文件，PATH 调整能力有限。");
        }
        plan.warnings.add("变更只影响「当前用户」，不会修改系统级变量；已打开的终端需重开才能读到新值。");

        return plan;
    }

    /**
     * 生成"删除某个版本的环境引用"的计划。
     *
     * <p>只清理环境引用，不碰磁盘文件——删目录是独立动作，由调用方另行确认：
     * <ul>
     *   <li>用户 PATH 中所有指向该版本的目录，重复条目一次清干净；</li>
     *   <li>指向该版本的 HOME 类变量（如 JAVA_HOME），留着就是死指针；</li>
     * </ul>
     * 系统级 PATH 条目不在本工具职责内（需要管理员权限），只做告警。
     *
     * <p>PATH 是否包含某目录，一律查注册表而不是进程环境变量：进程里的是启动快照，
     * 刚切换过版本时它是旧的，会漏删或多删。
     */
    public static Plan buildRemovePlan(LangSpec spec, Detected.Install inst, Detected.LangResult current) {
        Plan plan = new Plan("删除 " + spec.displayName + " " + inst.label() + " 的环境引用");
        plan.spec = spec;
        plan.target = inst;
        if (inst == null) {
            plan.warnings.add("未选择版本。");
            return plan;
        }
        String root = inst.rootDir != null ? inst.rootDir : guessRootFromExe(inst.exePath);

        // ---- 1. PATH 中指向该版本的目录 ----
        Set<String> candidates = new LinkedHashSet<>();
        if (root != null) {
            for (File d : resolveBinDirs(root, spec, inst)) {
                candidates.add(d.getAbsolutePath());
            }
        }
        if (inst.exePath != null) {
            File p = new File(inst.exePath).getParentFile();
            if (p != null) {
                candidates.add(p.getAbsolutePath());
            }
        }
        Set<String> systemLevel = new LinkedHashSet<>();
        for (String dir : candidates) {
            boolean inUser = EnvStore.userPathIndex(dir) >= 0;
            if (inUser) {
                plan.steps.add(new Step(Step.Kind.PATH_REMOVE, "PATH", dir,
                        "从用户 PATH 移除 " + dir));
            }
            if (!inUser && inSystemPath(dir)) {
                systemLevel.add(dir);
            }
        }

        // ---- 2. 指向该版本的 HOME 变量 ----
        if (root != null) {
            for (String var : spec.homeVars) {
                String raw = EnvStore.readRaw(var);
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                // 危险变量本就不该设置：删版本时顺手把误写的占位符等垃圾值一起清掉
                if (isRiskyVar(spec.id, var) && isLeaveEmptyHint(Os.expand(raw))) {
                    Step st = new Step(Step.Kind.DELETE_VAR, var, null,
                            "删除变量 " + var + "（原值 " + raw + "，建议留空的占位值）");
                    st.oldValue = raw;
                    plan.steps.add(st);
                    continue;
                }
                String eff = Os.expand(raw);
                if (!sameDir(eff, root) && !sameDir(eff, new File(root, "bin").getAbsolutePath())) {
                    continue;
                }
                Step st = new Step(Step.Kind.DELETE_VAR, var, null,
                        "删除变量 " + var + "（原值 " + eff + "，指向要被删除的版本）");
                st.oldValue = raw;
                plan.steps.add(st);
            }
        }

        // ---- 3. 告警 ----
        if (inst.active) {
            plan.warnings.add("这是当前生效的版本：清理后该语言的命令在新终端里会不可用，"
                    + "建议先切换到其它版本再删除。");
        }
        if (!systemLevel.isEmpty()) {
            plan.warnings.add("系统级 PATH 中还有指向该版本的条目，需管理员手动移除：\n  "
                    + String.join("\n  ", systemLevel));
        }
        if (plan.isEmpty()) {
            plan.warnings.add("用户环境里没有指向该版本的引用，无需清理。");
        }
        return plan;
    }

    private static boolean inSystemPath(String dir) {
        for (String e : EnvStore.systemPathEntries()) {
            if (EnvStore.samePath(Os.expand(e), dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 删除安装目录（可选动作，与清理环境引用相互独立）。
     *
     * <p>刻意保守：宁可拒绝也不误删。拒绝盘符根、Windows/Program Files 这类容器目录、
     * 本程序自身所在目录、以及用户主目录。
     *
     * @return 执行日志，失败项以 "×" 开头
     */
    public static List<String> deleteInstallDir(File root) {
        List<String> log = new ArrayList<>();
        if (root == null) {
            log.add("○ 没有指定目录，跳过磁盘清理。");
            return log;
        }
        String reason = unsafeToDelete(root);
        if (reason != null) {
            log.add("× 拒绝删除 " + root.getAbsolutePath() + "：" + reason);
            return log;
        }
        final List<String> failed = new ArrayList<>();
        final int[] counts = new int[2]; // 0=已删文件数, 1=已删目录数
        try {
            Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        Files.delete(file);
                        counts[0]++;
                    } catch (IOException ex) {
                        failed.add(file + "（" + ex.getMessage() + "）");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    failed.add(file + "（" + ex.getMessage() + "）");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException ex) {
                    if (ex != null) {
                        failed.add(dir + "（" + ex.getMessage() + "）");
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Files.delete(dir);
                        counts[1]++;
                    } catch (IOException e) {
                        failed.add(dir + "（" + e.getMessage() + "）");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.add("× 遍历目录失败：" + ex.getMessage());
            return log;
        }
        log.add("√ 已删除 " + counts[0] + " 个文件、" + counts[1] + " 个目录。");
        if (!failed.isEmpty()) {
            log.add("× 有 " + failed.size() + " 项删不掉（多半是被占用或需要管理员权限）：");
            int shown = 0;
            for (String f : failed) {
                if (shown++ >= 8) {
                    log.add("  …列表过长，仅显示前 8 项");
                    break;
                }
                log.add("  " + f);
            }
        }
        if (root.exists()) {
            log.add("× 目录仍然存在，可能有文件被占用；关掉相关程序后可手动删除。");
        } else {
            log.add("√ 安装目录已清空：" + root.getAbsolutePath());
        }
        return log;
    }

    /**
     * 删除 Oracle 转发器的残留目录（{@code javapath} / {@code javapath_target_*}）。
     *
     * <p>调用方给的是扫描器报告过的路径，这里仍重新校验一遍：只允许删除
     * "位于 Program Files\Common Files\Oracle\Java 下、名字以 javapath 开头、
     * 里面有 java.exe"的目录。任何不满足的路径直接拒绝——绝不能因为上层
     * 传错一个字符串就把用户目录删掉。
     *
     * @return 失败说明，全部成功返回 null
     */
    public static String deleteStaleForwarderDirs(List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return null;
        }
        List<String> failures = new ArrayList<>();
        for (String dir : dirs) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            File d = new File(dir);
            if (!d.isDirectory()) {
                continue;   // 已经不在了，视为成功
            }
            String reject = notAForwarderDir(d);
            if (reject != null) {
                failures.add(d.getAbsolutePath() + "（" + reject + "）");
                continue;
            }
            // 只清掉目录里的可执行文件与其它内容，目录本身尝试删掉；
            // 被占用（例如有终端正在用它启动的 java 进程）时如实报告
            List<String> log = deleteInstallDir(d);
            for (String line : log) {
                if (line.startsWith("×")) {
                    failures.add(d.getAbsolutePath() + "（" + line.substring(1).trim() + "）");
                    break;
                }
            }
        }
        if (failures.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("以下目录没能删干净（多半是被占用或需要管理员权限）：\n\n");
        for (int i = 0; i < failures.size() && i < 6; i++) {
            sb.append("  ").append(failures.get(i)).append("\n");
        }
        if (failures.size() > 6) {
            sb.append("  …共 ").append(failures.size()).append(" 项\n");
        }
        sb.append("\n可以关闭所有 java 进程、用管理员身份重试，或手动删除。");
        return sb.toString();
    }

    /** 校验是否真的是 Oracle 转发器目录；不是则返回拒绝原因。 */
    private static String notAForwarderDir(File d) {
        Path p = d.toPath().toAbsolutePath().normalize();
        String name = d.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.startsWith("javapath")) {
            return "目录名不是 javapath 系列，拒绝删除";
        }
        Path parent = p.getParent();
        if (parent == null || parent.getParent() == null) {
            return "层级过浅，拒绝删除";
        }
        String pp = parent.toString().replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
        if (!pp.endsWith("\\common files\\oracle\\java")) {
            return "不在 Program Files\\Common Files\\Oracle\\Java 下，拒绝删除";
        }
        if (!new File(d, "java.exe").isFile()) {
            return "目录里没有 java.exe，看起来不是转发器，拒绝删除";
        }
        return null;
    }

    /** 删除安装目录前的保守校验；返回拒绝原因，可删返回 null。 */
    private static String unsafeToDelete(File root) {        if (!root.exists()) {
            return "目录不存在";
        }
        if (!root.isDirectory()) {
            return "不是目录";
        }
        Path p = root.toPath().toAbsolutePath().normalize();
        if (p.getParent() == null) {
            return "这是盘符根目录";
        }
        String name = root.getName().toLowerCase();
        if (p.getParent().getParent() == null
                && (name.equals("windows") || name.equals("program files")
                || name.equals("program files (x86)") || name.equals("users")
                || name.equals("programdata"))) {
            return "这是系统容器目录，删除会破坏其他软件";
        }
        File home = AppPaths.home();
        if (home != null) {
            String h = home.getAbsolutePath();
            String r = root.getAbsolutePath();
            if (h.equalsIgnoreCase(r) || h.toLowerCase().startsWith(r.toLowerCase() + File.separator)) {
                return "本程序自身就在这个目录里";
            }
        }
        File userHome = Os.home();
        if (userHome != null) {
            String r = root.getAbsolutePath();
            String u = userHome.getAbsolutePath();
            if (u.equalsIgnoreCase(r)
                    || u.toLowerCase().startsWith(r.toLowerCase() + File.separator)) {
                return "用户主目录不能删";
            }
        }
        return null;
    }

    /** 某些变量设了反而有害，默认跳过并给出解释。 */
    private static boolean isRiskyVar(String langId, String var) {
        if (langId.equals("python") && var.equalsIgnoreCase("PYTHONHOME")) {
            return true; // 会覆盖 venv/conda 的定位，经典踩坑点
        }
        if (langId.equals("go") && var.equalsIgnoreCase("GOROOT")) {
            return true; // go 自带定位，硬设常导致版本错配
        }
        if (langId.equals("dotnet") && var.equalsIgnoreCase("DOTNET_ROOT")) {
            // SDK 版本由 global.json 决定；把 DOTNET_ROOT 指向 sdk 子目录
            // 反而会让 dotnet 宿主找不到 shared 运行时。
            return true;
        }
        return false;
    }

    /** 不同语言的 HOME 变量语义不同，需要区别取值。 */
    private static String valueForVar(String langId, String var, String root, Detected.Install target) {
        switch (var.toUpperCase()) {
            case "JAVA_HOME":
            case "JDK_HOME":
            case "FLUTTER_ROOT":
            case "FLUTTER_HOME":
            case "DART_HOME":
            case "RUBY_HOME":
            case "PHP_HOME":
            case "LLVM_HOME":
            case "CMAKE_HOME":
            case "NODE_HOME":
            case "SCALA_HOME":
            case "KOTLIN_HOME":
            case "PYTHON_HOME":
            case "DOTNET_ROOT":
                return root;
            case "GOPATH": {
                // GOPATH 是工作区而非安装目录，默认给 ~/go，不覆盖已有值
                String existing = EnvStore.readEffective("GOPATH");
                if (existing != null && !existing.trim().isEmpty()) {
                    return null;
                }
                return new File(Os.home(), "go").getAbsolutePath();
            }
            case "GOBIN": {
                String existing = EnvStore.readEffective("GOBIN");
                if (existing != null && !existing.trim().isEmpty()) {
                    return null;
                }
                String gp = EnvStore.readEffective("GOPATH");
                File base = (gp != null && !gp.trim().isEmpty()) ? new File(gp) : new File(Os.home(), "go");
                return new File(base, "bin").getAbsolutePath();
            }
            case "RUSTUP_HOME":
            case "CARGO_HOME":
            case "GEM_HOME":
            case "DENO_INSTALL":
            case "BUN_INSTALL":
            case "R_HOME":
                // 这些通常由安装器管理，仅在未设置且能推断时补上
                String existing = EnvStore.readEffective(var);
                if (existing != null && !existing.trim().isEmpty()) {
                    return null;
                }
                return inferAuxVar(langId, var, target);
            case "PHPRC": {
                // PHPRC 指向 php.ini（或包含 php.ini 的目录），不能直接写成安装根目录
                if (root == null) {
                    return null;
                }
                File ini = new File(root, "php.ini");
                if (ini.isFile()) {
                    return ini.getAbsolutePath();
                }
                File confIni = new File(root, "conf" + File.separator + "php.ini");
                return confIni.isFile() ? confIni.getAbsolutePath() : null;
            }
            default:
                return root;
        }
    }

    private static String inferAuxVar(String langId, String var, Detected.Install target) {
        if (target == null || target.exePath == null) {
            return null;
        }
        File exeDir = new File(target.exePath).getParentFile();
        if (exeDir == null) {
            return null;
        }
        if (var.equalsIgnoreCase("CARGO_HOME")) {
            File p = exeDir.getParentFile();
            return p == null ? null : p.getAbsolutePath();
        }
        if (var.equalsIgnoreCase("RUSTUP_HOME")) {
            File f = new File(Os.home(), ".rustup");
            return f.isDirectory() ? f.getAbsolutePath() : null;
        }
        if (var.equalsIgnoreCase("DENO_INSTALL")) {
            File p = exeDir.getParentFile();
            return p == null ? null : p.getAbsolutePath();
        }
        return null;
    }

    /** 找该版本真正应该进 PATH 的目录。 */
    private static File resolveBinDir(String root, LangSpec spec, Detected.Install target) {
        if (target != null && target.exePath != null) {
            File p = new File(target.exePath).getParentFile();
            if (p != null && p.isDirectory()) {
                return p;
            }
        }
        if (root == null) {
            return null;
        }
        for (String sub : new String[]{"bin", "Scripts", "cmd", "", "sbin"}) {
            File d = sub.isEmpty() ? new File(root) : new File(root, sub);
            if (!d.isDirectory()) {
                continue;
            }
            if (spec != null) {
                for (String exe : spec.exeNames) {
                    if (new File(d, exe).isFile() || new File(d, exe + ".exe").isFile()
                            || new File(d, exe + ".bat").isFile()) {
                        return d;
                    }
                }
            }
            if (sub.isEmpty()) {
                continue;
            }
            return d; // bin/Scripts/cmd 存在即认为是目标目录
        }
        return null;
    }

    /**
     * 该版本应该进 PATH 的全部目录。
     * Python 是特例：python.exe 在安装根，pip/控制台脚本在 Scripts，
     * 只加根目录会让切换后 pip 不可用。
     */
    private static List<File> resolveBinDirs(String root, LangSpec spec, Detected.Install target) {
        List<File> dirs = new ArrayList<>();
        File primary = resolveBinDir(root, spec, target);
        if (primary != null) {
            dirs.add(primary);
        }
        if (spec != null && "python".equals(spec.id) && root != null) {
            File scripts = new File(root, "Scripts");
            if (scripts.isDirectory()
                    && (primary == null || !sameDir(scripts.getAbsolutePath(), primary.getAbsolutePath()))) {
                dirs.add(scripts);
            }
        }
        return dirs;
    }

    private static String guessRootFromExe(String exePath) {
        if (exePath == null) {
            return null;
        }
        File parent = new File(exePath).getParentFile();
        if (parent == null) {
            return null;
        }
        String n = parent.getName().toLowerCase();
        if (n.equals("bin") || n.equals("scripts") || n.equals("cmd") || n.equals("sbin")) {
            File gp = parent.getParentFile();
            return gp == null ? parent.getAbsolutePath() : gp.getAbsolutePath();
        }
        return parent.getAbsolutePath();
    }

    private static boolean sameDir(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = strip(a), y = strip(b);
        return Os.isWindows() ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    private static boolean sameFile(String a, String b) {
        try {
            return new File(a).getCanonicalFile().equals(new File(b).getCanonicalFile());
        } catch (IOException ex) {
            return sameDir(a, b);
        }
    }

    private static String strip(String p) {
        String s = p.trim();
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        while (s.length() > 1 && (s.endsWith("\\") || s.endsWith("/"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /* ------------------------------------------------------- 备份 / 执行 */

    /** 备份当前用户 PATH 与计划涉及的变量，返回备份文件路径。 */
    public static Path backup(List<Step> steps) throws IOException {
        List<String> names = new ArrayList<>();
        for (Step s : steps) {
            if (s.kind == Step.Kind.SET_VAR || s.kind == Step.Kind.DELETE_VAR) {
                names.add(s.name);
            }
        }
        // 统一走 EnvStore.backup：它会同时记录注册表类型，回滚才能还原 REG_EXPAND_SZ
        return EnvStore.backup(names, true);
    }

    /** 执行计划。返回执行日志；任何一步失败会立即停止并返回已完成部分。 */
    public static List<String> apply(Plan plan) {
        List<String> log = new ArrayList<>();
        if (plan.isEmpty()) {
            log.add("计划为空，无需变更。");
            return log;
        }
        try {
            plan.backupFile = backup(plan.steps);
            log.add("已备份当前环境到：" + plan.backupFile);
        } catch (IOException ex) {
            log.add("× 备份失败，已中止执行（不会做任何修改）：" + ex.getMessage());
            return log;
        }

        List<String> userPath = new ArrayList<>(EnvStore.userPathEntries());
        boolean pathDirty = false;
        boolean changed = false;

        for (Step s : plan.steps) {
            switch (s.kind) {
                case SET_VAR: {
                    // 保守标记：写入哪怕返回失败，也可能已经落盘（例如写后校验失败）
                    changed = true;
                    String err = EnvStore.write(s.name, s.value, s.value != null && s.value.contains("%"));
                    if (err != null) {
                        log.add("× " + err);
                        abortWithRollback(log, plan, changed);
                        return log;
                    }
                    log.add("√ " + s.describe);
                    break;
                }
                case DELETE_VAR: {
                    changed = true;
                    String err = EnvStore.delete(s.name);
                    if (err != null) {
                        log.add("× " + err);
                        abortWithRollback(log, plan, changed);
                        return log;
                    }
                    log.add("√ " + s.describe);
                    break;
                }
                case PATH_ADD: {
                    userPath.removeIf(p -> EnvStore.samePath(Os.expand(p), s.value));
                    userPath.add(0, s.value);
                    pathDirty = true;
                    log.add("√ " + s.describe);
                    break;
                }
                case PATH_REORDER: {
                    userPath.removeIf(p -> EnvStore.samePath(Os.expand(p), s.value));
                    userPath.add(0, s.value);
                    pathDirty = true;
                    log.add("√ " + s.describe);
                    break;
                }
                case PATH_REMOVE: {
                    boolean removed = userPath.removeIf(p -> EnvStore.samePath(Os.expand(p), s.value));
                    if (removed) {
                        pathDirty = true;
                    }
                    log.add((removed ? "√ " : "○ ") + s.describe);
                    break;
                }
            }
        }

        if (pathDirty) {
            List<String> dedup = EnvStore.dedupePath(userPath);
            String joined = String.join(File.pathSeparator, dedup);
            if (joined.length() > EnvStore.REG_VALUE_LIMIT) {
                log.add("× 用户 PATH 长度 " + joined.length() + " 字符，超过注册表安全上限 "
                        + EnvStore.REG_VALUE_LIMIT + "，已中止写入。");
                abortWithRollback(log, plan, changed);
                return log;
            }
            changed = true;
            String err = EnvStore.write("PATH", joined, true);
            if (err != null) {
                log.add("× " + err);
                abortWithRollback(log, plan, changed);
                return log;
            }
            if (dedup.size() < userPath.size()) {
                log.add("√ 已顺带去重 PATH 条目（" + userPath.size() + " → " + dedup.size() + "）。");
            }
            log.add("√ 用户 PATH 已更新。");
        }

        EnvStore.broadcastChange();
        log.add("√ 已广播环境变更通知，新开的终端会读到新值。");
        log.add("提示：已经打开的终端和 IDE 不会自动刷新，需要重启才能生效。");
        return log;
    }

    /** 执行失败时：已经写入过部分变更就用备份自动回滚，避免环境处于半改状态。 */
    private static void abortWithRollback(List<String> log, Plan plan, boolean changed) {
        if (!changed || plan == null || plan.backupFile == null) {
            log.add("未写入任何变更，无需回滚。");
            return;
        }
        log.add("检测到已写入部分变更，正在用备份自动回滚...");
        log.addAll(rollback(plan.backupFile));
    }

    /** 从备份的 types 映射里取类型；旧版备份没有 types 时按是否含 %VAR% 推断。 */
    private static String typeOf(Map<String, Object> types, String name, String value) {
        String type = types == null ? null : Json.asString(types.get(name));
        if (type == null || type.trim().isEmpty()) {
            type = value != null && value.contains("%") ? "REG_EXPAND_SZ" : "REG_SZ";
        }
        return type;
    }

    /** 从备份文件回滚。 */
    public static List<String> rollback(Path backupFile) {
        List<String> log = new ArrayList<>();
        try {
            String text = new String(Files.readAllBytes(backupFile), StandardCharsets.UTF_8);
            Map<String, Object> data = Json.asMap(Json.parse(text));
            if (data == null) {
                log.add("× 备份文件解析失败：" + backupFile);
                return log;
            }
            Map<String, Object> types = Json.asMap(data.get("types"));
            String pathRaw = Json.asString(data.get("pathRaw"));
            Map<String, Object> vars = Json.asMap(data.get("vars"));
            // 回滚同样会覆盖当前值：先给“回滚前状态”再做一份安全备份，
            // 万一回滚本身写错，还能从这份备份再救回来。
            List<String> names = new ArrayList<>();
            if (vars != null) {
                names.addAll(vars.keySet());
            }
            try {
                Path safety = EnvStore.backup(names, true);
                log.add("已先备份回滚前状态到：" + safety);
            } catch (IOException ex) {
                log.add("× 回滚前安全备份失败，已中止（未做任何修改）：" + ex.getMessage());
                return log;
            }
            if (pathRaw != null) {
                String type = typeOf(types, "PATH", pathRaw);
                String err = EnvStore.writeTyped("PATH", pathRaw, type);
                log.add(err == null ? "√ PATH 已还原" : "× " + err);
            }
            if (vars != null) {
                for (Map.Entry<String, Object> e : vars.entrySet()) {
                    String v = Json.asString(e.getValue());
                    if (v == null) {
                        String err = EnvStore.delete(e.getKey());
                        log.add(err == null ? "√ 已删除 " + e.getKey() + "（备份中不存在）" : "× " + err);
                    } else {
                        String type = typeOf(types, e.getKey(), v);
                        String err = EnvStore.writeTyped(e.getKey(), v, type);
                        log.add(err == null ? "√ " + e.getKey() + " 已还原" : "× " + err);
                    }
                }
            }
            EnvStore.broadcastChange();
            log.add("√ 回滚完成，已广播变更。");
        } catch (IOException | RuntimeException ex) {
            log.add("× 回滚失败：" + ex.getMessage());
        }
        return log;
    }

    /** 列出所有备份文件，最新在前。 */
    public static List<Path> listBackups() {
        List<Path> out = new ArrayList<>();
        File dir = EnvStore.backupDir();
        if (!dir.isDirectory()) {
            return out;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir.toPath())) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** 删除单个备份文件（进回收站不可行，这里只在用户显式确认时物理删除）。 */
    public static String deleteBackup(Path p) {
        try {
            Files.deleteIfExists(p);
            return null;
        } catch (IOException ex) {
            return ex.getMessage();
        }
    }

    /* --------------------------------------------- 环境配置信息随版本自动设置 */

    /**
     * suggestEnvFor 对"危险变量"（PYTHONHOME / GOROOT / DOTNET_ROOT 这类设了反而
     * 有害的）返回的占位符，意思是"建议删除、不要设置"。
     * 调用方绝不能把这个字符串当普通值写进注册表——那会让检查器把该变量
     * 判成"指向的目录不存在"，一键处理又原样写回，问题永远消不掉。
     */
    public static final String LEAVE_EMPTY_HINT = "（建议留空）";

    /** 值是否是"建议留空"占位符。 */
    public static boolean isLeaveEmptyHint(String value) {
        return LEAVE_EMPTY_HINT.equals(value == null ? null : value.trim());
    }

    /**
     * 为指定语言生成"环境配置信息"建议——即该语言在该版本下推荐的变量组合。
     * 这是"环境配置信息随版本自动设置"功能的只读预览部分。
     */
    public static Map<String, String> suggestEnvFor(LangSpec spec, Detected.Install target) {
        Map<String, String> map = new LinkedHashMap<>();
        if (spec == null || target == null) {
            return map;
        }
        String root = target.rootDir != null ? target.rootDir : guessRootFromExe(target.exePath);
        for (String var : spec.homeVars) {
            if (isRiskyVar(spec.id, var)) {
                map.put(var, LEAVE_EMPTY_HINT);
                continue;
            }
            String v = valueForVar(spec.id, var, root, target);
            if (v != null) {
                map.put(var, v);
            }
        }
        File bin = resolveBinDir(root, spec, target);
        if (bin != null) {
            map.put("PATH(追加)", bin.getAbsolutePath());
        }
        return map;
    }

    /** 生成一份可执行的 shell 脚本片段，方便用户手动应用或在 CI 中使用。 */
    public static String exportScript(Plan plan) {
        StringBuilder sb = new StringBuilder();
        if (Os.isWindows()) {
            sb.append("@echo off\r\nREM 由多语言环境管理器生成，只含变量变更\r\n");
            for (Step s : plan.steps) {
                switch (s.kind) {
                    case SET_VAR: {
                        // 用 reg add 而不是 setx：保留 REG_EXPAND_SZ，且不会被截断到 1024 字符
                        String type = s.value != null && s.value.contains("%")
                                ? "REG_EXPAND_SZ" : "REG_SZ";
                        sb.append("reg add HKCU\\Environment /v ").append(s.name)
                                .append(" /t ").append(type)
                                .append(" /d \"").append(batchValue(s.value)).append("\" /f\r\n");
                        break;
                    }
                    case DELETE_VAR:
                        sb.append("reg delete HKCU\\Environment /v ").append(s.name).append(" /f\r\n");
                        break;
                    default:
                        sb.append("REM PATH 变更无法安全地生成静态脚本，请用图形界面执行\r\n");
                }
            }
        } else {
            sb.append("#!/usr/bin/env bash\n# 由多语言环境管理器生成\n");
            for (Step s : plan.steps) {
                if (s.kind == Step.Kind.SET_VAR) {
                    sb.append("export ").append(s.name).append("=\"").append(s.value).append("\"\n");
                }
            }
        }
        return sb.toString();
    }

    /** 把值放进 batch 的双引号里；% 要写成 %% 才不会被 cmd 当场展开。 */
    private static String batchValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("%", "%%").replace("\"", "\"\"");
    }

    /** 便捷：直接写 PATH（PATH 管理面板使用）。 */
    public static String writePath(List<String> entries) {
        List<String> dedup = EnvStore.dedupePath(entries);
        return EnvStore.write("PATH", String.join(File.pathSeparator, dedup), true);
    }

    /** 判断一个目录是否是"某语言版本的 bin 目录"，用于 PATH 面板标注归属。 */
    public static String annotateDir(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            return null;
        }
        String d = Os.expand(dir).trim();
        String low = d.toLowerCase();
        if (low.contains("java") || low.contains("jdk") || low.contains("jre")) {
            return "Java";
        }
        if (low.contains("python") || low.contains("anaconda") || low.contains("miniconda")) {
            return "Python";
        }
        if (low.contains("nodejs") || low.contains("\\node\\") || low.contains("nvm")) {
            return "Node.js";
        }
        if (low.contains("\\go\\bin") || low.endsWith("\\go")) {
            return "Go";
        }
        if (low.contains("rust") || low.contains("cargo")) {
            return "Rust";
        }
        if (low.contains("dotnet")) {
            return ".NET";
        }
        if (low.contains("maven")) {
            return "Maven";
        }
        if (low.contains("gradle")) {
            return "Gradle";
        }
        if (low.contains("flutter") || low.contains("dart")) {
            return "Flutter/Dart";
        }
        if (low.contains("php")) {
            return "PHP";
        }
        if (low.contains("ruby")) {
            return "Ruby";
        }
        if (low.contains("cmake")) {
            return "CMake";
        }
        if (low.contains("git")) {
            return "Git";
        }
        return null;
    }

    /** 供 PATH 面板：把用户 PATH 拆成条目并标注。 */
    public static List<Map<String, String>> dumpUserPath() {
        List<Map<String, String>> out = new ArrayList<>();
        List<String> entries = EnvStore.userPathEntries();
        int i = 0;
        for (String e : entries) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("order", String.valueOf(++i));
            row.put("raw", e);
            row.put("expanded", Os.expand(e));
            row.put("exists", new File(Os.expand(e)).isDirectory() ? "是" : "否");
            String tag = annotateDir(e);
            row.put("owner", tag == null ? "" : tag);
            out.add(row);
        }
        return out;
    }
}
