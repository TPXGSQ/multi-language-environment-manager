package com.langmgr.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 扫描结果的数据结构集合。 */
public final class Detected {

    private Detected() {
    }

    /** 一个具体的可执行安装实例（同一语言可能有多个）。 */
    public static final class Install {
        /** 展示版本，如 "21.0.2"。 */
        public String version = "未知";
        /** 可执行文件绝对路径。 */
        public String exePath;
        /** 推断出的安装根目录，如 JDK 根 / conda env 根。 */
        public String rootDir;
        /** 来源说明：PATH / 注册表 / 目录扫描 / py launcher ... */
        public String source = "PATH";
        /** 是否当前 PATH 生效的那个。 */
        public boolean active;
        /** 架构信息（若可得）。 */
        public String arch;
        /** 原始版本输出，便于排查。 */
        public String rawOutput;
        /** 是否为 64 位。 */
        public Boolean bit64;
        /**
         * 该条目是否在拦截切换。
         *
         * <p>指 PATH 里排在所有用户级条目之前的转发器（如 Oracle 的 {@code javapath\java.exe}）：
         * 它本身不是一个可选版本，却决定了新终端实际跑哪个版本。它存在就意味着
         * "往用户 PATH 里加版本目录"这类切换不会生效，属于需要处理的环境问题。
         */
        public boolean blocking;
        /**
         * 是否为"已不在 PATH 中的转发器残留"。
         *
         * <p>跟 {@link #blocking} 不同：它已经不影响命中（拦截问题已解决），
         * 只是磁盘上的文件还留着。列出它、并标为可删除，用户才知道
         * "命令生效了，但还有可清理的尾巴"。
         */
        public boolean stale;
        /**
         * 是否可通过改写环境变量来切换。
         * .NET 运行时这类由宿主自动选择的条目为 false——它们能被检测到、有参考价值，
         * 但不能像 JDK 那样通过改 HOME 变量来切换，混进切换列表会误导用户。
         */
        public boolean switchable = true;

        public String label() {
            StringBuilder sb = new StringBuilder();
            sb.append(version);
            if (arch != null && !arch.isEmpty()) {
                sb.append(" (").append(arch).append(")");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /** 环境变量状态。 */
    public static final class VarState {
        public String name;
        /** 注册表原始值（未展开）。 */
        public String rawValue;
        /** 展开后的实际值。 */
        public String effectiveValue;
        /** 指向的目录是否存在。 */
        public boolean targetExists;
        /** 该目录是否在 PATH 中。 */
        public boolean inPath;
        /** 备注，如"路径不存在""与当前生效的解释器不一致"。 */
        public String note = "";

        public boolean healthy() {
            return effectiveValue != null && !effectiveValue.trim().isEmpty() && targetExists
                    && (note == null || !note.contains("不一致"));
        }
    }

    /** 包管理器状态。 */
    public static final class PkgState {
        public String id;
        public String displayName;
        public boolean installed;
        public String version = "—";
        public String exePath;
        public String error;
    }

    /** 一种语言的完整检测结果。 */
    public static final class LangResult {
        public LangSpec spec;
        public boolean installed;
        /**
         * 当前生效的安装：<b>系统实际在用的那个</b>，即真正跑起来的解释器/编译器。
         * 命中转发器时这里是转发器背后的真实目标，而不是转发器本身。
         */
        public Install active;
        /** 实际命中的可执行文件——新终端里输入命令时最先被找到的那个（可能是转发器）。 */
        public String hitExePath;
        /**
         * 若 PATH 命中的是转发器，这里是转发器路径；null 表示命中即运行。
         *
         * <p>非 null 说明存在"命中与使用不一致"：命中是候选/配置，实际运行的是
         * {@link Install#exePath}。这在 Windows 上通常意味着切换版本不会生效，
         * 是需要处理的问题，而不是正常状态。
         */
        public String forwarderPath;
        /**
         * 已从 PATH 移出、但磁盘目录仍在的转发器路径（残留）。
         *
         * <p>它已经不影响命中，所以不再是"拦截"问题；但目录还在、还会被别的程序
         * 用绝对路径调到，也容易让人误以为"删除失败了"。这里把它单独报出来，
         * 让用户能主动清理，而不是靠猜。
         */
        public List<String> staleForwarderDirs = new ArrayList<>();
        /** 全部发现的安装实例。 */
        public List<Install> installs = new ArrayList<>();
        /** 关联环境变量状态。 */
        public Map<String, VarState> vars = new LinkedHashMap<>();
        /** 包管理器状态。 */
        public List<PkgState> pkgManagers = new ArrayList<>();
        /** 探测过程中的错误/说明。 */
        public List<String> notes = new ArrayList<>();
        public long scanMillis;

        /**
         * 展示用的版本号。
         * PATH 中没有生效项时，退回显示已发现的第一个版本——
         * 否则会出现"已安装却显示未安装"的自相矛盾。
         */
        public String activeVersion() {
            if (active != null) {
                return active.version;
            }
            return installs.isEmpty() ? "未安装" : installs.get(0).version;
        }

        /** 展示用的可执行文件路径，语义同 {@link #activeVersion()}。 */
        public String activePath() {
            Detected.Install i = active != null ? active : (installs.isEmpty() ? null : installs.get(0));
            if (i == null) {
                return "—";
            }
            String p = i.exePath != null ? i.exePath : i.rootDir;
            return p == null ? "—" : p;
        }

        /**
         * PATH 里实际命中的那个可执行文件（可能是转发器）。
         * 它只是候选/配置：真正在跑的是 {@link #runningPath()}，两者不一致即为问题。
         */
        public String hitPath() {
            if (hitExePath != null) {
                return hitExePath;
            }
            return active == null ? null : active.exePath;
        }

        /** 系统实际在用的可执行文件——"当前生效"就是它。 */
        public String runningPath() {
            return active == null ? null : active.exePath;
        }

        /**
         * 命中与使用是否不一致：PATH 先命中了转发器，真正跑的是另一个文件。
         * 这种状态下，任何"往用户 PATH 里加目录"式的切换都不会生效。
         */
        public boolean blockedByForwarder() {
            return forwarderPath != null
                    && hitPath() != null && runningPath() != null
                    && !EnvStore.samePath(hitPath(), runningPath());
        }

        /** PATH 中是否有生效的解释器。 */
        public boolean inPath() {
            return active != null;
        }

        /** 可用于切换的实例（排除 .NET 运行时这类由宿主自动选择的条目）。 */
        public List<Install> switchableInstalls() {
            List<Install> out = new ArrayList<>();
            for (Install i : installs) {
                if (i.switchable) {
                    out.add(i);
                }
            }
            return out;
        }

        /**
         * 环境配置就绪度：0=无关联变量需求, 1=已配置且健康, 2=配置了但异常, 3=需要但未配置。
         */
        public int envLevel() {
            if (vars.isEmpty()) {
                return 0;
            }
            boolean anySet = false;
            boolean allBad = true;
            for (VarState v : vars.values()) {
                boolean set = v.effectiveValue != null && !v.effectiveValue.trim().isEmpty();
                if (set) {
                    anySet = true;
                    if (v.healthy()) {
                        allBad = false;
                    }
                }
            }
            if (!anySet) {
                // 语言本身可用但没配 HOME 变量：多数情况不影响使用，标记为"可选未配"
                return installed ? 4 : 3;
            }
            return allBad ? 2 : 1;
        }

        public String envLabel() {
            switch (envLevel()) {
                case 1: return "已配置";
                case 2: return "配置异常";
                case 3: return "未配置";
                case 4: return "可选未配";
                default: return "无需配置";
            }
        }
    }

    /** 整次扫描的汇总。 */
    public static final class Report {
        public final List<LangResult> results = new ArrayList<>();
        public String osLabel = Os.label();
        public String javaHome = System.getenv("JAVA_HOME");
        public String systemPathRaw;
        public long totalMillis;
        public String scannedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        public LangResult byId(String id) {
            for (LangResult r : results) {
                if (r.spec.id.equals(id)) {
                    return r;
                }
            }
            return null;
        }

        public int installedCount() {
            int n = 0;
            for (LangResult r : results) {
                if (r.installed) {
                    n++;
                }
            }
            return n;
        }

        /** 存在多个可切换版本的语言数量。 */
        public int multiVersionCount() {
            int n = 0;
            for (LangResult r : results) {
                if (r.switchableInstalls().size() > 1) {
                    n++;
                }
            }
            return n;
        }

        /** 已安装但不在 PATH 中的语言数量。 */
        public int notInPathCount() {
            int n = 0;
            for (LangResult r : results) {
                if (r.installed && !r.inPath()) {
                    n++;
                }
            }
            return n;
        }

        public int envIssueCount() {
            int n = 0;
            for (LangResult r : results) {
                int lv = r.envLevel();
                if (lv == 2) {
                    n++;
                }
            }
            return n;
        }
    }
}
