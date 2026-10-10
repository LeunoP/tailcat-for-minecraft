package com.tailscale.mclink;

import java.math.BigInteger;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

public final class HostPlayerLimit {
    public static final BigInteger MAX_PLAYERS = BigInteger.valueOf(64);
    private static final Map<Object, Integer> ACTIVE_LIMITS = new WeakHashMap<>();

    private HostPlayerLimit() {
    }

    public static Lease applyDynamic(Object target, int maxPlayers) {
        synchronized (ACTIVE_LIMITS) {
            ACTIVE_LIMITS.put(target, maxPlayers);
        }
        return new Lease(() -> {
            synchronized (ACTIVE_LIMITS) {
                ACTIVE_LIMITS.remove(target);
            }
        });
    }

    public static int minecraftLimit(BigInteger maxPlayers) {
        return normalizePlayerCount(maxPlayers).intValue();
    }

    public static BigInteger normalizePlayerCount(BigInteger maxPlayers) {
        return maxPlayers.max(BigInteger.TWO).min(MAX_PLAYERS);
    }

    public static BigInteger helperMaxConnections(BigInteger maxPlayers) {
        return normalizePlayerCount(maxPlayers).subtract(BigInteger.ONE).max(BigInteger.ONE);
    }

    public static BigInteger tcpResourceLimit(BigInteger maxPlayers) {
        return normalizePlayerCount(maxPlayers).multiply(BigInteger.valueOf(4)).max(BigInteger.valueOf(64));
    }

    public static boolean isValidPlayerCount(BigInteger maxPlayers) {
        return maxPlayers.compareTo(BigInteger.TWO) >= 0
                && maxPlayers.compareTo(MAX_PLAYERS) <= 0;
    }

    public static Lease applyField(Object target, int maxPlayers, ToIntFunction<Object> getter,
                                   ObjIntConsumer<Object> setter) {
        int previous = getter.applyAsInt(target);
        setter.accept(target, maxPlayers);
        return new Lease(() -> setter.accept(target, previous));
    }

    public static int activeFor(Object target) {
        synchronized (ACTIVE_LIMITS) {
            return ACTIVE_LIMITS.getOrDefault(target, 0);
        }
    }

    public static final class Lease {
        private final Runnable restoreAction;
        private boolean restored;

        private Lease(Runnable restoreAction) {
            this.restoreAction = restoreAction;
        }

        public synchronized void restore() {
            if (restored) {
                return;
            }
            restored = true;
            restoreAction.run();
        }
    }
}
