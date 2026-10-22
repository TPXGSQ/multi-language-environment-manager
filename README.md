# 多语言环境管理器

> 一个用来查看、校验和切换本机开发环境的 Windows 桌面工具。纯 Java + Swing 实现，零第三方依赖。

<!-- Badges：纯静态，无需联网渲染 -->
![Platform](https://img.shields.io/badge/platform-Windows%2010%20%7C%2011-0078D6?style=flat-square)
![Java](https://img.shields.io/badge/Java-11%2B-orange?style=flat-square)
![UI](https://img.shields.io/badge/UI-Swing-4B8BBE?style=flat-square)
![Dependencies](https://img.shields.io/badge/dependencies-0-brightgreen?style=flat-square)
![Languages](https://img.shields.io/badge/detects-32%20toolchains-blueviolet?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

**32 种**语言 / 工具链的版本检测、存储位置定位、环境配置校验、多版本切换、依赖清单解析与诊断报告导出。
不需要管理员权限，不写系统级变量，所有变更都可一键回滚。

> [!IMPORTANT]
> **本项目由 AI 辅助完成。**
> 代码、架构设计、文档与缺陷排查均在 AI 编程助手协作下产出，由仓库维护者[@TPXGSQ](https://github.com/TPXGSQ)负责需求定义、验收测试与最终审核发布。
> 同一原则适用于本仓库的后续提交：请把代码与文档视为"AI 生成 + 人工审阅"的产物，关键操作（尤其是修改环境变量、删除目录）请自行确认后再执行。

---

## 目录

- [核心功能](#核心功能)
- [界面预览](#界面预览)
- [快速开始](#快速开始)
- [功能详解](#功能详解)
- [安全设计](#安全设计)
- [检测能力边界](#检测能力边界)
- [从源码构建](#从源码构建)
- [项目结构](#项目结构)
- [扩展一种新语言](#扩展一种新语言)
- [本机实测结果](#本机实测结果)
- [已知局限](#已知局限)
- [贡献](#贡献)
- [许可](#许可)
---

## 核心功能

| 能力 | 位置 | 说明 |
|---|---|---|
| **版本检测** | 语言与解释器页 | 32 种语言/工具链，逐个执行版本命令并用正则提取；含 MSVC / .NET Framework 的离线发现 |
| **存储位置定位** | 语言与解释器页 | 可执行文件绝对路径 + 推断的安装根目录 + 发现方式（PATH / 注册表 / 目录扫描…） |
| **环境配置校验** | 环境概览页 / 语言详情 | 校验 HOME 类变量是否设置、目录是否存在、是否在 PATH、是否与生效解释器一致 |
| **版本快速切换** | 版本切换页 | 发现 PATH 之外的已安装版本，一键切换并同步环境变量与 PATH，执行后复核 |
| **依赖管理** | 依赖管理页 | 全局已安装包（pip / npm / gem / conda / cargo…）+ 12 种项目依赖声明文件解析 |
| **配置随版本自动生成** | 版本切换页 | 按目标版本推导推荐的变量组合，只写差异项，不覆盖无关配置 |

---

## 界面预览

| 环境概览 | 语言与解释器 | 版本切换 |
|---|---|---|
| 统计卡片 + 待处理问题清单，每类问题带"一键处理" | 左侧搜索/筛选，右侧详情回答「是什么版本、装在哪、怎么发现、配置对不对」 | 三步式：选版本 → 生成计划 → 确认应用，全程先看后改 |

> 界面截图待补充。可在 Issue 中提供你的截图帮助完善本节。

---

## 快速开始

### 方式 1：免安装 exe（推荐）

直接运行 `dist/多语言环境管理器/多语言环境管理器.exe`。
自带精简 JRE，**无需预先安装 Java**。

### 方式 2：双击启动脚本

```bat
:: 自动寻找 Java、必要时从源码编译、然后启动图形界面
启动.bat
```

> [!WARNING]
> `启动.bat` 必须保持**纯 ASCII 内容 + CRLF 换行**。若用编辑器改过并保存成 UTF-8 中文或 LF 换行，
> `cmd` 会解析错乱并刷出大量 `'XX' 不是内部或外部命令`。误改后请改用方式 1 或方式 3。

### 方式 3：运行 jar

```bash
# 图形界面
java -Dfile.encoding=UTF-8 -jar langmgr.jar

# 只读扫描，打印表格（适合脚本 / CI，或界面出问题时排查）
java -Djava.awt.headless=true -jar langmgr.jar --cli

# 查看帮助
java -jar langmgr.jar --help
```

需要 **JDK 11 或更高版本**（用到 `Process.descendants()`、`Files.list()` 等 API；本机以 JDK 21 验证）。

---

## 功能详解

### 环境概览

四张统计卡片（可用语言数 / 多版本共存数 / 环境配置异常数 / PATH 条目数）、已安装语言速览，
以及**需要关注清单**——把 HOME 变量失效、装了但不在 PATH、PATH 中的死条目、PATH 过长或重复等问题一次性列出。

每类问题尽量提供「一键处理」：

| 问题 | 一键处理动作 |
|---|---|
| HOME 变量失效 / 不一致 | 改写成当前生效版本的推导值 |
| 装了但不在 PATH | 把安装目录追加到用户 PATH |
| 失效 PATH 条目 | 备份后清理 |
| PATH 过长且有重复 | 一键去重 |

所有修改**执行前自动备份**到 `backup/`，只写当前用户作用域（HKCU），可随时在「环境变量 → 备份与回滚」还原。

> [!NOTE]
> 有一部分问题软件**无法自动处理**，例如"PATH 转发器拦截"必须改系统级 PATH。
> 这类问题点「处理」会直接跳到对应页面的操作面板，并在右侧给出「它是什么 / 为什么切换不生效 / 怎么解决」，
> 而不是留一个点了没反应的按钮。

### 语言与解释器

左侧可按名称/路径搜索、按「已安装 / 未安装 / 多版本」筛选；右侧详情回答四个问题：

- **当前生效版本**与可执行文件绝对路径（双击复制）。判定依据是**系统实际在用**的那个：
  PATH 命中的只是候选，若命中的是转发器，真正跑起来的是它背后的目标，详情里会把两者都列出来
- **安装根目录**与发现方式（PATH / 注册表 / py launcher / conda 环境 / 目录扫描…）
- **全部已发现的安装**——包括装了但不在 PATH 里的，右键可查看原始版本输出、打开所在目录
- **关联环境变量状态**，异常时提供「设为当前版本」一键修正

系统 PATH 里的**转发器**（如 Oracle 的 `javapath\java.exe`）会单独列一条，状态标为「转发器 · 拦截」：
它不是一个可选版本，而是"命中与使用不一致、导致切换失效"的原因。

每种语言都登记了**官方下载页**与各历史版本页，单击直接在浏览器打开。

### 版本切换

刻意做成三步式，因为改 PATH 出错会直接让终端瘫痪：

1. **选语言、选目标版本**
2. **点「生成变更计划」**——逐条列出将写哪个变量、PATH 怎么动，此时不碰任何配置
3. **点「确认并应用」**——执行前自动备份，执行后给日志，可一键回滚

可勾选项：

- 同时调整 PATH（默认开）：把目标版本的 bin 提到 PATH 首位
- 移除其他版本的 PATH 条目（默认开）：避免新旧版本互相干扰

> [!TIP]
> **执行后会复核，不会把"写变量成功"说成"切换成功"。**
> 按新开终端真正命中的顺序（系统级 PATH 在前、用户级在后）重新解析一次，再比对目标可执行文件。
> 典型失败场景是 Oracle 的 `javapath` 转发器——它在系统级 PATH 里，用户级永远排在它之后，压不过它。

**删除版本**：清理用户环境里所有指向它的引用（PATH 目录含重复条目、HOME 类变量），一次清干净不留死指针。
可选勾选"同时删除磁盘上的安装目录"（**默认不勾，勾选后不可恢复**）。

**导出脚本**：变量变更生成 `reg add`（保留注册表类型）或 `export`（bash）片段，用于 CI 或另一台机器。

### 依赖管理

三个标签页：

- **全局已安装依赖**——调包管理器的 list 命令（`pip list --format=json`、`npm list --json`、
  `conda list --json`、`gem list`、`cargo tree`…），支持筛选、多选复制安装命令，下方保留原始输出便于排查
- **项目依赖清单**——选一个项目目录，解析 `requirements.txt` / `environment.yml` / `pyproject.toml` /
  `package.json` / `pom.xml` / `build.gradle` / `go.mod` / `Cargo.toml` / `composer.json` / `Gemfile` /
  `pubspec.yaml` / `.csproj` / `packages.config`。不要求对应工具已安装，也不联网
- **命令速查**——各语言常用依赖命令，双击复制

### 环境变量

- **环境变量**：增删改当前用户变量，同时显示注册表原始值（含 `%VAR%`）与展开后的实际值
- **PATH 管理**：上移/下移/置顶、添加/编辑/移除、去重、清理失效条目、按字母排序。
  所有操作先改内存列表，必须点「提交 PATH 变更」才写入，提交前给出完整 diff
- **备份与回滚**：列出全部备份，可查看内容、回滚到任意一份、删除备份
- **系统级变量（只读）**：展示 HKLM 变量，并标注哪些被用户级覆盖

### 诊断报告

两个页签：**表格视图**把总览、包管理器、官方下载网址渲染成表格直读（下载网址双击可在浏览器打开）；
**文本视图**是原始 Markdown / JSON，可导出为报告文件，便于留档、对比两台机器的环境差异、或提 Issue。

### 界面设置

右上角「界面设置」可调整三项外观，选择保存在用户偏好里，重启后仍然生效：

- **界面字体**：列出系统已安装且支持中文的字体（微软雅黑 / 等线 / 黑体 / 宋体 / 楷体等），下拉列表每项用其自身字体预览
- **字体大小**：较小 85% / 标准 100% / 较大 115% / 特大 130% 四档，按钮、徽章、表格行高等尺寸同步缩放
- **窗口标题图标**：默认自绘品牌图标（蓝底白字"语"），可换成本地 PNG / JPG / GIF，可一键恢复默认

---

## 安全设计

改环境变量是有风险的操作，所以这个工具做了几层约束：

1. **扫描全程只读**——检测阶段不写注册表、不改文件、不动 PATH
2. **只写当前用户（HKCU）**——不碰系统级变量，因此不需要管理员权限，影响面限于你自己
3. **不用 `setx`**——`setx` 有两个会破坏环境的已知问题：把值截断到 1024 字符（长 PATH 会丢后半段），
   以及写入前展开 `%VAR%`（导致 `%JAVA_HOME%` 这类引用被永久固化成字面路径）。
   本工具改用 `reg add` 读写注册表，保留 `REG_EXPAND_SZ` 类型
4. **写入前强制备份**——备份失败就中止，绝不带病写入。备份是纯 JSON，即使程序打不开也能手动照着还原；
   回滚前也会先备份当前状态，应用计划中途失败会自动回滚
5. **先看计划再执行**——版本切换必须先预览变更计划，PATH 修改必须先点提交，都会显示完整 diff
6. **主动跳过有害变量**——`PYTHONHOME` 会覆盖 venv/conda 的定位、`GOROOT` 硬设常导致版本错配，
   这两类默认不设并给出解释，而不是闷头写入
7. **删除目录有白名单**——盘符根、`Windows`、`Program Files`、`Users`、`ProgramData`、
   程序自身目录、用户主目录全部拒绝删除
8. **命令执行不经 shell 拼接**——所有外部命令走参数列表，无命令注入面
9. **广播变更通知**——写完调用 `SendMessageTimeout(WM_SETTINGCHANGE)`，让新开的终端立即读到新值

> [!WARNING]
> 已打开的终端和 IDE **不会**自动刷新环境变量，这是 Windows 的固有行为，需要重启才能生效。

---

## 检测能力边界

### 支持的 32 种语言 / 工具链

| 分类 | 语言 / 工具链 |
|---|---|
| JVM 系 | Java、Kotlin、Scala |
| 脚本语言 | Python、Node.js、Deno、Bun、PHP、Ruby、Perl、Lua、R、Julia、PowerShell、TypeScript |
| C / C++ 编译器 | **GCC (MinGW)**、**Clang/LLVM**、**MSVC (Visual Studio)**、**Intel oneAPI (icx)**、**TinyCC (tcc)** |
| 其它编译型 | Go、Rust、.NET SDK、C# (.NET Framework / Roslyn)、Mono、Dart、Flutter |
| 构建与工具 | CMake、Make、Ninja、Git、Docker |

> [!NOTE]
> **同一门语言的不同实现会分别列出、各自独立检测。**
> C/C++ 有 GCC / Clang / MSVC / oneAPI / TinyCC 五条；C# 有 .NET Framework 的 `csc.exe`、Roslyn 编译器、Mono 的 `mcs` 三条。
> 它们不是"重复项"——工具链、ABI、标准库、平台支持各不相同，装了几个就该看到几个。

### 多版本发现渠道（不只是看 PATH）

| 语言 | 发现渠道 |
|---|---|
| Java | 常见 JDK 目录 + `~/.jdks` + `~/.sdkman` + 注册表 `JavaSoft` 键 |
| Python | `py -0p`（兼容新旧两种输出）+ `conda env list --json` + 常见安装目录 + WindowsApps（自动跳过 0 字节的别名 stub） |
| Node.js | 官方安装目录 + nvm + fnm + volta + scoop |
| .NET | `dotnet --list-sdks` / `--list-runtimes` |
| **C / C++** | `vswhere.exe` 枚举全部 VS 实例（含 Build Tools / 预览版），遍历 `VC\Tools\MSVC\<版本>\bin\Host{x64,x86,arm64}\*`；MinGW 系扫 `C:\mingw64`、`C:\msys64\mingw64`、`TDM-GCC-64` |
| **C#** | `%WINDIR%\Microsoft.NET\Framework64\v*` 与 `Framework\v*` 下的编译器 + Roslyn（`MSBuild\Current\Bin\Roslyn\csc.exe`），只收 v4 及以上 |
| Rust | `~/.rustup/toolchains` |
| Go | 常见目录 + `~/sdk` |
| Ruby / Flutter | rbenv / fvm；其余走标准前缀兜底 |

---

## 从源码构建

### 编译 jar

最简单是双击 `build.bat`（纯 ASCII，自动找 JDK、编译、重建 `langmgr.jar`，并把 icons 与 jar 同步进 dist 下的 app-image）：

```bat
build.bat
```

等价的手工命令（`-sourcepath` 会递归拉取依赖类，不需要 `sources.txt`）：

```bash
javac -encoding UTF-8 -d out -sourcepath src src/com/langmgr/App.java
jar --create --file langmgr.jar --main-class com.langmgr.App -C out .
```

> [!IMPORTANT]
> 手工打包时别忘了把 `icons/` 也放进 `out/`，否则 jar 内缺少图标兜底资源：
> ```bash
> cp -r icons out/icons && jar --create --file langmgr.jar --main-class com.langmgr.App -C out .
> ```

### 重新打包 exe（jpackage，免安装、自带运行时）

JDK 14+ 自带 `jpackage`，无需第三方工具：

```bat
mkdir exe-input 2>nul
copy /y langmgr.jar exe-input\
jpackage --type app-image --input exe-input --name "多语言环境管理器" ^
  --main-jar langmgr.jar --icon icons\app.ico --app-version 1.0 --vendor langmgr ^
  --add-modules java.base,java.desktop,java.prefs,java.logging,jdk.charsets,jdk.localedata ^
  --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=2" ^
  --dest dist
```

产物在 `dist/多语言环境管理器/`，约 58MB（其中 `runtime/` 是裁剪后的 JRE）。

> [!TIP]
> - 模块裁剪参数删不得——`jdk.charsets` 负责 GBK 输出解码，`jdk.localedata` 负责中文区域格式
> - `dist` 目录若被正在运行的 exe 占用会打包失败，先退出程序
> - 打完包再跑一次 `build.bat`，可把图标与最新 jar 同步进 app-image

---

## 项目结构

```
.
├── 启动.bat                      一键启动（自动编译 + 运行）
├── build.bat                     重编译打包 langmgr.jar，并同步 icons / jar 到 dist
├── langmgr.jar                   已打包的可执行 jar
├── README.md                     本文档
├── .gitignore                    忽略 out/、backup/、reports/ 等生成物
├── src/com/langmgr/
│   ├── App.java                  入口：GUI / --cli 两种模式
│   ├── core/
│   │   ├── Json.java             轻量 JSON 解析与序列化（支持 NDJSON，带嵌套深度保护）
│   │   ├── Proc.java             命令执行（超时、进程树销毁、GBK/UTF-8 编码探测）
│   │   ├── Os.java               平台判断、%VAR% 展开、路径归属判断
│   │   ├── AppPaths.java         应用/备份/报告/图标目录解析（不依赖启动时工作目录）
│   │   ├── EnvStore.java         用户环境变量读写（注册表方式）、PATH 操作、变更广播、备份
│   │   ├── LangSpec.java         语言规格注册表：探测命令、版本正则、关联变量、包管理器
│   │   ├── Detected.java         扫描结果数据结构
│   │   ├── Scanner.java          扫描引擎：PATH 探测 + 多版本发现 + 环境校验（并发）
│   │   ├── EnvApplier.java       版本切换：计划生成、备份、执行、回滚
│   │   ├── RefScanner.java       引用扫描：注册表 / 快捷方式 / 环境变量中对某目录的引用
│   │   └── DepInspector.java     依赖：包管理器清单 + 项目声明文件解析
│   └── ui/
│       ├── Theme.java            配色、字体、自绘组件、悬停提示（不依赖第三方外观库）
│       ├── AppModel.java         共享状态与后台扫描调度（SwingWorker）
│       ├── MainFrame.java        主窗口：侧栏导航 + CardLayout 页面切换
│       ├── DashboardPanel.java   环境概览
│       ├── LanguagePanel.java    语言与解释器
│       ├── SwitchPanel.java      版本切换
│       ├── DependencyPanel.java  依赖管理
│       ├── EnvPanel.java         环境变量与 PATH
│       ├── ReportPanel.java      诊断报告
│       ├── SettingsDialog.java   界面设置
│       ├── ForwarderHelp.java    转发器拦截的处置指引
│       └── AppIcon.java          图标加载（文件优先、jar 内资源兜底、最后自绘）
├── icons/
│   ├── head-pic.png              应用图标源图（窗口/任务栏/侧栏品牌区）
│   └── app.ico                   exe 图标（多尺寸）
├── dist/
│   └── 多语言环境管理器/         免安装 exe（自带精简 JRE，可脱离 Java 运行）
├── backup/                       环境备份（首次变更后自动创建）
└── out/                          编译产物（不存在属正常，重新编译时生成）
```

> 交付/分发时只需 `dist/多语言环境管理器` 整个文件夹；`src`、`icons` 供后续修改与重新构建。

---

## 扩展一种新语言

在 `LangSpec.java` 的静态块里 `register(...)` 一条即可，不用改任何 UI 代码：

```java
register(LangSpec.builder("zig", "Zig", "编译型")
        .probe("([\\d.]+)", "zig", "version")   // 命令 + 提取版本号的正则
        .exe("zig")                              // 用于 which 查找的可执行名
        .homeVar("ZIG_HOME")                     // 关联的环境变量
        .hint("说明文字，显示在详情页。")
        .build());
```

若还需要发现 PATH 之外的安装，在 `Scanner.discoverInstalls()` 里加一个 `case` 分支。

---

## 本机实测结果

开发机（Windows 10 19045 / JDK 21）上 `--cli` 的实际输出：

| 语言 | 版本 | 位置 | 安装数 |
|---|---|---|---|
| Java | 21.0.2 | `C:\Program Files\Java\jdk-21\bin\java.exe` | 3（jdk-21、jdk1.8.0_361、jre1.8.0_361） |
| Python | 3.13.14 | `...\python\versions\3.13.12\python.exe` | 4（3.13.14、Anaconda 3.9.13、Python39 3.9.10、conda env 3.8.20） |
| Node.js | 22.22.2 | `...\node\versions\22.22.2-6\node.exe` | 1 |
| PowerShell | 5.1.19041.7802 | `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` | 1 |
| MSVC | 14.29.30133（工具集） | `...\VS2019\Community\VC\Tools\MSVC\14.29.30133\bin\Hostx64\x64\cl.exe` | 1 |
| C# (.NET Framework) | 4.8.9363.0 | `C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe` | 3 |
| .NET SDK | 10.0.1 | `C:\Program Files\dotnet\dotnet.exe` | 8（1 SDK 宿主 + 7 运行时） |
| Git | 2.55.0.windows.3 | `...\PortableGit\mingw64\bin\git.exe` | 1 |

32 种语言全跑一遍约 **10–11 秒**（大量未安装的语言也要启动探测进程并等超时否决）。

### 开发中通过实测发现并修掉的真实缺陷

1. **`javapath` 是 Junction 转发目录** → 按路径反推会得到转发目录而非真实 JDK，导致 `JAVA_HOME` 被误报"不一致"。
   改为通过 `-XshowSettings:properties` 读 `java.home` 拿权威根目录，且加缓存（否则每个 JDK 启一次 JVM，扫描从 5s 涨到 35s）
2. **`py -0p` 旧版格式解析失败** → 原正则只认新版 `-V:3.12`，漏掉整个 Python 3.9.10
3. **`Programs\Python` 下的版本扫不到** → 解释器在子目录 `Python39\` 里，补了一层子目录遍历
4. **`dotnet --version` 在未装 SDK 时报错退出** → 输出里的 `.NET` 被正则当成了版本号。加退出码校验并补 `--info` 兜底
5. **PowerShell 被误判未安装 + 版本时有时无** → 它装在不在 PATH 的 `v1.0\` 子目录；且冷启动 1–9 秒不等。
   改为优先读注册表 `PowerShellEngine\PowerShellVersion`（毫秒级），进程探测仅作兜底
6. **探测失败被当成"未安装"** → 改为只要可执行文件存在就报「已安装」，版本拿不到时降级显示"已安装（版本未获取）"并保留原因
7. **`broadcastChange()` 静默失效** → 裸命令名 `powershell` 启动失败（该目录不在 PATH），改为定位绝对路径
8. **.NET 运行时混入切换列表** → 运行时由宿主选择、不能靠环境变量切换，新增 `switchable` 标记排除
9. **`cl /?` 版本横幅在 stderr** → 原逻辑只解析 stdout，版本号读成 `2.0`；改为合并 stdout + stderr 解析
10. **中文 VS 横幅正则不匹配** → "优化编译器 19.29.30153 版" 匹配不上 `Compiler Version`，改为容错正则
11. **JSON 深嵌套导致 `StackOverflowError`** → 递归下降无深度限制，且 `Error` 不被 `catch (RuntimeException)` 拦住，
    会打挂扫描线程。已加 64 层上限
12. **自定义表格渲染器丢失悬停提示** → 装了 `setCellRenderer` 的列绕过了通用提示逻辑，截断文字后悬停无反应

---

## 已知局限

- 装在非标准路径、既不在 PATH 也没有版本管理器登记的语言可能扫不到——可在「环境变量」页手动指定
- **Docker** 检测的是 CLI 版本，实际可用性还取决于守护进程是否启动
- **.NET 运行时**由宿主按项目选择、**SDK** 由 `global.json` 决定，都不能通过环境变量切换，因此排除在切换列表外
- **MSVC / C#** 的版本由 Visual Studio Installer 与宿主（.NET Framework）决定，软件只做检测与展示，不提供切换
- 已打开的终端 / IDE 不会自动刷新环境变量，需要重启
- 仅在 Windows 上完整验证；非 Windows 平台有降级实现（写 shell profile），但未做完整测试
- 扫描需要启动几十个外部进程，耗时取决于 Python / Anaconda 等解释器的冷启动速度

---

## 贡献

欢迎提 Issue 或 Pull Request，详细约定见 **[CONTRIBUTING.md](CONTRIBUTING.md)**。

- 🐛 [报告缺陷](https://github.com/TPXGSQ/multi-language-environment-manager/issues/new?template=bug_report.yml) — 请尽量附上「诊断报告」页导出的报告文件，能极大加快定位
- ✨ [功能建议](https://github.com/TPXGSQ/multi-language-environment-manager/issues/new?template=feature_request.yml)
- 📝 [更新日志](CHANGELOG.md)

代码风格：纯 JDK、零第三方依赖、中文注释、不引入构建工具（保持 `javac` 直编可用）。

---

## 许可

本项目采用 [MIT License](LICENSE) 开源。

```
Copyright (c) 2026 TPXGSQ
```

你可以自由地使用、修改、分发本项目，包括商业用途，只需保留原始版权声明与许可声明。

---

<div align="center">

**本项目由 AI 辅助完成 · 由 [@TPXGSQ](https://github.com/TPXGSQ) 需求定义与审核**

</div>
