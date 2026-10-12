package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ServerTunnelConfigTest {
    @TempDir Path directory;
    @Test void createsDefaultConfig() {
        Path file = directory.resolve("config/tailcat-server.json");
        assertEquals(ServerTunnelConfig.defaults(), ServerTunnelConfig.load(file, warning -> fail(warning)));
        assertTrue(Files.exists(file));
    }
    @Test void validatesEachSettingSeparately() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"autoShare\":false,\"relayOnly\":true,\"tcpConnectionLimit\":1.5,\"startupTimeoutSeconds\":200,\"language\":\"ko_kr\"}");
        ArrayList<String> warnings = new ArrayList<>();
        ServerTunnelConfig config = ServerTunnelConfig.load(file, warnings::add);
        assertFalse(config.autoShare()); assertTrue(config.relayOnly()); assertEquals(256, config.tcpConnectionLimit());
        assertEquals(20, config.startupTimeoutSeconds()); assertEquals("ko_kr", config.language()); assertEquals(2, warnings.size());
    }
    @Test void malformedConfigIsNotOverwritten() throws Exception {
        Path file = directory.resolve("config.json"); Files.writeString(file, "broken");
        assertEquals(ServerTunnelConfig.defaults(), ServerTunnelConfig.load(file, warning -> {}));
        assertEquals("broken", Files.readString(file));
    }
}
