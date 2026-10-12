package com.tailscale.mclink;

public final class HelperFailure extends java.io.IOException {
    private final String code;
    public HelperFailure(String code, String message, Throwable cause) {
        super(message, cause); this.code = code;
    }
    public String code() { return code; }
}
