package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HostPlayerLimitTest {
    @org.junit.jupiter.api.Test void tcpBudgetDoesNotConsumePlayerSlots() {
        org.junit.jupiter.api.Assertions.assertEquals(java.math.BigInteger.valueOf(256), HostPlayerLimit.tcpResourceLimit(java.math.BigInteger.valueOf(64)));
        org.junit.jupiter.api.Assertions.assertEquals(64, HostPlayerLimit.minecraftLimit(java.math.BigInteger.valueOf(64)));
        org.junit.jupiter.api.Assertions.assertEquals(java.math.BigInteger.valueOf(63), HostPlayerLimit.helperMaxConnections(java.math.BigInteger.valueOf(64)));
    }
    @Test void sixtyFourIncludesHost() {
        assertEquals(64, HostPlayerLimit.minecraftLimit(BigInteger.valueOf(64)));
        assertEquals(BigInteger.valueOf(63), HostPlayerLimit.helperMaxConnections(BigInteger.valueOf(64)));
        assertFalse(HostPlayerLimit.isValidPlayerCount(BigInteger.valueOf(65)));
        assertFalse(HostPlayerLimit.isValidPlayerCount(BigInteger.ONE));
    }
    @Test void clampsOversizedRequests() {
        assertEquals(64, HostPlayerLimit.minecraftLimit(BigInteger.TEN.pow(100)));
        assertEquals(2, HostPlayerLimit.minecraftLimit(BigInteger.ZERO));
    }
    @Test void restoresOriginalFieldOnlyOnce() {
        AtomicInteger count = new AtomicInteger(8);
        HostPlayerLimit.Lease lease = HostPlayerLimit.applyField(count, 64,
                target -> ((AtomicInteger) target).get(), (target, limit) -> ((AtomicInteger) target).set(limit));
        assertEquals(64, count.get());
        lease.restore();
        assertEquals(8, count.get());
        count.set(20);
        lease.restore();
        assertEquals(20, count.get());
    }
}
