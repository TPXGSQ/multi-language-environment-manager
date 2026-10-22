# 贡献指南

感谢你愿意改进这个项目。本文说明参与方式与代码约定。

> [!IMPORTANT]
> 本项目由 **AI 辅助完成**。提交 PR 即表示你理解这一点，并同意你的贡献也以相同方式维护
> （AI 生成 + 人工审阅）。请勿提交你无法解释或无法维护的代码。

---

## 报告问题

提 Issue 前请先确认：

1. 用最新 `main` 分支复现过
2. 搜索过现有 Issue，避免重复

提 Issue 时请附上：

- Windows 版本（`winver`）
- `java -version` 输出（若用 exe 运行，注明）
- **诊断报告页导出的报告文件**（这一项能极大加快定位）
- 复现步骤：点了哪里、期望什么、实际什么

---

## 提交代码

```bash
# 1. Fork 后克隆
git clone https://github.com/<你的用户名>/multi-language-environment-manager.git
cd multi-language-environment-manager

# 2. 新建分支
git checkout -b fix/python-version-regex

# 3. 改代码，然后编译验证
build.bat

# 4. 只读扫描跑一遍，确认没把检测弄坏
java -Djava.awt.headless=true -jar langmgr.jar --cli

# 5. 提交并推送
git commit -m "fix: 修正 Python 3.13 版本号正则"
git push origin fix/python-version-regex
```

然后到 GitHub 发起 Pull Request。

---

## 代码约定

| 约定 | 说明 |
|---|---|
| **零第三方依赖** | 只用 JDK 标准库。`pom.xml` / `build.gradle` 一律不要 |
| **不用构建工具** | 必须保持 `javac -encoding UTF-8 -d out -sourcepath src src/com/langmgr/App.java` 可直接编译 |
| **JDK 11+ 兼容** | 不使用 12+ 的语法（如 `switch` 表达式、`record`、文本块） |
| **注释用中文** | 说明白"为什么这么写"，而不是复述代码在做什么 |
| **UI 走 Theme** | 颜色、字号、间距、按钮一律用 `Theme` 里的常量与工厂方法，不要硬编码 |
| **表格加只读** | 纯展示的 `JTable` 调 `Theme.readOnly(t)`；自定义渲染器末尾调 `Theme.cellTooltip(...)` |
| **写环境变量必须备份** | 任何写操作前先备份，失败即中止 |
| **批处理文件纯 ASCII** | `.bat` 不能含中文，且必须是 CRLF 换行 |

### 新增一种语言需要改的地方

1. `LangSpec.java` → 静态块里 `register(...)` 一条规格
2. `Scanner.java` → 若需发现 PATH 之外的安装，在 `discoverInstalls()` 加 `case`
3. `DepInspector.java` → 若该语言有包管理器，补 `cheatsheet` 与 list 命令
4. `EnvApplier.java` → 若需识别其 PATH 条目类型，补 `describePathEntry`

---

## Commit 信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```
<type>: <简短描述>

<可选正文：为什么要改>
```

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 修缺陷 |
| `docs` | 只改文档 |
| `refactor` | 重构（不改行为） |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `chore` | 构建、依赖、杂项 |

示例：

```
fix: MSVC 版本号读成 2.0

cl /? 的版本横幅打在 stderr、帮助打在 stdout，原来只解析 stdout 所以读错。
改为合并两路输出后再匹配。
```

---

## 审查标准

PR 合并前会检查：

- [ ] 能编译，`--cli` 扫描结果无回归
- [ ] 没有新增第三方依赖
- [ ] 写操作有备份与回滚路径
- [ ] 危险操作（删除目录、改 PATH）有防护与二次确认
- [ ] 文档同步更新（README / CHANGELOG）

---

## 行为准则

- 对事不对人，技术分歧用数据和复现步骤说话
- 不接受任何形式的骚扰、歧视与人身攻击
- 维护者有权关闭不符合本指南的 PR / Issue，并说明理由
