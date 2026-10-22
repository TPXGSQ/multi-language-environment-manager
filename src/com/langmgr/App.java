package com.langmgr;

import com.langmgr.core.Detected;
import com.langmgr.core.LangSpec;
import com.langmgr.core.Os;
import com.langmgr.core.Scanner;
import com.langmgr.ui.AppModel;
import com.langmgr.ui.MainFrame;
import com.langmgr.ui.Theme;

import javax.swing.*;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 程序入口。
 *
 * <p>两种运行方式：
 * <ul>
 *   <li>无参数——启动 Swing 图形界面（默认，也是正常使用方式）；</li>
 *   <li>{@code --cli}——纯命令行扫描并打印表格，便于在脚本/CI 中调用，或界面出问题时排查。</li>
 * </ul>
 */
public final class App {

    public static void main(String[] args) {
        List<String> argList = new ArrayList<>();
        if (args != null) {
            for (String a : args) {
                argList.add(a);
            }
        }
        if (argList.contains("--cli") || argList.contains("-c")) {
            runCli();
            return;
        }
        if (argList.contains("--help") || argList.contains("-h")) {
            printHelp();
            return;
        }
        launchGui();
    }

    /* ---------------------------------------------------------- GUI */

    private static void launchGui() {
        // Windows 上让 Swing 使用现代 DPI 感知与系统字体渲染
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        if (Os.isWindows()) {
            System.setProperty("sun.java2d.d3d", "true");
            // 禁用 D3D 屏幕表面，只保留离屏加速、上屏走 GDI blit：
            // 规避 Win11 + 高 DPI 下 DWM 合成层与 Java 2D 表面错位导致的黑线残影
            System.setProperty("sun.java2d.d3d.onscreen", "false");
            // 不再设置 sun.awt.nopixfmt：该属性绕过原生像素格式协商，
            // 在部分 Windows 11 + 高 DPI 组合下会造成表面格式不匹配（加剧残影）
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            Theme.install();

            AppModel model = new AppModel();
            MainFrame frame = new MainFrame(model);
            frame.setVisible(true);
            // 启动后自动扫描一次，用户不必手动点
            model.rescan();
        });
    }

    /* ---------------------------------------------------------- CLI */

    private static void runCli() {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println("多语言环境管理器 · 命令行扫描");
        out.println("系统：" + Os.label());
        out.println("已登记语言：" + LangSpec.all().size() + " 种");
        out.println();

        long t0 = System.currentTimeMillis();
        Scanner scanner = new Scanner((lang, done, total) ->
                out.print("\r正在检测 " + lang + "（" + done + "/" + total + "）        "));
        Detected.Report report = scanner.scanAll();
        out.println("\r扫描完成，耗时 " + ((System.currentTimeMillis() - t0) / 1000.0) + " 秒。        ");
        out.println();

        out.println(String.format("%-18s %-8s %-16s %-6s %s",
                "语言", "状态", "版本", "安装数", "可执行文件"));
        out.println(repeat('-', 118));
        for (Detected.LangResult r : report.results) {
            out.println(String.format("%-18s %-8s %-16s %-6d %s",
                    cut(r.spec.displayName, 18),
                    r.installed ? "已安装" : "未安装",
                    cut(r.installed ? r.activeVersion() : "—", 16),
                    r.installs.size(),
                    cut(r.installed ? r.activePath() : "—", 62)));
        }
        out.println();
        out.println("多版本语言（可在图形界面中切换）：");
        boolean any = false;
        for (Detected.LangResult r : report.results) {
            List<Detected.Install> switchable = r.switchableInstalls();
            if (switchable.size() > 1) {
                any = true;
                out.println("  · " + r.spec.displayName + "：" + switchable.size() + " 个可切换版本");
                for (Detected.Install i : switchable) {
                    out.println("      " + (i.active ? "● " : "  ") + i.label() + "  " + i.exePath);
                }
            }
        }
        if (!any) {
            out.println("  （无）");
        }
        out.println();

        // 耗时排行：便于定位哪种语言的探测偏慢
        List<Detected.LangResult> byTime = new ArrayList<>(report.results);
        byTime.sort((a, b) -> Long.compare(b.scanMillis, a.scanMillis));
        out.println("扫描耗时排行（前 5）：");
        for (int i = 0; i < Math.min(5, byTime.size()); i++) {
            Detected.LangResult r = byTime.get(i);
            out.println(String.format("  %-18s %6d ms%s", cut(r.spec.displayName, 18), r.scanMillis,
                    r.installs.isEmpty() ? "" : ("  （" + r.installs.size() + " 个安装）")));
        }
        out.println();

        // 扫描备注：暴露探测失败/超时等细节，便于排查
        boolean anyNote = false;
        for (Detected.LangResult r : report.results) {
            if (r.notes.isEmpty()) {
                continue;
            }
            if (!anyNote) {
                out.println("探测备注：");
                anyNote = true;
            }
            out.println("  · " + r.spec.displayName);
            for (String n : r.notes) {
                out.println("      " + n);
            }
        }
        if (anyNote) {
            out.println();
        }
        out.println("环境变量：");
        boolean anyVar = false;
        for (Detected.LangResult r : report.results) {
            for (Detected.VarState v : r.vars.values()) {
                anyVar = true;
                String val = v.effectiveValue == null || v.effectiveValue.trim().isEmpty()
                        ? "（未设置）" : v.effectiveValue;
                out.println(String.format("  %-14s = %s%s", v.name, cut(val, 70),
                        v.note == null || v.note.isEmpty() ? "" : ("   # " + v.note)));
            }
        }
        if (!anyVar) {
            out.println("  （已安装语言均无关联变量）");
        }
        out.println();
        out.println("提示：命令行模式为只读扫描，不做任何修改。切换版本与管理 PATH 请运行图形界面。");
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void printHelp() {
        System.out.println("多语言环境管理器");
        System.out.println();
        System.out.println("用法：");
        System.out.println("  java -jar langmgr.jar          启动图形界面（默认）");
        System.out.println("  java -jar langmgr.jar --cli    命令行只读扫描，打印检测结果");
        System.out.println("  java -jar langmgr.jar --help   显示本帮助");
        System.out.println();
        System.out.println("支持检测：" + LangSpec.all().size() + " 种语言/工具链");
        System.out.println("功能：版本检测、存储位置定位、环境配置校验、多版本切换、依赖管理、报告导出");
        System.out.println();
        System.out.println("安全说明：");
        System.out.println("  · 扫描过程完全只读，不修改任何配置");
        System.out.println("  · 版本切换只写当前用户作用域（HKCU），不需要管理员权限");
        System.out.println("  · 写入前自动备份到 backup 目录，可一键回滚");
        System.out.println("  · 不使用 setx，避免长 PATH 被截断、%VAR% 引用被永久展开");
    }
}
