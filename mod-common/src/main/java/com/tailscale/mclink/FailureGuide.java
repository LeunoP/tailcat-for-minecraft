package com.tailscale.mclink;

public final class FailureGuide {
    private FailureGuide() {}
    public static String key(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HelperFailure failure) return key(failure.code());
            if (cause instanceof java.util.concurrent.TimeoutException) return key("timeout");
        }
        return key("helper_failed");
    }
    public static String key(String code) {
        return "mclink.failure." + (code == null ? "helper_failed" : switch (code) {
            case "invalid_invite", "derp_unreachable", "backend_unreachable", "remote_unreachable", "capacity", "timeout" -> code;
            default -> "helper_failed";
        });
    }
}
