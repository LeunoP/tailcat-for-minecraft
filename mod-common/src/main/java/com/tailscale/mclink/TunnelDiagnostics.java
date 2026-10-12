package com.tailscale.mclink;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TunnelDiagnostics {
    private static final Pattern SECRETS = Pattern.compile("(?i)(?:mcl1_[a-z0-9_=-]+|tc[a-z0-9_=-]{16,}|(?:privkey|nodekey|privatekey|publickey)[=:][^\\s,]+)");
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4_ADDRESS = Pattern.compile("(?<![0-9.])" + IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}(?::[0-9]{1,5})?(?![0-9.])");
    private static final String IPV6_ZONE = "(?:%[A-Za-z0-9_.\\x2D]+)?";
    private static final Pattern IPV6_CANDIDATE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(?:\\[[0-9A-Fa-f:.]+" + IPV6_ZONE + "\\](?::[0-9]{1,5})?|[0-9A-Fa-f:.]+" + IPV6_ZONE + ")(?![A-Za-z0-9_.])");
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final Map<String, String> endpoints = new LinkedHashMap<>();
    private final Map<String, String> routes = new LinkedHashMap<>();
    private String phase = "helper_preparing";
    private String errorCode;
    private String region = "";
    private String remoteTransport = "unknown";
    private Integer queryRoundTripMs;

    public synchronized void reset() {
        logs.clear(); endpoints.clear(); routes.clear(); phase = "helper_preparing"; errorCode = null; region = ""; remoteTransport = "unknown"; queryRoundTripMs = null;
    }

    public synchronized void accept(HelperEvent event) {
        if ("progress".equals(event.type())) phase = event.stage();
        if ("ready".equals(event.type())) phase = "host".equals(event.mode()) ? "sharing" : "listener_ready";
        if (event.code() != null) errorCode = event.code();
        if ("error".equals(event.type())) phase = "failed";
        if (event.region() != null) region = redact(event.region());
        if (event.transport() != null && event.connectionId() == null) remoteTransport = event.transport();
        if (event.latencyMs() != null) queryRoundTripMs = Integer.parseInt(event.latencyMs());
        else if ("status".equals(event.type()) && event.connectionId() == null) queryRoundTripMs = null;
        if ("connection".equals(event.type())) {
            if ("opened".equals(event.stage())) endpoints.put(event.connectionId(), event.backendAddress());
            else { endpoints.remove(event.connectionId()); routes.remove(event.connectionId()); }
        }
        if (event.transport() != null && event.connectionId() != null && endpoints.containsKey(event.connectionId())) {
            routes.put(event.connectionId(), event.transport());
        }
    }

    public synchronized void failed(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HelperFailure failure) { errorCode = failure.code(); break; }
            if (cause instanceof java.util.concurrent.TimeoutException) errorCode = "timeout";
        }
        if (errorCode == null) errorCode = "helper_failed";
        phase = "failed";
        log(error.getMessage());
    }

    public synchronized void stopped() { endpoints.clear(); routes.clear(); remoteTransport = "unknown"; if (!"failed".equals(phase)) phase = "stopped"; }
    public synchronized void connected() { phase = "connected"; errorCode = null; }
    public synchronized String phase() { return phase; }
    public synchronized String errorCode() { return errorCode; }
    public synchronized String region() { return region; }

    public synchronized String transport() {
        if (routes.isEmpty()) return remoteTransport;
        if (routes.containsValue("unknown")) return "unknown";
        return routes.values().stream().distinct().count() == 1 ? routes.values().iterator().next() : "mixed";
    }

    public synchronized boolean owns(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet) || inet.getAddress() == null || !inet.getAddress().isLoopbackAddress()) return false;
        return endpoints.containsValue("127.0.0.1:" + inet.getPort());
    }

    public synchronized String route(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) return "unknown";
        for (var endpoint : endpoints.entrySet()) {
            if (endpoint.getValue().equals("127.0.0.1:" + inet.getPort())) return routes.getOrDefault(endpoint.getKey(), "unknown");
        }
        return "unknown";
    }

    public synchronized void log(String line) {
        if (line == null) return;
        String safe = redact(line);
        logs.addLast(safe.substring(0, Math.min(512, safe.length())));
        while (logs.size() > 64) logs.removeFirst();
    }

    public static String redact(String text) {
        if (text == null) return "";
        String safe = SECRETS.matcher(text).replaceAll("[redacted]");
        safe = redactIpv6Addresses(safe);
        return IPV4_ADDRESS.matcher(safe).replaceAll("[IP redacted]")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    }

    private static String redactIpv6Addresses(String text) {
        var matcher = IPV6_CANDIDATE.matcher(text);
        var result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String candidate = token;
            if (candidate.startsWith("[")) {
                int end = candidate.indexOf(']');
                candidate = candidate.substring(1, end);
            }
            String unscoped = candidate.replaceFirst("%[A-Za-z0-9_.\\x2D]+$", "");
            long colons = unscoped.chars().filter(character -> character == ':').count();
            boolean ipv6Shape = unscoped.contains("::") || colons >= 7;
            if (ipv6Shape && isNumericAddress(unscoped)) matcher.appendReplacement(result, "[IP redacted]");
            else matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(token));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isNumericAddress(String value) {
        try {
            return InetAddress.getByName(value).getAddress().length == 16;
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    public synchronized Path export(Path directory, Map<String, String> metadata) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, String> safeMetadata = new LinkedHashMap<>();
        metadata.forEach((key, value) -> safeMetadata.put(redact(key), redact(value)));
        data.put("metadata", safeMetadata);
        data.put("platform", System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        data.put("java", System.getProperty("java.version"));
        data.put("protocol", 2); data.put("phase", phase); data.put("errorCode", errorCode);
        data.put("transport", transport()); data.put("region", region);
        data.put("activeTcpConnections", endpoints.size());
        data.put("tunnelStatusQueryRoundTripMs", queryRoundTripMs);
        data.put("recentLogs", logs.toArray(String[]::new));
        Files.createDirectories(directory);
        Path file = directory.resolve("tailcat-diagnose-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(data), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        return file.toAbsolutePath();
    }
}
