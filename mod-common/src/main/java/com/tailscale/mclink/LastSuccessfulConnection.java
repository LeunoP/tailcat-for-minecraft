package com.tailscale.mclink;

public final class LastSuccessfulConnection {
    public record Entry(String invite, boolean relayOnly) {}
    private static volatile Entry last;
    private LastSuccessfulConnection() {}
    public static void remember(String invite, boolean relayOnly) { last = new Entry(invite, relayOnly); }
    public static Entry get() { return last; }
}
