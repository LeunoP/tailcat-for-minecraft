package com.tailscale.mclink;

public final class HostPreferences {
    public static final HostPreferences SESSION = new HostPreferences();

    public record Settings(boolean relayOnly, int maxPlayers) {
        public Settings {
            if (maxPlayers < 2) {
                throw new IllegalArgumentException("Host player limit must be at least 2");
            }
        }
    }

    private volatile Settings settings = new Settings(false, 8);

    public Settings get() {
        return settings;
    }

    public void remember(boolean relayOnly, int maxPlayers) {
        settings = new Settings(relayOnly, maxPlayers);
    }
}
