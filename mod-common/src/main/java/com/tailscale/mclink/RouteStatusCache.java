package com.tailscale.mclink;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RouteStatusCache {
    private static final long STALE_AFTER_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    private static final long SNAPSHOT_ASSEMBLY_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private record Snapshot(Map<String, RouteStatus.PlayerRoute> players, boolean received, long receivedAtNanos,
            boolean heartbeatSupported) {}
    private static volatile Snapshot snapshot = new Snapshot(Map.of(), false, 0, false);
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
                || nowNanos - pendingSinceNanos >= SNAPSHOT_ASSEMBLY_TIMEOUT_NANOS) {
            pending = null;
            throw new IOException("Out-of-order or expired route status snapshot");
        }
        pending.addAll(packet.players());
        nextPacketIndex++;
        if (packet.last()) {
            Map<String, RouteStatus.PlayerRoute> players = new LinkedHashMap<>();
            for (RouteStatus.PlayerRoute player : pending) players.put(player.playerName(), player);
            snapshot = new Snapshot(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(players)), true,
                    nowNanos, packet.version() >= 4);
            pending = null;
        }
    }

    public static List<RouteStatus.PlayerRoute> players() {
        Snapshot current = snapshot;
        return List.copyOf(current.players().values());
    }

    public static boolean hasSnapshot() {
        return snapshot.received();
    }

    public static boolean supportsHeartbeat() {
        return snapshot.heartbeatSupported();
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
        snapshot = new Snapshot(Map.of(), false, 0, false);
        pending = null;
        pendingId = 0;
        pendingSinceNanos = 0;
        nextPacketIndex = 0;
    }
}
