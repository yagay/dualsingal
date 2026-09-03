package com.yagay.dualsignal;

import android.content.Context;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final Set<ViewGroup> PENDING =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<String> LOGGED_CANDIDATES =
            Collections.synchronizedSet(new java.util.HashSet<>());
    private static final Map<ViewGroup, Boolean> LOGGED_SCANS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<View> TRANSFORMED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<View> CLAIMED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<String> LOGGED_STATUS_ROOTS =
            Collections.synchronizedSet(new java.util.HashSet<>());
    private static final Map<View, BaseTransform> BASE_TRANSFORMS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, PairState> PAIR_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicInteger DIAGNOSTIC_COUNT = new AtomicInteger();

    private static final float SIGNAL_SCALE = 0.66f;
    private static final int MAX_ANCESTOR_DEPTH = 5;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName());
        diagnostic(currentApplication(), "I", "MODULE_LOADED", "process=" + param.getProcessName());
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
            diagnostic(currentApplication(), "I", "HOOK_INSTALLED",
                    "View.onAttachedToWindow ancestorDepth=" + MAX_ANCESTOR_DEPTH
                            + " scale=" + SIGNAL_SCALE);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed: " + t);
            diagnostic(currentApplication(), "E", "HOOK_INSTALL_FAILED", stackSummary(t));
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
                } else if (self instanceof ViewGroup group && looksLikeStatusBarRoot(group)) {
                    logStatusRootOnce(group);
                }
            } catch (Throwable t) {
                Log.w(TAG, "attach callback ignored: " + t);
                diagnostic(currentApplication(), "E", "ATTACH_CALLBACK_FAILED", stackSummary(t));
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
        if (!PENDING.add(parent)) return;

        Handler handler = source.getHandler();
        Runnable action = () -> {
            PENDING.remove(parent);
            try {
                tryStack(parent);
            } catch (Throwable t) {
                Log.w(TAG, "stack attempt ignored: " + t);
                diagnostic(source.getContext(), "E", "STACK_ATTEMPT_FAILED", stackSummary(t));
            }
        };

        if (handler != null) handler.post(action);
        else new Handler(Looper.getMainLooper()).post(action);
    }

    private static void tryStack(ViewGroup parent) {
        List<View> mobiles = collectSignalCandidates(parent);
        List<View> pair = pickDistinctPair(parent, mobiles);
        if (pair.size() != 2) {
            if (LOGGED_SCANS.put(parent, Boolean.TRUE) == null) {
                diagnostic(parent.getContext(), "I", "PAIR_NOT_FOUND",
                        "parent=" + describeDetailed(parent) + " candidateCount=" + mobiles.size()
                                + " descendants=" + dumpChildren(parent, 0));
            }
            return;
        }
        View first = pair.get(0);
        View second = pair.get(1);
        if (TRANSFORMED.contains(first) && TRANSFORMED.contains(second)) return;
        if (CLAIMED.contains(first) || CLAIMED.contains(second)) return;
        if (first == second) { reject(parent, "same view selected"); return; }
        Log.i(TAG, "pair found parent=" + describe(parent)
                + " first=" + describe(first)
                + " second=" + describe(second));
        diagnostic(parent.getContext(), "I", "PAIR_FOUND", "parent=" + describeDetailed(parent)
                + " first=" + describeDetailed(first) + " second=" + describeDetailed(second));
        PairState state = new PairState(parent, first, second);
        CLAIMED.add(first);
        CLAIMED.add(second);
        PAIR_STATES.put(first, state);
        PAIR_STATES.put(second, state);
        first.addOnLayoutChangeListener(state);
        second.addOnLayoutChangeListener(state);
        state.schedule();
    }

    private static void applyVisualStack(ViewGroup parent, View first, View second) {
        try {
            if (!first.isAttachedToWindow() || !second.isAttachedToWindow()) {
                reject(parent, "pair detached before transform"); return;
            }
            int w1 = Math.max(first.getWidth(), first.getMeasuredWidth());
            int w2 = Math.max(second.getWidth(), second.getMeasuredWidth());
            int h1 = Math.max(first.getHeight(), first.getMeasuredHeight());
            int h2 = Math.max(second.getHeight(), second.getMeasuredHeight());
            if (w1 <= 0 || w2 <= 0 || h1 <= 0 || h2 <= 0) {
                diagnostic(parent.getContext(), "I", "WAITING_FOR_LAYOUT",
                        "sizes=" + w1 + "x" + h1 + "," + w2 + "x" + h2);
                return;
            }
            BaseTransform base1 = BASE_TRANSFORMS.computeIfAbsent(first, BaseTransform::new);
            BaseTransform base2 = BASE_TRANSFORMS.computeIfAbsent(second, BaseTransform::new);
            first.setPivotX(first.getWidth() / 2f);
            first.setPivotY(first.getHeight() / 2f);
            second.setPivotX(second.getWidth() / 2f);
            second.setPivotY(second.getHeight() / 2f);
            first.setScaleX(base1.scaleX * SIGNAL_SCALE);
            first.setScaleY(base1.scaleY * SIGNAL_SCALE);
            second.setScaleX(base2.scaleX * SIGNAL_SCALE);
            second.setScaleY(base2.scaleY * SIGNAL_SCALE);

            // Use screen coordinates rather than only getLeft(), because OPlus wrappers
            // can sit inside translated/intermediate containers.
            int[] loc1 = new int[2];
            int[] loc2 = new int[2];
            first.getLocationOnScreen(loc1);
            second.getLocationOnScreen(loc2);
            float center1 = loc1[0] + first.getWidth() / 2f - (first.getTranslationX() - base1.translationX);
            float center2 = loc2[0] + second.getWidth() / 2f - (second.getTranslationX() - base2.translationX);
            float targetCenter = Math.min(center1, center2) + Math.min(w1, w2) * 0.30f;
            float centerY1 = loc1[1] + first.getHeight() / 2f - (first.getTranslationY() - base1.translationY);
            float centerY2 = loc2[1] + second.getHeight() / 2f - (second.getTranslationY() - base2.translationY);
            float targetCenterY = (centerY1 + centerY2) / 2f;
            float rowOffset = Math.max(2f, Math.min(h1, h2) * 0.24f);
            first.setTranslationX(base1.translationX + targetCenter - center1);
            second.setTranslationX(base2.translationX + targetCenter - center2);
            first.setTranslationY(base1.translationY + targetCenterY - rowOffset - centerY1);
            second.setTranslationY(base2.translationY + targetCenterY + rowOffset - centerY2);
            TRANSFORMED.add(first);
            TRANSFORMED.add(second);

            Log.i(TAG, "safe-stacked two mobile views: "
                    + describe(first) + " + " + describe(second));
            diagnostic(parent.getContext(), "I", "STACK_APPLIED",
                    "first=" + describeDetailed(first) + " loc=" + loc1[0] + "," + loc1[1]
                            + " tx=" + first.getTranslationX() + " ty=" + first.getTranslationY()
                            + " second=" + describeDetailed(second) + " loc=" + loc2[0] + "," + loc2[1]
                            + " tx=" + second.getTranslationX() + " ty=" + second.getTranslationY());
        } catch (Throwable t) {
            Log.w(TAG, "visual stack ignored: " + t);
            diagnostic(parent.getContext(), "E", "STACK_APPLY_FAILED", stackSummary(t));
        }
    }

    private static List<View> collectSignalCandidates(View root) {
        ArrayList<View> all = new ArrayList<>();
        collectSignalCandidates(root, 0, all);
        all.sort((a, b) -> Integer.compare(candidateScore(b), candidateScore(a)));
        ArrayList<View> pruned = new ArrayList<>();
        for (View candidate : all) {
            boolean duplicateBranch = false;
            for (View kept : pruned) {
                if (isAncestor(candidate, kept) || isAncestor(kept, candidate)) {
                    duplicateBranch = true;
                    break;
                }
            }
            if (!duplicateBranch) pruned.add(candidate);
        }
        return pruned;
    }

    private static void collectSignalCandidates(View view, int depth, List<View> out) {
        if (depth > 5 || out.size() >= 32) return;
        if (view.getVisibility() == View.VISIBLE && candidateScore(view) > 0) out.add(view);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSignalCandidates(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static List<View> pickDistinctPair(ViewGroup root, List<View> candidates) {
        ArrayList<View> pair = new ArrayList<>(2);
        View firstBranch = null;
        for (View candidate : candidates) {
            View branch = branchUnder(root, candidate);
            if (branch == null) continue;
            if (pair.isEmpty()) {
                pair.add(candidate);
                firstBranch = branch;
            } else if (branch != firstBranch) {
                pair.add(candidate);
                break;
            }
        }
        if (pair.size() == 2) {
            pair.sort((a, b) -> Integer.compare(screenX(a), screenX(b)));
        }
        return pair;
    }

    private static View branchUnder(ViewGroup root, View view) {
        View current = view;
        ViewParent parent = current.getParent();
        while (parent instanceof View next && parent != root) {
            current = next;
            parent = next.getParent();
        }
        return parent == root ? current : null;
    }

    private static boolean isAncestor(View possibleAncestor, View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == possibleAncestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static int screenX(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[0];
    }

    private static boolean isMobileCandidate(View view) {
        return candidateScore(view) > 0;
    }

    private static int candidateScore(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceEntryName(view).toLowerCase(Locale.ROOT);
        if ((id.contains("mobile_type") || id.contains("network_type") || id.contains("data_type"))
                && !id.contains("signal")) return 0;
        int score = 0;
        if (id.contains("mobile_signal") || id.contains("cellular_signal")
                || id.contains("signal_icon") || id.contains("signal_strength")) score += 120;
        if (cls.contains("mobilesignal") || cls.contains("cellularsignal")
                || (cls.contains("signal") && cls.contains("mobile"))) score += 100;
        if (cls.contains("statusbarmobileview")) score += 75;
        if (id.contains("mobile_icon") || id.contains("status_bar_mobile")) score += 60;
        if (view instanceof ViewGroup) score -= 20;
        if (id.contains("group") || id.contains("container") || id.contains("combo")) score -= 30;
        return Math.max(score, 0);
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
            diagnostic(v.getContext(), "I", "CANDIDATE", describeDetailed(v) + " parents=" + parents);
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

    private static String describeDetailed(View v) {
        return v.getClass().getName() + "#" + resourceEntryName(v)
                + " vis=" + v.getVisibility() + " attached=" + v.isAttachedToWindow()
                + " bounds=" + v.getLeft() + "," + v.getTop() + "-" + v.getRight() + "," + v.getBottom();
    }

    private static String dumpChildren(View view, int depth) {
        if (depth > 3) return "...";
        StringBuilder out = new StringBuilder(describeDetailed(view));
        if (view instanceof ViewGroup group) {
            out.append('[');
            int limit = Math.min(group.getChildCount(), 16);
            for (int i = 0; i < limit; i++) {
                if (i > 0) out.append("; ");
                out.append(dumpChildren(group.getChildAt(i), depth + 1));
            }
            if (group.getChildCount() > limit) out.append("; +").append(group.getChildCount() - limit);
            out.append(']');
        }
        String value = out.toString();
        return value.length() > 6000 ? value.substring(0, 6000) + "..." : value;
    }

    private static void reject(ViewGroup parent, String reason) {
        if (LOGGED_SCANS.put(parent, Boolean.TRUE) == null) {
            diagnostic(parent.getContext(), "I", "PAIR_REJECTED", reason);
        }
    }

    private static boolean looksLikeStatusBarRoot(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceEntryName(view).toLowerCase(Locale.ROOT);
        return cls.contains("statusbar") || id.contains("status_bar") || id.contains("system_icons");
    }

    private static void logStatusRootOnce(ViewGroup root) {
        try {
            String key = root.getClass().getName() + "#" + resourceEntryName(root);
            if (LOGGED_STATUS_ROOTS.size() >= 12 || !LOGGED_STATUS_ROOTS.add(key)) return;
            diagnostic(root.getContext(), "I", "STATUS_BAR_STRUCTURE", dumpChildren(root, 0));
        } catch (Throwable ignored) {}
    }

    private static void diagnostic(Context context, String level, String event, String detail) {
        if (!"E".equals(level) && DIAGNOSTIC_COUNT.incrementAndGet() > 200) return;
        Diagnostics.record(context, level, event, detail);
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Context) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) { return null; }
    }

    private static String stackSummary(Throwable t) {
        StringBuilder out = new StringBuilder(t.toString());
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 8); i++) out.append(" <- ").append(stack[i]);
        return out.toString();
    }

    private static final class BaseTransform {
        final float translationX;
        final float translationY;
        final float scaleX;
        final float scaleY;
        BaseTransform(View view) {
            translationX = view.getTranslationX();
            translationY = view.getTranslationY();
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
        }
    }

    private static final class PairState implements View.OnLayoutChangeListener {
        private final ViewGroup root;
        private final View first;
        private final View second;
        private boolean scheduled;
        PairState(ViewGroup root, View first, View second) {
            this.root = root;
            this.first = first;
            this.second = second;
        }
        void schedule() {
            if (scheduled) return;
            scheduled = true;
            root.postOnAnimation(() -> {
                scheduled = false;
                applyVisualStack(root, first, second);
            });
        }
        @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                             int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) schedule();
        }
    }
}
