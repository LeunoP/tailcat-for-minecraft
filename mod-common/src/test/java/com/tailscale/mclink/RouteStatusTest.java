package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteStatusTest {
    @Test void distinguishesDirectRelayUnknownAndOrdinaryConnections() {
        assertEquals(RouteStatus.NOT_TAILCAT, RouteStatus.encode(false, "direct"));
        assertEquals(RouteStatus.DIRECT, RouteStatus.encode(true, "direct"));
        assertEquals(RouteStatus.RELAY, RouteStatus.encode(true, "relay"));
        assertEquals(RouteStatus.UNKNOWN, RouteStatus.encode(true, "mixed"));
        assertEquals(RouteStatus.UNKNOWN, RouteStatus.encode(true, null));
    }

    @Test void statusLabelsNeverExposeNumericCodes() {
        assertEquals("mclink.status_direct", RouteStatus.translationKey(RouteStatus.DIRECT));
        assertEquals("mclink.status_relay", RouteStatus.translationKey(RouteStatus.RELAY));
        assertEquals("mclink.status_unknown", RouteStatus.translationKey(RouteStatus.UNKNOWN));
        assertEquals("mclink.status_not_tailcat", RouteStatus.translationKey(RouteStatus.NOT_TAILCAT));
    }

    @Test void commandLabelsMatchTheConnectionStatusDisplay() {
        for (String language : List.of("en_us", "ko_kr", "ja_jp")) {
            assertEquals(ServerMessages.text(language, "mclink.status_direct"),
                    RouteStatus.commandLabel(RouteStatus.encode(true, "direct"), language));
            assertEquals(ServerMessages.text(language, "mclink.status_relay"),
                    RouteStatus.commandLabel(RouteStatus.encode(true, "relay"), language));
            assertEquals(ServerMessages.text(language, "mclink.status_unknown"),
                    RouteStatus.commandLabel(RouteStatus.encode(true, null), language));
            assertEquals(ServerMessages.text(language, "mclink.status_not_tailcat"),
                    RouteStatus.commandLabel(RouteStatus.encode(false, "direct"), language));
        }
    }

    @Test void snapshotRoundTripsPlayerNamesRoutesAndPings() throws Exception {
        List<RouteStatus.PlayerRoute> expected = List.of(
                new RouteStatus.PlayerRoute("Alice_1", RouteStatus.DIRECT, 0),
                new RouteStatus.PlayerRoute("Bob", RouteStatus.RELAY, 1234),
                new RouteStatus.PlayerRoute("Carol", RouteStatus.NOT_TAILCAT, -1));
        assertEquals(expected, RouteStatus.decode(RouteStatus.encode(expected)));
    }

    @Test void snapshotRejectsInvalidNamesStatusesAndOversizedLists() {
        assertThrows(IllegalArgumentException.class, () -> RouteStatus.encode(
                List.of(new RouteStatus.PlayerRoute("Alice", RouteStatus.DIRECT, -2))));
        assertThrows(IllegalArgumentException.class, () -> RouteStatus.encode(
                List.of(new RouteStatus.PlayerRoute("bad name", RouteStatus.DIRECT))));
        assertThrows(IllegalArgumentException.class, () -> RouteStatus.encode(
                List.of(new RouteStatus.PlayerRoute("Alice", 9))));
        List<RouteStatus.PlayerRoute> oversized = new ArrayList<>();
        for (int i = 0; i <= RouteStatus.MAX_PACKET_PLAYERS; i++)
            oversized.add(new RouteStatus.PlayerRoute("P" + i, RouteStatus.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> RouteStatus.encode(oversized));
        assertEquals(2, RouteStatus.encodePackets(oversized).size());
    }

    @Test void oldSnapshotsDecodeWithUnavailablePing() throws Exception {
        byte[] legacy = {0x54, 0x43, 1, 1, 3, 'B', 'o', 'b', 2};
        assertEquals(List.of(new RouteStatus.PlayerRoute("Bob", RouteStatus.RELAY, -1)), RouteStatus.decode(legacy));
    }

    @Test void packedNamesPreserveEveryCharacterAndLength() throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";
        for (int start = 0; start < alphabet.length(); start++) {
            for (int length = 1; length <= 16; length++) {
                String name = (alphabet + alphabet).substring(start, start + length);
                var routes = List.of(new RouteStatus.PlayerRoute(name, RouteStatus.RELAY, 35));
                assertEquals(routes, RouteStatus.decode(RouteStatus.encode(routes)));
            }
        }
    }

    @Test void packedNamesRejectReservedCharacterAndNonzeroPadding() {
        byte[] reserved = RouteStatus.encode(List.of(new RouteStatus.PlayerRoute("A", RouteStatus.DIRECT, 35)));
        reserved[18] = (byte) 0xfc;
        assertThrows(IOException.class, () -> RouteStatus.decode(reserved));
        byte[] padding = RouteStatus.encode(List.of(new RouteStatus.PlayerRoute("A", RouteStatus.DIRECT, 35)));
        padding[18] = 1;
        assertThrows(IOException.class, () -> RouteStatus.decode(padding));
    }

    @Test void fullPacketsPreservePingValuesWithinLegacySizeBudget() throws Exception {
        List<RouteStatus.PlayerRoute> players = new ArrayList<>();
        for (int i = 0; i < RouteStatus.MAX_PACKET_PLAYERS; i++)
            players.add(new RouteStatus.PlayerRoute(String.format("Player%010d", i), RouteStatus.DIRECT, Integer.MAX_VALUE));
        byte[] encoded = RouteStatus.encode(players);
        org.junit.jupiter.api.Assertions.assertTrue(encoded.length <= RouteStatus.MAX_PAYLOAD_BYTES);
        assertEquals(players, RouteStatus.decode(encoded));
    }

    @Test void snapshotRejectsTruncatedOrTrailingData() throws Exception {
        byte[] encoded = RouteStatus.encode(List.of(new RouteStatus.PlayerRoute("Alice", RouteStatus.DIRECT)));
        assertThrows(IOException.class, () -> RouteStatus.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> RouteStatus.decode(trailing));
    }
}
