package com.langmgr.ui;

import com.langmgr.core.EnvStore;
import com.langmgr.core.LangSpec;
import com.langmgr.core.Os;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * "PATH 命中了转发器、导致切换不生效"的处理指引。
 *
 * <p>只说明"为什么失败"是不够的——用户真正需要的是下一步动作：
 * 该用什么命令、需要什么权限、做完怎么验证。这里把可复制的命令和验证步骤一起给出。
 */
final class ForwarderHelp {

    private ForwarderHelp() {
    }

    /** 取可执行文件的父目录（PATH 里存的是目录，不是 exe 全路径）。 */
    static String parentDirOf(String exeOrDir) {
        if (exeOrDir == null || exeOrDir.isEmpty()) {
            return "";
        }
        String p = EnvStore.strip(exeOrDir);
        if (p.toLowerCase().endsWith(".exe")) {
            int idx = p.lastIndexOf(File.separatorChar);
            if (idx > 0) {
                p = p.substring(0, idx);
            }
        }
        while (p.length() > 3 && (p.endsWith("\\") || p.endsWith("/"))) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 从某个作用域的 PATH 里删掉一条目录的 PowerShell 命令。
     *
     * <p>这里每一处细节都来自一次真实的失败（用户执行完再检测，结果毫无变化）：
     * <ul>
     *   <li><b>PATH 里存的是目录</b>（{@code …\javapath}），不是 {@code …\javapath\java.exe}。
     *       按 exe 全路径去过滤，一条都匹配不上，整条命令等于什么也没做；</li>
     *   <li>比较要忽略大小写和结尾反斜杠，并且把条目里的 {@code %VAR%} 展开后再比，
     *       否则写成 {@code %ProgramFiles%\…} 的条目也删不掉；</li>
     *   <li>用注册表读原文（{@code DoNotExpandEnvironmentNames}）并沿用原值类型写回，
     *       避免把 REG_EXPAND_SZ 拍平成 REG_SZ，把 {@code %SystemRoot%} 冻成固定路径；</li>
     *   <li>不用 {@code [Environment]::SetEnvironmentVariable}：.NET Framework 下它会
     *       把超过 2047 字符的系统 PATH 截断。</li>
     * </ul>
     */
    static String removeFromPathCommand(String exeOrDir, boolean system) {
        String p = parentDirOf(exeOrDir).replace("'", "''");   // PowerShell 单引号转义
        String key = system
                ? "HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"
                : "HKCU:\\Environment";
        return "$p='" + p + "';"
                + "$k='" + key + "';"
                + "$i=Get-Item $k;"
                + "$t=$i.GetValueKind('Path');"
                + "$raw=$i.GetValue('Path',$null,'DoNotExpandEnvironmentNames');"
                + "$n=(($raw -split ';')|Where-Object{"
                + "($_.Trim().TrimEnd('\\') -ine $p.TrimEnd('\\')) -and "
                + "([Environment]::ExpandEnvironmentVariables($_.Trim()).TrimEnd('\\') -ine $p.TrimEnd('\\'))"
                + "}) -join ';';"
                + "Set-ItemProperty -Path $k -Name Path -Value $n -Type $t;"
                + "'done, entries='+(($n -split ';').Count)";
    }

    /** 该目录在这个作用域的 PATH 里排第几条（1 开始）；0 表示不在。 */
    private static int indexIn(List<String> entries, String dir) {
        if (dir == null || dir.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < entries.size(); i++) {
            String e = EnvStore.strip(Os.expand(entries.get(i)));
            if (EnvStore.samePath(e, dir)
                    || EnvStore.samePath(e, dir + File.separator + "java.exe")) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * @param owner     父窗口
     * @param spec      语言规格（决定是否给出 Java 专属的注册表方案），可空
     * @param forwarder 转发器路径，可空
     * @param running   实际在用的可执行文件，可空
     * @param target    用户想切到的目标，可空
     */
    static void show(Component owner, LangSpec spec, String forwarder, String running, String target) {
        boolean isJava = spec != null && "java".equals(spec.id);
        String dir = parentDirOf(forwarder);
        int sysIdx = indexIn(EnvStore.systemPathEntries(), dir);
        int usrIdx = indexIn(EnvStore.userPathEntries(), dir);
        // 两个作用域都可能出现（HKCU 的那份永远被 HKLM 压住），默认按系统级给命令
        boolean systemScope = sysIdx > 0 || usrIdx == 0;
        String cmd = forwarder == null ? "" : removeFromPathCommand(forwarder, systemScope);
        String userCmd = forwarder == null ? "" : removeFromPathCommand(forwarder, false);

        StringBuilder sb = new StringBuilder();
        if (forwarder != null) {
            sb.append("为什么会这样\n")
              .append("  系统级 PATH 里的 ").append(forwarder).append("\n")
              .append("  排在所有用户级条目之前，所以无论用户 PATH 怎么调整，\n")
              .append("  新开的终端都会先命中它。\n");
            if (running != null) {
                sb.append("  它当前转发到：").append(running).append("\n");
            }
            sb.append("  这条目录出现在：");
            if (sysIdx > 0) {
                sb.append("系统 PATH 第 ").append(sysIdx).append(" 条");
            }
            if (sysIdx > 0 && usrIdx > 0) {
                sb.append("、");
            }
            if (usrIdx > 0) {
                sb.append("用户 PATH 第 ").append(usrIdx).append(" 条");
            }
            if (sysIdx == 0 && usrIdx == 0) {
                sb.append("（未在 PATH 中直接找到，可能是链接或其它查找顺序命中）");
            }
            sb.append("\n\n");
        }

        sb.append("上次那条命令为什么没用\n")
          .append("  PATH 里登记的是目录 ").append(dir).append("\n")
          .append("  而不是 ").append(dir).append("\\java.exe。\n")
          .append("  按 exe 全路径去过滤，一条都匹配不上 —— 命令执行成功但什么都没删，\n")
          .append("  所以再检测结果当然还是老样子。下面的命令按目录匹配，并已修正大小写、\n")
          .append("  结尾反斜杠、%VAR% 写法这几种差异。\n\n");

        sb.append("方案 1（推荐）从 ").append(systemScope ? "系统" : "用户")
          .append(" PATH 移除这个转发器")
          .append(systemScope ? " — 需要管理员权限\n" : " — 不需要管理员权限\n");
        if (systemScope) {
            sb.append("  1) 以管理员身份打开 PowerShell（开始菜单右键 → 终端(管理员)）\n")
              .append("  2) 粘贴执行下面这条（已复制，直接 Ctrl+V）：\n\n")
              .append(cmd).append("\n\n")
              .append("     输出 done, entries=NN 表示已写回注册表（NN 是剩余条目数）。\n")
              .append("  3) 关闭所有终端并重新打开（旧终端仍持有旧 PATH）\n")
              .append("  4) 回到本工具，点「刷新版本列表」复核「PATH 先命中」是否已不再指向它\n");
            if (usrIdx > 0) {
                sb.append("\n  注：用户级 PATH 里也有一条同样的目录，不会被系统级的删除带走。\n")
                  .append("      它在「环境变量」页可以直接删（不需要管理员），命令是：\n\n")
                  .append(userCmd).append("\n");
            }
        } else {
            sb.append("  1) 打开 PowerShell（不需要管理员）\n")
              .append("  2) 粘贴执行下面这条（已复制，直接 Ctrl+V）：\n\n")
              .append(cmd).append("\n\n")
              .append("  3) 关闭所有终端并重新打开\n")
              .append("  4) 回到本工具，点「刷新版本列表」复核\n")
              .append("\n  也可以直接在「环境变量」页删掉这条目录，效果相同。\n");
        }

        if (isJava) {
            sb.append("\n方案 2 让转发器指向目标版本 — 全局生效，会影响所有依赖它的程序\n")
              .append("  仅当目标版本由 Oracle 安装器安装（已注册到 JavaSoft\\JDK）时可用。\n")
              .append("  先查询已注册的版本：\n")
              .append("    reg query \"HKLM\\SOFTWARE\\JavaSoft\\JDK\" /s\n")
              .append("  确认存在目标版本子键后，以管理员身份执行（把 <版本号> 换成实际值，\n")
              .append("  JDK 8 形如 1.8.0_361，JDK 9+ 形如 21.0.2）：\n")
              .append("    reg add \"HKLM\\SOFTWARE\\JavaSoft\\JDK\" /v CurrentVersion /t REG_SZ /d <版本号> /f\n");
        }

        sb.append("\n方案 3 不动系统配置，绕开 PATH\n")
          .append("  · 构建工具（Maven / Gradle / IDEA / VS Code）多数直接读 JAVA_HOME，\n")
          .append("    把它指向目标版本即可，不依赖 PATH 的顺序；\n");
        if (target != null) {
            sb.append("  · 命令行里直接用完整路径调用：\n      ").append(target).append("\n");
        } else {
            sb.append("  · 命令行里直接用完整路径调用目标版本的可执行文件。\n");
        }
        sb.append("\n说明：本工具只写当前用户作用域（HKCU），系统级 PATH 需要你手动处理，\n")
          .append("这也正是「工具里点了切换却没反应」的根源。\n")
          .append("另外，装了 Oracle 的其它产品（如 Oracle 数据库客户端）后，\n")
          .append("javapath 这条可能被重新写回，届时按上面的步骤再删一次即可。");

        JTextArea ta = new JTextArea(sb.toString());
        ta.setEditable(false);
        ta.setFocusable(false);
        ta.setLineWrap(false);
        ta.setFont(Theme.F_MONO_SM);
        ta.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(880, 500));

        String[] opts = {"复制方案 1 命令", "关闭"};
        int r = JOptionPane.showOptionDialog(owner, sp, "切换为什么没生效 · 怎么解决",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[1]);
        if (r == 0 && !cmd.isEmpty()) {
            Theme.copy(owner, cmd, (systemScope ? "管理员" : "用户级") + "命令，粘贴到 PowerShell 回车即可");
        }
    }
}
