package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TunnelDiagnosticsTest {
    @TempDir Path directory;
    @Test void readyIsNotMinecraftSuccess() {
        TunnelDiagnostics diagnostics = new TunnelDiagnostics();
        diagnostics.accept(new HelperEvent("ready", "join", null, "127.0.0.1:1234", null, null));
        assertEquals("listener_ready", diagnostics.phase());
        diagnostics.connected(); assertEquals("connected", diagnostics.phase());
    }
    @Test void structuredRouteCanReturnToRelay() throws Exception {
        TunnelDiagnostics diagnostics = new TunnelDiagnostics();
        diagnostics.accept(HelperEvent.parse("{\"type\":\"connection\",\"stage\":\"opened\",\"connectionId\":\"1\",\"backendAddress\":\"127.0.0.1:4567\"}"));
        assertTrue(diagnostics.owns(new InetSocketAddress("127.0.0.1", 4567)));
        assertFalse(diagnostics.owns(new InetSocketAddress("127.0.0.1", 4568)));
        assertEquals("", diagnostics.region());
        for (String route : new String[]{"direct", "relay", "unknown"}) {
            diagnostics.accept(HelperEvent.parse("{\"type\":\"status\",\"connectionId\":\"1\",\"transport\":\"" + route + "\"}"));
            assertEquals(route, diagnostics.transport());
        }
        diagnostics.accept(HelperEvent.parse("{\"type\":\"connection\",\"stage\":\"closed\",\"connectionId\":\"1\"}"));
        assertFalse(diagnostics.owns(new InetSocketAddress("127.0.0.1", 4567)));
    }
    @Test void exportRedactsAndBoundsLogs() throws Exception {
        TunnelDiagnostics diagnostics = new TunnelDiagnostics();
        for (int i = 0; i < 100; i++) diagnostics.log("line " + i + " mcl1_secret_invite tc0123456789abcdefghijkl privkey:private-secret " + "x".repeat(2048));
        diagnostics.accept(new HelperEvent("ready", "host", "mcl1_very_secret", null, null, null));
        Path file = diagnostics.export(directory, Map.of("modVersion", "0.3.0"));
        String report = Files.readString(file);
        assertFalse(report.contains("mcl1_")); assertFalse(report.contains("tc012345"));
        assertFalse(report.contains("private-secret")); assertFalse(report.contains("line 0 "));
        assertTrue(report.contains("line 99 ")); assertTrue(report.length() < 40000);
    }
    @Test void errorCodeSurvivesWrappedException() {
        TunnelDiagnostics diagnostics = new TunnelDiagnostics();
        diagnostics.failed(new java.util.concurrent.CompletionException(new HelperFailure("invalid_invite", "bad code", new java.io.IOException("bad"))));
        assertEquals("invalid_invite", diagnostics.errorCode());
        diagnostics.stopped(); assertEquals("failed", diagnostics.phase());
    }
    @Test void legacyLogDoesNotGuessTransport() {
        TunnelDiagnostics diagnostics = new TunnelDiagnostics();
        diagnostics.log("derp-301 now using 127.0.0.1:80 mtu=1280");
        assertEquals("unknown", diagnostics.transport()); assertEquals("", diagnostics.region());
    }
}
