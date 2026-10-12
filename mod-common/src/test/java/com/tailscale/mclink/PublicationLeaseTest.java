package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PublicationLeaseTest {
    @Test void closesOnlyOwnedListenerAndRestoresOnce() {
        Object memory = new Object(), previousLan = new Object(), owned = new Object(), later = new Object();
        List<Object> listeners = new ArrayList<>(List.of(memory, previousLan));
        List<Object> closed = new ArrayList<>(); AtomicInteger restores = new AtomicInteger();
        PublicationLease<Object> lease = new PublicationLease<>(listeners, closed::add, restores::incrementAndGet);
        listeners.add(owned); lease.published(); listeners.add(later);
        lease.restore(); lease.restore();
        assertEquals(List.of(owned), closed); assertEquals(List.of(memory, previousLan, later), listeners);
        assertEquals(1, restores.get());
    }
    @Test void failedPublicationCanRollBackPartialListener() {
        List<Object> listeners = new ArrayList<>(); List<Object> closed = new ArrayList<>();
        PublicationLease<Object> lease = new PublicationLease<>(listeners, closed::add, () -> {});
        Object partial = new Object(); listeners.add(partial); lease.published(); lease.restore();
        assertEquals(List.of(partial), closed); assertTrue(listeners.isEmpty());
    }
}
