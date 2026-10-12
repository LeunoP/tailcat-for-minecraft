package com.tailscale.mclink;

public final class RouteStatusHeartbeat {
    public static final int MARKER = 0x54;

    private RouteStatusHeartbeat() {}

    public static boolean isValid(int payloadLength, int firstByte) {
        return payloadLength == 1 && firstByte == MARKER;
    }
}
