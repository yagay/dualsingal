package com.yagay.dualsignal;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Crash-safe runtime dual-SIM stacker for OPlus SystemUI.
 *
 * Important safety rule: never re-parent, remove, add, clone, or replace a SystemUI
 * mobile signal View. OPlus SystemUI controllers may retain references to the original
 * parent/index/layout params. Re-parenting those Views can cause a SystemUI crash loop
 * and trigger LSPosed safe mode.
 *
 * This implementation only applies visual transforms to the two existing mobile Views.
 */
public final class DualSignalModule extends XposedModule {
    private static final String TAG = "DualSignal102";
    private static final String TARGET = "com.android.systemui";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final Map<ViewGroup, Boolean> DONE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<ViewGroup> PENDING =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private static final float SIGNAL_SCALE = 0.66f;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        installOnce();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        installOnce();
    }

    private void installOnce() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Method attached = View.class.getDeclaredMethod("onAttachedToWindow");
            attached.setAccessible(true);
            hook(attached).intercept(AttachHook.INSTANCE);
            log(Log.INFO, TAG, "safe attach hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed: " + t);
        }
    }

    public static final class AttachHook implements XposedInterface.Hooker {
        static final AttachHook INSTANCE = new AttachHook();

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object self = chain.getThisObject();
                if (self instanceof View view && isMobileCandidate(view)) {
                    scheduleParent(view);
                }
            } catch (Throwable t) {
                Log.w(TAG, "attach callback ignored: " + t);
            }
            return result;
        }
    }

    private static void scheduleParent(View view) {
        if (!(view.getParent() instanceof ViewGroup parent)) return;
        if (DONE.containsKey(parent) || !PENDING.add(parent)) return;

        Handler handler = view.getHandler();
        Runnable action = () -> {
            PENDING.remove(parent);
            try {
                tryStack(parent);
            } catch (Throwable t) {
                Log.w(TAG, "stack attempt ignored: " + t);
            }
        };

        if (handler != null) handler.post(action);
        else new Handler(Looper.getMainLooper()).post(action);
    }

    private static void tryStack(ViewGroup parent) {
        if (DONE.containsKey(parent)) return;

        List<View> mobiles = collectDirectMobileChildren(parent);
        if (mobiles.size() != 2) {
            mobiles = collectWrappedMobileChildren(parent);
        }
        if (mobiles.size() != 2) return;

        View first = mobiles.get(0);
        View second = mobiles.get(1);
        if (first == second) return;
        if (first.getParent() != second.getParent()) return;
        if (!(first.getParent() instanceof ViewGroup actualParent)) return;

        int i1 = actualParent.indexOfChild(first);
        int i2 = actualParent.indexOfChild(second);
        if (i1 < 0 || i2 < 0 || Math.abs(i1 - i2) > 3) return;

        // Wait until SystemUI has completed at least one layout pass.
        actualParent.post(() -> applyVisualStack(actualParent, first, second));
        DONE.put(actualParent, Boolean.TRUE);
    }

    private static void applyVisualStack(ViewGroup parent, View first, View second) {
        try {
            if (!first.isAttachedToWindow() || !second.isAttachedToWindow()) return;
            if (first.getParent() != parent || second.getParent() != parent) return;

            int w1 = Math.max(first.getWidth(), first.getMeasuredWidth());
            int w2 = Math.max(second.getWidth(), second.getMeasuredWidth());
            int h1 = Math.max(first.getHeight(), first.getMeasuredHeight());
            int h2 = Math.max(second.getHeight(), second.getMeasuredHeight());
            if (w1 <= 0 || w2 <= 0 || h1 <= 0 || h2 <= 0) {
                parent.post(() -> applyVisualStack(parent, first, second));
                return;
            }

            // Keep each SystemUI View in its original parent and original slot.
            first.setPivotX(first.getWidth() / 2f);
            first.setPivotY(first.getHeight() / 2f);
            second.setPivotX(second.getWidth() / 2f);
            second.setPivotY(second.getHeight() / 2f);
            first.setScaleX(SIGNAL_SCALE);
            first.setScaleY(SIGNAL_SCALE);
            second.setScaleX(SIGNAL_SCALE);
            second.setScaleY(SIGNAL_SCALE);

            // Move the visual centers onto the same X coordinate without touching layout params.
            float center1 = first.getLeft() + first.getWidth() / 2f;
            float center2 = second.getLeft() + second.getWidth() / 2f;
            float targetCenter = Math.min(center1, center2) + Math.min(w1, w2) * 0.30f;
            first.setTranslationX(targetCenter - center1);
            second.setTranslationX(targetCenter - center2);

            // Split the normal status-bar height into upper/lower visual rows.
            float rowOffset = Math.max(2f, Math.min(h1, h2) * 0.23f);
            first.setTranslationY(-rowOffset);
            second.setTranslationY(rowOffset);

            // Transforms do not modify touch handling, parent ownership, layout params,
            // controller references, or SystemUI signal state callbacks.
            Log.i(TAG, "safe-stacked two mobile views: " + describe(first) + " + " + describe(second));
        } catch (Throwable t) {
            Log.w(TAG, "visual stack ignored: " + t);
        }
    }

    private static List<View> collectDirectMobileChildren(ViewGroup parent) {
        ArrayList<View> out = new ArrayList<>(2);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (isMobileCandidate(child)) out.add(child);
        }
        return out;
    }

    private static List<View> collectWrappedMobileChildren(ViewGroup parent) {
        ArrayList<View> out = new ArrayList<>(2);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!(child instanceof ViewGroup vg)) continue;
            if (vg.getChildCount() > 8) continue;
            View hit = findOneMobile(vg, 0);
            if (hit != null) out.add(child);
        }
        return out;
    }

    private static View findOneMobile(View view, int depth) {
        if (depth > 2) return null;
        if (isMobileCandidate(view)) return view;
        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findOneMobile(vg.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isMobileCandidate(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceEntryName(view).toLowerCase(Locale.ROOT);

        boolean classHit = cls.contains("statusbarmobile")
                || cls.contains("mobilesignal")
                || (cls.contains("mobile") && cls.contains("statusbar"));

        boolean idHit = id.contains("mobile_signal")
                || id.contains("mobile_group")
                || id.contains("status_bar_mobile")
                || id.contains("mobile_combo")
                || id.contains("signal_cluster_mobile");

        return classHit || idHit;
    }

    private static String resourceEntryName(View v) {
        int id = v.getId();
        if (id == View.NO_ID || id == 0) return "";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String describe(View v) {
        return v.getClass().getSimpleName() + "#" + resourceEntryName(v);
    }
}
