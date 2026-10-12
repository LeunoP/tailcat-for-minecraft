package com.tailscale.mclink;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteStatusLivenessTest {
    @AfterEach void reset() { RouteStatusLiveness.clear(); }

    @Test void tailcatRouteExpiresAfterFiveSecondsWithoutAnyHeartbeat() {
        UUID player = UUID.randomUUID();
        long connectedAt = 100;

        assertEquals(RouteStatus.DIRECT,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.DIRECT, false, connectedAt));
        assertEquals(RouteStatus.DIRECT,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.DIRECT, false,
                        connectedAt + TimeUnit.SECONDS.toNanos(5) - 1));
        assertEquals(RouteStatus.DISCONNECTED,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.DIRECT, false,
                        connectedAt + TimeUnit.SECONDS.toNanos(5)));
    }

    @Test void heartbeatRefreshesTheFiveSecondWindow() {
        UUID player = UUID.randomUUID();
        long connectedAt = 200;
        RouteStatusLiveness.effectiveStatus(player, RouteStatus.RELAY, false, connectedAt);
        long heartbeatAt = connectedAt + TimeUnit.SECONDS.toNanos(4);
        RouteStatusLiveness.heartbeat(player, heartbeatAt);

        assertEquals(RouteStatus.RELAY,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.RELAY, false,
                        heartbeatAt + TimeUnit.SECONDS.toNanos(5) - 1));
        assertEquals(RouteStatus.DISCONNECTED,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.RELAY, false,
                        heartbeatAt + TimeUnit.SECONDS.toNanos(5)));
    }

    @Test void nonTailcatAndHostRowsDoNotExpire() {
        UUID player = UUID.randomUUID();
        long connectedAt = 300;
        assertEquals(RouteStatus.NOT_TAILCAT,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.NOT_TAILCAT, false, connectedAt));
        assertEquals(RouteStatus.NOT_TAILCAT,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.NOT_TAILCAT, false,
                        connectedAt + TimeUnit.SECONDS.toNanos(30)));
        assertEquals(RouteStatus.DIRECT,
                RouteStatusLiveness.effectiveStatus(player, RouteStatus.DIRECT, true,
                        connectedAt + TimeUnit.SECONDS.toNanos(60)));
    }
}
