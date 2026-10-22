package com.langmgr.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 外部命令执行封装。
 * <p>
 * 针对 Windows 中文环境做了两件关键处理：
 * 1) 输出编码探测（控制台多为 GBK/MS936，工具链多为 UTF-8），先按 UTF-8 严格解码，
 *    出现非法字节再退回 GBK，避免版本字符串变成乱码；
 * 2) 强制超时并销毁进程树，防止某个卡住的解释器把整个扫描线程拖死。
 */
public final class Proc {

    /** 命令执行结果。 */
    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        public final String error;

        Result(int exitCode, String stdout, String stderr, boolean timedOut, String error) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timedOut = timedOut;
            this.error = error;
        }

        public boolean ok() {
            return error == null && !timedOut && exitCode == 0;
        }

        /** stdout 为空时回退到 stderr——很多工具把版本信息打在 stderr（如 java、gcc）。 */
        public String effective() {
            String s = stdout.trim();
            return s.isEmpty() ? stderr.trim() : s;
        }
    }

    /** Windows 中文系统控制台默认代码页对应的字符集。 */
    private static final Charset FALLBACK;

    static {
        Charset c;
        try {
            c = Charset.forName("MS936");
        } catch (Exception e1) {
            try {
                c = Charset.forName("GBK");
            } catch (Exception e2) {
                c = StandardCharsets.UTF_8;
            }
        }
        FALLBACK = c;
    }

    private Proc() {
    }

    /** 在 PATH 中查找可执行文件的绝对路径，找不到返回 null。 */
    public static String which(String exe) {
        if (exe == null || exe.trim().isEmpty()) {
            return null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        List<String> suffixes = Os.isWindows()
                ? Arrays.asList(".exe", ".cmd", ".bat", ".com", "")
                : Arrays.asList("", ".sh");
        for (String rawDir : pathEnv.split(File.pathSeparator)) {
            String dir = rawDir.trim();
            if (dir.length() > 1 && dir.startsWith("\"") && dir.endsWith("\"")) {
                dir = dir.substring(1, dir.length() - 1); // PATH 条目允许带引号
            }
            if (dir.isEmpty()) {
                continue;
            }
            for (String suf : suffixes) {
                File f = new File(dir, exe + suf);
                if (f.isFile() && f.canRead()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** 运行命令，默认 15 秒超时。 */
    public static Result run(List<String> command) {
        return run(command, null, 15_000);
    }

    public static Result run(List<String> command, File workDir, long timeoutMillis) {
        if (command == null || command.isEmpty()) {
            return new Result(-1, "", "", false, "空命令");
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            if (workDir != null && workDir.isDirectory()) {
                pb.directory(workDir);
            }
            process = pb.start();

            final Process p = process;
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread outThread = drain(p.getInputStream(), outBuf);
            Thread errThread = drain(p.getErrorStream(), errBuf);

            boolean finished = p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyTree(p);
                outThread.join(500);
                errThread.join(500);
                return new Result(-1, decode(outBuf), decode(errBuf), true, "执行超时（" + timeoutMillis + "ms）");
            }
            outThread.join(2000);
            errThread.join(2000);
            return new Result(p.exitValue(), decode(outBuf), decode(errBuf), false, null);
        } catch (IOException ex) {
            return new Result(-1, "", "", false, "无法启动进程：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroyTree(process);
            }
            return new Result(-1, "", "", false, "执行被中断");
        } catch (RuntimeException ex) {
            if (process != null) {
                destroyTree(process);
            }
            return new Result(-1, "", "", false, "执行失败：" + ex.getMessage());
        }
    }

    public static Result run(String... command) {
        return run(new ArrayList<>(Arrays.asList(command)), null, 15_000);
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    sink.write(buf, 0, n);
                    if (sink.size() > 4 * 1024 * 1024) {
                        break; // 保护：超大输出截断
                    }
                }
            } catch (IOException ignored) {
                // 进程结束后流关闭属正常情况
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String decode(ByteArrayOutputStream buf) {
        byte[] bytes = buf.toByteArray();
        if (bytes.length == 0) {
            return "";
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        return new String(bytes, FALLBACK);
    }

    private static void destroyTree(Process p) {
        try {
            p.descendants().forEach(ph -> {
                try {
                    ph.destroyForcibly();
                } catch (RuntimeException ignored) {
                }
            });
        } catch (RuntimeException ignored) {
            // 部分 JVM 实现不支持 descendants()，忽略即可
        }
        p.destroyForcibly();
    }

    /** 把多行文本压成单行摘要，用于表格显示。 */
    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 取第一个非空行。 */
    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return "";
    }
}
