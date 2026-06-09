package com.w25watch.barphone;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "W25WatchHook";
    // W25 cover screen policy: COVER=has cover, WATCHFACE=watchface support, AOD=AOD on cover,
    // LOCKSCREEN=lock screen on cover, LARGESCREEN=large cover (W-series specific)
    private static final String SUBDISPLAY_POLICY =
            "COVER|WATCHFACE|AOD|LOCKSCREEN|LARGESCREEN";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        if ("com.android.systemui".equals(pkg)) {
            XposedBridge.log(TAG + ": hooking SystemUI");
            hookFloatingFeature(lpparam.classLoader);
            hookWindowManager(lpparam.classLoader);
            hookDisplayManager(lpparam.classLoader);
            hookSystemUIForPremiumWatchLaunch(lpparam.classLoader);
        } else if ("com.sec.android.app.premiumwatch".equals(pkg)) {
            XposedBridge.log(TAG + ": hooking PremiumWatch");
            hookWindowManager(lpparam.classLoader);
        } else if ("com.samsung.android.app.aodservice".equals(pkg)) {
            XposedBridge.log(TAG + ": hooking AODService");
            // Must hook SemFloatingFeature before forcing AbstractC0974St0 to load,
            // so its static init reads our W25 subdisplay policy instead of the real one.
            hookFloatingFeature(lpparam.classLoader);
            hookWindowManager(lpparam.classLoader);
            hookAbstractFlagsB(lpparam.classLoader);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 1: SemFloatingFeature -- makes AbstractC0974St0.D/E/G/I/J all true
    // -------------------------------------------------------------------------
    private void hookFloatingFeature(ClassLoader cl) {
        final String FF_CLASS = "com.samsung.android.feature.SemFloatingFeature";
        final String KEY = "SEC_FLOATING_FEATURE_LOCKSCREEN_CONFIG_SUBDISPLAY_POLICY";

        XC_MethodHook interceptor = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object arg0 = param.args[0];
                if (KEY.equals(arg0)) {
                    param.setResult(SUBDISPLAY_POLICY);
                    XposedBridge.log(TAG + ": intercepted getString(" + KEY + ")");
                }
            }
        };

        // getString(String key)
        try {
            XposedHelpers.findAndHookMethod(FF_CLASS, cl, "getString", String.class, interceptor);
            XposedBridge.log(TAG + ": hooked SemFloatingFeature.getString(String)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getString(String) not found: " + t);
        }

        // getString(String key, String defaultValue)
        try {
            XposedHelpers.findAndHookMethod(FF_CLASS, cl, "getString",
                    String.class, String.class, interceptor);
            XposedBridge.log(TAG + ": hooked SemFloatingFeature.getString(String,String)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getString(String,String) not found: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 2: SemWindowManager.isFolded() -- always returns true
    // The app calls this to decide whether to show cover screen AOD.
    // PremiumWatch also calls finish() if isFolded() is false.
    // -------------------------------------------------------------------------
    private void hookWindowManager(ClassLoader cl) {
        final String WM_CLASS = "com.samsung.android.view.SemWindowManager";

        try {
            XposedHelpers.findAndHookMethod(WM_CLASS, cl, "isFolded",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
            XposedBridge.log(TAG + ": hooked SemWindowManager.isFolded()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SemWindowManager.isFolded() not found: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 4: AbstractC0974St0.B -- forces "e5 model" flag to true
    //
    // AbstractC0974St0 has a static initializer that sets boolean flags from
    // SemFloatingFeature and Build.MODEL. Flag B requires model to contain "e5"
    // (W25 series), which is never true on S24. B=false means ClockInfoManager
    // (C4854zm) never calls t(TYPE_WATCHFACE_ANALOG_PREMIUM_WATCH, ...) so the
    // premium watch clock type (100014) is never registered.
    //
    // We call findClass() here which triggers the static init (with our
    // SemFloatingFeature hook already active, so D/E/G/I/J all get set to true),
    // then immediately override B=true via reflection.
    // -------------------------------------------------------------------------
    private void hookAbstractFlagsB(ClassLoader cl) {
        try {
            // AbstractC0974St0 in device AODService v8.9.x = aod.aB0 (classes3.dex)
            // Field B = "e5" model check; must be true to register TYPE_WATCHFACE_ANALOG_PREMIUM_WATCH
            Class<?> stClass = XposedHelpers.findClass("aod.aB0", cl);
            java.lang.reflect.Field fieldB = stClass.getDeclaredField("B");
            fieldB.setAccessible(true);
            fieldB.set(null, true);
            XposedBridge.log(TAG + ": set aod.aB0.B = true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to patch aod.aB0.B: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 5: Launch PremiumWatch on SCREEN_OFF (lock screen) for ~8 seconds
    //
    // On W25, folding the phone activates the cover screen immediately.
    // On a bar phone we listen for ACTION_SCREEN_OFF in SystemUI.
    // After 500 ms (enough for keyguard to engage), we launch PremiumWatch.
    // PremiumWatch has setTurnScreenOn(true) so it wakes the display itself,
    // and its built-in 8-second timer calls semGoToSleep() when done.
    // -------------------------------------------------------------------------
    private void hookSystemUIForPremiumWatchLaunch(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application", cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final Context ctx = (Context) param.thisObject;
                            final Handler handler = new Handler(Looper.getMainLooper());
                            // Cooldown: ignore SCREEN_OFF for 30 s after each launch
                            // to prevent PremiumWatch's own semGoToSleep() from re-triggering.
                            final long[] lastLaunch = {0L};
                            final long COOLDOWN_MS = 30000L;
                            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                            ctx.registerReceiver(new BroadcastReceiver() {
                                @Override
                                public void onReceive(final Context context, Intent intent) {
                                    final long now = android.os.SystemClock.elapsedRealtime();
                                    if (now - lastLaunch[0] < COOLDOWN_MS) {
                                        XposedBridge.log(TAG + ": SCREEN_OFF ignored (cooldown)");
                                        return;
                                    }
                                    // Delay 500 ms so keyguard has time to lock
                                    handler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                int enabled = Settings.System.getInt(
                                                        context.getContentResolver(),
                                                        "premium_watch_switch_onoff", 0);
                                                if (enabled != 1) return;
                                                KeyguardManager km = (KeyguardManager)
                                                        context.getSystemService(Context.KEYGUARD_SERVICE);
                                                if (km == null || !km.isKeyguardLocked()) return;
                                                lastLaunch[0] = android.os.SystemClock.elapsedRealtime();
                                                Intent watchIntent = new Intent();
                                                watchIntent.setComponent(new ComponentName(
                                                        "com.sec.android.app.premiumwatch",
                                                        "com.sec.android.app.premiumwatch.activity.PremiumWatch"));
                                                watchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                                context.startActivity(watchIntent);
                                                XposedBridge.log(TAG + ": launched PremiumWatch (SCREEN_OFF+locked)");
                                            } catch (Throwable t) {
                                                XposedBridge.log(TAG + ": PremiumWatch launch failed: " + t);
                                            }
                                        }
                                    }, 500);
                                }
                            }, filter);
                            XposedBridge.log(TAG + ": registered SCREEN_OFF receiver in SystemUI");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookSystemUIForPremiumWatchLaunch failed: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 3: DisplayManager.getDisplay(int) -- maps cover display ID to 0
    //
    // On foldables, cover screen has a separate display ID (EXTRA_BUILT_IN_DISPLAY).
    // On bar phones that field doesn't exist (returns -1), so getDisplay(-1) = null.
    // With G=true the app calls getDisplay(EXTRA_BUILT_IN_DISPLAY) to get the cover
    // display; we redirect any null result back to display 0.
    // -------------------------------------------------------------------------
    private void hookDisplayManager(ClassLoader cl) {
        // Use ThreadLocal to prevent re-entrant calls when we call getDisplay(0) ourselves
        final ThreadLocal<Boolean> redirecting = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() { return false; }
        };

        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.display.DisplayManager", cl,
                    "getDisplay", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.getResult() == null && !redirecting.get()) {
                                redirecting.set(true);
                                try {
                                    // Return the bar phone's primary display instead
                                    Object dm = param.thisObject;
                                    Object display = XposedHelpers.callMethod(dm, "getDisplay", 0);
                                    param.setResult(display);
                                    XposedBridge.log(TAG + ": redirected getDisplay("
                                            + param.args[0] + ") -> display 0");
                                } finally {
                                    redirecting.set(false);
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked DisplayManager.getDisplay(int)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DisplayManager.getDisplay() not found: " + t);
        }
    }
}
