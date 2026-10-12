package com.tailscale.mclink;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

public record ServerTunnelConfig(boolean autoShare, boolean relayOnly, int tcpConnectionLimit,
                                 int startupTimeoutSeconds, String language) {
    public static ServerTunnelConfig defaults() { return new ServerTunnelConfig(true, false, 256, 20, "en_us"); }
    public Duration timeout() { return Duration.ofSeconds(startupTimeoutSeconds); }

    public static ServerTunnelConfig load(Path file, Consumer<String> warning) {
        ServerTunnelConfig defaults = defaults();
        try {
            if (!Files.exists(file)) {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(defaults), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                return defaults;
            }
            if (Files.size(file) > 16384) throw new IOException("config exceeds 16 KiB");
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : json.keySet()) {
                if (!Set.of("autoShare", "relayOnly", "tcpConnectionLimit", "startupTimeoutSeconds", "language").contains(key)) {
                    warning.accept("Unknown Tailcat setting '" + key + "'; it is ignored.");
                }
            }
            return new ServerTunnelConfig(bool(json, "autoShare", defaults.autoShare, warning),
                    bool(json, "relayOnly", defaults.relayOnly, warning),
                    integer(json, "tcpConnectionLimit", defaults.tcpConnectionLimit, 1, 65536, warning),
                    integer(json, "startupTimeoutSeconds", defaults.startupTimeoutSeconds, 5, 120, warning),
                    language(json, warning));
        } catch (IOException | RuntimeException error) {
            warning.accept("Could not read " + file + ": " + error.getMessage() + "; using Tailcat defaults. Fix the file and run /tailcat restart.");
            return defaults;
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback, Consumer<String> warning) {
        if (!json.has(key)) return fallback;
        if (json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean()) return json.get(key).getAsBoolean();
        warning.accept(key + " must be true or false; using " + fallback + "."); return fallback;
    }

    private static int integer(JsonObject json, String key, int fallback, int min, int max, Consumer<String> warning) {
        if (!json.has(key)) return fallback;
        try {
            if (json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isNumber()) {
                int value = json.get(key).getAsBigDecimal().intValueExact();
                if (value >= min && value <= max) return value;
            }
        } catch (ArithmeticException | NumberFormatException ignored) {}
        warning.accept(key + " must be an integer from " + min + " to " + max + "; using " + fallback + "."); return fallback;
    }

    private static String language(JsonObject json, Consumer<String> warning) {
        if (!json.has("language")) return "en_us";
        if (json.get("language").isJsonPrimitive() && json.getAsJsonPrimitive("language").isString()
                && Set.of("en_us", "ko_kr", "ja_jp").contains(json.get("language").getAsString())) return json.get("language").getAsString();
        warning.accept("language must be en_us, ko_kr or ja_jp; using en_us."); return "en_us";
    }
}
