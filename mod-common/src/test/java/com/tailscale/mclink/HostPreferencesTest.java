package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostPreferencesTest {
    @Test void newSessionStartsWithDefaults() {
        HostPreferences preferences = new HostPreferences();
        assertEquals(new HostPreferences.Settings(false, 8), preferences.get());
    }

    @Test void remembersRelayAndHostInclusiveLimitTogether() {
        HostPreferences preferences = new HostPreferences();
        preferences.remember(true, 1000);
        assertEquals(new HostPreferences.Settings(true, 1000), preferences.get());
        preferences.remember(false, 2);
        assertEquals(new HostPreferences.Settings(false, 2), preferences.get());
    }

    @Test void invalidLimitDoesNotReplaceLastValidSettings() {
        HostPreferences preferences = new HostPreferences();
        preferences.remember(true, 64);
        assertThrows(IllegalArgumentException.class, () -> preferences.remember(false, -1));
        assertThrows(IllegalArgumentException.class, () -> preferences.remember(false, 1));
        assertEquals(new HostPreferences.Settings(true, 64), preferences.get());
    }

    @Test void previousSessionDoesNotAffectFreshSession() {
        HostPreferences previous = new HostPreferences();
        previous.remember(true, 64);
        assertEquals(new HostPreferences.Settings(false, 8), new HostPreferences().get());
    }
}
