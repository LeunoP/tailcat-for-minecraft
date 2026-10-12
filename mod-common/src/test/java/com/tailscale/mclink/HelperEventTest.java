package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class HelperEventTest {
    @Test void validatesV2Events() throws Exception {
        assertEquals("42", HelperEvent.parse("{\"type\":\"status\",\"latencyMs\":\"42\"}").latencyMs());
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"status\",\"latencyMs\":\"-1\"}"));
        assertEquals("relay", HelperEvent.parse("{\"type\":\"status\",\"transport\":\"relay\"}").transport());
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"status\",\"transport\":\"maybe\"}"));
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"progress\",\"stage\":\"login_success\"}"));
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"connection\",\"stage\":\"opened\",\"connectionId\":\"1\",\"backendAddress\":\"1.2.3.4:123\"}"));
    }
    @Test void parsesHostReady() throws Exception {
        assertEquals("mcl1_x", HelperEvent.parse("{\"type\":\"ready\",\"mode\":\"host\",\"invite\":\"mcl1_x\"}").invite());
    }
    @Test void rejectsUnknownFields() {
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"stopped\",\"extra\":1}"));
    }
    @Test void rejectsUnknownEvents() {
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"connected\"}"));
    }
    @Test void rejectsNonLoopbackJoin() {
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"ready\",\"mode\":\"join\",\"address\":\"0.0.0.0:1\"}"));
    }
    @Test void rejectsNonStringFields() {
        for (String value : new String[]{"123", "true", "[]", "{}"}) {
            assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"error\",\"message\":" + value + "}"));
        }
    }
    @Test void acceptsLoopbackJoin() throws Exception {
        assertEquals("127.0.0.1:4321", HelperEvent.parse("{\"type\":\"ready\",\"mode\":\"join\",\"address\":\"127.0.0.1:4321\"}").address());
    }
}
