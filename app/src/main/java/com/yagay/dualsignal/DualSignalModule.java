package com.yagay.dualsignal;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Enables the native Android 16 stacked-mobile pipeline shipped in the target
 * OPlus SystemUI. No SystemUI View, Drawable or LayoutParams is modified.
 */
public final class DualSignalModule extends XposedModule {
    private static final String TAG = "DualSignal102";
    private static final String TARGET = "com.android.systemui";
    private static final String BINDABLE =
            "com.android.systemui.statusbar.pipeline.mobile.ui.StackedMobileBindableIcon";
    private static final String CLASSIC_INTERACTOR =
            "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl";
    private static final String KAIROS_INTERACTOR =
            "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorKairosImpl";

    private static volatile DualSignalModule instance;
    private static volatile boolean installed;
    private static volatile boolean applicationHookInstalled;
    private static volatile Object trueFlow;
    private static volatile Object trueState;
    private static final AtomicBoolean BIND_CALLED = new AtomicBoolean();
    private static final AtomicBoolean FLOW_CALLED = new AtomicBoolean();
    private static final AtomicBoolean STATE_CALLED = new AtomicBoolean();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        instance = this;
        moduleLog(Log.INFO, "MODULE_LOADED process=" + param.getProcessName());
        Diagnostics.record(null, "I", "MODULE_LOADED", "process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || !TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        moduleLog(Log.INFO, "PACKAGE_LOADED waiting-for-final-classloader");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        installNativeStackHooks(param.getClassLoader());
    }

    private synchronized void installNativeStackHooks(ClassLoader loader) {
        if (installed) return;
        try {
            installApplicationReadyHook(loader);
            Class<?> bindable = Class.forName(BINDABLE, false, loader);
            Method shouldBind = bindable.getDeclaredMethod("getShouldBindIcon");
            hook(shouldBind).intercept(ForceTrueHook.INSTANCE);

            int constructorHooks = 0;
            for (Constructor<?> constructor : bindable.getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length > 0 && Context.class.isAssignableFrom(types[0])) {
                    hook(constructor).intercept(BindableCreatedHook.INSTANCE);
                    constructorHooks++;
                }
            }

            int reactiveHooks = 0;
            reactiveHooks += hookIfPresent(loader, CLASSIC_INTERACTOR,
                    "isStackable", ForceTrueFlowHook.INSTANCE);
            reactiveHooks += hookIfPresent(loader, KAIROS_INTERACTOR,
                    "isStackable", ForceTrueStateHook.INSTANCE);
            if (reactiveHooks == 0) {
                throw new NoSuchMethodException("No native isStackable implementation found");
            }

            installed = true;
            moduleLog(Log.INFO, "HOOK_INSTALLED mode=native-stacked-mobile"
                    + " reactiveHooks=" + reactiveHooks + " constructorHooks=" + constructorHooks);
            Diagnostics.record(null, "I", "HOOK_INSTALLED",
                    "mode=native-stacked-mobile reactiveHooks=" + reactiveHooks
                            + " constructorHooks=" + constructorHooks);
        } catch (Throwable t) {
            moduleLog(Log.ERROR, "HOOK_INSTALL_FAILED " + stackSummary(t));
            Diagnostics.record(null, "E", "HOOK_INSTALL_FAILED", stackSummary(t));
        }
    }

    private void installApplicationReadyHook(ClassLoader loader) {
        if (applicationHookInstalled) return;
        try {
            Class<?> application = Class.forName("com.android.systemui.SystemUIApplication", false, loader);
            hook(application.getDeclaredMethod("onCreate")).intercept(ApplicationReadyHook.INSTANCE);
            applicationHookInstalled = true;
        } catch (Throwable t) {
            moduleLog(Log.WARN, "APPLICATION_HOOK_MISSING " + t);
        }
    }

    private int hookIfPresent(ClassLoader loader, String className, String methodName,
                              XposedInterface.Hooker hooker) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            Method method = type.getDeclaredMethod(methodName);
            hook(method).intercept(hooker);
            return 1;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            moduleLog(Log.WARN, "OPTIONAL_HOOK_MISSING " + className + "#" + methodName);
            return 0;
        }
    }

    public static final class ForceTrueHook implements XposedInterface.Hooker {
        static final ForceTrueHook INSTANCE = new ForceTrueHook();
        @Override public Object intercept(XposedInterface.Chain chain) {
            if (BIND_CALLED.compareAndSet(false, true)) {
                Diagnostics.record(currentApplication(), "I", "NATIVE_BIND_ENABLED",
                        "StackedMobileBindableIcon.getShouldBindIcon -> true");
            }
            return Boolean.TRUE;
        }
    }

    public static final class ForceTrueFlowHook implements XposedInterface.Hooker {
        static final ForceTrueFlowHook INSTANCE = new ForceTrueFlowHook();
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (FLOW_CALLED.compareAndSet(false, true)) {
                Diagnostics.record(currentApplication(), "I", "CLASSIC_STACK_ENABLED",
                        "MobileIconsInteractorImpl.isStackable -> true flow");
            }
            Object cached = trueFlow;
            if (cached != null) return cached;
            ClassLoader loader = chain.getThisObject().getClass().getClassLoader();
            Class<?> flowKt = Class.forName("kotlinx.coroutines.flow.FlowKt", true, loader);
            Method flowOf = flowKt.getMethod("flowOf", Object.class);
            cached = flowOf.invoke(null, Boolean.TRUE);
            trueFlow = cached;
            return cached;
        }
    }

    public static final class ForceTrueStateHook implements XposedInterface.Hooker {
        static final ForceTrueStateHook INSTANCE = new ForceTrueStateHook();
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (STATE_CALLED.compareAndSet(false, true)) {
                Diagnostics.record(currentApplication(), "I", "KAIROS_STACK_ENABLED",
                        "MobileIconsInteractorKairosImpl.isStackable -> true state");
            }
            Object cached = trueState;
            if (cached != null) return cached;
            ClassLoader loader = chain.getThisObject().getClass().getClassLoader();
            Class<?> stateKt = Class.forName("com.android.systemui.kairos.StateKt", true, loader);
            Method stateOf = stateKt.getMethod("stateOf", Object.class);
            cached = stateOf.invoke(null, Boolean.TRUE);
            trueState = cached;
            return cached;
        }
    }

    public static final class BindableCreatedHook implements XposedInterface.Hooker {
        static final BindableCreatedHook INSTANCE = new BindableCreatedHook();
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                if (arg instanceof Context context) {
                    Diagnostics.record(context, "I", "NATIVE_STACK_READY",
                            "StackedMobileBindableIcon constructed; native pipeline enabled");
                }
            } catch (Throwable t) {
                moduleLog(Log.WARN, "DIAGNOSTIC_DELIVERY_FAILED " + t);
            }
            return result;
        }
    }

    public static final class ApplicationReadyHook implements XposedInterface.Hooker {
        static final ApplicationReadyHook INSTANCE = new ApplicationReadyHook();
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (self instanceof Context context) {
                Diagnostics.record(context, "I", "SYSTEMUI_READY",
                        "SystemUIApplication.onCreate completed");
            }
            return result;
        }
    }

    private static void moduleLog(int priority, String message) {
        try {
            DualSignalModule module = instance;
            if (module != null) module.log(priority, TAG, message);
            else Log.println(priority, TAG, message);
        } catch (Throwable ignored) {}
    }

    private static String stackSummary(Throwable t) {
        StringBuilder out = new StringBuilder(t.toString());
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 8); i++) out.append(" <- ").append(stack[i]);
        return out.toString();
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Context) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
