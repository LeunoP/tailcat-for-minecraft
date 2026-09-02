package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class HelperEventTest {
    @Test void parsesHostReady() throws Exception { assertEquals("mcl1_x", HelperEvent.parse("{\"type\":\"ready\",\"mode\":\"host\",\"invite\":\"mcl1_x\"}").invite()); }
    @Test void rejectsUnknownFields() { assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"stopped\",\"extra\":1}")); }
    @Test void rejectsNonLoopbackJoin() { assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"ready\",\"mode\":\"join\",\"address\":\"0.0.0.0:1\"}")); }
    @Test void rejectsMalformed() { assertThrows(IOException.class, () -> HelperEvent.parse("not json")); }
}
