package de.robv.android.xposed;

import java.lang.reflect.Method;
import java.util.Set;

public final class XposedBridge {
    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();

    public static synchronized void log(String text) {}
    public static synchronized void log(Throwable t) {}

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook hookMethod(Method method, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }
}
