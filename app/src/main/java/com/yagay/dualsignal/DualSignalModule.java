package com.yagay.dualsignal;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * ColorOS / OxygenOS 16 dual-SIM signal as a single iOS-style dual-row icon (LSPosed).
 *
 * v1.7: One DualRowSignalDrawable (top=SIM1, bottom=SIM2). Collapse right slot width.
 */
public final class DualSignalModule extends XposedModule {
    private static final String TAG = "DualSignal102";
    private static final String TARGET = "com.android.systemui";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile DualSignalModule INSTANCE;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<ViewGroup> WATCHED_ROOTS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, PairState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> SAVED_WIDTH =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> LAST_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LOGGED =
            Collections.synchronizedSet(new java.util.HashSet<>());
    private static final AtomicInteger DIAGNOSTIC_COUNT = new AtomicInteger();
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();

    private static final int MAX_SCAN_DEPTH = 16;
    private static final int MAX_RETRIES = 30;
    private static final long RETRY_MS = 300L;
    private static final int MIN_CANDIDATE_SCORE = 40;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        INSTANCE = this;
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

            try {
                Method setDrawable = ImageView.class.getDeclaredMethod("setImageDrawable", Drawable.class);
                hook(setDrawable).intercept(ImageDrawableHook.INSTANCE);
            } catch (Throwable ignored) {}
            try {
                Method setLevel = ImageView.class.getDeclaredMethod("setImageLevel", int.class);
                hook(setLevel).intercept(ImageLevelHook.INSTANCE);
            } catch (Throwable ignored) {}

            log(Log.INFO, TAG, "dual-row signal hook installed v1.7.1 (fix missing icon)");
            diagnostic(currentApplication(), "I", "HOOK_INSTALLED",
                    "mode=dual-row-drawable maxDepth=" + MAX_SCAN_DEPTH +
                            " retries=" + MAX_RETRIES + " minScore=" + MIN_CANDIDATE_SCORE);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed: " + t);
            diagnostic(currentApplication(), "E", "HOOK_INSTALL_FAILED", stackSummary(t));
        }
    }

    public static final class ImageDrawableHook implements XposedInterface.Hooker {
        static final ImageDrawableHook INSTANCE = new ImageDrawableHook();
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object self = chain.getThisObject();
            // Capture level from the incoming system drawable before we replace it.
            if (!Boolean.TRUE.equals(APPLYING.get()) && self instanceof ImageView iv) {
                try {
                    List<Object> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof Drawable d) {
                        int lvl = DualRowSignalDrawable.normalizeLevel(d.getLevel());
                        LAST_LEVEL.put(iv, lvl);
                    }
                } catch (Throwable ignored) {}
            }
            Object result = chain.proceed();
            if (!Boolean.TRUE.equals(APPLYING.get()) && self instanceof View v) {
                reapplyFor(v);
            }
            return result;
        }
    }

    public static final class ImageLevelHook implements XposedInterface.Hooker {
        static final ImageLevelHook INSTANCE = new ImageLevelHook();
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object self = chain.getThisObject();
            if (!Boolean.TRUE.equals(APPLYING.get()) && self instanceof ImageView iv) {
                try {
                    List<Object> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof Integer) {
                        LAST_LEVEL.put(iv, DualRowSignalDrawable.normalizeLevel((Integer) args.get(0)));
                    }
                } catch (Throwable ignored) {}
            }
            Object result = chain.proceed();
            if (!Boolean.TRUE.equals(APPLYING.get()) && self instanceof View v) {
                reapplyFor(v);
            }
            return result;
        }
    }

    private static void reapplyFor(View view) {
        PairState state = STATES.get(view);
        if (state == null) {
            ViewParent p = view.getParent();
            int d = 0;
            while (p instanceof View pv && d++ < 6) {
                state = STATES.get(pv);
                if (state != null) break;
                p = pv.getParent();
            }
        }
        if (state == null || state.root == null || !state.root.isAttachedToWindow()) return;
        if (!state.first.isAttachedToWindow() || !state.second.isAttachedToWindow()) return;
        state.schedule();
    }

    public static final class AttachHook implements XposedInterface.Hooker {
        static final AttachHook INSTANCE = new AttachHook();
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object self = chain.getThisObject();
                if (self instanceof View view) {
                    if (isMobileCandidate(view)) logCandidateOnce(view);
                    attachWatchers(view);
                }
            } catch (Throwable t) {
                diagnostic(currentApplication(), "E", "ATTACH_CALLBACK_FAILED", stackSummary(t));
            }
            return result;
        }
    }

    private static void attachWatchers(View view) {
        ViewParent p = view.getParent();
        int depth = 0;
        while (p instanceof ViewGroup group && depth++ < MAX_SCAN_DEPTH) {
            if (isStatusIconContainer(group)) {
                watchRoot(group);
                return;
            }
            p = group.getParent();
        }
        p = view.getParent();
        depth = 0;
        while (p instanceof ViewGroup group && depth++ < 8) {
            if (looksLikeStatusBarRoot(group)) {
                watchRoot(group);
                return;
            }
            p = group.getParent();
        }
    }

    private static void watchRoot(ViewGroup root) {
        if (!WATCHED_ROOTS.add(root)) return;
        if (isStatusIconContainer(root)) {
            diagnostic(root.getContext(), "I", "WATCH_ROOT", describeDetailed(root));
        }
        Runnable first = new Runnable() {
            int tries;
            @Override public void run() {
                if (!root.isAttachedToWindow()) return;
                tries++;
                scanRoot(root);
                if (tries < MAX_RETRIES && !hasActivePair(root)) {
                    MAIN.postDelayed(this, RETRY_MS);
                }
            }
        };
        root.post(first);
        try {
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    () -> {
                        if (root.isAttachedToWindow()) MAIN.post(() -> scanRoot(root));
                    });
        } catch (Throwable ignored) {}
    }

    private static boolean hasActivePair(ViewGroup root) {
        synchronized (STATES) {
            for (PairState state : STATES.values()) {
                if (state != null && state.root == root
                        && state.first.isAttachedToWindow() && state.second.isAttachedToWindow()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void scanRoot(ViewGroup root) {
        try {
            List<View> candidates = collectCandidates(root);
            Pair pair = choosePair(root, candidates);
            if (pair == null) {
                logScanOnce(root, candidates);
                return;
            }
            View first = pair.first;
            View second = pair.second;
            if (first == second) return;

            PairState existing = STATES.get(first);
            if (existing != null && existing.root == root && existing.contains(second)) {
                existing.schedule();
                return;
            }

            PairState state = new PairState(root, first, second);
            STATES.put(first, state);
            STATES.put(second, state);
            ImageView iv1 = findSignalImage(first);
            ImageView iv2 = findSignalImage(second);
            if (iv1 != null) STATES.put(iv1, state);
            if (iv2 != null) STATES.put(iv2, state);

            first.addOnLayoutChangeListener(state);
            second.addOnLayoutChangeListener(state);

            diagnostic(root.getContext(), "I", "PAIR_FOUND",
                    "root=" + describeDetailed(root)
                            + " first=" + describeDetailed(first)
                            + " second=" + describeDetailed(second));
            state.schedule();
        } catch (Throwable t) {
            diagnostic(root.getContext(), "E", "SCAN_FAILED", stackSummary(t));
        }
    }

    private static List<View> collectCandidates(ViewGroup root) {
        ArrayList<View> out = new ArrayList<>();
        collect(root, 0, out);
        out.sort(Comparator.comparingInt(DualSignalModule::candidateScore).reversed());
        return out;
    }

    private static void collect(View view, int depth, List<View> out) {
        if (depth > MAX_SCAN_DEPTH || out.size() >= 80) return;
        if (view.getVisibility() == View.VISIBLE && candidateScore(view) >= MIN_CANDIDATE_SCORE) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static Pair choosePair(ViewGroup root, List<View> candidates) {
        ArrayList<View> leaves = new ArrayList<>();
        ArrayList<View> combos = new ArrayList<>();
        for (View c : candidates) {
            String id = resourceEntryName(c).toLowerCase(Locale.ROOT);
            String cls = c.getClass().getName().toLowerCase(Locale.ROOT);
            if (id.contains("mobile_signal")
                    || (cls.contains("animatedimageview") && id.contains("signal"))) {
                leaves.add(c);
            } else if (cls.contains("oplusmodernstatusbar") || id.contains("mobile_combo")
                    || cls.contains("statusbarmobile")) {
                combos.add(c);
            }
        }
        for (View combo : combos) collectSignalLeaves(combo, 0, leaves);

        ArrayList<View> unique = new ArrayList<>();
        for (View v : leaves) {
            if (v.getVisibility() != View.VISIBLE) continue;
            boolean dup = false;
            for (View u : unique) if (u == v) { dup = true; break; }
            if (!dup) unique.add(v);
        }

        if (unique.size() >= 2) {
            ArrayList<View> under = new ArrayList<>();
            for (View v : unique) {
                if (branchUnder(root, v) != null || isUnderStatusBar(v)) under.add(v);
            }
            List<View> pool = under.size() >= 2 ? under : unique;
            pool.sort(Comparator.comparingInt(DualSignalModule::screenX));
            for (int i = 0; i < pool.size(); i++) {
                for (int j = i + 1; j < pool.size(); j++) {
                    View a = pool.get(i), b = pool.get(j);
                    if (isAncestor(a, b) || isAncestor(b, a)) continue;
                    if (!looksLikeMobilePair(a, b)) continue;
                    return orderedPair(a, b);
                }
            }
        }

        if (combos.size() >= 2) {
            combos.sort(Comparator.comparingInt(DualSignalModule::screenX));
            for (int i = 0; i < combos.size(); i++) {
                for (int j = i + 1; j < combos.size(); j++) {
                    View a = combos.get(i), b = combos.get(j);
                    if (isAncestor(a, b) || isAncestor(b, a)) continue;
                    return orderedPair(a, b);
                }
            }
        }

        ArrayList<View> best = new ArrayList<>(2);
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                View a = candidates.get(i), b = candidates.get(j);
                if (!looksLikeMobilePair(a, b)) continue;
                best.clear();
                best.add(a);
                best.add(b);
                break;
            }
            if (best.size() == 2) break;
        }
        if (best.size() != 2) return null;
        return orderedPair(best.get(0), best.get(1));
    }

    private static Pair orderedPair(View a, View b) {
        if (screenX(a) <= screenX(b)) return new Pair(a, b);
        return new Pair(b, a);
    }

    private static void collectSignalLeaves(View view, int depth, List<View> out) {
        if (depth > 8 || out.size() >= 16) return;
        String id = resourceEntryName(view).toLowerCase(Locale.ROOT);
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        if (view.getVisibility() == View.VISIBLE) {
            if (id.contains("mobile_signal")
                    || (cls.contains("animatedimageview") && id.contains("signal"))
                    || (cls.contains("imageview") && id.contains("signal") && !id.contains("wifi"))) {
                out.add(view);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSignalLeaves(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static void applyDualRow(ViewGroup root, View first, View second) {
        if (!first.isAttachedToWindow() || !second.isAttachedToWindow()) return;
        if (Boolean.TRUE.equals(APPLYING.get())) return;
        try {
            final boolean keyguard = isUnderKeyguard(root) || isUnderKeyguard(first);

            ImageView iv1 = findSignalImage(first);
            ImageView iv2 = findSignalImage(second);
            int level1 = readSignalLevel(iv1 != null ? iv1 : first);
            int level2 = readSignalLevel(iv2 != null ? iv2 : second);
            // Never show a fully blank dual icon: if both levels unknown, show mid bars
            // so the slot stays visible until SystemUI reports real levels.
            if (level1 == 0 && level2 == 0) {
                level1 = 3;
                level2 = 2;
            }

            ImageView host = iv1 != null ? iv1 : (first instanceof ImageView ? (ImageView) first : null);
            if (host == null) host = findAnyImage(first);
            if (host == null) {
                diagnostic(root.getContext(), "W", "NO_HOST_IMAGE", "first=" + describeDetailed(first));
                return;
            }

            // Ensure host stays visible and has non-zero layout size.
            if (host.getVisibility() != View.VISIBLE) host.setVisibility(View.VISIBLE);
            if (first.getVisibility() != View.VISIBLE) first.setVisibility(View.VISIBLE);

            DualRowSignalDrawable dual;
            Drawable cur = host.getDrawable();
            if (cur instanceof DualRowSignalDrawable) {
                dual = (DualRowSignalDrawable) cur;
            } else {
                dual = new DualRowSignalDrawable();
            }
            dual.setLevels(level1, level2);

            APPLYING.set(Boolean.TRUE);
            try {
                host.setImageDrawable(dual);
                host.invalidate();
                // Only clear the RIGHT sim glyph — do not GONE/INVISIBLE the whole branch
                // until width collapse is applied; use alpha to hide glyph only.
                if (iv2 != null && iv2 != host) {
                    iv2.setImageDrawable(null);
                    iv2.setAlpha(0f);
                }
            } finally {
                APPLYING.remove();
            }

            int collapsed = -1;
            if (!keyguard) {
                ViewGroup container = findStatusContainer(root, first, second);
                View branchRight = container != null
                        ? branchUnder(container, second) : branchUnder(root, second);
                if (branchRight == null) branchRight = second;
                // Never collapse the branch that still hosts the dual icon.
                if (branchRight == first || isAncestor(branchRight, host) || branchRight == host) {
                    diagnostic(root.getContext(), "W", "SKIP_COLLAPSE_HOST",
                            "branchR=" + describeDetailed(branchRight));
                } else {
                    int rw = Math.max(second.getWidth(), second.getMeasuredWidth());
                    if (rw <= 1) {
                        Integer sw = SAVED_WIDTH.get(branchRight);
                        rw = sw != null ? sw : 60;
                    }
                    collapsed = collapseLayoutWidth(branchRight, rw);
                }
            } else {
                View branchRight = branchUnder(root, second);
                if (branchRight != null) restoreLayoutWidth(branchRight);
                if (iv2 != null) iv2.setAlpha(0f);
            }

            diagnostic(root.getContext(), "I", "STACK_APPLIED",
                    "mode=dual-row-drawable keyguard=" + keyguard
                            + " L1=" + level1 + " L2=" + level2
                            + " collapsedW=" + collapsed
                            + " host=" + describeDetailed(host)
                            + " hostSize=" + host.getWidth() + "x" + host.getHeight()
                            + " first=" + describeDetailed(first)
                            + " second=" + describeDetailed(second));
        } catch (Throwable t) {
            diagnostic(root.getContext(), "E", "STACK_APPLY_FAILED", stackSummary(t));
        }
    }

    private static ImageView findSignalImage(View root) {
        if (root instanceof ImageView iv) return iv;
        if (!(root instanceof ViewGroup group)) return null;
        ImageView best = null;
        int bestScore = -1;
        ArrayList<View> stack = new ArrayList<>();
        stack.add(group);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 40) {
            View v = stack.remove(stack.size() - 1);
            if (v instanceof ImageView iv) {
                int sc = candidateScore(iv);
                String id = resourceEntryName(iv).toLowerCase(Locale.ROOT);
                if (id.contains("mobile_signal")) sc += 50;
                if (sc > bestScore) {
                    bestScore = sc;
                    best = iv;
                }
            } else if (v instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return best;
    }

    private static ImageView findAnyImage(View root) {
        if (root instanceof ImageView) return (ImageView) root;
        if (!(root instanceof ViewGroup group)) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = findAnyImage(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static int readSignalLevel(View view) {
        try {
            if (view instanceof ImageView iv) {
                Integer cached = LAST_LEVEL.get(iv);
                if (cached != null && cached > 0) return cached;
                Drawable d = iv.getDrawable();
                if (d instanceof DualRowSignalDrawable) {
                    // Already our drawable — keep previous cached if any
                    if (cached != null) return cached;
                } else if (d != null) {
                    int lvl = d.getLevel();
                    if (lvl > 0) {
                        int n = DualRowSignalDrawable.normalizeLevel(lvl);
                        LAST_LEVEL.put(iv, n);
                        return n;
                    }
                }
                if (cached != null) return cached;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int collapseLayoutWidth(View branch, int originalWidth) {
        if (branch == null) return -1;
        try {
            ViewGroup.LayoutParams lp = branch.getLayoutParams();
            if (lp == null) return -1;
            SAVED_WIDTH.putIfAbsent(branch, lp.width != 0 ? lp.width : originalWidth);
            if (lp.width == 0) return 0;
            lp.width = 0;
            branch.setLayoutParams(lp);
            ViewParent parent = branch.getParent();
            if (parent instanceof View) ((View) parent).requestLayout();
            return 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void restoreLayoutWidth(View branch) {
        if (branch == null) return;
        try {
            Integer w = SAVED_WIDTH.get(branch);
            if (w == null) return;
            ViewGroup.LayoutParams lp = branch.getLayoutParams();
            if (lp == null || lp.width == w) return;
            lp.width = w;
            branch.setLayoutParams(lp);
        } catch (Throwable ignored) {}
    }

    private static ViewGroup findStatusContainer(ViewGroup root, View first, View second) {
        ViewParent p = first.getParent();
        int depth = 0;
        while (p instanceof ViewGroup group && depth++ < 12) {
            if (isStatusIconContainer(group)
                    && (branchUnder(group, second) != null || isAncestor(group, second))) {
                return group;
            }
            p = group.getParent();
        }
        return root;
    }

    private static boolean isUnderKeyguard(View start) {
        if (start != null) {
            String id0 = resourceEntryName(start).toLowerCase(Locale.ROOT);
            String cls0 = start.getClass().getName().toLowerCase(Locale.ROOT);
            if (cls0.contains("keyguard") || id0.contains("keyguard")) return true;
        }
        ViewParent p = start != null ? start.getParent() : null;
        int depth = 0;
        while (p instanceof ViewGroup group && depth++ < 16) {
            String id = resourceEntryName(group).toLowerCase(Locale.ROOT);
            String cls = group.getClass().getName().toLowerCase(Locale.ROOT);
            if (cls.contains("keyguard") || id.contains("keyguard")) return true;
            p = group.getParent();
        }
        return false;
    }

    private static boolean isStatusIconContainer(ViewGroup v) {
        String cls = v.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceEntryName(v).toLowerCase(Locale.ROOT);
        if (cls.contains("statusiconcontainer")) return true;
        return id.equals("statusicons") || id.equals("status_icons") || id.equals("status_icon_container");
    }

    private static boolean isMobileCandidate(View view) {
        return candidateScore(view) >= MIN_CANDIDATE_SCORE;
    }

    private static int candidateScore(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        String simple = view.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String id = resourceEntryName(view).toLowerCase(Locale.ROOT);
        if ((id.contains("mobile_type") || id.contains("network_type") || id.contains("data_type")
                || simple.contains("networktype"))
                && !id.contains("signal") && !cls.contains("signal")) {
            return 0;
        }
        int score = 0;
        if ("mobile_signal".equals(id) || id.endsWith("_mobile_signal")) score += 200;
        if (cls.contains("animatedimageview") && id.contains("signal")) score += 150;
        if (cls.contains("oplusmodernstatusbar") || simple.contains("oplusmodernstatusbarmobile")) score += 180;
        if (id.contains("mobile_combo")) score += 120;
        if (id.contains("mobile_signal") || id.contains("cellular_signal")) score += 120;
        if (cls.contains("statusbarmobile") || cls.contains("mobilesignal")) score += 120;
        if (cls.contains("signal") && (cls.contains("mobile") || cls.contains("oplus"))) score += 100;
        if (view instanceof ViewGroup) {
            boolean explicit = cls.contains("oplusmodernstatusbar") || cls.contains("statusbarmobile")
                    || id.contains("mobile_combo");
            score += explicit ? 20 : -100;
        }
        return Math.max(score, 0);
    }

    private static boolean isUnderStatusBar(View v) {
        ViewParent p = v.getParent();
        int depth = 0;
        while (p instanceof View && depth++ < 16) {
            View pv = (View) p;
            String id = resourceEntryName(pv).toLowerCase(Locale.ROOT);
            String cls = pv.getClass().getName().toLowerCase(Locale.ROOT);
            if (cls.contains("statusiconcontainer") || id.equals("statusicons")
                    || id.equals("system_icons") || cls.contains("phonestatusbarview")) {
                return true;
            }
            p = pv.getParent();
        }
        return false;
    }

    private static boolean looksLikeMobilePair(View a, View b) {
        return candidateScore(a) >= MIN_CANDIDATE_SCORE
                && candidateScore(b) >= MIN_CANDIDATE_SCORE
                && !isAncestor(a, b) && !isAncestor(b, a);
    }

    private static View branchUnder(ViewGroup root, View view) {
        View current = view;
        ViewParent parent = current.getParent();
        while (parent instanceof View && parent != root) {
            current = (View) parent;
            parent = current.getParent();
        }
        return parent == root ? current : null;
    }

    private static boolean isAncestor(View possibleAncestor, View view) {
        ViewParent p = view.getParent();
        while (p instanceof View) {
            if (p == possibleAncestor) return true;
            p = p.getParent();
        }
        return false;
    }

    private static int screenX(View view) {
        int[] p = new int[2];
        view.getLocationOnScreen(p);
        return p[0];
    }

    private static boolean looksLikeStatusBarRoot(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceEntryName(view).toLowerCase(Locale.ROOT);
        if (cls.contains("phonestatusbarview") || id.equals("status_bar")) return true;
        if (id.equals("system_icons") || id.equals("status_bar_end_side_content")) return true;
        return cls.contains("statusiconcontainer");
    }

    private static void logCandidateOnce(View v) {
        String key = v.getClass().getName() + "#" + resourceEntryName(v);
        if (!LOGGED.add("C:" + key)) return;
        diagnostic(v.getContext(), "I", "CANDIDATE",
                "score=" + candidateScore(v) + " " + describeDetailed(v));
    }

    private static void logScanOnce(ViewGroup root, List<View> candidates) {
        String key = "S:" + root.getClass().getName() + "#" + resourceEntryName(root);
        if (!LOGGED.add(key)) return;
        StringBuilder sb = new StringBuilder();
        sb.append("root=").append(describeDetailed(root))
                .append(" candidateCount=").append(candidates.size());
        int limit = Math.min(10, candidates.size());
        for (int i = 0; i < limit; i++) {
            View c = candidates.get(i);
            sb.append(" | [").append(i).append("] score=").append(candidateScore(c))
                    .append(" ").append(describeDetailed(c));
        }
        diagnostic(root.getContext(), "I", "PAIR_NOT_FOUND", sb.toString());
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

    private static String describeDetailed(View v) {
        return v.getClass().getName() + "#" + resourceEntryName(v)
                + " vis=" + v.getVisibility()
                + " attached=" + v.isAttachedToWindow()
                + " bounds=" + v.getLeft() + "," + v.getTop() + "-" + v.getRight() + "," + v.getBottom()
                + " size=" + v.getWidth() + "x" + v.getHeight();
    }

    private static void diagnostic(Context context, String level, String event, String detail) {
        if (!"E".equals(level) && DIAGNOSTIC_COUNT.incrementAndGet() > 400) return;
        String msg = event + ": " + detail;
        try {
            DualSignalModule m = INSTANCE;
            if (m != null) {
                int prio = "E".equals(level) ? Log.ERROR : ("W".equals(level) ? Log.WARN : Log.INFO);
                m.log(prio, TAG, msg);
            } else {
                android.util.Log.println("E".equals(level) ? Log.ERROR : Log.INFO, TAG, msg);
            }
        } catch (Throwable ignored) {}
        try {
            Diagnostics.record(context, level, event, detail);
        } catch (Throwable ignored) {}
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stackSummary(Throwable t) {
        StringBuilder s = new StringBuilder(t.toString());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) {
            s.append(" <- ").append(st[i]);
        }
        return s.toString();
    }

    private static final class Pair {
        final View first, second;
        Pair(View first, View second) {
            this.first = first;
            this.second = second;
        }
    }

    private static final class PairState implements View.OnLayoutChangeListener {
        final ViewGroup root;
        final View first, second;
        boolean scheduled;

        PairState(ViewGroup root, View first, View second) {
            this.root = root;
            this.first = first;
            this.second = second;
        }

        boolean contains(View v) {
            return v == first || v == second;
        }

        void schedule() {
            if (scheduled) return;
            scheduled = true;
            root.postOnAnimation(() -> {
                scheduled = false;
                applyDualRow(root, first, second);
            });
        }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b,
                                   int ol, int ot, int or, int ob) {
            if (l != ol || t != ot || r != or || b != ob) schedule();
        }
    }
}
