package com.langmgr.core;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 依赖管理：读取已安装包清单，以及解析项目内的依赖声明文件。
 *
 * <p>两条路径互补：
 * <ul>
 *   <li><b>全局依赖</b>——调各语言包管理器的 list 命令（pip/npm/gem/conda…），拿到当前解释器下装了什么；</li>
 *   <li><b>项目依赖</b>——直接解析 requirements.txt / package.json / pom.xml / go.mod / Cargo.toml 等声明文件，
 *       不要求对应工具已安装，也能看清项目需要什么。</li>
 * </ul>
 */
public final class DepInspector {

    /** 一个依赖条目。 */
    public static final class Dep {
        public String name;
        public String version;
        /** 声明的版本约束（如 ^1.2.0、>=3.8）。 */
        public String constraint;
        /** 来源：pip list / package.json / requirements.txt ... */
        public String source;
        /** 是否为开发依赖。 */
        public boolean dev;

        public Dep(String name, String version, String constraint, String source, boolean dev) {
            this.name = name;
            this.version = version;
            this.constraint = constraint;
            this.source = source;
            this.dev = dev;
        }

        public String displayVersion() {
            if (version != null && !version.isEmpty()) {
                return version;
            }
            return constraint == null ? "—" : constraint;
        }
    }

    /** 依赖查询结果。 */
    public static final class DepResult {
        public String managerName;
        public boolean ok;
        public String error;
        public String rawOutput;
        public List<Dep> deps = new ArrayList<>();
        public long millis;

        public String summary() {
            if (!ok) {
                return "不可用：" + (error == null ? "未知错误" : error);
            }
            return deps.size() + " 个依赖";
        }
    }

    private static final long TIMEOUT = 45_000;

    private DepInspector() {
    }

    /* -------------------------------------------------- 全局依赖（包管理器） */

    /** 查询某个语言下所有可用包管理器的已安装依赖。 */
    public static List<DepResult> queryGlobal(Detected.LangResult lang) {
        List<DepResult> out = new ArrayList<>();
        if (lang == null) {
            return out;
        }
        for (LangSpec.PkgManager pm : lang.spec.pkgManagers) {
            Detected.PkgState st = findPkgState(lang, pm.id);
            if (st == null || !st.installed) {
                DepResult r = new DepResult();
                r.managerName = pm.displayName;
                r.ok = false;
                r.error = st == null ? "未检测到" : (st.error != null ? st.error : "未安装");
                out.add(r);
                continue;
            }
            out.add(listPackages(pm, lang));
        }
        return out;
    }

    private static Detected.PkgState findPkgState(Detected.LangResult lang, String pmId) {
        for (Detected.PkgState s : lang.pkgManagers) {
            if (s.id.equals(pmId)) {
                return s;
            }
        }
        return null;
    }

    /** 执行 list 命令并解析。 */
    public static DepResult listPackages(LangSpec.PkgManager pm, Detected.LangResult lang) {
        DepResult result = new DepResult();
        result.managerName = pm.displayName;
        long t0 = System.currentTimeMillis();

        if (pm.listCmd == null || pm.listCmd.isEmpty()) {
            result.ok = false;
            result.error = "该包管理器未定义清单命令";
            return result;
        }

        List<String> cmd = new ArrayList<>(pm.listCmd);
        String head = cmd.get(0);
        if (lang.spec.exeNames.stream().anyMatch(n -> n.equalsIgnoreCase(head))) {
            if (lang.active == null || lang.active.exePath == null) {
                result.ok = false;
                result.error = "语言解释器不可用";
                return result;
            }
            cmd.set(0, lang.active.exePath);
        } else {
            String resolved = Proc.which(head);
            if (resolved == null) {
                result.ok = false;
                result.error = "找不到命令：" + head;
                return result;
            }
            cmd.set(0, resolved);
        }

        Proc.Result pr = Proc.run(cmd, null, TIMEOUT);
        result.millis = System.currentTimeMillis() - t0;
        result.rawOutput = pr.effective();

        if (pr.timedOut) {
            result.ok = false;
            result.error = "命令超时（" + TIMEOUT / 1000 + "s），依赖可能过多或网络阻塞";
            return result;
        }
        if (pr.error != null) {
            result.ok = false;
            result.error = pr.error;
            return result;
        }

        try {
            result.deps = pm.listJson
                    ? parseJsonDeps(result.rawOutput, pm.id)
                    : parseTextDeps(result.rawOutput, pm.id);
            result.ok = true;
        } catch (RuntimeException ex) {
            result.ok = true; // 命令本身成功，只是解析受限
            result.error = "输出已获取，但结构化解析失败：" + ex.getMessage();
        }
        return result;
    }

    /* ------------------------------------------------------ JSON 输出解析 */

    private static List<Dep> parseJsonDeps(String output, String pmId) {
        List<Dep> out = new ArrayList<>();
        Object root = Json.parse(output);
        if (root == null) {
            return out;
        }
        switch (pmId) {
            case "pip":
                collectPip(Json.asList(root), out);
                break;
            case "conda":
                collectConda(root, out);
                break;
            case "npm":
                collectNpm(root, out, "");
                break;
            case "yarn": {
                // yarn list --json 可能逐行输出多个 JSON 对象
                List<Object> roots = Json.parseAll(output);
                if (roots.isEmpty()) {
                    roots.add(root);
                }
                for (Object r : roots) {
                    collectYarn(r, out);
                }
                break;
            }
            case "pnpm":
                collectPnpm(root, out, "");
                break;
            case "gomod": {
                // go list -m -json all 是多个连续 JSON 对象，只解析第一个会丢掉全部依赖
                List<Object> roots = Json.parseAll(output);
                if (roots.isEmpty()) {
                    roots.add(root);
                }
                for (Object r : roots) {
                    collectGoModule(r, out);
                }
                break;
            }
            case "composer":
                collectComposer(root, out);
                break;
            default:
                genericJsonWalk(root, out);
        }
        out.sort(Comparator.comparing(d -> d.name == null ? "" : d.name.toLowerCase()));
        return out;
    }

    private static void collectPip(List<Object> arr, List<Dep> out) {
        if (arr == null) {
            return;
        }
        for (Object o : arr) {
            Map<String, Object> m = Json.asMap(o);
            if (m == null) {
                continue;
            }
            String name = Json.asString(m.get("name"));
            if (name == null) {
                continue;
            }
            out.add(new Dep(name, Json.asString(m.get("version")), null, "pip", false));
        }
    }

    private static void collectConda(Object root, List<Dep> out) {
        // conda list --json 直接是数组
        List<Object> arr = Json.asList(root);
        if (arr == null) {
            Map<String, Object> m = Json.asMap(root);
            arr = m == null ? null : Json.asList(m.get("packages"));
        }
        if (arr == null) {
            return;
        }
        for (Object o : arr) {
            Map<String, Object> m = Json.asMap(o);
            if (m == null) {
                continue;
            }
            String name = Json.asString(m.get("name"));
            if (name == null) {
                continue;
            }
            String ver = Json.asString(m.get("version"));
            String build = Json.asString(m.get("build_string"));
            if (build == null) {
                build = Json.asString(m.get("build"));
            }
            String channel = Json.asString(m.get("channel"));
            String full = ver == null ? null : (build == null ? ver : ver + "+" + build);
            Dep d = new Dep(name, full, null, "conda" + (channel == null ? "" : " (" + channel + ")"), false);
            out.add(d);
        }
    }

    private static void collectNpm(Object root, List<Dep> out, String prefix) {
        Map<String, Object> m = Json.asMap(root);
        if (m == null) {
            return;
        }
        Object depsObj = m.get("dependencies");
        Map<String, Object> deps = Json.asMap(depsObj);
        if (deps != null) {
            for (Map.Entry<String, Object> e : deps.entrySet()) {
                Map<String, Object> info = Json.asMap(e.getValue());
                String ver = info == null ? null : Json.asString(info.get("version"));
                boolean dev = info != null && Boolean.TRUE.equals(info.get("dev"));
                out.add(new Dep(prefix + e.getKey(), ver, null, dev ? "npm (dev)" : "npm", dev));
                // 只展开一层，避免全树输出爆炸
            }
        }
        // npm ls --json 顶层是 {"name":..,"version":..}
        if (deps == null && m.get("name") != null && m.get("version") != null) {
            out.add(new Dep(Json.asString(m.get("name")), Json.asString(m.get("version")), null, "npm", false));
        }
    }

    private static void collectYarn(Object root, List<Dep> out) {
        Map<String, Object> m = Json.asMap(root);
        if (m == null) {
            return;
        }
        List<Object> trees = Json.asList(m.get("trees"));
        if (trees != null) {
            for (Object o : trees) {
                Map<String, Object> t = Json.asMap(o);
                if (t == null) {
                    continue;
                }
                String name = Json.asString(t.get("name"));
                if (name == null) {
                    continue;
                }
                // yarn 形如 "lodash@4.17.21"
                int at = name.lastIndexOf('@');
                String ver = null;
                if (at > 0) {
                    ver = name.substring(at + 1);
                    name = name.substring(0, at);
                }
                out.add(new Dep(name, ver, null, "yarn", false));
            }
            return;
        }
        // yarn list --json 也可能是 {type:"tree",data:{type:"list",items:[{value:"a@1.0"}]}}
        Object data = m.get("data");
        Map<String, Object> dm = Json.asMap(data);
        List<Object> items = dm == null ? null : Json.asList(dm.get("items"));
        if (items != null) {
            for (Object o : items) {
                Map<String, Object> it = Json.asMap(o);
                if (it == null) {
                    continue;
                }
                String value = Json.asString(it.get("value"));
                if (value == null) {
                    continue;
                }
                int at = value.lastIndexOf('@');
                String ver = at > 0 ? value.substring(at + 1) : null;
                String nm = at > 0 ? value.substring(0, at) : value;
                out.add(new Dep(nm, ver, null, "yarn", false));
            }
        }
    }

    private static void collectPnpm(Object root, List<Dep> out, String prefix) {
        Map<String, Object> m = Json.asMap(root);
        if (m == null) {
            return;
        }
        Map<String, Object> deps = Json.asMap(m.get("dependencies"));
        if (deps != null) {
            for (Map.Entry<String, Object> e : deps.entrySet()) {
                Map<String, Object> info = Json.asMap(e.getValue());
                String ver = info == null ? null : Json.asString(info.get("version"));
                out.add(new Dep(prefix + e.getKey(), ver, null, "pnpm", false));
            }
        }
        Map<String, Object> devDeps = Json.asMap(m.get("devDependencies"));
        if (devDeps != null) {
            for (Map.Entry<String, Object> e : devDeps.entrySet()) {
                Map<String, Object> info = Json.asMap(e.getValue());
                String ver = info == null ? null : Json.asString(info.get("version"));
                out.add(new Dep(prefix + e.getKey(), ver, null, "pnpm (dev)", true));
            }
        }
    }

    private static void collectGoModule(Object root, List<Dep> out) {
        Map<String, Object> m = Json.asMap(root);
        if (m == null) {
            return;
        }
        String path = Json.asString(m.get("Path"));
        if (path == null || path.isEmpty()) {
            return;
        }
        String version = Json.asString(m.get("Version"));
        out.add(new Dep(path, version, null, "go mod", false));
    }

    private static void collectComposer(Object root, List<Dep> out) {
        Map<String, Object> m = Json.asMap(root);
        if (m == null) {
            return;
        }
        for (String key : new String[]{"installed", "packages"}) {
            List<Object> arr = Json.asList(m.get(key));
            if (arr == null) {
                continue;
            }
            for (Object o : arr) {
                Map<String, Object> p = Json.asMap(o);
                if (p == null) {
                    continue;
                }
                out.add(new Dep(Json.asString(p.get("name")), Json.asString(p.get("version")),
                        null, "composer", false));
            }
        }
    }

    private static void genericJsonWalk(Object root, List<Dep> out) {
        if (out.size() > 500) {
            return;
        }
        Map<String, Object> m = Json.asMap(root);
        if (m != null) {
            Object name = m.get("name");
            Object ver = m.get("version");
            if (name != null && ver != null) {
                out.add(new Dep(Json.asString(name), Json.asString(ver), null, "包管理器", false));
            }
            for (Object v : m.values()) {
                genericJsonWalk(v, out);
            }
            return;
        }
        List<Object> l = Json.asList(root);
        if (l != null) {
            for (Object v : l) {
                genericJsonWalk(v, out);
            }
        }
    }

    /* ------------------------------------------------------ 文本输出解析 */

    private static List<Dep> parseTextDeps(String output, String pmId) {
        List<Dep> out = new ArrayList<>();
        if (output == null || output.trim().isEmpty()) {
            return out;
        }
        for (String line : output.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            switch (pmId) {
                case "gem": {
                    // "rake (13.0.6, 13.0.1)" 或 "rails (7.0.0)"
                    Matcher m = Pattern.compile("^([\\w.\\-]+)\\s+\\(([^)]+)\\)").matcher(t);
                    if (m.find()) {
                        out.add(new Dep(m.group(1), m.group(2), null, "gem", false));
                    }
                    break;
                }
                case "bundler": {
                    Matcher m = Pattern.compile("^\\s*\\*\\s+([\\w.\\-]+)\\s+\\(([^)]+)\\)").matcher(line);
                    if (m.find()) {
                        out.add(new Dep(m.group(1), m.group(2), null, "bundle", false));
                    }
                    break;
                }
                case "sbt": {
                    Matcher m = Pattern.compile("([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)").matcher(t);
                    if (m.find()) {
                        out.add(new Dep(m.group(1) + ":" + m.group(2), m.group(3), null, "sbt", false));
                    }
                    break;
                }
                case "cargo": {
                    Matcher m = Pattern.compile("([\\w.\\-]+)\\s+v?([\\d.]+[\\w.\\-+]*)").matcher(t);
                    if (m.find()) {
                        out.add(new Dep(m.group(1), m.group(2), null, "cargo", false));
                    }
                    break;
                }
                case "luarocks": {
                    String[] parts = t.split("\\s+");
                    if (parts.length >= 2) {
                        out.add(new Dep(parts[0], parts[1], null, "luarocks", false));
                    }
                    break;
                }
                default:
                    break;
            }
        }
        out.sort(Comparator.comparing(d -> d.name == null ? "" : d.name.toLowerCase()));
        return out;
    }

    /* -------------------------------------------------- 项目依赖文件解析 */

    /** 在给定目录下寻找依赖声明文件。 */
    public static List<Path> findManifests(File dir, int maxDepth) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        scanDir(dir.toPath(), 0, maxDepth, out);
        return out;
    }

    private static void scanDir(Path dir, int depth, int maxDepth, List<Path> out) {
        if (depth > maxDepth || out.size() > 200) {
            return;
        }
        try {
            List<Path> kids;
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                kids = new ArrayList<>(s.collect(java.util.stream.Collectors.toList()));
            }
            for (Path p : kids) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    // 跳过依赖与版本目录，避免扫描量爆炸
                    if (n.equals("node_modules") || n.equals(".git") || n.equals("target")
                            || n.equals("build") || n.equals("dist") || n.equals("__pycache__")
                            || n.equals(".venv") || n.equals("venv") || n.equals(".gradle")
                            || n.equals("vendor") || n.equals(".idea") || n.equals(".vscode")) {
                        continue;
                    }
                    scanDir(p, depth + 1, maxDepth, out);
                } else if (isManifest(n)) {
                    out.add(p);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isManifest(String name) {
        String n = name.toLowerCase();
        return n.equals("requirements.txt") || n.equals("requirements-dev.txt")
                || n.equals("environment.yml") || n.equals("environment.yaml")
                || n.equals("pipfile") || n.equals("pyproject.toml")
                || n.equals("package.json")
                || n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts")
                || n.equals("go.mod") || n.equals("cargo.toml")
                || n.equals("composer.json") || n.equals("gemfile")
                || n.equals("pubspec.yaml") || n.equals("project.clj")
                || n.equals("packages.config") || n.endsWith(".csproj")
                || n.equals("stack.yaml");
    }

    /** 解析单个清单文件。 */
    public static DepResult parseManifest(Path file) {
        DepResult r = new DepResult();
        r.managerName = file.getFileName().toString();
        long t0 = System.currentTimeMillis();
        try {
            long size = Files.size(file);
            if (size > 8L * 1024 * 1024) {
                r.ok = false;
                r.error = "文件过大（" + (size / 1024 / 1024) + " MB），已跳过解析";
                r.millis = System.currentTimeMillis() - t0;
                return r;
            }
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            // Windows 上不少 pom.xml / yml / gradle 文件仍是 GBK；
            // 严格按 UTF-8 解出替换字符时退回 GBK，避免依赖名乱码。
            if (text.indexOf('\uFFFD') >= 0) {
                text = new String(bytes, Charset.forName("GBK"));
            }
            String n = r.managerName.toLowerCase();
            if (n.equals("package.json")) {
                parsePackageJson(text, r.deps);
            } else if (n.startsWith("requirements")) {
                parseRequirements(text, r.deps);
            } else if (n.equals("environment.yml") || n.equals("environment.yaml")) {
                parseCondaYaml(text, r.deps);
            } else if (n.equals("pyproject.toml") || n.equals("pipfile")) {
                parsePyproject(text, r.deps);
            } else if (n.equals("pom.xml")) {
                parsePom(text, r.deps);
            } else if (n.startsWith("build.gradle")) {
                parseGradle(text, r.deps);
            } else if (n.equals("go.mod")) {
                parseGoMod(text, r.deps);
            } else if (n.equals("cargo.toml")) {
                parseCargoToml(text, r.deps);
            } else if (n.equals("composer.json")) {
                parseComposerJson(text, r.deps);
            } else if (n.equals("gemfile")) {
                parseGemfile(text, r.deps);
            } else if (n.equals("pubspec.yaml")) {
                parsePubspec(text, r.deps);
            } else if (n.endsWith(".csproj") || n.equals("packages.config")) {
                parseCsproj(text, r.deps);
            } else {
                r.ok = false;
                r.error = "暂不支持解析该格式，可查看原文";
                r.rawOutput = text;
                return r;
            }
            r.ok = true;
            r.rawOutput = text.length() > 20000 ? text.substring(0, 20000) + "\n…（已截断）" : text;
        } catch (Exception ex) {
            r.ok = false;
            r.error = ex.getMessage();
        }
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    private static void parsePackageJson(String text, List<Dep> out) {
        Map<String, Object> root = Json.asMap(Json.parse(text));
        if (root == null) {
            return;
        }
        collectSection(root, "dependencies", out, "package.json", false);
        collectSection(root, "devDependencies", out, "package.json", true);
        collectSection(root, "peerDependencies", out, "package.json", false);
        collectSection(root, "optionalDependencies", out, "package.json", false);
    }

    private static void collectSection(Map<String, Object> root, String key, List<Dep> out,
                                       String source, boolean dev) {
        Map<String, Object> m = Json.asMap(root.get(key));
        if (m == null) {
            return;
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.add(new Dep(e.getKey(), null, Json.asString(e.getValue()), source + (dev ? " (dev)" : ""), dev));
        }
    }

    private static void parseRequirements(String text, List<Dep> out) {
        for (String raw : text.split("\\r?\\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty() || line.startsWith("-") || line.startsWith("#")) {
                continue;
            }
            Matcher m = Pattern.compile("^([A-Za-z0-9_.\\-\\[\\]]+)\\s*(==|>=|<=|~=|!=|>|<)?\\s*([\\w.*,\\s]*)")
                    .matcher(line);
            if (m.find()) {
                String op = m.group(2) == null ? "" : m.group(2);
                String ver = m.group(3) == null ? "" : m.group(3).trim();
                out.add(new Dep(m.group(1), null, (op + ver).trim(), "requirements.txt", false));
            }
        }
    }

    private static String stripComment(String line) {
        int idx = line.indexOf('#');
        if (idx < 0) {
            return line;
        }
        // 避免误伤 URL 中的 #（requirements 里少见）
        return line.substring(0, idx);
    }

    private static void parseCondaYaml(String text, List<Dep> out) {
        boolean inDeps = false;
        boolean inPip = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = line.length() - line.replaceAll("^\\s+", "").length();
            String t = line.trim();
            if (indent == 0) {
                inDeps = t.startsWith("dependencies:");
                inPip = false;
                continue;
            }
            if (!inDeps) {
                continue;
            }
            if (t.equals("- pip:")) {
                inPip = true;
                continue;
            }
            if (t.startsWith("- ")) {
                String item = t.substring(2).trim();
                if (inPip) {
                    addPipRequirement(item, out);
                    continue;
                }
                int eq = item.indexOf('=');
                if (eq > 0) {
                    out.add(new Dep(item.substring(0, eq), item.substring(eq + 1).replace('=', ' ').trim(),
                            null, "environment.yml", false));
                } else {
                    out.add(new Dep(item, null, null, "environment.yml", false));
                }
            } else if (inPip && indent >= 4) {
                // 少数 environment.yml 会把 pip 依赖写成不带 "- " 的缩进列表
                addPipRequirement(t, out);
            }
        }
    }

    private static void addPipRequirement(String item, List<Dep> out) {
        String s = item == null ? "" : item.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return;
        }
        Matcher m = Pattern.compile(
                "^([A-Za-z0-9_.\\-\\[\\]]+)\\s*(==|>=|<=|~=|!=|>|<)?\\s*([\\w.*,\\s]*)").matcher(s);
        if (m.find()) {
            String op = m.group(2) == null ? "" : m.group(2);
            String ver = m.group(3) == null ? "" : m.group(3).trim();
            out.add(new Dep(m.group(1), null, (op + ver).trim(), "environment.yml (pip)", false));
        }
    }

    private static void parsePyproject(String text, List<Dep> out) {
        // 极简 TOML 行解析，覆盖 dependencies = [...] 与 [tool.poetry.dependencies]
        boolean inArray = false;
        boolean inDependencyTable = false;
        String arrayKey = null;
        StringBuilder buf = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("[")) {
                inDependencyTable = t.toLowerCase().contains("dependencies");
                inArray = false;
                continue;
            }
            if (inArray) {
                buf.append(' ').append(t);
                if (t.contains("]")) {
                    inArray = false;
                    emitPyprojectArray(arrayKey, buf.toString(), out);
                }
                continue;
            }
            if (t.startsWith("dependencies") && t.contains("=")) {
                String rest = t.substring(t.indexOf('=') + 1);
                if (rest.contains("]")) {
                    emitPyprojectArray("dependencies", rest, out);
                } else {
                    inArray = true;
                    arrayKey = "dependencies";
                    buf.setLength(0);
                    buf.append(rest);
                }
                continue;
            }
            if (!inDependencyTable) {
                continue;
            }
            Matcher m = Pattern.compile("^([A-Za-z0-9_.\\-]+)\\s*=\\s*(.*)$").matcher(t);
            if (m.find()) {
                String key = m.group(1);
                if (key.equalsIgnoreCase("python") || key.equalsIgnoreCase("version")
                        || key.equalsIgnoreCase("name")) {
                    continue;
                }
                out.add(new Dep(key, null, tomlConstraint(m.group(2)), "pyproject.toml", false));
            }
        }
    }

    /**
     * 取 TOML 值的版本约束：
     * {@code "^1.2"}、{@code { version = ">=1.2", extras = [...] }} 都能识别；
     * path/git 这类没有 version 的内联表返回 null，而不是把整段表当成约束。
     */
    private static String tomlConstraint(String rhs) {
        if (rhs == null || rhs.trim().isEmpty()) {
            return null;
        }
        String s = rhs.trim();
        Matcher vm = Pattern.compile("version\\s*=\\s*[\"']([^\"']+)[\"']").matcher(s);
        if (vm.find()) {
            return vm.group(1).trim();
        }
        if (s.startsWith("\"") || s.startsWith("'")) {
            char quote = s.charAt(0);
            int end = s.indexOf(quote, 1);
            return end > 0 ? s.substring(1, end).trim() : null;
        }
        return null;
    }

    private static void emitPyprojectArray(String key, String content, List<Dep> out) {
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(content);
        while (m.find()) {
            String item = m.group(1).trim();
            Matcher mm = Pattern.compile("^([A-Za-z0-9_.\\-\\[\\]]+)\\s*(>=|==|<=|~=|>|<)?\\s*([\\d.*\\w,\\s]*)")
                    .matcher(item);
            if (mm.find()) {
                out.add(new Dep(mm.group(1), null,
                        ((mm.group(2) == null ? "" : mm.group(2)) + (mm.group(3) == null ? "" : mm.group(3))).trim(),
                        "pyproject.toml", false));
            } else {
                out.add(new Dep(item, null, null, "pyproject.toml", false));
            }
        }
    }

    private static void parsePom(String text, List<Dep> out) {
        Matcher m = Pattern.compile(
                "<dependency>\\s*(?:<optional>[^<]*</optional>\\s*)?"
                        + "(?:<groupId>([^<]*)</groupId>)?\\s*"
                        + "(?:<artifactId>([^<]*)</artifactId>)?\\s*"
                        + "(?:<version>([^<]*)</version>)?[\\s\\S]*?</dependency>",
                Pattern.MULTILINE).matcher(text);
        while (m.find()) {
            String g = m.group(1) == null ? "" : m.group(1).trim();
            String a = m.group(2) == null ? "" : m.group(2).trim();
            String v = m.group(3) == null ? "" : m.group(3).trim();
            if (a.isEmpty()) {
                continue;
            }
            out.add(new Dep(g.isEmpty() ? a : (g + ":" + a), v.isEmpty() ? null : v, null, "pom.xml", false));
        }
    }

    private static void parseGradle(String text, List<Dep> out) {
        // implementation 'group:artifact:version'  /  implementation("g:a:v")
        Matcher m = Pattern.compile(
                "(implementation|api|compileOnly|runtimeOnly|testImplementation|classpath)"
                        + "\\s*\\(?\\s*['\"]([^:'\"]+):([^:'\"]+):([^'\"]+)['\"]")
                .matcher(text);
        while (m.find()) {
            boolean dev = m.group(1).contains("test") || m.group(1).equals("classpath");
            out.add(new Dep(m.group(2) + ":" + m.group(3), m.group(4), null,
                    "build.gradle (" + m.group(1) + ")", dev));
        }
    }

    private static void parseGoMod(String text, List<Dep> out) {
        Matcher m = Pattern.compile("^\\s*([\\w./\\-]+)\\s+(v[\\w.\\-+]+)", Pattern.MULTILINE).matcher(text);
        while (m.find()) {
            out.add(new Dep(m.group(1), m.group(2), null, "go.mod", false));
        }
    }

    private static void parseCargoToml(String text, List<Dep> out) {
        boolean inDeps = false;
        boolean inDev = false;
        for (String raw : text.split("\\r?\\n")) {
            String t = raw.trim();
            if (t.startsWith("[")) {
                inDeps = t.equalsIgnoreCase("[dependencies]");
                inDev = t.equalsIgnoreCase("[dev-dependencies]") || t.equalsIgnoreCase("[build-dependencies]");
                continue;
            }
            if (!(inDeps || inDev) || t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            Matcher m = Pattern.compile("^([\\w\\-]+)\\s*=\\s*(?:\"([^\"]*)\"|\\{[^}]*version\\s*=\\s*\"([^\"]*)\")")
                    .matcher(t);
            if (m.find()) {
                String ver = m.group(2) != null ? m.group(2) : m.group(3);
                out.add(new Dep(m.group(1), null, ver, "Cargo.toml" + (inDev ? " (dev)" : ""), inDev));
            }
        }
    }

    private static void parseComposerJson(String text, List<Dep> out) {
        Map<String, Object> root = Json.asMap(Json.parse(text));
        if (root == null) {
            return;
        }
        collectSection(root, "require", out, "composer.json", false);
        collectSection(root, "require-dev", out, "composer.json", true);
    }

    private static void parseGemfile(String text, List<Dep> out) {
        Matcher m = Pattern.compile("^\\s*gem\\s+['\"]([^'\"]+)['\"](?:\\s*,\\s*['\"]([^'\"]+)['\"])?",
                Pattern.MULTILINE).matcher(text);
        while (m.find()) {
            out.add(new Dep(m.group(1), null, m.group(2), "Gemfile", false));
        }
    }

    private static void parsePubspec(String text, List<Dep> out) {
        boolean inDeps = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("\\s+$", "");
            int indent = line.length() - line.replaceAll("^\\s+", "").length();
            String t = line.trim();
            if (indent == 0) {
                inDeps = t.equals("dependencies:") || t.equals("dev_dependencies:");
                continue;
            }
            if (!inDeps || t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            Matcher m = Pattern.compile("^([\\w_]+):\\s*(.*)$").matcher(t);
            if (m.find()) {
                String v = m.group(2).trim();
                out.add(new Dep(m.group(1), null, v.isEmpty() ? "any" : v, "pubspec.yaml", false));
            }
        }
    }

    private static void parseCsproj(String text, List<Dep> out) {
        Matcher m = Pattern.compile(
                "<PackageReference\\s+Include=\"([^\"]+)\"(?:\\s+Version=\"([^\"]+)\")?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            out.add(new Dep(m.group(1), m.group(2), null, "csproj", false));
        }
        Matcher m2 = Pattern.compile(
                "<package\\s+id=\"([^\"]+)\"\\s+version=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (m2.find()) {
            out.add(new Dep(m2.group(1), m2.group(2), null, "packages.config", false));
        }
    }

    /* ------------------------------------------------------- 便捷方法 */

    /** 生成 pip/npm 风格的安装命令，便于用户复制。 */
    public static String installCommand(String langId, String pmId, List<Dep> deps) {
        if (deps == null || deps.isEmpty()) {
            return "";
        }
        List<String> args = new ArrayList<>();
        for (Dep d : deps) {
            String spec = d.name;
            if (d.constraint != null && !d.constraint.isEmpty()) {
                spec += d.constraint.startsWith("=") || d.constraint.startsWith(">")
                        || d.constraint.startsWith("<") || d.constraint.startsWith("~") ? d.constraint : "";
            }
            args.add(spec);
        }
        String joined = String.join(" ", args);
        switch (pmId == null ? "" : pmId) {
            case "pip":      return "python -m pip install " + joined;
            case "conda":    return "conda install -y " + String.join(" ", names(deps));
            case "npm":      return "npm install " + joined;
            case "yarn":     return "yarn add " + joined;
            case "pnpm":     return "pnpm add " + joined;
            case "gem":      return "gem install " + String.join(" ", names(deps));
            case "cargo":    return "cargo add " + String.join(" ", names(deps));
            default:         return "# 请参考对应包管理器文档安装：" + joined;
        }
    }

    private static List<String> names(List<Dep> deps) {
        List<String> out = new ArrayList<>();
        for (Dep d : deps) {
            out.add(d.name);
        }
        return out;
    }

    /** 把依赖列表转成 Map 结构，用于导出报告。 */
    public static List<Object> toMaps(List<Dep> deps) {
        List<Object> out = new ArrayList<>();
        for (Dep d : deps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name);
            m.put("version", d.version);
            m.put("constraint", d.constraint);
            m.put("source", d.source);
            m.put("dev", d.dev);
            out.add(m);
        }
        return out;
    }

    /** 常用包管理器命令速查，供界面"复制命令"使用。 */
    public static Map<String, List<String>> cheatsheet(String langId) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        switch (langId) {
            case "python":
                map.put("pip", Arrays.asList("python -m pip list", "python -m pip install <pkg>",
                        "python -m pip freeze > requirements.txt", "python -m pip install -r requirements.txt",
                        "python -m pip install --upgrade <pkg>", "python -m pip uninstall <pkg>"));
                map.put("conda", Arrays.asList("conda env list", "conda create -n <env> python=3.12",
                        "conda activate <env>", "conda install <pkg>", "conda list", "conda env export > environment.yml"));
                map.put("venv", Arrays.asList("python -m venv .venv", ".venv\\Scripts\\activate (Windows)",
                        "source .venv/bin/activate (macOS/Linux)"));
                break;
            case "node":
                map.put("npm", Arrays.asList("npm list --depth=0", "npm install <pkg>", "npm install -D <pkg>",
                        "npm install", "npm outdated", "npm uninstall <pkg>", "npx <pkg>"));
                map.put("pnpm", Arrays.asList("pnpm list --depth=0", "pnpm add <pkg>", "pnpm install",
                        "pnpm outdated", "pnpm remove <pkg>"));
                map.put("yarn", Arrays.asList("yarn list --depth=0", "yarn add <pkg>", "yarn install",
                        "yarn outdated", "yarn remove <pkg>"));
                break;
            case "java":
                map.put("maven", Arrays.asList("mvn -v", "mvn clean install", "mvn dependency:tree",
                        "mvn dependency:resolve", "mvn versions:display-dependency-updates"));
                map.put("gradle", Arrays.asList("gradle -v", "gradle build", "gradle dependencies",
                        "gradle --refresh-dependencies", "gradle clean"));
                break;
            case "go":
                map.put("go", Arrays.asList("go mod tidy", "go get <pkg>@<version>", "go list -m all",
                        "go mod download", "go clean -modcache", "go env GOPATH"));
                break;
            case "rust":
                map.put("cargo", Arrays.asList("cargo add <pkg>", "cargo tree", "cargo update",
                        "cargo build --release", "cargo outdated"));
                break;
            case "dotnet":
                map.put("dotnet", Arrays.asList("dotnet add package <pkg>", "dotnet list package",
                        "dotnet restore", "dotnet list package --outdated", "dotnet nuget locals all --list"));
                break;
            case "csharp":
                map.put("NuGet", Arrays.asList("nuget restore <solution.sln>", "nuget list <pkg>",
                        "nuget install <pkg>", "nuget update <pkg>", "msbuild <proj>.csproj /t:Restore"));
                map.put("编译", Arrays.asList("csc <file>.cs", "csc /target:exe <file>.cs",
                        "csc /version", "msbuild <proj>.csproj /p:Configuration=Release"));
                break;
            case "msvc":
                map.put("编译", Arrays.asList("cl <file>.cpp", "cl /std:c++17 <file>.cpp",
                        "cl /EHsc /W4 <file>.cpp", "vcvars64.bat（进入开发者环境）"));
                map.put("vcpkg", Arrays.asList("vcpkg list", "vcpkg install <pkg>",
                        "vcpkg install <pkg>:x64-windows", "vcpkg remove <pkg>", "vcpkg search <pkg>"));
                map.put("构建", Arrays.asList("msbuild <proj>.vcxproj /p:Configuration=Release",
                        "cmake -G \"Visual Studio 16 2019\" ..", "cmake --build . --config Release"));
                break;
            case "ruby":
                map.put("gem", Arrays.asList("gem list --local", "gem install <pkg>", "gem update",
                        "gem cleanup", "bundle install", "bundle list"));
                break;
            case "php":
                map.put("composer", Arrays.asList("composer install", "composer require <pkg>",
                        "composer show", "composer update", "composer outdated"));
                break;
            default:
                map.put("提示", Arrays.asList("该语言未收录常用依赖命令，可参考其官方文档。"));
        }
        return map;
    }
}
