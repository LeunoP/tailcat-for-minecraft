package com.tailscale.mclink;

public final class HostPreferences {
    public static final HostPreferences SESSION = new HostPreferences();

    public record Settings(boolean relayOnly, int maxPlayers) {
        public Settings {
            if (maxPlayers < 2 || maxPlayers > 64) {
                throw new IllegalArgumentException("Host player limit must be between 2 and 64");
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
