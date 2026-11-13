package com.example.cotalkproject.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用前后台状态：通过 Activity 启停计数判断，避免引入额外生命周期依赖。
 */
public final class AppForegroundTracker {

    private static final AtomicInteger STARTED_ACTIVITIES = new AtomicInteger();

    private AppForegroundTracker() {
    }

    public static void onActivityStarted() {
        STARTED_ACTIVITIES.incrementAndGet();
    }

    public static void onActivityStopped() {
        STARTED_ACTIVITIES.updateAndGet(value -> value > 0 ? value - 1 : 0);
    }

    public static boolean isForeground() {
        return STARTED_ACTIVITIES.get() > 0;
    }
}
