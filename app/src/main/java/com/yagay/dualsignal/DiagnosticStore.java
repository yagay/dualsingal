package com.yagay.dualsignal;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class DiagnosticStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "dualsignal-diagnostics.log";
    private static final long MAX_BYTES = 512 * 1024;
    private DiagnosticStore() {}
    static File file(Context context) { return new File(context.getFilesDir(), FILE_NAME); }
    static void append(Context context, String line) {
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.length() > MAX_BYTES) {
                    File old = new File(context.getFilesDir(), FILE_NAME + ".old");
                    if (old.exists()) old.delete();
                    target.renameTo(old);
                }
                try (FileOutputStream out = new FileOutputStream(target, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
        }
    }
    static String read(Context context) {
        synchronized (LOCK) {
            File target = file(context);
            if (!target.exists()) return "尚无诊断日志。启用模块并重启 SystemUI 后再刷新。";
            try (FileInputStream in = new FileInputStream(target)) {
                byte[] bytes = new byte[(int) Math.min(target.length(), MAX_BYTES)];
                int count = in.read(bytes);
                return count <= 0 ? "日志文件为空。" : new String(bytes, 0, count, StandardCharsets.UTF_8);
            } catch (Throwable t) { return "读取日志失败：" + t; }
        }
    }
    static void clear(Context context) {
        synchronized (LOCK) {
            file(context).delete();
            new File(context.getFilesDir(), FILE_NAME + ".old").delete();
        }
    }
}
