package de.robv.android.xposed.callbacks;

import java.util.concurrent.CopyOnWriteArraySet;

public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_HIGHEST = 100;
    public static final int PRIORITY_LOWEST = -100;
    public final int priority;

    @Deprecated
    public XCallback() { this.priority = PRIORITY_DEFAULT; }
    public XCallback(int priority) { this.priority = priority; }

    @Override
    public int compareTo(XCallback other) {
        return Integer.compare(other.priority, this.priority);
    }

    public static abstract class Param {
        Object[] callbacks;
        public Object extra;
        protected Param(Object[] callbacks) { this.callbacks = callbacks; }
    }

    // Minimal stub for CopyOnWriteSortedSet
    public static class CopyOnWriteSortedSet<T> {
        public T[] getSnapshot() { return null; }
    }
}
