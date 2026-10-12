package com.tailscale.mclink;

import java.util.concurrent.TimeUnit;

public final class RouteStatusHeartbeatClock {
    private static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static long lastSentNanos;

    private RouteStatusHeartbeatClock() {}

    public static synchronized boolean shouldSend() {
        if (!RouteStatusCache.supportsHeartbeat()) return false;
        long now = System.nanoTime();
        if (now - lastSentNanos < INTERVAL_NANOS) return false;
        lastSentNanos = now;
        return true;
    }
}
