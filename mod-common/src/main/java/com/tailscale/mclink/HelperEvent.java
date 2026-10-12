package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Set;

public record HelperEvent(String type, String mode, String invite, String address, String code, String message,
                          String stage, String transport, String region, String connectionId, String backendAddress, String latencyMs) {
    private static final Set<String> TYPES = Set.of("ready", "error", "stopped", "progress", "status", "connection", "warning");
    private static final Set<String> FIELDS = Set.of("type", "mode", "invite", "address", "code", "message",
            "stage", "transport", "region", "connectionId", "backendAddress", "latencyMs");

    public HelperEvent(String type, String mode, String invite, String address, String code, String message) {
        this(type, mode, invite, address, code, message, null, null, null, null, null, null);
    }

    public static HelperEvent parse(String line) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            for (String key : o.keySet()) {
                if (!FIELDS.contains(key)) {
                    throw new IOException("unknown helper event field: " + key);
                }
            }
            String type = required(o, "type");
            if (!TYPES.contains(type)) {
                throw new IOException("unknown helper event type: " + type);
            }
            HelperEvent e = new HelperEvent(type, optional(o, "mode"), optional(o, "invite"),
                    optional(o, "address"), optional(o, "code"), optional(o, "message"), optional(o, "stage"),
                    optional(o, "transport"), optional(o, "region"), optional(o, "connectionId"), optional(o, "backendAddress"), optional(o, "latencyMs"));
            if (e.latencyMs != null && (!e.latencyMs.matches("[0-9]{1,5}") || Integer.parseInt(e.latencyMs) > 60000)) {
                throw new IOException("invalid tunnel-query latency");
            }
            if (e.transport != null && !Set.of("unknown", "direct", "relay").contains(e.transport)) {
                throw new IOException("invalid transport");
            }
            if (type.equals("progress") && (e.stage == null || !Set.of("relay_preparing", "validating_invite",
                    "listener_ready", "tunnel_connecting", "minecraft_connecting").contains(e.stage))) {
                throw new IOException("invalid progress stage");
            }
            if (type.equals("connection") && (e.connectionId == null || !Set.of("opened", "closed").contains(e.stage)
                    || ("opened".equals(e.stage) && (e.backendAddress == null || !e.backendAddress.matches("127\\.0\\.0\\.1:[0-9]{1,5}"))))) {
                throw new IOException("invalid connection event");
            }
            if (type.equals("ready") && !("host".equals(e.mode) ^ "join".equals(e.mode))) {
                throw new IOException("invalid ready mode");
            }
            if (type.equals("ready") && "host".equals(e.mode) && e.invite == null) {
                throw new IOException("host ready event lacks invite");
            }
            if (type.equals("ready") && "join".equals(e.mode)
                    && (e.address == null || !e.address.startsWith("127.0.0.1:"))) {
                throw new IOException("join ready address is not loopback");
            }
            return e;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("malformed helper event", e);
        }
    }

    private static String required(JsonObject o, String key) throws IOException {
        String value = optional(o, key);
        if (value == null) {
            throw new IOException("missing " + key);
        }
        return value;
    }

    private static String optional(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = o.get(key).getAsString();
        if (value.length() > (key.equals("invite") ? 8192 : 2048)) throw new IllegalArgumentException(key + " is too long");
        return value;
    }
}
