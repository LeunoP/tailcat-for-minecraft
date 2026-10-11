package com.tailscale.mclink;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteStatusCacheTest {
    private final byte[] payload = RouteStatus.encode(List.of(new RouteStatus.PlayerRoute("Alice", RouteStatus.DIRECT, 35)));

    @AfterEach void reset() { RouteStatusCache.clear(); }

    @Test void disconnectsAtExactlyOneMinuteWithoutLosingRoster() throws Exception {
        RouteStatusCache.accept(payload, 100);
        assertFalse(RouteStatusCache.isStale(100 + TimeUnit.SECONDS.toNanos(60) - 1));
        assertTrue(RouteStatusCache.isStale(100 + TimeUnit.SECONDS.toNanos(60)));
        assertEquals(1, RouteStatusCache.players().size());
    }

    @Test void identicalNewSnapshotRestoresFreshStatus() throws Exception {
        RouteStatusCache.accept(payload, 100);
        long later = 100 + TimeUnit.SECONDS.toNanos(90);
        assertTrue(RouteStatusCache.isStale(later));
        RouteStatusCache.accept(payload, later);
        assertFalse(RouteStatusCache.isStale(later));
        assertEquals(35, RouteStatusCache.players().get(0).pingMillis());
    }

    @Test void malformedSnapshotDoesNotRefreshValidData() throws Exception {
        RouteStatusCache.accept(payload, 100);
        long later = 100 + TimeUnit.SECONDS.toNanos(60);
        assertThrows(IOException.class, () -> RouteStatusCache.accept(new byte[]{0}, later));
        assertTrue(RouteStatusCache.isStale(later));
        assertEquals("Alice", RouteStatusCache.players().get(0).playerName());
    }

    @Test void rostersBeyondSixtyFourPublishOnlyWhenAllPacketsArrive() throws Exception {
        RouteStatusCache.accept(payload, 100);
        List<RouteStatus.PlayerRoute> players = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> new RouteStatus.PlayerRoute("Player" + i, RouteStatus.RELAY, i)).toList();
        List<byte[]> packets = RouteStatus.encodePackets(players);
        long later = 100 + TimeUnit.SECONDS.toNanos(61);
        for (int i = 0; i < packets.size(); i++) {
            assertTrue(packets.get(i).length <= RouteStatus.MAX_PAYLOAD_BYTES);
            RouteStatusCache.accept(packets.get(i), later + i);
            if (i < packets.size() - 1) {
                assertEquals("Alice", RouteStatusCache.players().get(0).playerName());
                assertTrue(RouteStatusCache.isStale(later + i));
            }
        }
        assertEquals(players, RouteStatusCache.players());
        assertFalse(RouteStatusCache.isStale(later + packets.size()));
        RouteStatusCache.accept(RouteStatus.encode(List.of()), later + packets.size());
        assertTrue(RouteStatusCache.players().isEmpty());
    }

    @Test void missingOrMixedPacketsCannotRenewFreshness() throws Exception {
        RouteStatusCache.accept(payload, 100);
        List<RouteStatus.PlayerRoute> rows = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new RouteStatus.PlayerRoute("P" + i, RouteStatus.DIRECT, 1)).toList();
        List<byte[]> first = RouteStatus.encodePackets(rows), second = RouteStatus.encodePackets(rows);
        long later = 100 + TimeUnit.SECONDS.toNanos(61);
        assertThrows(IOException.class, () -> RouteStatusCache.accept(first.get(1), later));
        RouteStatusCache.accept(first.get(0), later);
        assertThrows(IOException.class, () -> RouteStatusCache.accept(second.get(1), later + 1));
        assertTrue(RouteStatusCache.isStale(later + 1));
        for (byte[] packet : second) RouteStatusCache.accept(packet, later + 2);
        assertEquals(rows, RouteStatusCache.players());
        assertFalse(RouteStatusCache.isStale(later + 3));
    }

    @Test void expiredPartialSnapshotMustRestartAtFirstPacket() throws Exception {
        List<RouteStatus.PlayerRoute> rows = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new RouteStatus.PlayerRoute("P" + i, RouteStatus.DIRECT, 1)).toList();
        List<byte[]> packets = RouteStatus.encodePackets(rows);
        RouteStatusCache.accept(packets.get(0), 100);
        assertThrows(IOException.class, () -> RouteStatusCache.accept(packets.get(1), 100 + TimeUnit.SECONDS.toNanos(60)));
        assertFalse(RouteStatusCache.hasSnapshot());
    }

    @Test void disconnectClearsAllSessionState() throws Exception {
        RouteStatusCache.accept(payload, 100);
        RouteStatusCache.clear();
        assertFalse(RouteStatusCache.hasSnapshot());
        assertFalse(RouteStatusCache.isStale(Long.MAX_VALUE));
        assertTrue(RouteStatusCache.players().isEmpty());
    }

    @Test void newConnectionStartsWithAnEmptySnapshot() throws Exception {
        Object serverA = new Object();
        Object serverB = new Object();
        RouteStatusCache.beginSession(serverA);
        assertTrue(RouteStatusCache.accept(serverA, payload, 100));
        assertEquals("Alice", RouteStatusCache.players().get(0).playerName());

        assertTrue(RouteStatusCache.endSession(serverA));
        RouteStatusCache.beginSession(serverB);
        assertFalse(RouteStatusCache.hasSnapshot());
        assertTrue(RouteStatusCache.players().isEmpty());
        assertFalse(RouteStatusCache.isStale(100 + TimeUnit.SECONDS.toNanos(120)));
    }

    @Test void queuedPayloadAndLateLogoutFromPreviousConnectionCannotAffectNewSession() throws Exception {
        Object serverA = new Object();
        Object serverB = new Object();
        byte[] oldQueuedPayload = RouteStatus.encode(List.of(
                new RouteStatus.PlayerRoute("OldServer", RouteStatus.RELAY, 500)));
        RouteStatusCache.beginSession(serverA);
        assertTrue(RouteStatusCache.accept(serverA, payload, 100));

        RouteStatusCache.beginSession(serverB);
        assertFalse(RouteStatusCache.endSession(serverA));
        assertFalse(RouteStatusCache.accept(serverA, oldQueuedPayload, 200));
        assertFalse(RouteStatusCache.hasSnapshot());
        assertTrue(RouteStatusCache.players().isEmpty());

        assertTrue(RouteStatusCache.accept(serverB, RouteStatus.encode(List.of(
                new RouteStatus.PlayerRoute("NewServer", RouteStatus.DIRECT, 12))), 300));
        assertEquals("NewServer", RouteStatusCache.players().get(0).playerName());
        assertEquals(12, RouteStatusCache.players().get(0).pingMillis());
    }

    @Test void duplicateInitializationForSameConnectionPreservesPartialSnapshot() throws Exception {
        Object server = new Object();
        List<RouteStatus.PlayerRoute> rows = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new RouteStatus.PlayerRoute("P" + i, RouteStatus.DIRECT, i)).toList();
        List<byte[]> packets = RouteStatus.encodePackets(rows);
        RouteStatusCache.beginSession(server);
        assertTrue(RouteStatusCache.accept(server, packets.get(0), 100));

        RouteStatusCache.beginSession(server);
        assertTrue(RouteStatusCache.accept(server, packets.get(1), 101));
        assertEquals(rows, RouteStatusCache.players());
    }

    @Test void lateFinalFragmentFromOldConnectionCannotCompleteOrClearNewConnectionSnapshot() throws Exception {
        Object serverA = new Object();
        Object serverB = new Object();
        List<RouteStatus.PlayerRoute> rowsA = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new RouteStatus.PlayerRoute("A" + i, RouteStatus.RELAY, i)).toList();
        List<RouteStatus.PlayerRoute> rowsB = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new RouteStatus.PlayerRoute("B" + i, RouteStatus.DIRECT, i + 20)).toList();
        List<byte[]> packetsA = RouteStatus.encodePackets(rowsA);
        List<byte[]> packetsB = RouteStatus.encodePackets(rowsB);

        RouteStatusCache.beginSession(serverA);
        assertTrue(RouteStatusCache.accept(serverA, packetsA.get(0), 100));
        assertFalse(RouteStatusCache.hasSnapshot());
        assertTrue(RouteStatusCache.endSession(serverA));

        RouteStatusCache.beginSession(serverB);
        assertTrue(RouteStatusCache.accept(serverB, packetsB.get(0), 200));
        assertFalse(RouteStatusCache.accept(serverA, packetsA.get(1), 201));
        assertFalse(RouteStatusCache.hasSnapshot());
        assertTrue(RouteStatusCache.players().isEmpty());

        assertTrue(RouteStatusCache.accept(serverB, packetsB.get(1), 202));
        assertEquals(rowsB, RouteStatusCache.players());
        assertFalse(RouteStatusCache.isStale(203));
    }

    @Test void mismatchedConnectionCannotAddPartialSnapshotOrRefreshExistingSession() throws Exception {
        Object active = new Object();
        Object stale = new Object();
        RouteStatusCache.beginSession(active);
        assertFalse(RouteStatusCache.accept(stale, payload, 100));
        assertFalse(RouteStatusCache.hasSnapshot());

        assertTrue(RouteStatusCache.accept(active, payload, 100));
        assertFalse(RouteStatusCache.accept(stale, payload, 100 + TimeUnit.SECONDS.toNanos(61)));
        assertTrue(RouteStatusCache.isStale(100 + TimeUnit.SECONDS.toNanos(61)));
        assertEquals("Alice", RouteStatusCache.players().get(0).playerName());
    }
}
