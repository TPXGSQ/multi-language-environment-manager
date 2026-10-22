# 更新日志

本文件记录项目的所有重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> **说明：本项目由 AI 辅助完成**，代码与文档均为 AI 生成 + 人工审阅的产物。

---

## [Unreleased]

### 文档

- README 重写为符合 GitHub 规范的结构：徽章、目录、提示块（`[!NOTE]` / `[!WARNING]` / `[!IMPORTANT]`）、
  分节表格、贡献指南入口
- 在 README 顶部与页脚明确标注**本项目由 AI 辅助完成**，并说明维护者职责边界
- 新增 `LICENSE`（MIT）、`CONTRIBUTING.md`、`CHANGELOG.md`、Issue 与 PR 模板

---

## [1.0.0] - 2026-10-22

首个公开版本。支持 32 种语言 / 工具链的检测、切换、依赖管理与诊断报告。

### 新增

- **32 种语言 / 工具链检测**，覆盖 JVM 系、脚本语言、C/C++ 五大编译器实现、其它编译型语言与构建工具
- **同语言多实现独立检测**：C/C++ 支持 GCC / Clang / MSVC / Intel oneAPI / TinyCC 五条；
  C# 支持 .NET Framework `csc.exe`、Roslyn、Mono `mcs` 三条
- **MSVC 离线发现**：通过 `vswhere.exe` 枚举全部 Visual Studio 实例（含 Build Tools 与预览版），
  遍历 `VC\Tools\MSVC\<版本>\bin\Host{x64,x86,arm64}\*`
- **C# 离线发现**：扫描 `%WINDIR%\Microsoft.NET\Framework64|Framework\v*` 下的编译器，
  以及 Roslyn 编译器；只收 v4 及以上
- **版本切换**三步式流程：选版本 → 生成变更计划 → 确认应用；执行后按新终端真实命中顺序复核
- **依赖管理**：全局包清单（pip / npm / conda / gem / cargo…）+ 12 种项目依赖声明文件解析
- **诊断报告**：表格视图与原始 Markdown / JSON 双视图，可导出留档
- **界面设置**：字体、字号档位、自定义窗口图标，即时生效并持久化

### 修复

- `javapath` Junction 导致 `JAVA_HOME` 误报不一致 → 改用 `-XshowSettings:properties` 读权威根目录并缓存
- `py -0p` 旧版输出格式解析失败 → 兼容 `-3.9-64  路径` 形式
- `Programs\Python` 下子目录版本扫不到 → 补一层子目录遍历
- `dotnet --version` 未装 SDK 时报错，`.NET` 被当成版本号 → 加退出码校验并补 `--info` 兜底
- PowerShell 误判未安装、版本时有时无 → 优先读注册表 `PowerShellEngine\PowerShellVersion`
- 探测失败被当成"未安装" → 改为只要可执行文件存在就报「已安装」，版本缺失时降级显示原因
- `broadcastChange()` 静默失效 → 裸命令名 `powershell` 不在 PATH，改为定位绝对路径
- .NET 运行时混入切换列表 → 新增 `switchable` 标记排除
- **`cl /?` 版本号读成 `2.0`** → 版本横幅打在 stderr，原逻辑只解析 stdout，改为合并两路输出
- **中文 VS 横幅正则不匹配** → "优化编译器 19.29.30153 版" 匹配不上 `Compiler Version`，改为容错正则
- **一键处理后其他版本从切换列表消失** → 版本列表改为无条件显示全部安装，不可切换的标徽标并说明
- **MSVC / C# 条目显示了 Java 转发器文案** → 拆分 `blocking` 与 `!switchable` 两条路由，各自解释
- **变更计划预览被通用文案覆盖** → 选中"安装器/宿主管理"条目时提前返回，不再覆盖专属说明
- **长文本截断后无悬停提示** → 统一用 `Theme.autoTooltip`，仅当真的显示不全时才提示
- **提示窗不换行、冲出屏幕** → HTML 提示限宽 + `\n` → `<br>`，并给不可断行的 `\ / - _` 插 `<wbr>`
- **短文字提示窗仍留大片空白** → 提示宽度上限改为"文字所在盒子的宽度"，放得下就按内容收缩
- **状态列悬停无提示** → 自定义 `setCellRenderer` 的列绕过了通用逻辑，新增 `Theme.cellTooltip(...)` 补上
- **JSON 深嵌套导致 `StackOverflowError`** → 递归下降加 64 层深度上限

### 安全

- 扫描全程只读，不写注册表、不改文件、不动 PATH
- 只写当前用户作用域（HKCU），不需要管理员权限
- 不用 `setx`（会截断长 PATH、会永久展开 `%VAR%`），改用 `reg add` 并保留 `REG_EXPAND_SZ`
- 写入前强制备份，备份失败即中止；回滚前再备份一次；中途失败自动回滚
- 删除目录有白名单：盘符根、系统容器目录、程序自身目录、用户主目录全部拒绝
- 外部命令全部走参数列表，不经 shell 拼接
- 默认不写 `PYTHONHOME` / `GOROOT` 等易破坏环境的变量

[Unreleased]: https://github.com/TPXGSQ/multi-language-environment-manager/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/TPXGSQ/multi-language-environment-manager/releases/tag/v1.0.0
