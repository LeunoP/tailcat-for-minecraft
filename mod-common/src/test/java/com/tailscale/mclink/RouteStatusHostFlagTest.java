package com.tailscale.mclink;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteStatusHostFlagTest {
    @Test
    void hostFlagSurvivesStatusSnapshotEncoding() throws IOException {
        RouteStatus.PlayerRoute host = new RouteStatus.PlayerRoute(
                "LeunoP", RouteStatus.NOT_TAILCAT, 0, true, false);

        assertEquals(List.of(host), RouteStatus.decode(RouteStatus.encode(List.of(host))));
    }
}
