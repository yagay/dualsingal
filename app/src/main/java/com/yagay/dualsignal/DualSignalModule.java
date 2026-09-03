package com.yagay.dualsignal;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

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
 * Safety rule: never re-parent/remove/add/clone SystemUI mobile views. We only
 * apply visual transforms to the original views or their existing wrappers.
 */
public final class DualSignalModule extends XposedModule {
    private static final String TAG = "DualSignal102";
    private static final String TARGET = "com.android.systemui";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final Map<ViewGroup, Boolean> DONE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<ViewGroup> PENDING =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<String> LOGGED_CANDIDATES =
            Collections.synchronizedSet(new java.util.HashSet<>());

    private static final float SIGNAL_SCALE = 0.66f;
    private static final int MAX_ANCESTOR_DEPTH = 5;

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
            log(Log.INFO, TAG, "safe attach hook installed (ancestor scan enabled)");
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
                    logCandidateOnce(view);
                    scheduleAncestors(view);
                }
            } catch (Throwable t) {
                Log.w(TAG, "attach callback ignored: " + t);
            }
            return result;
        }
    }

    /**
     * OPlus commonly nests each SIM inside its own wrapper. The previous version
     * only checked the immediate parent, so it never reached the common parent
     * containing SIM1-wrapper and SIM2-wrapper. Walk several ancestors and test
     * each one without changing the hierarchy.
     */
    private static void scheduleAncestors(View view) {
        ViewParent p = view.getParent();
        int depth = 0;
        while (p instanceof ViewGroup vg && depth < MAX_ANCESTOR_DEPTH) {
            scheduleParent(vg, view);
            p = vg.getParent();
            depth++;
        }
    }

    private static void scheduleParent(ViewGroup parent, View source) {
        if (DONE.containsKey(parent) || !PENDING.add(parent)) return;

        Handler handler = source.getHandler();
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
        String mode = "direct";
        if (mobiles.size() != 2) {
            mobiles = collectWrappedMobileChildren(parent);
            mode = "wrapped";
        }
        if (mobiles.size() != 2) return;

        View first = mobiles.get(0);
        View second = mobiles.get(1);
        if (first == second) return;
        if (first.getParent() != second.getParent()) return;
        if (!(first.getParent() instanceof ViewGroup actualParent)) return;

        int i1 = actualParent.indexOfChild(first);
        int i2 = actualParent.indexOfChild(second);
        if (i1 < 0 || i2 < 0 || Math.abs(i1 - i2) > 4) return;

        final String selectedMode = mode;
        Log.i(TAG, "pair found mode=" + selectedMode
                + " parent=" + describe(actualParent)
                + " first=" + describe(first)
                + " second=" + describe(second));

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

            first.setPivotX(first.getWidth() / 2f);
            first.setPivotY(first.getHeight() / 2f);
            second.setPivotX(second.getWidth() / 2f);
            second.setPivotY(second.getHeight() / 2f);
            first.setScaleX(SIGNAL_SCALE);
            first.setScaleY(SIGNAL_SCALE);
            second.setScaleX(SIGNAL_SCALE);
            second.setScaleY(SIGNAL_SCALE);

            // Use screen coordinates rather than only getLeft(), because OPlus wrappers
            // can sit inside translated/intermediate containers.
            int[] loc1 = new int[2];
            int[] loc2 = new int[2];
            first.getLocationOnScreen(loc1);
            second.getLocationOnScreen(loc2);
            float center1 = loc1[0] + first.getWidth() / 2f;
            float center2 = loc2[0] + second.getWidth() / 2f;
            float targetCenter = Math.min(center1, center2) + Math.min(w1, w2) * 0.30f;

            first.setTranslationX(first.getTranslationX() + targetCenter - center1);
            second.setTranslationX(second.getTranslationX() + targetCenter - center2);

            float rowOffset = Math.max(2f, Math.min(h1, h2) * 0.24f);
            first.setTranslationY(first.getTranslationY() - rowOffset);
            second.setTranslationY(second.getTranslationY() + rowOffset);

            Log.i(TAG, "safe-stacked two mobile views: "
                    + describe(first) + " + " + describe(second));
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
            // Status icon wrappers are small. Keep this conservative to avoid QS panels.
            if (vg.getChildCount() > 12) continue;
            View hit = findOneMobile(vg, 0);
            if (hit != null) out.add(child);
        }
        return out;
    }

    private static View findOneMobile(View view, int depth) {
        if (depth > 4) return null;
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
                || cls.contains("mobileicon")
                || cls.contains("cellularsignal")
                || (cls.contains("mobile") && (cls.contains("statusbar") || cls.contains("signal")))
                || (cls.contains("oplus") && cls.contains("mobile"));

        boolean idHit = id.contains("mobile_signal")
                || id.contains("mobile_group")
                || id.contains("status_bar_mobile")
                || id.contains("mobile_combo")
                || id.contains("signal_cluster_mobile")
                || id.equals("mobile")
                || id.contains("mobile_icon")
                || id.contains("mobile_type");

        return classHit || idHit;
    }

    private static void logCandidateOnce(View v) {
        try {
            String key = v.getClass().getName() + "#" + resourceEntryName(v);
            if (!LOGGED_CANDIDATES.add(key)) return;
            StringBuilder parents = new StringBuilder();
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View pv && depth < MAX_ANCESTOR_DEPTH) {
                if (parents.length() > 0) parents.append(" <- ");
                parents.append(describe(pv));
                p = pv.getParent();
                depth++;
            }
            Log.i(TAG, "candidate=" + describe(v) + " parents=" + parents);
        } catch (Throwable ignored) {
        }
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
