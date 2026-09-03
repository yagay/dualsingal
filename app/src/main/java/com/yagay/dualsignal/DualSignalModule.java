package com.yagay.dualsignal;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

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
 * Runtime dual-SIM stacker for OPlus SystemUI.
 *
 * Design rules:
 * 1. Never replace signal drawables/resources. SystemUI remains the single source of truth.
 * 2. Re-parent the two existing mobile views instead of cloning them, preserving controllers/listeners.
 * 3. Apply only after both views share a stable parent.
 * 4. Fail closed: if the hierarchy is unfamiliar, leave SystemUI untouched.
 */
public final class DualSignalModule extends XposedModule {
    private static final String TAG = "DualSignal102";
    private static final String TARGET = "com.android.systemui";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final Map<ViewGroup, Boolean> DONE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<ViewGroup> PENDING =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

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
            log(Log.INFO, TAG, "View attach hook installed");
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
                Log.w(TAG, "attach callback ignored", t);
            }
            return result;
        }
    }

    private static void scheduleParent(View view) {
        if (!(view.getParent() instanceof ViewGroup parent)) return;
        if (DONE.containsKey(parent) || !PENDING.add(parent)) return;

        Handler h = view.getHandler();
        Runnable r = () -> {
            PENDING.remove(parent);
            tryStack(parent);
        };
        if (h != null) h.post(r);
        else new Handler(Looper.getMainLooper()).post(r);
    }

    private static void tryStack(ViewGroup parent) {
        if (DONE.containsKey(parent)) return;
        List<View> mobiles = collectDirectMobileChildren(parent);

        // Some OPlus builds put each mobile view in one thin wrapper. Handle that level too.
        if (mobiles.size() != 2) {
            mobiles = collectWrappedMobileChildren(parent);
        }
        if (mobiles.size() != 2) return;

        View first = mobiles.get(0);
        View second = mobiles.get(1);
        if (first == second || first.getParent() != second.getParent()) return;
        if (!(first.getParent() instanceof ViewGroup actualParent)) return;
        if (actualParent instanceof DualSignalContainer) return;

        int i1 = actualParent.indexOfChild(first);
        int i2 = actualParent.indexOfChild(second);
        if (i1 < 0 || i2 < 0) return;

        // Require proximity. This prevents accidentally grabbing a QS/mobile view elsewhere.
        if (Math.abs(i1 - i2) > 3) return;

        ViewGroup.LayoutParams lp1 = first.getLayoutParams();
        ViewGroup.LayoutParams lp2 = second.getLayoutParams();
        int insertAt = Math.min(i1, i2);

        DualSignalContainer stack = new DualSignalContainer(actualParent.getContext());
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER);
        stack.setClipChildren(false);
        stack.setClipToPadding(false);
        stack.setBaselineAligned(false);
        stack.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        // Preserve the outer footprint as much as possible. The children are scaled slightly
        // so two rows fit inside a normal status-bar height without forcing parent re-layout.
        int h = ViewGroup.LayoutParams.MATCH_PARENT;
        // Do not force a fixed width: large 5G/4G badges on OPlus builds otherwise get clipped.
        ViewGroup.LayoutParams outer = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, h);

        actualParent.removeView(first);
        actualParent.removeView(second);

        prepareChild(first);
        prepareChild(second);
        stack.addView(first, childLayout(lp1));
        stack.addView(second, childLayout(lp2));
        actualParent.addView(stack, Math.min(insertAt, actualParent.getChildCount()), outer);

        DONE.put(actualParent, Boolean.TRUE);
        Log.i(TAG, "stacked two mobile views: " + describe(first) + " + " + describe(second));
    }

    private static void prepareChild(View v) {
        v.setPivotX(0f);
        v.setPivotY(0f);
        v.setScaleX(0.72f);
        v.setScaleY(0.72f);
        // Scale does not affect measured size; translations compensate so rows visually tighten.
        v.setTranslationX(0f);
        v.setTranslationY(0f);
    }

    private static LinearLayout.LayoutParams childLayout(ViewGroup.LayoutParams old) {
        int width = old != null && old.width > 0 ? old.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, 0, 1f);
        lp.gravity = Gravity.CENTER;
        return lp;
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
            if (hit != null) out.add(child); // move the wrapper, preserving its internals
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
        if (view instanceof DualSignalContainer) return false;
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

        // Avoid generic ImageViews whose id merely contains "signal".
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

    /** Marker subclass prevents re-processing our own container. */
    private static final class DualSignalContainer extends LinearLayout {
        DualSignalContainer(android.content.Context context) {
            super(context);
        }
    }
}
