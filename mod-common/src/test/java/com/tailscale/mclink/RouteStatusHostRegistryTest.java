package com.tailscale.mclink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteStatusHostRegistryTest {
    private final Object firstServer = new Object();
    private final Object secondServer = new Object();

    @AfterEach
    void clear() {
        RouteStatusHostRegistry.forget(firstServer);
        RouteStatusHostRegistry.forget(secondServer);
    }

    @Test
    void recognizesOnlyTheRegisteredOwnerForThatServer() {
        RouteStatusHostRegistry.register(firstServer, "LeunoP");

        assertTrue(RouteStatusHostRegistry.isHost(firstServer, "LeunoP"));
        assertFalse(RouteStatusHostRegistry.isHost(firstServer, "tahey142"));
        assertFalse(RouteStatusHostRegistry.isHost(secondServer, "LeunoP"));
    }

    @Test
    void forgettingServerRemovesItsOwner() {
        RouteStatusHostRegistry.register(firstServer, "LeunoP");
        RouteStatusHostRegistry.forget(firstServer);

        assertFalse(RouteStatusHostRegistry.isHost(firstServer, "LeunoP"));
    }
}
