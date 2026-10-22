package com.langmgr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一种语言 / 工具链的探测规格。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code id} 内部唯一标识</li>
 *   <li>{@code displayName} 界面显示名</li>
 *   <li>{@code category} 分类，用于界面分组</li>
 *   <li>{@code probes} 探测命令列表，按顺序尝试，第一个成功产出版本的胜出</li>
 *   <li>{@code exeNames} 用于 which 查找的可执行名</li>
 *   <li>{@code homeVars} 该语言关联的环境变量名（如 JAVA_HOME），决定"环境配置是否就绪"</li>
 *   <li>{@code pkgManagers} 该语言的包管理器探测规格</li>
 * </ul>
 */
public final class LangSpec {

    /** 单条探测命令。 */
    public static final class Probe {
        public final List<String> command;
        /** 从输出中提取版本号的正则，第 1 组为版本号。 */
        public final Pattern versionRegex;
        /** 该命令是否输出 JSON（用于依赖解析）。 */
        public final boolean json;

        public Probe(List<String> command, String versionRegex, boolean json) {
            this.command = command;
            this.versionRegex = versionRegex == null ? null : Pattern.compile(versionRegex, Pattern.CASE_INSENSITIVE);
            this.json = json;
        }

        public String extract(String output) {
            if (output == null || output.isEmpty()) {
                return null;
            }
            if (versionRegex != null) {
                Matcher m = versionRegex.matcher(output);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
            // 兜底：抓第一个形如 1.2.3 的串
            Matcher m = Pattern.compile("(\\d+\\.\\d+[\\w.\\-+]*)").matcher(output);
            return m.find() ? m.group(1) : Proc.firstLine(output);
        }
    }

    /** 包管理器规格。 */
    public static final class PkgManager {
        public final String id;
        public final String displayName;
        public final List<String> versionCmd;
        public final String versionRegex;
        public final List<String> listCmd;
        public final boolean listJson;
        public final String manifestName;

        public PkgManager(String id, String displayName, List<String> versionCmd, String versionRegex,
                          List<String> listCmd, boolean listJson, String manifestName) {
            this.id = id;
            this.displayName = displayName;
            this.versionCmd = versionCmd;
            this.versionRegex = versionRegex;
            this.listCmd = listCmd;
            this.listJson = listJson;
            this.manifestName = manifestName;
        }
    }

    public final String id;
    public final String displayName;
    public final String category;
    public final List<Probe> probes;
    public final List<String> exeNames;
    public final List<String> homeVars;
    public final List<PkgManager> pkgManagers;
    /** 该语言是否支持多版本共存检测（如 py launcher、conda env、sdkman）。 */
    public final boolean multiVersion;
    /**
     * 版本是否可以通过环境变量选择。
     * MSVC / C# 编译器这类由安装器（Visual Studio Installer、.NET 安装包）管理的语言
     * 应设为 false：即使把它们的目录加进 PATH（使命令可被调用），也不构成"版本切换"，
     * 混进切换候选只会误导用户以为切了就能生效。
     */
    public final boolean envSwitchable;
    /** 一句话说明，用于界面提示。 */
    public final String hint;
    /**
     * 官方下载网址（标签 → URL）。
     * 标签如 "官方下载页" / "全部历史版本" / "Windows 安装包"，按登记顺序展示。
     * 供「语言与解释器」详情页与诊断报告使用：未安装时可照着网址去装，
     * 已安装多版本时也可从历史版本页补装其它版本。
     */
    public final Map<String, String> downloads;

    private LangSpec(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.category = b.category;
        this.probes = Collections.unmodifiableList(b.probes);
        this.exeNames = Collections.unmodifiableList(b.exeNames);
        this.homeVars = Collections.unmodifiableList(b.homeVars);
        this.pkgManagers = Collections.unmodifiableList(b.pkgManagers);
        this.multiVersion = b.multiVersion;
        this.envSwitchable = b.envSwitchable;
        this.hint = b.hint;
        this.downloads = Collections.unmodifiableMap(b.downloads);
    }

    public static Builder builder(String id, String displayName, String category) {
        return new Builder(id, displayName, category);
    }

    public static final class Builder {
        private final String id;
        private final String displayName;
        private final String category;
        private final List<Probe> probes = new ArrayList<>();
        private final List<String> exeNames = new ArrayList<>();
        private final List<String> homeVars = new ArrayList<>();
        private final List<PkgManager> pkgManagers = new ArrayList<>();
        private final Map<String, String> downloads = new LinkedHashMap<>();
        private boolean multiVersion = false;
        private boolean envSwitchable = true;
        private String hint = "";

        Builder(String id, String displayName, String category) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
        }

        public Builder probe(String versionRegex, String... cmd) {
            probes.add(new Probe(Arrays.asList(cmd), versionRegex, false));
            return this;
        }

        public Builder probeJson(List<String> cmd) {
            probes.add(new Probe(cmd, null, true));
            return this;
        }

        public Builder exe(String... names) {
            exeNames.addAll(Arrays.asList(names));
            return this;
        }

        public Builder homeVar(String... vars) {
            homeVars.addAll(Arrays.asList(vars));
            return this;
        }

        public Builder pkg(PkgManager pm) {
            pkgManagers.add(pm);
            return this;
        }

        /** 登记一条官方下载网址（标签 + URL），可连续调用多条。 */
        public Builder download(String label, String url) {
            if (label != null && url != null && !label.isEmpty() && !url.isEmpty()) {
                downloads.put(label, url);
            }
            return this;
        }

        public Builder multiVersion(boolean v) {
            multiVersion = v;
            return this;
        }

        /** 声明该语言的版本不由环境变量选择（MSVC/C# 等），禁止进入切换候选。 */
        public Builder envSwitchable(boolean v) {
            envSwitchable = v;
            return this;
        }

        public Builder hint(String h) {
            hint = h;
            return this;
        }

        public LangSpec build() {
            return new LangSpec(this);
        }
    }

    /* ------------------------------------------------------------ 注册表 */

    private static final Map<String, LangSpec> REGISTRY = new LinkedHashMap<>();

    static {
        // ---------------- JVM ----------------
        register(LangSpec.builder("java", "Java (JDK/JRE)", "JVM 系")
                .probe("version \"?([\\d._\\-ea+]+)\"?", "java", "-version")
                .probe("javac\\s+([\\d._\\-+]+)", "javac", "-version")
                .exe("java", "javac")
                .homeVar("JAVA_HOME", "JDK_HOME")
                .pkg(new PkgManager("maven", "Maven",
                        Arrays.asList("mvn", "-v"), "Apache Maven ([\\d.]+)",
                        Arrays.asList("mvn", "-o", "dependency:list"), false, "pom.xml"))
                .pkg(new PkgManager("gradle", "Gradle",
                        Arrays.asList("gradle", "-v"), "Gradle ([\\d.]+)",
                        Arrays.asList("gradle", "dependencies"), false, "build.gradle"))
                .multiVersion(true)
                .hint("JAVA_HOME 决定默认 JDK；多版本切换通过改写该变量并同步 PATH 实现。")
                .download("官方下载页（Oracle JDK）", "https://www.oracle.com/java/technologies/downloads/")
                .download("OpenJDK（Eclipse Temurin）", "https://adoptium.net/temurin/releases/")
                .download("全部历史版本（JDK Archive）", "https://jdk.java.net/archive/")
                .build());

        register(LangSpec.builder("kotlin", "Kotlin", "JVM 系")
                .probe("Kotlin ([\\w.\\-]+)", "kotlin", "-version")
                .probe("info: kotlinc-jvm ([\\w.\\-]+)", "kotlinc", "-version")
                .exe("kotlin", "kotlinc")
                .homeVar("KOTLIN_HOME")
                .hint("通常随 IntelliJ 或 SDKMAN 安装，独立命令行较少。")
                .download("官方下载页（含全部版本）", "https://kotlinlang.org/releases.html")
                .download("GitHub Releases", "https://github.com/JetBrains/kotlin/releases")
                .build());

        register(LangSpec.builder("scala", "Scala", "JVM 系")
                .probe("Scala (?:code )?version ([\\w.\\-]+)", "scala", "-version")
                .probe("Scala compiler version ([\\w.\\-]+)", "scalac", "-version")
                .exe("scala", "scalac")
                .homeVar("SCALA_HOME")
                .pkg(new PkgManager("sbt", "sbt",
                        Arrays.asList("sbt", "about"), "sbt version[:\\s]+([\\d.]+)",
                        Arrays.asList("sbt", "show", "dependencyList"), false, "build.sbt"))
                .download("官方下载页", "https://www.scala-lang.org/download/")
                .download("全部历史版本", "https://www.scala-lang.org/download/all.html")
                .build());

        // ---------------- 脚本语言 ----------------
        register(LangSpec.builder("python", "Python", "脚本语言")
                .probe("Python ([\\d.]+[\\w.]*)", "python", "--version")
                .probe("Python ([\\d.]+[\\w.]*)", "python3", "--version")
                .probe("Python ([\\d.]+[\\w.]*)", "py", "--version")
                .exe("python", "python3", "py")
                .homeVar("PYTHON_HOME", "PYTHONHOME")
                .pkg(new PkgManager("pip", "pip",
                        Arrays.asList("python", "-m", "pip", "--version"), "pip ([\\d.]+)",
                        Arrays.asList("python", "-m", "pip", "list", "--format=json"), true, "requirements.txt"))
                .pkg(new PkgManager("conda", "Conda",
                        Arrays.asList("conda", "--version"), "conda ([\\d.]+)",
                        Arrays.asList("conda", "list", "--json"), true, "environment.yml"))
                .pkg(new PkgManager("poetry", "Poetry",
                        Arrays.asList("poetry", "--version"), "Poetry \\(?version ([\\d.]+)",
                        Arrays.asList("poetry", "show"), false, "pyproject.toml"))
                .multiVersion(true)
                .hint("PYTHONHOME 与虚拟环境冲突时建议留空；版本切换优先用 conda env 或 py launcher。")
                .download("官方下载页", "https://www.python.org/downloads/")
                .download("Windows 各版本安装包", "https://www.python.org/downloads/windows/")
                .download("全部历史版本（源码归档）", "https://www.python.org/downloads/source/")
                .download("Anaconda / Miniconda", "https://docs.conda.io/en/latest/miniconda.html")
                .build());

        register(LangSpec.builder("node", "Node.js", "脚本语言")
                .probe("v([\\d.]+)", "node", "--version")
                .exe("node", "nodejs")
                .homeVar("NODE_HOME")
                .pkg(new PkgManager("npm", "npm",
                        Arrays.asList("npm", "--version"), "^\\s*([\\d.]+)\\s*$",
                        Arrays.asList("npm", "list", "--json", "--depth=0"), true, "package.json"))
                .pkg(new PkgManager("yarn", "Yarn",
                        Arrays.asList("yarn", "--version"), "^\\s*([\\d.]+)\\s*$",
                        Arrays.asList("yarn", "list", "--json", "--depth=0"), true, "yarn.lock"))
                .pkg(new PkgManager("pnpm", "pnpm",
                        Arrays.asList("pnpm", "--version"), "^\\s*([\\d.]+)\\s*$",
                        Arrays.asList("pnpm", "list", "--json", "--depth=0"), true, "pnpm-lock.yaml"))
                .multiVersion(true)
                .hint("已装 nvm/fnm/volta 时，切换应由版本管理器接管，本工具只做展示与校验。")
                .download("官方下载页", "https://nodejs.org/zh-cn/download")
                .download("全部历史版本（含 LTS）", "https://nodejs.org/zh-cn/download/releases/")
                .download("nvm-windows（多版本管理）", "https://github.com/coreybutler/nvm-windows/releases")
                .build());

        register(LangSpec.builder("deno", "Deno", "脚本语言")
                .probe("deno ([\\d.]+)", "deno", "--version")
                .exe("deno")
                .homeVar("DENO_INSTALL")
                .download("官方下载页", "https://docs.deno.com/runtime/getting_started/installation/")
                .download("全部历史版本（Releases）", "https://github.com/denoland/deno/releases")
                .build());

        register(LangSpec.builder("bun", "Bun", "脚本语言")
                .probe("([\\d.]+)", "bun", "--version")
                .exe("bun")
                .homeVar("BUN_INSTALL")
                .download("官方下载页", "https://bun.sh/docs/installation")
                .download("全部历史版本（Releases）", "https://github.com/oven-sh/bun/releases")
                .build());

        register(LangSpec.builder("php", "PHP", "脚本语言")
                .probe("PHP ([\\d.]+[\\w\\-.]*)", "php", "--version")
                .exe("php")
                .homeVar("PHP_HOME", "PHPRC")
                .pkg(new PkgManager("composer", "Composer",
                        Arrays.asList("composer", "--version"), "Composer(?:\\s+version)?\\s+([\\d.]+)",
                        Arrays.asList("composer", "show", "--format=json"), true, "composer.json"))
                .download("官方下载页", "https://www.php.net/downloads/")
                .download("全部历史版本", "https://www.php.net/releases/")
                .download("Windows 专用构建", "https://windows.php.net/download/")
                .build());

        register(LangSpec.builder("ruby", "Ruby", "脚本语言")
                .probe("ruby ([\\d.p]+)", "ruby", "--version")
                .exe("ruby")
                .homeVar("RUBY_HOME", "GEM_HOME")
                .pkg(new PkgManager("gem", "RubyGems",
                        Arrays.asList("gem", "--version"), "^\\s*([\\d.]+)\\s*$",
                        Arrays.asList("gem", "list", "--local"), false, "Gemfile"))
                .pkg(new PkgManager("bundler", "Bundler",
                        Arrays.asList("bundle", "--version"), "Bundler version ([\\d.]+)",
                        Arrays.asList("bundle", "list"), false, "Gemfile"))
                .download("官方下载页", "https://www.ruby-lang.org/zh_CN/downloads/")
                .download("RubyInstaller for Windows（各版本）", "https://rubyinstaller.org/downloads/")
                .download("全部历史版本", "https://www.ruby-lang.org/en/downloads/releases/")
                .build());

        register(LangSpec.builder("perl", "Perl", "脚本语言")
                .probe("\\(v?([\\d.]+)\\)", "perl", "--version")
                .exe("perl")
                .download("官方下载页", "https://www.perl.org/get.html")
                .download("Strawberry Perl（Windows 各版本）", "https://strawberryperl.com/")
                .build());

        register(LangSpec.builder("lua", "Lua", "脚本语言")
                .probe("Lua ([\\d.]+)", "lua", "-v")
                .exe("lua", "lua5.4", "lua5.3")
                .pkg(new PkgManager("luarocks", "LuaRocks",
                        Arrays.asList("luarocks", "--version"), "luarocks ([\\d.]+)",
                        Arrays.asList("luarocks", "list", "--porcelain"), false, "rockspec"))
                .download("官方下载页", "https://www.lua.org/download.html")
                .download("Lua for Windows（各版本）", "https://github.com/rjpcomputing/luaforwindows/releases")
                .build());

        register(LangSpec.builder("r", "R", "脚本语言")
                .probe("R version ([\\d.]+)", "Rscript", "--version")
                .probe("R version ([\\d.]+)", "R", "--version")
                .exe("Rscript", "R")
                .homeVar("R_HOME")
                .download("官方下载页（CRAN）", "https://cran.r-project.org/")
                .download("Windows 各历史版本", "https://cran.r-project.org/bin/windows/base/old/")
                .build());

        register(LangSpec.builder("julia", "Julia", "脚本语言")
                .probe("julia version ([\\d.]+)", "julia", "--version")
                .exe("julia")
                .download("官方下载页", "https://julialang.org/downloads/")
                .download("全部历史版本", "https://julialang-s3.julialang.org/bin/versions.html")
                .build());

        register(LangSpec.builder("powershell", "PowerShell", "脚本语言")
                .probe("PowerShell ([\\d.]+)", "pwsh", "--version")
                // Windows PowerShell 5.1 不认识 --version，用 PSVersionTable 取版本；
                // 输出形如 "PowerShell 5.1.26100.4202"，与上面的正则共用
                .probe("PowerShell ([\\d.]+)", "powershell", "-NoProfile", "-NonInteractive",
                        "-Command", "'PowerShell ' + $PSVersionTable.PSVersion.ToString()")
                .exe("pwsh", "powershell")
                .download("PowerShell 7+（各版本 Releases）", "https://github.com/PowerShell/PowerShell/releases")
                .download("官方安装文档", "https://learn.microsoft.com/zh-cn/powershell/scripting/install/installing-powershell-on-windows")
                .build());

        // ---------------- 编译型 ----------------
        register(LangSpec.builder("gcc", "GCC (C/C++)", "编译型")
                // 不要用 gcc.*?([\d.]+)：MinGW 首行含 x86_64，会先抓到 "64"。
                .probe("gcc\\s.*?\\b(\\d+\\.\\d+(?:\\.\\d+)?)", "gcc", "--version")
                .probe("\\(GCC\\)\\s+(\\d+(?:\\.\\d+)+)", "gcc", "--version")
                // 有的环境只装了 g++（gcc 前端缺失），单独给 g++ 一组探测命令
                .probe("g\\+\\+\\s.*?\\b(\\d+\\.\\d+(?:\\.\\d+)?)", "g++", "--version")
                .probe("\\(GCC\\)\\s+(\\d+(?:\\.\\d+)+)", "g++", "--version")
                .exe("gcc", "g++", "cc")
                .homeVar("MINGW_HOME", "MSYS_HOME")
                .pkg(new PkgManager("cmake", "CMake",
                        Arrays.asList("cmake", "--version"), "cmake version ([\\d.]+)",
                        null, false, "CMakeLists.txt"))
                .hint("Windows 上常见来源：MinGW-w64、MSYS2、TDM-GCC、WSL 内 gcc。"
                        + "用 Visual Studio 装的 C++ 是 MSVC（cl.exe），见独立的 MSVC 条目。")
                .download("GCC 官方发布页", "https://gcc.gnu.org/releases.html")
                .download("MinGW-w64（WinLibs 构建，各版本）", "https://winlibs.com/")
                .download("MSYS2", "https://www.msys2.org/")
                .build());

        register(LangSpec.builder("msvc", "MSVC (Visual Studio C/C++)", "编译型")
                // cl 不带参数会打印用法说明，不能作为版本探测；/? 的首行横幅带真实版本号。
                // 横幅随 VS 语言包本地化：
                //   英文："Microsoft (R) C/C++ Optimizing Compiler Version 19.29.30155 for x64"
                //   中文："Microsoft (R) C/C++ 优化编译器 19.29.30153 版"
                // 因此正则锚定 "C/C++" 与其后 60 字符内的三段式版本号，中英文通吃。
                // cl 命中失败也没关系——Scanner.discoverMsvc() 会通过 vswhere /
                // 安装目录兜底发现，版本号直接取目录名。
                .probe("C/C\\+\\+.{0,60}?(\\d+\\.\\d+\\.\\d+)", "cl", "/?")
                .exe("cl")
                .envSwitchable(false)
                .pkg(new PkgManager("vcpkg", "vcpkg",
                        Arrays.asList("vcpkg", "version"), "version ([\\w.\\-]+)",
                        Arrays.asList("vcpkg", "list"), false, "vcpkg.json"))
                .hint("随 Visual Studio / Build Tools 安装（cl.exe 默认不在 PATH，需 vcvars64.bat）；"
                        + "本工具通过 vswhere 与安装目录直接发现，无需进入开发者命令行。")
                .download("Visual Studio 下载页（含 Community）", "https://visualstudio.microsoft.com/zh-hans/downloads/")
                .download("Visual Studio 2019 及更早版本", "https://visualstudio.microsoft.com/zh-hans/older-releases/")
                .download("Build Tools for Visual Studio（仅编译器）", "https://visualstudio.microsoft.com/zh-hans/downloads/#build-tools-for-visual-studio-2022")
                .build());

        register(LangSpec.builder("clang", "Clang/LLVM", "编译型")
                .probe("clang version ([\\d.]+)", "clang", "--version")
                // clang-cl 是 LLVM 的 MSVC 兼容驱动（随 LLVM 一起安装），
                // --version 输出与 clang 相同的 "clang version x.y.z"
                .probe("clang version ([\\d.]+)", "clang-cl", "--version")
                .exe("clang", "clang++", "clang-cl")
                .homeVar("LLVM_HOME")
                .hint("Clang 可直接使用，clang-cl 用于替代 MSVC 驱动语法（/std:c++17 等）。")
                .download("全部版本（LLVM Releases）", "https://github.com/llvm/llvm-project/releases")
                .download("官方下载页", "https://releases.llvm.org/")
                .build());

        register(LangSpec.builder("icx", "Intel C++ (oneAPI)", "编译型")
                // icx --version 输出形如
                // "Intel(R) oneAPI DPC++/C++ Compiler 2024.2.1 ..."（也有旧式 icl）
                .probe("Compiler\\s+([\\d.]{4,})", "icx", "--version")
                .probe("Compiler\\s+([\\d.]{4,})", "icl", "/?")
                .exe("icx", "icl")
                .homeVar("ONEAPI_ROOT")
                .hint("随 Intel oneAPI Base/HPC Toolkit 安装，主要面向高性能计算场景。")
                .download("Intel oneAPI Base Toolkit", "https://www.intel.com/content/www/us/en/developer/tools/oneapi/base-toolkit-download.html")
                .download("Intel C++ Compiler Classic（历史版本）", "https://www.intel.com/content/www/us/en/developer/articles/tool/compilers-redistributable-libraries.html")
                .build());

        register(LangSpec.builder("tcc", "TinyCC (tcc)", "编译型")
                .probe("tcc version ([\\d.]+)", "tcc", "-v")
                .exe("tcc")
                .hint("超小型 C 编译器，常用于学习与脚本场景，非完整工具链。")
                .download("官方镜像（各版本）", "https://download.savannah.gnu.org/releases/tinycc/")
                .build());

        register(LangSpec.builder("go", "Go", "编译型")
                .probe("go([\\d.]+[\\w.]*)", "go", "version")
                .exe("go")
                .homeVar("GOROOT", "GOPATH", "GOBIN")
                .pkg(new PkgManager("gomod", "Go Modules",
                        Arrays.asList("go", "version"), "go([\\d.]+)",
                        Arrays.asList("go", "list", "-m", "-json", "all"), true, "go.mod"))
                .multiVersion(true)
                .hint("GOROOT 一般无需手设（go 自带定位），GOPATH 决定模块与二进制缓存位置。")
                .download("官方下载页（含全部历史版本）", "https://go.dev/dl/")
                .build());

        register(LangSpec.builder("rust", "Rust", "编译型")
                .probe("rustc ([\\d.]+[\\w.\\-+]*)", "rustc", "--version")
                .exe("rustc", "cargo")
                .homeVar("RUSTUP_HOME", "CARGO_HOME")
                .pkg(new PkgManager("cargo", "Cargo",
                        Arrays.asList("cargo", "--version"), "cargo ([\\d.]+)",
                        Arrays.asList("cargo", "tree"), false, "Cargo.toml"))
                .multiVersion(true)
                .hint("已装 rustup 时，切换 toolchain 请用 rustup default，本工具只做校验与展示。")
                .download("官方安装页（rustup）", "https://www.rust-lang.org/zh-CN/tools/install")
                .download("rustc 各历史版本（Releases）", "https://github.com/rust-lang/rust/releases")
                .build());

        register(LangSpec.builder("dotnet", ".NET SDK", "编译型")
                // --version 只有装了 SDK 才成功；仅装运行时时它会报错退出，
                // 因此追加 --info 作为兜底，从 Host 段取版本号，避免误判成"未安装"。
                .probe("([\\d.]+[\\w\\-.]*)", "dotnet", "--version")
                .probe("Version:\\s+([\\d.]+)", "dotnet", "--info")
                .exe("dotnet")
                .homeVar("DOTNET_ROOT")
                .pkg(new PkgManager("nuget", "NuGet",
                        Arrays.asList("dotnet", "nuget", "locals", "all", "--list"), null,
                        Arrays.asList("dotnet", "list", "package"), false, "*.csproj"))
                .multiVersion(true)
                .hint("多 SDK 版本共存，可用 global.json 按项目锁定 SDK 版本；"
                        + "SDK 不能靠改环境变量切换。运行时版本由宿主自动选择。"
                        + "仅装 .NET Framework（传统 C# 编译器 csc.exe）时请看独立的 C# 条目。")
                .download("官方下载页（SDK 与运行时，各版本）", "https://dotnet.microsoft.com/zh-cn/download/dotnet")
                .build());

        register(LangSpec.builder("csharp", "C# (.NET Framework / Roslyn)", "编译型")
                // csc 不带参数会打印用法说明且常以非 0 退出，不能作为版本探测；
                // /? 的横幅带真实版本号（"Visual C# Compiler version 4.8.x"）。
                // PATH 命中失败也不影响判断——Scanner.discoverCsharp() 会扫描
                // .NET Framework 目录与 VS 的 Roslyn 目录兜底。
                .probe("Compiler version\\s+([\\d.]+)", "csc", "/?")
                .exe("csc")
                .envSwitchable(false)
                .hint("传统 .NET Framework 自带 csc.exe（C:\\Windows\\Microsoft.NET\\...），"
                        + "Visual Studio 还带 Roslyn 编译器；两者都不在 PATH 也能被发现。"
                        + "新式 .NET（dotnet CLI）见独立的 .NET SDK 条目。")
                .download(".NET Framework 下载页", "https://dotnet.microsoft.com/zh-cn/download/dotnet-framework")
                .download(".NET Framework 各历史版本", "https://dotnet.microsoft.com/zh-cn/download/dotnet-framework/net48")
                .build());

        register(LangSpec.builder("mono", "Mono (C# 跨平台)", "编译型")
                .probe("Mono JIT compiler version ([\\d.]+[\\w.\\-]*)", "mono", "--version")
                .probe("compiler version ([\\d.]+)", "mcs", "--version")
                .exe("mono", "mcs")
                .homeVar("MONO_HOME", "MONO_PATH")
                .pkg(new PkgManager("nuget", "NuGet",
                        Arrays.asList("msbuild", "-version"), null,
                        null, false, "*.csproj"))
                .hint("跨平台开源 .NET 实现，自带 mcs/csc 编译器；Windows 上较少见，"
                        + "多与 Xamarin/旧版跨平台项目并存。新项目建议用 .NET SDK。")
                .download("Mono 官方下载页", "https://www.mono-project.com/download/stable/")
                .download("全部历史版本（GitHub Releases）", "https://github.com/mono/mono/releases")
                .build());

        register(LangSpec.builder("typescript", "TypeScript", "编译型")
                .probe("Version ([\\d.]+)", "tsc", "--version")
                .exe("tsc", "tsserver")
                .hint("通常作为项目级依赖存在，全局安装较少。")
                .download("官方下载页（npm 安装）", "https://www.typescriptlang.org/download")
                .download("全部历史版本（Releases）", "https://github.com/microsoft/TypeScript/releases")
                .build());

        register(LangSpec.builder("dart", "Dart", "编译型")
                .probe("Dart SDK version: ([\\d.]+)", "dart", "--version")
                .exe("dart")
                .homeVar("DART_HOME")
                .download("官方下载页", "https://dart.dev/get-dart")
                .download("SDK 全部历史版本", "https://dart.dev/tools/sdk/archive")
                .build());

        register(LangSpec.builder("flutter", "Flutter", "编译型")
                .probe("Flutter ([\\d.]+)", "flutter", "--version")
                .exe("flutter")
                .homeVar("FLUTTER_ROOT", "FLUTTER_HOME")
                .multiVersion(true)
                .hint("Flutter 自带 Dart SDK；版本切换用 flutter version 或 fvm。")
                .download("官方下载页", "https://docs.flutter.dev/get-started/install")
                .download("SDK 全部历史版本", "https://docs.flutter.dev/release/archive")
                .build());

        // ---------------- 构建与容器 ----------------
        register(LangSpec.builder("cmake", "CMake", "构建工具")
                .probe("cmake version ([\\d.]+)", "cmake", "--version")
                .exe("cmake")
                .homeVar("CMAKE_HOME")
                .download("官方下载页", "https://cmake.org/download/")
                .download("全部历史版本", "https://cmake.org/files/")
                .build());

        register(LangSpec.builder("make", "Make", "构建工具")
                .probe("GNU Make ([\\d.]+)", "make", "--version")
                .exe("make", "mingw32-make", "gmake")
                .download("官方下载页（GNU Make）", "https://www.gnu.org/software/make/#downloading")
                .build());

        register(LangSpec.builder("ninja", "Ninja", "构建工具")
                .probe("([\\d.]+)", "ninja", "--version")
                .exe("ninja")
                .download("全部版本（Releases）", "https://github.com/ninja-build/ninja/releases")
                .build());

        register(LangSpec.builder("git", "Git", "构建工具")
                .probe("git version ([\\w.\\-]+)", "git", "--version")
                .exe("git")
                .download("官方下载页（各平台）", "https://git-scm.com/downloads")
                .download("Windows 各历史版本", "https://github.com/git-for-windows/git/releases")
                .build());

        register(LangSpec.builder("docker", "Docker", "构建工具")
                .probe("Docker version ([\\d.]+)", "docker", "--version")
                .exe("docker", "docker-compose")
                .hint("检测到的版本来自 Docker CLI，实际可用性还取决于守护进程是否启动。")
                .download("Docker Desktop 下载页", "https://www.docker.com/products/docker-desktop/")
                .download("CLI 各版本（Releases）", "https://github.com/docker/cli/releases")
                .build());
    }

    private static void register(LangSpec spec) {
        REGISTRY.put(spec.id, spec);
    }

    public static Map<String, LangSpec> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    public static LangSpec get(String id) {
        return REGISTRY.get(id);
    }

    public static List<String> categories() {
        List<String> cats = new ArrayList<>();
        for (LangSpec s : REGISTRY.values()) {
            if (!cats.contains(s.category)) {
                cats.add(s.category);
            }
        }
        return cats;
    }

    public static List<LangSpec> byCategory(String category) {
        List<LangSpec> list = new ArrayList<>();
        for (LangSpec s : REGISTRY.values()) {
            if (s.category.equals(category)) {
                list.add(s);
            }
        }
        return list;
    }
}
