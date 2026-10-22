package com.langmgr.ui;

import com.langmgr.core.Detected;
import com.langmgr.core.EnvStore;
import com.langmgr.core.Scanner;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 界面共享状态：持有最近一次扫描报告，并把后台扫描的进度广播给各面板。
 *
 * <p>扫描必须在后台线程执行——它要跑几十个外部进程，放 EDT 上会直接把界面冻住。
 * 结果通过 {@link SwingUtilities#invokeLater} 回投到 EDT 更新 UI。
 */
public final class AppModel {

    /** 扫描完成 / 进度变化监听。 */
    public interface Listener {
        /** 扫描进行中。 */
        default void onScanning(String currentLang, int done, int total) {
        }

        /** 扫描结束，report 可能为 null（失败）。 */
        default void onScanned(Detected.Report report) {
        }
    }

    private Detected.Report report;
    private boolean scanning;
    private String lastError;
    private final List<Listener> listeners = new ArrayList<>();
    private SwingWorker<Detected.Report, String> worker;

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Detected.Report report() {
        return report;
    }

    public boolean isScanning() {
        return scanning;
    }

    public String lastError() {
        return lastError;
    }

    /** 手动设置报告（用于导入缓存）。 */
    public void setReport(Detected.Report r) {
        this.report = r;
        fireScanned(r);
    }

    /** 触发一次全量扫描（后台）。重复调用会忽略。 */
    public synchronized void rescan() {
        if (scanning) {
            return;
        }
        scanning = true;
        lastError = null;
        worker = new SwingWorker<Detected.Report, String>() {
            @Override
            protected Detected.Report doInBackground() {
                // 每次扫描都丢弃环境变量缓存：用户可能在别处（管理员 PowerShell、
                // 系统属性面板）刚改过 PATH，用 5 秒内的旧值会让"刷新后还是老样子"。
                EnvStore.invalidateCaches();
                Scanner scanner = new Scanner((lang, done, total) -> {
                    publish(lang + "\u0001" + done + "\u0001" + total);
                });
                return scanner.scanAll();
            }

            @Override
            protected void process(List<String> chunks) {
                String last = chunks.get(chunks.size() - 1);
                String[] parts = last.split("\u0001");
                if (parts.length == 3) {
                    try {
                        int done = Integer.parseInt(parts[1]);
                        int total = Integer.parseInt(parts[2]);
                        fireScanning(parts[0], done, total);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            @Override
            protected void done() {
                scanning = false;
                try {
                    report = get();
                    fireScanned(report);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    lastError = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
                    report = null;
                    fireScanned(null);
                }
            }
        };
        worker.execute();
    }

    /** 取消进行中的扫描。 */
    public synchronized void cancelScan() {
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        scanning = false;
    }

    /** 只重扫单个语言，用于切换版本后的局部刷新。 */
    public synchronized void rescanOne(String langId, Runnable onFinish) {
        com.langmgr.core.LangSpec spec = com.langmgr.core.LangSpec.get(langId);
        if (spec == null || report == null) {
            if (onFinish != null) {
                onFinish.run();
            }
            return;
        }
        final Detected.Report current = report;
        SwingWorker<Detected.LangResult, Void> w = new SwingWorker<Detected.LangResult, Void>() {
            @Override
            protected Detected.LangResult doInBackground() {
                // 单语言重扫通常发生在切换/删除之后，必须读到刚写入的值
                EnvStore.invalidateCaches();
                return new Scanner().scanOne(spec);
            }

            @Override
            protected void done() {
                try {
                    Detected.LangResult fresh = get();
                    for (int i = 0; i < current.results.size(); i++) {
                        if (current.results.get(i).spec.id.equals(langId)) {
                            current.results.set(i, fresh);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
                fireScanned(current);
                if (onFinish != null) {
                    onFinish.run();
                }
            }
        };
        w.execute();
    }

    private void fireScanning(String lang, int done, int total) {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onScanning(lang, done, total);
        }
    }

    private void fireScanned(Detected.Report r) {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onScanned(r);
        }
    }
}
