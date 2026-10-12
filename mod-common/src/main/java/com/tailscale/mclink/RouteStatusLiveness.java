package com.tailscale.mclink;

import java.util.UUID;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class RouteStatusLiveness {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final ConcurrentHashMap<UUID, Long> LAST_HEARTBEAT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> FIRST_SEEN = new ConcurrentHashMap<>();

    private RouteStatusLiveness() {}

    public static void heartbeat(UUID playerId) {
        heartbeat(playerId, System.nanoTime());
    }

    static void heartbeat(UUID playerId, long nowNanos) {
        if (playerId != null) LAST_HEARTBEAT.put(playerId, nowNanos);
    }

    public static boolean isDisconnected(UUID playerId) {
        return isDisconnected(playerId, System.nanoTime());
    }

    private static boolean isDisconnected(UUID playerId, long nowNanos) {
        if (playerId == null) return false;
        Long last = LAST_HEARTBEAT.get(playerId);
        if (last == null) last = FIRST_SEEN.get(playerId);
        return last != null && nowNanos - last >= TIMEOUT_NANOS;
    }

    public static int effectiveStatus(UUID playerId, int status, boolean host) {
        return effectiveStatus(playerId, status, host, System.nanoTime());
    }

    static int effectiveStatus(UUID playerId, int status, boolean host, long nowNanos) {
        if (host || playerId == null) return status;
        if (status == RouteStatus.NOT_TAILCAT) {
            FIRST_SEEN.remove(playerId);
            LAST_HEARTBEAT.remove(playerId);
            return status;
        }
        FIRST_SEEN.putIfAbsent(playerId, nowNanos);
        return isDisconnected(playerId, nowNanos) ? RouteStatus.DISCONNECTED : status;
    }

    public static RouteStatus.PlayerRoute playerRoute(UUID playerId, String playerName, int status, int pingMillis, boolean host) {
        return playerRoute(playerId, playerName, status, pingMillis, host, System.nanoTime());
    }

    static RouteStatus.PlayerRoute playerRoute(UUID playerId, String playerName, int status, int pingMillis,
            boolean host, long nowNanos) {
        int currentStatus = effectiveStatus(playerId, status, host, nowNanos);
        boolean disconnected = currentStatus == RouteStatus.DISCONNECTED;
        return new RouteStatus.PlayerRoute(playerName, currentStatus, disconnected ? -1 : pingMillis, host, disconnected);
    }

    public static void forget(UUID playerId) {
        if (playerId == null) return;
        FIRST_SEEN.remove(playerId);
        LAST_HEARTBEAT.remove(playerId);
    }

    public static void retainPlayers(Collection<UUID> onlinePlayers) {
        Set<UUID> online = new HashSet<>(onlinePlayers);
        FIRST_SEEN.keySet().removeIf(playerId -> !online.contains(playerId));
        LAST_HEARTBEAT.keySet().removeIf(playerId -> !online.contains(playerId));
    }

    public static void clear() {
        LAST_HEARTBEAT.clear();
        FIRST_SEEN.clear();
    }
}
