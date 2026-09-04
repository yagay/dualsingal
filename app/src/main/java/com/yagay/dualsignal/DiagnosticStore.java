package com.yagay.dualsignal;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class DiagnosticStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "dualsignal-diagnostics.log";
    private static final long MAX_BYTES = 512 * 1024;

    private DiagnosticStore() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static void append(Context context, String line) {
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.length() > MAX_BYTES) {
                    File old = new File(context.getFilesDir(), FILE_NAME + ".old");
                    if (old.exists()) old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    target.renameTo(old);
                }
                try (FileOutputStream out = new FileOutputStream(target, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                android.util.Log.w("DualSignal102", "DiagnosticStore.append failed: " + t);
            }
        }
    }

    static String read(Context context) {
        synchronized (LOCK) {
            File target = file(context);
            File old = new File(context.getFilesDir(), FILE_NAME + ".old");
            if (!target.exists() && !old.exists()) {
                return "尚无诊断日志。\n\n"
                        + "请确认：\n"
                        + "1. LSPosed 已启用本模块，作用域包含 com.android.systemui\n"
                        + "2. 已强制停止 SystemUI 或重启手机\n"
                        + "3. 到 LSPosed 日志里过滤标签 DualSignal102 查看实时输出\n"
                        + "（Android 16 上广播写回可能失败，以 LSPosed 日志为准）";
            }
            try {
                StringBuilder out = new StringBuilder();
                if (old.exists()) {
                    out.append(new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
                }
                if (target.exists()) {
                    out.append(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
                }
                return out.length() == 0 ? "日志文件为空。" : out.toString();
            } catch (Throwable t) {
                return "读取日志失败：" + t;
            }
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            file(context).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), FILE_NAME + ".old").delete();
        }
    }
}
