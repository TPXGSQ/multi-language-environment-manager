# 多语言环境管理器 / Multi-Language Environment Manager

![Platform](https://img.shields.io/badge/platform-Windows-blue) ![Java](https://img.shields.io/badge/Java-11%2B-orange) ![Dependencies](https://img.shields.io/badge/dependencies-zero-brightgreen) ![AI-Assisted](https://img.shields.io/badge/AI_Assisted-yes-8A2BE2)

用**纯 Java（JDK 11+ / Swing）**实现的 Windows 本机开发环境可视化管理工具，零第三方依赖，`javac` 直接编译即可运行。

## ⚠️ AI 辅助开发声明 / AI-Assisted Development Notice

**本项目为 AI 辅助完成的软件项目。** 代码、文档与功能设计在 AI（大型语言模型）的辅助下开发，并经人工验证与实测调整。This project was developed with the assistance of AI (large language models). The code, documentation, and feature design were produced with AI assistance and verified through real-machine testing by a human developer.

- 项目中的代码主要由 AI 生成，人工参与需求制定、测试验证与缺陷修复确认；
- 项目已在 Windows 10 (19045) / JDK 21 环境下完成实际编译、运行与功能验证；
- 本项目按现状（AS-IS）提供，使用前请自行阅读源码与安全设计说明，重要操作（修改环境变量等）均已内置备份与回滚机制。

---

## 功能概览

| 功能 | 实现位置 | 说明 |
|---|---|---|
| 检测各语言解释器/编译器的版本 | 语言与解释器页 | 27 种语言/工具链，逐个跑版本命令并用正则提取 |
| 存储位置 | 语言与解释器页 | 可执行文件绝对路径 + 推断的安装根目录 + 发现方式 |
| 是否有环境配置 | 环境概览页 / 语言详情 | 校验 HOME 类变量是否设置、目录是否存在、是否在 PATH、是否与生效解释器一致 |
| 语言版本快速切换 | 版本切换页 | 发现 PATH 之外的已安装版本，一键切换并同步环境变量与 PATH |
| 依赖管理 | 依赖管理页 | 全局已安装包（pip/npm/gem/conda/cargo…）+ 项目依赖声明文件解析 |
| 环境配置信息随版本自动设置 | 版本切换页 | 按目标版本自动生成推荐的变量组合，只写差异项 |

---

## 一、运行

### 方式 1：双击启动（推荐）

双击 `启动.bat`。它会自动找 Java、必要时编译、然后启动图形界面。

> 注意：`启动.bat` 必须保持纯 ASCII 内容 + CRLF（Windows）换行。
> 如果用编辑器改过它并保存成 UTF-8 中文内容或 LF 换行，cmd 会把多字节字符
> 和行边界解析错乱，出现大量 `'XX' 不是内部或外部命令` 报错（正是本项目
> 早期遇到的问题）。误改后可用下面的方式 2 或方式 3 启动。

### 方式 2：命令行

```bat
:: 直接运行已打包好的 jar
java -Dfile.encoding=UTF-8 -jar langmgr.jar

:: 只读扫描，打印表格（适合脚本/CI，或界面出问题时排查）
java -Dfile.encoding=UTF-8 -jar langmgr.jar --cli

:: 查看帮助
java -jar langmgr.jar --help
```

### 方式 3：从源码重新编译

最简单的方式是双击 `build.bat`（纯 ASCII，自动找 JDK、编译并重建 `langmgr.jar`）：

```bat
build.bat
java -jar langmgr.jar
```

等价的手工命令（不需要 `sources.txt`，`-sourcepath` 会递归拉取依赖类）：

```bat
cd /d 项目目录
javac -encoding UTF-8 -d out -sourcepath src src\com\langmgr\App.java
jar --create --file langmgr.jar --main-class com.langmgr.App -C out .
java -jar langmgr.jar
```

> 需要 JDK 11 或更高版本（用到 `Process.descendants()`、`Files.list()` 等 API）。
> 本机为 JDK 21，已验证可编译运行。

### 方式 4：重新打包 exe（jpackage，免安装、自带运行时）

JDK 14+ 自带 `jpackage`，无需第三方工具。先按方式 3 编译出最新 `langmgr.jar`，然后：

```bat
cd /d 项目目录
mkdir exe-input 2>nul
copy /y langmgr.jar exe-input\
jpackage --type app-image --input exe-input --name "多语言环境管理器" ^
  --main-jar langmgr.jar --icon icons\app.ico --app-version 1.0 --vendor langmgr ^
  --add-modules java.base,java.desktop,java.prefs,java.logging,jdk.charsets,jdk.localedata ^
  --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=2" ^
  --dest dist
```

产物在 `dist\多语言环境管理器\`，约 57MB。注意：

- 模块裁剪参数删不得——`jdk.charsets` 负责 GBK 输出解码，`jdk.localedata` 负责中文区域格式；
- `dist` 目录若被正在运行的 exe 占用会打包失败，先退出程序；
- 若只想单个 .exe 文件或 msi 安装包，需额外工具（Launch4j / WiX）；
- `jpackage --input` 不会生成 `<exe目录>\icons\`，而 `AppIcon` 会优先读它。打完包再跑一次 `build.bat` 即可把图标同步进去（该步骤只增量覆盖 `dist\*\icons`，不会碰 jar 和 runtime）。

---

## 二、功能页说明

### 环境概览

四张统计卡片（可用语言数 / 多版本共存数 / 环境配置异常数 / PATH 条目数），
已安装语言速览，以及需要关注清单——会把 HOME 变量失效、装了但不在 PATH、
PATH 中的死条目、PATH 过长/重复等问题一次性列出来。

每类问题都尽量提供「一键处理」：HOME 变量失效/不一致 → 改写成当前生效版本的推导值；
装了但不在 PATH → 把安装目录追加到用户 PATH；失效 PATH 条目 → 备份后清理；
PATH 过长且有重复 → 一键去重。所有修改执行前自动备份到 backup 目录，
只写当前用户作用域（HKCU），可随时在「环境变量 → 备份与回滚」还原；
旁边保留「详情」按钮可跳到对应页面查看与手动处理。

软件**无法自动处理**的问题（如"PATH 转发器拦截"要改系统级 PATH），点「处理」会
直接跳到对应页面的操作面板（版本切换页会选中转发器条目，右侧变更计划预览区
给出"它是什么 / 为什么切换不生效 / 怎么解决"），而不是留一个切不动的按钮。

「转发器拦截」卡把两条路径并排摆出：**PATH 先命中**（转发器）与**实际在用**
（真实版本），一眼可判；指引对话框里给出可复制的 PowerShell 命令——按
**目录**匹配 PATH 条目（此前按 `…\javapath\java.exe` 过滤是空操作，因为 PATH
里登记的是目录 `…\javapath`），并兼容大小写、结尾反斜杠与 `%VAR%` 写法；
命令走注册表写回并沿用原值类型，不用 `[Environment]::SetEnvironmentVariable`
（.NET Framework 下它会把超过 2047 字符的系统 PATH 截断）。

**删完 PATH 条目 ≠ 目录被删除**：Oracle 的 `javapath` 目录（连同它指向的
`javapath_target_*`）是安装器创建的实体文件夹，从 PATH 移除只是让系统不再
经过它，文件仍留在 `Program Files\Common Files\Oracle\Java` 下。软件会把这种
"已不在 PATH、但磁盘还在"的状态识别为**转发器目录残留**（蓝色信息卡 +
版本列表里标"转发器残留 · 可删除"）。点「处理」时**由程序自动确认**是否还有
程序按绝对路径引用这些目录——扫描范围包括系统/用户 PATH、其它环境变量、
注册表（App Paths、启动项、卸载项、服务、文件关联）和开始菜单快捷方式：

- 未发现引用 → 直接提供「删除目录」；
- 发现引用 → 逐条列出引用者与处置办法（含复制处置命令），按钮变为「先处理引用」；
- 注册表扫描超时 → 明说没有扫全，再由用户决定是否继续。

删除本身仍带白名单保护：只允许删 `Common Files\Oracle\Java` 下名字以
javapath 开头且含 java.exe 的目录，任何其它路径都会被拒绝。
重装或修复 Oracle Java 时安装器可能重新创建。

### 语言与解释器

左侧可按名称/路径搜索、按"已安装/未安装/多版本"筛选；右侧详情回答四个问题：

- 当前生效版本与可执行文件绝对路径（双击可复制）。"当前生效"按**系统实际在用**的那个判定：
  PATH 命中的东西只是候选/配置，若命中的是转发器，真正跑起来的是它背后的目标，
  详情里会把两者都列出来（第二行标"PATH 命中 …（转发器，切换会被它拦截）"）
- 安装根目录与发现方式（PATH / 注册表 / py launcher / conda 环境 / 目录扫描…）
- 全部已发现的安装——包括装了但不在 PATH 里的，右键可查看原始版本输出、打开所在目录。
  系统 PATH 里的**转发器**（如 Oracle 的 `javapath\java.exe`）也单独列一条，状态标为
  「转发器·拦截」：它不是一个可选版本，而是"命中与使用不一致、导致切换失效"的原因，
  环境概览会把它当作待处理问题列出。
- 关联环境变量状态，异常时提供「设为当前版本」一键修正
- 包管理器可用性，以及探测细节（原始输出、探测命令、耗时）
- 官方下载网址：每种语言都登记了官方下载页与各历史版本页
  （如 Oracle JDK / Temurin / JDK Archive、nodejs.org releases、python.org 各版本安装包、
  winlibs.com 的 MinGW 构建、dotnet 下载页等）。单击链接直接在浏览器打开，也可复制。
  未安装时照着网址去装；已装多个版本时可从历史版本页补装其它版本

### 版本切换

三步式，因为改 PATH 出错会直接让终端瘫痪，所以刻意做成"先看再改"：

1. 选语言、选目标版本
2. 点「生成变更计划」——逐条列出将写哪个变量、PATH 怎么动，此时不碰任何配置
3. 点「确认并应用」——执行前自动备份，执行后给日志，可一键回滚

可勾选项：

- 同时调整 PATH（默认开）：把目标版本的 bin 提到 PATH 首位
- 移除其他版本的 PATH 条目（默认开）：避免新旧版本互相干扰

**执行后会复核，不会把"写变量成功"说成"切换成功"**：按新开终端真正命中的顺序
（注册表 PATH，系统级在前、用户级在后）重新解析一次，再比对目标可执行文件。
典型失败场景是 Oracle 的 `javapath` 转发器——它在系统级 PATH 里，用户级 PATH 永远排在
它之后，压不过它；这时会弹出「切换未生效」并说明实际命中的是哪个、需要管理员移除
哪条系统 PATH。

**删除版本**：选中版本后点「删除此版本…」，清理范围是用户环境里所有指向它的引用——
用户 PATH 中的目录（含重复条目）、以及指向它的 HOME 类变量，一次清干净不留死指针；
可选勾选"同时删除磁盘上的安装目录"（默认不勾，勾选后不可恢复）。删除同样走
"先备份、再执行、可回滚"，执行后复核该版本是否还占着"当前生效"。
系统级 PATH 只提示、不擅自修改；`C:\`、`C:\Windows`、`C:\Program Files`、用户主目录
这类危险目标会被直接拒绝。

还能「导出脚本」：变量变更生成 `reg add`（Windows，保留注册表类型）或 `export`（bash）片段，用于 CI 或另一台机器；PATH 变更仍建议用图形界面执行，避免静态脚本写坏长 PATH。

### 依赖管理

三个标签页：

- 全局已安装依赖：调包管理器的 list 命令（`pip list --format=json`、`npm list --json`、
  `conda list --json`、`gem list`、`cargo tree`…），支持筛选、多选复制安装命令，
  下方保留原始输出便于排查解析问题
- 项目依赖清单：选一个项目目录，解析 `requirements.txt` / `environment.yml` /
  `pyproject.toml` / `package.json` / `pom.xml` / `build.gradle` / `go.mod` / `Cargo.toml` /
  `composer.json` / `Gemfile` / `pubspec.yaml` / `.csproj` / `packages.config`。
  不要求对应工具已安装，也不联网
- 命令速查：各语言常用依赖命令，双击复制

### 环境变量

- 环境变量：增删改当前用户变量，同时显示注册表原始值（含 `%VAR%`）与展开后的实际值
- PATH 管理：上移/下移/置顶、添加/编辑/移除、去重、清理失效条目、按字母排序。
  所有操作先改内存列表，必须点「提交 PATH 变更」才写入，提交前给出完整 diff
- 备份与回滚：列出全部备份，可查看内容、回滚到任意一份、删除备份
- 系统级变量（只读）：展示 HKLM 变量，并标注哪些被用户级覆盖

### 诊断报告

分两个页签：表格视图把总览、包管理器、官方下载网址直接渲染成表格直读
（下载网址表双击可在浏览器打开）；文本视图是原始 Markdown / JSON，
可导出为报告文件，便于留档、对比两台机器的环境差异、或提 issue。

### 界面设置

右上角「界面设置」可调整三项外观，选择保存在用户偏好里，重启后仍然生效：

- 界面字体：列出系统已安装且支持中文的字体（微软雅黑 / 等线 / 黑体 / 宋体 / 楷体等），
  下拉列表里每项用其自身字体渲染预览
- 字体大小：较小 85% / 标准 100% / 较大 115% / 特大 130% 四档，
  按钮、徽章、表格行高等尺寸会随字号同步缩放
- 窗口标题图标：默认为自绘的品牌图标（蓝底白字"语"），可选择本地 PNG / JPG / GIF
  图片自定义标题栏与任务栏图标，可一键恢复默认

点「应用并重建界面」后三项设置立即生效，无需重启。

---

## 三、安全设计（重要）

改环境变量是有风险的操作，所以这个工具做了几层约束：

1. 扫描全程只读——检测阶段不写注册表、不改文件、不动 PATH
2. 只写当前用户（HKCU）——不碰系统级变量，因此不需要管理员权限，影响面也限于你自己
3. 不用 `setx`——`setx` 有两个会破坏环境的已知问题：把值截断到 1024 字符（长 PATH 会丢后半段），
   以及写入前展开 `%VAR%`（导致 `%JAVA_HOME%` 这类引用被永久固化成字面路径）。
   本工具改用 `reg add` 读写注册表，保留 `REG_EXPAND_SZ` 类型
4. 写入前强制备份——备份失败就中止，绝不带病写入。备份是纯 JSON，存在应用目录的 `backup/`
   （应用目录不可写时退回 `%LOCALAPPDATA%\langmgr\backup`），
   即使程序打不开也能手动照着还原；回滚前也会先备份当前状态，应用计划中途失败会自动回滚
5. 先看计划再执行——版本切换必须先预览变更计划，PATH 修改必须先点提交，都会显示完整 diff
6. 主动跳过有害变量——`PYTHONHOME` 会覆盖 venv/conda 的定位、`GOROOT` 硬设常导致版本错配，
   这两类默认不设并给出解释，而不是闷头写入
7. 广播变更通知——写完调用 `SendMessageTimeout(WM_SETTINGCHANGE)`，让新开的终端立即读到新值

> ⚠️ 已打开的终端和 IDE 不会自动刷新环境变量，这是 Windows 的固有行为，需要重启才能生效。

---

## 四、检测能力边界

### 支持的语言/工具链（27 种）

- JVM 系：Java、Kotlin、Scala
- 脚本语言：Python、Node.js、Deno、Bun、PHP、Ruby、Perl、Lua、R、Julia、PowerShell
- 编译型：GCC、Clang/LLVM、Go、Rust、.NET、TypeScript、Dart、Flutter
- 构建工具：CMake、Make、Ninja、Git、Docker

### 多版本发现渠道（不只是看 PATH）

- Java：常见 JDK 安装目录 + `~/.jdks` + `~/.sdkman` + 注册表 `JavaSoft` 键
- Python：`py -0p`（兼容新旧两种输出格式）+ `conda env list --json` + 常见安装目录 + WindowsApps（自动跳过 0 字节的别名 stub）
- Node.js：官方安装目录 + nvm + fnm + volta + scoop
- .NET：`dotnet --list-sdks` / `--list-runtimes`
- Rust：`~/.rustup/toolchains`
- Go：常见目录 + `~/sdk`（go 官方多版本目录）
- Ruby：rbenv；Flutter：fvm；其余：标准前缀兜底

### 已知局限

- 装在非标准路径、且既不在 PATH 也没有版本管理器登记的语言，可能扫不到——可在「环境变量」页手动指定
- Docker 检测的是 CLI 版本，实际可用性还取决于守护进程是否启动
- .NET 运行时由宿主按项目自动选择，不能通过环境变量切换，因此被标记为"宿主选择"并排除在切换列表外；SDK 版本由 `global.json` 决定，同样不通过环境变量切换
- 扫描需要启动几十个外部进程，本机实测约 8 秒（注册表读取做了短缓存后；具体取决于 Python/Anaconda 等解释器冷启动）

---

## 五、项目结构

```text
├── 启动.bat                    一键启动（自动编译 + 运行）
├── build.bat                   重编译打包 langmgr.jar，并把 icons 同步进 dist 下的 app-image
├── langmgr.jar                 已打包的可执行 jar
├── README.md                   本文档
├── .gitignore                  忽略 out/、backup/、reports/ 等生成物
├── src/com/langmgr/
│   ├── App.java                入口：GUI / --cli 两种模式
│   ├── core/
│   │   ├── Json.java           轻量 JSON 解析与序列化（支持 NDJSON 多值）
│   │   ├── Proc.java           命令执行（超时、进程树销毁、GBK/UTF-8 编码探测）
│   │   ├── Os.java             平台判断、%VAR% 展开、路径归属判断
│   │   ├── AppPaths.java       应用/备份/报告/图标目录解析（不依赖启动时工作目录）
│   │   ├── EnvStore.java       用户环境变量读写（注册表方式）、PATH 操作、变更广播、备份
│   │   ├── LangSpec.java       语言规格注册表：探测命令、版本正则、关联变量、包管理器
│   │   ├── Detected.java       扫描结果数据结构
│   │   ├── Scanner.java        扫描引擎：PATH 探测 + 多版本发现 + 环境校验（并发）
│   │   ├── EnvApplier.java     版本切换：计划生成、备份、执行、回滚
│   │   └── DepInspector.java   依赖：包管理器清单 + 项目声明文件解析
│   └── ui/
│       ├── Theme.java          配色、字体、自绘组件（扁平风格，不依赖第三方外观库）
│       ├── AppModel.java       共享状态与后台扫描调度（SwingWorker）
│       ├── MainFrame.java      主窗口：侧栏导航 + CardLayout 页面切换
│       ├── DashboardPanel.java 环境概览
│       ├── LanguagePanel.java  语言与解释器
│       ├── SwitchPanel.java    版本切换
│       ├── DependencyPanel.java依赖管理
│       ├── EnvPanel.java       环境变量与 PATH
│       └── ReportPanel.java    诊断报告
├── icons/
│   ├── head-pic.png            应用图标源图（窗口/任务栏/侧栏品牌区）
│   └── app.ico                 exe 图标（多尺寸）
├── dist/
│   └── 多语言环境管理器/       免安装 exe（自带精简 JRE，可脱离 Java 运行）
├── backup/                     环境备份（首次变更后自动创建）
└── out/                        编译产物（不存在属正常，重新编译时生成）
```

> 交付/分发时只需 `dist/多语言环境管理器` 整个文件夹；`src`、`icons` 供后续修改与重新构建。

### 加一种新语言

在 `LangSpec.java` 的静态块里 `register(...)` 一条即可，不用改任何 UI 代码：

```java
register(LangSpec.builder("zig", "Zig", "编译型")
        .probe("([\\d.]+)", "zig", "version")   // 命令 + 提取版本号的正则
        .exe("zig")                               // 用于 which 查找的可执行名
        .homeVar("ZIG_HOME")                      // 关联的环境变量
        .hint("说明文字，显示在详情页。")
        .build());
```

若还需要发现 PATH 之外的安装，在 `Scanner.discoverInstalls()` 里加一个 `case` 分支。

---

## 六、本机实测结果

在开发这台机器（Windows 10 19045 / JDK 21）上实际跑出来的结果：

| 语言 | 版本 | 位置 | 安装数 |
|---|---|---|---|
| Java | 21.0.2 | `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe` | 4（21.0.2 ×2、1.8.0_361 ×2） |
| Python | 3.9.13 | `D:\Anaconda3\python.exe` | 3（Anaconda 3.9.13、Programs\Python39 3.9.10、conda env 3.8.20） |
| Node.js | 22.14.0 | Qianwen 内置 bins 目录 | 1 |
| PowerShell | 5.1.19041.1 | `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` | 1 |
| .NET | 10.0.1 | `C:\Program Files\dotnet\dotnet.exe` | 8（1 SDK 宿主 + 7 运行时） |
| Git | 2.45.2.windows.1 | `C:\Program Files\Git\cmd\git.exe` | 1 |

开发过程中通过实测发现并修掉的真实缺陷：

1. `javapath` 是 Junction 转发目录 → 直接按路径反推会得到转发目录而非真实 JDK，
   导致 `JAVA_HOME` 被误报"不一致"。改为通过 `-XshowSettings:properties` 读 `java.home` 拿权威根目录，
   且仅在必要时查询并加缓存（否则每个 JDK 都启一次 JVM，扫描从 5s 涨到 35s）
2. `py -0p` 旧版格式解析失败 → 原正则只认新版 `-V:3.12`，本机是旧版 `-3.9-64  路径`，
   导致漏掉一整个 Python 3.9.10
3. `Programs\Python` 下的版本扫不到 → 解释器在子目录 `Python39\` 里，补了一层子目录遍历
4. `dotnet --version` 在未装 SDK 时报错退出 → 输出里的 `.NET` 被正则当成了版本号。
   加了退出码校验，并补 `--info` 兜底探测，版本号从 `.NET` 修正为 `10.0.1`
5. PowerShell 被误判未安装 + 版本时检得到时检不到 → 两层问题：
   一是它装在 `System32\WindowsPowerShell\v1.0\`，该子目录不在 PATH 中，`which` 找不到，
   改为按已知绝对路径探测；二是 `powershell.exe` 冷启动要加载 .NET 与内置模块，实测从 <1s 到近 9s 不等，
   用进程探测版本卡在超时临界点、结果随机。改为优先读注册表 `PowerShellEngine\PowerShellVersion`
   （毫秒级、不受冷启动影响），进程探测仅作兜底。修复后连续 3 次实测版本稳定一致
6. 探测失败被当成"未安装" → 原逻辑一旦版本没解析出来就丢弃整个条目，
   导致"文件明明在却报未安装"。改为只要可执行文件存在就报告为「已安装」，
   版本拿不到时降级显示"已安装（版本未获取）"并保留原因，不再给出错误结论
7. `broadcastChange()` 静默失效 → 调用裸命令名 `powershell` 会启动失败（该目录不在 PATH），
   改为定位绝对路径，优先 `pwsh`
8. .NET 运行时混入切换列表 → 运行时由宿主自动选择，不能靠改环境变量切换，
   新增 `switchable` 标记将其排除，避免给出无效甚至有害的选项

---

## 许可与声明

- 本项目为 **AI 辅助开发**项目（详见文首声明），代码按现状提供，不附带任何明示或默示的担保。
- 使用本项目修改环境变量前，请先阅读 [安全设计](#三安全设计重要) 一节。
