package com.tailscale.mclink;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ScreenState implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    private Session session;
    private volatile String lastError;

    private static int findLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 25565;
        }
    }

    public synchronized CompletableFuture<String> share(Minecraft client) {
        if (session != null && session.mode == SessionMode.HOST && session.process.isAlive()) {
            if (session.invite != null) return CompletableFuture.completedFuture(session.invite);
            return awaitReady(session).thenApply(event -> {
                session.invite = event.invite();
                return event.invite();
            });
        }
        stop();
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null)
            return CompletableFuture.failedFuture(new IllegalStateException("No integrated server is running"));
        boolean previousOnlineMode = server.usesAuthentication();
        enableDevelopmentOfflineAuth(server);
        int port = server.getPort();
        if (port <= 0) {
            port = findLocalPort();
            if (!server.publishServer(server.getDefaultGameType(), false, port)) {
                server.setUsesAuthentication(previousOnlineMode);
                return CompletableFuture.failedFuture(new IllegalStateException("Minecraft could not publish this world"));
            }
        }
        try {
            Session started = start(SessionMode.HOST, List.of("host", "--target", "127.0.0.1:" + port), server, previousOnlineMode);
            return awaitReady(started).thenApply(event -> {
                started.invite = event.invite();
                return event.invite();
            });
        } catch (Exception e) {
            stop();
            server.setUsesAuthentication(previousOnlineMode);
            return CompletableFuture.failedFuture(e);
        }
    }

    public synchronized boolean isHosting() {
        return session != null && session.mode == SessionMode.HOST && session.process.isAlive();
    }

    public synchronized String getInvite() {
        return session != null ? session.invite : null;
    }

    public synchronized CompletableFuture<Void> join(Minecraft client, Screen parent, String invitation) {
        stop();
        try {
            Session started = start(SessionMode.JOIN, List.of("join", "--invite", invitation.trim()), null, false);
            return awaitReady(started).thenAccept(event -> client.execute(() -> {
                ServerData info = new ServerData("Tailcat World", event.address(), ServerData.Type.OTHER);
                ConnectScreen.startConnecting(parent, client, ServerAddress.parseString(event.address()), info, false, null);
            }));
        } catch (Exception e) {
            stop();
            return CompletableFuture.failedFuture(e);
        }
    }

    public synchronized void tick(Minecraft client) {
        if (session == null) return;
        if (!session.process.isAlive()) { stop(); return; }
        if (session.mode == SessionMode.HOST
                && (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null || !client.getSingleplayerServer().isRunning())) {
            stop();
        } else if (session.mode == SessionMode.JOIN && client.level == null
                && !(client.screen instanceof ConnectScreen)
                && !(client.screen instanceof JoinRemoteScreen)) {
            stop();
        }
    }

    public enum TransportMode {
        WAITING("대기 중..."), RELAY("릴레이 (DERP)"), DIRECT("다이렉트 (P2P WireGuard)");
        private final String label;
        TransportMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    private volatile boolean isDirect = false;
    private volatile boolean hasRelayActivity = false;
    private volatile String relayRegionName = "";

    public synchronized TransportMode getTransportMode() {
        if (session == null || !session.process.isAlive()) return TransportMode.WAITING;
        return isDirect ? TransportMode.DIRECT : (hasRelayActivity ? TransportMode.RELAY : TransportMode.WAITING);
    }

    public synchronized String getRelayRegion() {
        return relayRegionName.isEmpty() ? "도쿄" : relayRegionName;
    }

    private void onHelperLog(String line) {
        if (line == null) return;
        String lower = line.toLowerCase();
        if (lower.contains("derp-")) {
            hasRelayActivity = true;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("derp-(\\d+)").matcher(line);
            if (m.find()) relayRegionName = mapDerpRegion(m.group(1));
        }
        if (lower.contains("now using") && (lower.contains(":") || lower.contains("mtu="))) isDirect = true;
    }

    private static String mapDerpRegion(String id) {
        return switch (id) {
            case "1" -> "뉴욕 (derp-1)"; case "2" -> "샌프란시스코 (derp-2)";
            case "3" -> "싱가포르 (derp-3)"; case "4" -> "프랑크푸르트 (derp-4)";
            case "5" -> "시드니 (derp-5)"; case "6" -> "상파울루 (derp-6)";
            case "7" -> "런던 (derp-7)"; case "8" -> "댈러스 (derp-8)";
            case "9" -> "시애틀 (derp-9)"; case "19" -> "파리 (derp-19)";
            case "24" -> "홍콩 (derp-24)";
            case "301", "302", "303", "304" -> "도쿄 (derp-" + id + ")";
            default -> "derp-" + id;
        };
    }

    public synchronized void stop() {
        if (session == null) return;
        Session stopped = session;
        session = null; isDirect = false; hasRelayActivity = false; relayRegionName = "";
        stopped.process.close();
        if (stopped.offlineAuthServer != null) stopped.offlineAuthServer.setUsesAuthentication(stopped.previousOnlineMode);
    }

    public String takeError() { String v = lastError; lastError = null; return v; }

    @Override public synchronized void close() { stop(); }

    private Session start(SessionMode mode, List<String> arguments, IntegratedServer offlineAuthServer, boolean previousOnlineMode) throws Exception {
        HelperProcess process = HelperProcess.start(arguments, this::onEvent, this::onHelperLog);
        session = new Session(mode, process, offlineAuthServer, previousOnlineMode);
        return session;
    }

    private CompletableFuture<HelperEvent> awaitReady(Session expected) {
        return expected.process.ready(STARTUP_TIMEOUT).whenComplete((event, error) -> {
            if (error != null) { synchronized (this) { if (session == expected) stop(); } }
        });
    }

    private void onEvent(HelperEvent event) {
        if (event.type().equals("error")) { lastError = event.message(); synchronized (this) { stop(); } }
    }

    private static void enableDevelopmentOfflineAuth(IntegratedServer server) {
        if ("1".equals(System.getenv("MCLINK_DEV_OFFLINE_AUTH"))) server.setUsesAuthentication(false);
    }

    private enum SessionMode { HOST, JOIN }

    private static class Session {
        final SessionMode mode; final HelperProcess process;
        final IntegratedServer offlineAuthServer; final boolean previousOnlineMode;
        volatile String invite;
        Session(SessionMode mode, HelperProcess process, IntegratedServer offlineAuthServer, boolean previousOnlineMode) {
            this.mode = mode; this.process = process;
            this.offlineAuthServer = offlineAuthServer; this.previousOnlineMode = previousOnlineMode;
        }
    }
}
