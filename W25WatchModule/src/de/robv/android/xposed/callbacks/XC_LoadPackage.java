package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

public final class XC_LoadPackage extends XCallback {
    private XC_LoadPackage() {}

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;

        public LoadPackageParam(CopyOnWriteSortedSet<XC_LoadPackage> registeredHooks) {
            super(null);
        }
    }
}
