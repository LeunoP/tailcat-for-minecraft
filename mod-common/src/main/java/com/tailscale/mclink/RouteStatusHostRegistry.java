package com.tailscale.mclink;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Tracks the integrated-server owner without depending on loader-specific server APIs. */
public final class RouteStatusHostRegistry {
    private static final Map<Object, String> HOSTS = Collections.synchronizedMap(new WeakHashMap<>());

    private RouteStatusHostRegistry() {}

    public static void register(Object server, String playerName) {
        if (server == null || playerName == null || playerName.isBlank()) return;
        HOSTS.put(server, playerName);
    }

    public static boolean isHost(Object server, String playerName) {
        if (server == null || playerName == null) return false;
        return playerName.equals(HOSTS.get(server));
    }

    public static void forget(Object server) {
        if (server != null) HOSTS.remove(server);
    }
}
