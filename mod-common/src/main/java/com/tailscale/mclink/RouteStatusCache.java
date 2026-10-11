package com.tailscale.mclink;

import java.io.IOException;
import java.util.List;

public final class RouteStatusCache {
    private static final long STALE_AFTER_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private record Snapshot(List<RouteStatus.PlayerRoute> players, boolean received, long receivedAtNanos) {}
    private static volatile Snapshot snapshot = new Snapshot(List.of(), false, 0);
    private static java.util.ArrayList<RouteStatus.PlayerRoute> pending;
    private static long pendingId, pendingSinceNanos;
    private static int nextPacketIndex;
    private static Object activeConnection;

    private RouteStatusCache() {}

    public static synchronized void beginSession(Object connection) {
        if (connection == null) return;
        if (activeConnection == connection) return;
        activeConnection = connection;
        resetSnapshot();
    }

    public static synchronized boolean accept(Object connection, byte[] payload) throws IOException {
        return accept(connection, payload, System.nanoTime());
    }

    static synchronized boolean accept(Object connection, byte[] payload, long nowNanos) throws IOException {
        if (connection == null || connection != activeConnection) return false;
        accept(payload, nowNanos);
        return true;
    }

    public static synchronized boolean endSession(Object connection) {
        if (connection == null || connection != activeConnection) return false;
        activeConnection = null;
        resetSnapshot();
        return true;
    }

    public static void accept(byte[] payload) throws IOException {
        accept(payload, System.nanoTime());
    }

    static synchronized void accept(byte[] payload, long nowNanos) throws IOException {
        RouteStatus.Packet packet = RouteStatus.decodePacket(payload);
        if (packet.index() == 0) {
            pending = new java.util.ArrayList<>();
            pendingId = packet.snapshotId();
            pendingSinceNanos = nowNanos;
            nextPacketIndex = 0;
        }
        if (pending == null || packet.snapshotId() != pendingId || packet.index() != nextPacketIndex
                || nowNanos - pendingSinceNanos >= STALE_AFTER_NANOS) {
            pending = null;
            throw new IOException("Out-of-order or expired route status snapshot");
        }
        pending.addAll(packet.players());
        nextPacketIndex++;
        if (packet.last()) {
            snapshot = new Snapshot(List.copyOf(pending), true, nowNanos);
            pending = null;
        }
    }

    public static List<RouteStatus.PlayerRoute> players() {
        return snapshot.players();
    }

    public static boolean hasSnapshot() {
        return snapshot.received();
    }

    public static boolean isStale() {
        return isStale(System.nanoTime());
    }

    static boolean isStale(long nowNanos) {
        Snapshot current = snapshot;
        return current.received() && nowNanos - current.receivedAtNanos() >= STALE_AFTER_NANOS;
    }

    public static synchronized void clear() {
        activeConnection = null;
        resetSnapshot();
    }

    private static void resetSnapshot() {
        snapshot = new Snapshot(List.of(), false, 0);
        pending = null;
        pendingId = 0;
        pendingSinceNanos = 0;
        nextPacketIndex = 0;
    }
}
