package com.tailscale.mclink;

import java.math.BigInteger;

import com.tailscale.mclink.mixin.IntegratedServerAccessor;
import com.tailscale.mclink.mixin.PlayerManagerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.NetworkUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ScreenState implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    private Session session;
    private java.util.concurrent.Executor clientExecutor;
    private HostPlayerLimit.Lease playerLimitLease;
    private PublicationLease<?> publicationLease;
    private CompletableFuture<Void> restoring = CompletableFuture.completedFuture(null);
    private long operationId;
    private int routeStatusTicks;
    private volatile String lastError;
    private final TunnelDiagnostics diagnostics = new TunnelDiagnostics();

    public synchronized CompletableFuture<String> share(MinecraftClient client) {
        return share(client, false, BigInteger.valueOf(8));
    }

    public synchronized CompletableFuture<String> share(MinecraftClient client, boolean relayOnly, BigInteger requestedMaxPlayers) {
        clientExecutor = client::execute;
        if (!restoring.isDone()) {
            long request = operationId;
            return restoring.thenCompose(ignored -> {
                CompletableFuture<String> resumed = new CompletableFuture<>();
                client.execute(() -> {
                    synchronized (ScreenState.this) {
                        if (operationId != request) { resumed.cancel(false); return; }
                        share(client, relayOnly, requestedMaxPlayers).whenComplete((value, error) -> {
                            if (error == null) resumed.complete(value); else resumed.completeExceptionally(error);
                        });
                    }
                });
                return resumed;
            });
        }
        if (session != null && session.mode == SessionMode.HOST && session.process.isAlive()) {
            if (session.invite != null) {
                return CompletableFuture.completedFuture(session.invite);
            }
            Session expected = session;
            return awaitReady(expected).thenApply(event -> {
                expected.invite = event.invite();
                return event.invite();
            });
        }
        stop();
        IntegratedServer server = client.getServer();
        if (!restoring.isDone()) return share(client, relayOnly, requestedMaxPlayers);
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No integrated server is running"));
        }
        boolean previousOnlineMode = server.isOnlineMode();
        enableDevelopmentOfflineAuth(server);
        int port = ((IntegratedServerAccessor) server).mclink$getLanPort();
        if (port <= 0) {
            publicationLease = capturePublication(server);
            port = NetworkUtils.findLocalPort();
            if (!server.openToLan(server.getDefaultGameMode(), false, port)) {
                publicationLease.published();
                publicationLease.restore();
                publicationLease = null;
                server.setOnlineMode(previousOnlineMode);
                return CompletableFuture.failedFuture(new IllegalStateException("Minecraft could not publish this world"));
            }
            publicationLease.published();
        }
        try {
            playerLimitLease = HostPlayerLimit.applyField(server.getPlayerManager(),
                    HostPlayerLimit.minecraftLimit(requestedMaxPlayers),
                    target -> ((PlayerManagerAccessor) target).mclink$getMaxPlayers(),
                    (target, value) -> ((PlayerManagerAccessor) target).mclink$setMaxPlayers(value));
            Session started = start(SessionMode.HOST, List.of("host", "--target", "127.0.0.1:" + port, "--max-connections", HostPlayerLimit.tcpResourceLimit(requestedMaxPlayers).toString()),
                    server, previousOnlineMode, relayOnly);
            return awaitReady(started).thenApply(event -> {
                started.invite = event.invite();
                return event.invite();
            });
        } catch (Exception e) {
            stop();
            server.setOnlineMode(previousOnlineMode);
            return CompletableFuture.failedFuture(e);
        }
    }

    public synchronized boolean isHosting() {
        return session != null && session.mode == SessionMode.HOST && session.process.isAlive();
    }

    public synchronized boolean hasActiveSession() {
        return session != null && session.process.isAlive();
    }

    public synchronized boolean keepsExistingLan() {
        return isHosting() && publicationLease == null;
    }

    public synchronized String getInvite() {
        return session != null ? session.invite : null;
    }

    public synchronized CompletableFuture<Void> join(MinecraftClient client, Screen parent, String invitation, boolean relayOnly) {
        clientExecutor = client::execute;
        stop();
        try {
            Session started = start(SessionMode.JOIN, List.of("join", "--invite", invitation.trim()), null, false, relayOnly);
            started.pendingInvitation = invitation.trim();
            started.previousWorld = client.world;
            started.relayOnly = relayOnly;
            return awaitReady(started).thenAccept(event -> client.execute(() -> {
                if (!isCurrent(started)) return;
                ServerInfo info = new ServerInfo("Tailcat World", event.address(), ServerInfo.ServerType.OTHER);
                ConnectScreen.connect(parent, client, ServerAddress.parse(event.address()), info, false, null);
            }));
        } catch (Exception e) {
            stop();
            return CompletableFuture.failedFuture(e);
        }
    }

    public synchronized void tick(MinecraftClient client) {
        if (session != null && session.mode == SessionMode.HOST && ++routeStatusTicks >= 20) {
            routeStatusTicks = 0;
            IntegratedServer hostServer = client.getServer();
            if (hostServer != null) hostServer.execute(() -> {
                synchronized (ScreenState.this) {
                    if (session == null || session.mode != SessionMode.HOST || session.invite == null) {
                        RouteStatusPublisher.clear(hostServer);
                        return;
                    }
                    RouteStatusPublisher.publish(hostServer, address -> RouteStatus.encode(
                            diagnostics.owns(address), diagnostics.route(address)));
                }
            });
        }
        if (session == null) {
            return;
        }
        if (session.mode == SessionMode.JOIN && client.world != null && client.world != session.previousWorld
                && session.pendingInvitation != null) {
            LastSuccessfulConnection.remember(session.pendingInvitation, session.relayOnly);
            session.pendingInvitation = null;
            session.previousWorld = null;
            diagnostics.connected();
        }
        if (!session.process.isStarting() && !session.process.isAlive()) {
            stop();
            return;
        }
        if (session.mode == SessionMode.HOST
                && (!client.isIntegratedServerRunning() || client.getServer() == null || !client.getServer().isRunning())) {
            stop();
        } else if (session.mode == SessionMode.JOIN && client.world == null
                && !(client.currentScreen instanceof ConnectScreen)
                && !(client.currentScreen instanceof JoinRemoteScreen)) {
            stop();
        }
    }

    public enum TransportMode {
        WAITING("mclink.transport_unknown"), RELAY("mclink.transport_relay"), DIRECT("mclink.transport_direct"), MIXED("mclink.transport_mixed");
        private final String key;
        TransportMode(String key) { this.key = key; }
        public String label() { return net.minecraft.text.Text.translatable(key).getString(); }
    }

    public synchronized TransportMode getTransportMode() {
        if (session == null || !session.process.isAlive()) return TransportMode.WAITING;
        return switch (diagnostics.transport()) {
            case "direct" -> TransportMode.DIRECT;
            case "relay" -> TransportMode.RELAY;
            case "mixed" -> TransportMode.MIXED;
            default -> TransportMode.WAITING;
        };
    }

    public synchronized String getRelayRegion() {
        return diagnostics.region().isEmpty() ? net.minecraft.text.Text.translatable("mclink.unknown_region").getString() : diagnostics.region();
    }
    public String routeLabel(java.net.SocketAddress address) {
        return switch (diagnostics.route(address)) { case "direct" -> "P2P"; case "relay" -> "DERP"; default -> "?"; };
    }
    public String phaseKey() { return "mclink.phase." + diagnostics.phase(); }
    public String failureKey() {
        String code = diagnostics.errorCode();
        return "mclink.failure." + (code == null ? "helper_failed" : switch (code) {
            case "invalid_invite", "derp_unreachable", "backend_unreachable", "remote_unreachable", "capacity", "timeout" -> code;
            default -> "helper_failed";
        });
    }
    public synchronized boolean owns(java.net.SocketAddress address) {
        return session != null && session.mode == SessionMode.HOST && diagnostics.owns(address);
    }
    public CompletableFuture<java.nio.file.Path> diagnose() {
        return CompletableFuture.supplyAsync(() -> {
            try { return diagnostics.export(java.nio.file.Path.of("tailcat-for-minecraft", "diagnostics"),
                    BuildIdentity.metadata("mod-fabric-1.21.1")); }
            catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
        }, HelperTasks.executor());
    }

    private synchronized void onHelperLog(Session expected, String line) {
        if (session == expected) diagnostics.log(line);
    }

    public synchronized void stop() {
        operationId++;
        HostPlayerLimit.Lease stoppedLimit = playerLimitLease;
        PublicationLease<?> stoppedPublication = publicationLease;
        playerLimitLease = null; publicationLease = null;
        Session stopped = session;
        session = null;
        routeStatusTicks = 0;
        diagnostics.stopped();
        if (stopped != null) stopped.process.close();
        IntegratedServer server = stopped == null ? null : stopped.offlineAuthServer;
        Runnable restore = () -> {
            if (stopped != null && stopped.mode == SessionMode.HOST && server != null) RouteStatusPublisher.clear(server);
            if (stoppedPublication != null) stoppedPublication.restore();
            if (stoppedLimit != null) stoppedLimit.restore();
            if (server != null) server.setOnlineMode(stopped.previousOnlineMode);
        };
        CompletableFuture<Void> restored;
        if (server != null && server.isRunning()) restored = CompletableFuture.runAsync(restore, server::execute);
        else { restore.run(); restored = CompletableFuture.completedFuture(null); }
        restoring = CompletableFuture.allOf(restoring, restored,
                stopped == null ? CompletableFuture.completedFuture(null) : stopped.process.closed());
    }

    public synchronized Runnable stopAction() {
        Session expected = session;
        return () -> {
            synchronized (ScreenState.this) {
                if (session == expected) stop();
            }
        };
    }

    public synchronized String takeError() {
        String value = lastError;
        lastError = null;
        return value;
    }

    @Override
    public synchronized void close() {
        stop();
    }

    private Session start(SessionMode mode, List<String> arguments, IntegratedServer offlineAuthServer,
                          boolean previousOnlineMode) throws Exception {
        return start(mode, arguments, offlineAuthServer, previousOnlineMode, false);
    }

    private Session start(SessionMode mode, List<String> arguments, IntegratedServer offlineAuthServer,
                          boolean previousOnlineMode, boolean relayOnly) throws Exception {
        Session started = new Session(mode, offlineAuthServer, previousOnlineMode, clientExecutor);
        session = started;
        lastError = null;
        diagnostics.reset();
        CompletableFuture<Void> cleanup = restoring;
        started.process.start(STARTUP_TIMEOUT,
                (events, logs) -> {
                    cleanup.join();
                    if (!isCurrent(started)) throw new java.util.concurrent.CancellationException();
                    return HelperProcess.start(arguments, events, logs, relayOnly);
                },
                process -> process.ready(STARTUP_TIMEOUT),
                event -> onEvent(started, event), line -> onHelperLog(started, line),
                error -> onFailure(started, error));
        return started;
    }

    private CompletableFuture<HelperEvent> awaitReady(Session expected) {
        return expected.process.ready().thenApply(event -> {
            if (!isCurrent(expected)) throw new java.util.concurrent.CancellationException();
            return event;
        });
    }

    private synchronized boolean isCurrent(Session expected) {
        return session == expected && expected.process.isActive();
    }

    private void onEvent(Session expected, HelperEvent event) {
        synchronized (this) {
            if (session != expected) return;
            diagnostics.accept(event);
            if ("warning".equals(event.type())) lastError = net.minecraft.text.Text.translatable(failureKey()).getString();
        }
        if ("error".equals(event.type()) || "stopped".equals(event.type())) {
            String message = event.message() == null ? "Tailcat helper stopped" : event.message();
            onFailure(expected, new IOException(message));
        }
    }

    private void onFailure(Session expected, Throwable error) {
        synchronized (this) { if (session != expected) return; diagnostics.failed(error); }
        while (error.getCause() != null) error = error.getCause();
        if (error instanceof java.util.concurrent.CancellationException) return;
        synchronized (this) {
            if (session != expected) return;
            lastError = net.minecraft.text.Text.translatable(failureKey()).getString();
        }
        expected.clientExecutor.execute(() -> {
            synchronized (ScreenState.this) {
                if (session == expected) stop();
            }
        });
    }

    private static PublicationLease<?> capturePublication(IntegratedServer server) {
        com.tailscale.mclink.mixin.LanPublicationAccessor state = (com.tailscale.mclink.mixin.LanPublicationAccessor) server;
        int port = state.mclink$getPublishedPort();
        var gameType = state.mclink$getPublishedGameType();
        var pinger = state.mclink$getPinger();
        boolean commands = server.getPlayerManager().areCheatsAllowed();
        var listeners = ((com.tailscale.mclink.mixin.LanListenersAccessor) server.getNetworkIo()).mclink$getListeners();
        return new PublicationLease<>(listeners, listener -> listener.channel().close(), () -> {
            var ownedPinger = state.mclink$getPinger();
            if (ownedPinger != null && ownedPinger != pinger) ownedPinger.interrupt();
            state.mclink$setPinger(pinger);
            state.mclink$setPublishedPort(port);
            state.mclink$setPublishedGameType(gameType);
            server.getPlayerManager().setCheatsAllowed(commands);
            for (var player : server.getPlayerManager().getPlayerList()) {
                server.getPlayerManager().sendCommandTree(player);
            }
        });
    }

    private static void enableDevelopmentOfflineAuth(IntegratedServer server) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()
                && "1".equals(System.getenv("MCLINK_DEV_OFFLINE_AUTH"))) {
            server.setOnlineMode(false);
        }
    }

    private enum SessionMode { HOST, JOIN }

    private static final class Session {
        final SessionMode mode;
        final AsyncProcessSession<HelperProcess, HelperEvent> process =
                new AsyncProcessSession<>(HelperProcess::isAlive, HelperProcess::closed);
        final IntegratedServer offlineAuthServer;
        final boolean previousOnlineMode;
        final java.util.concurrent.Executor clientExecutor;
        volatile String invite;
        volatile String pendingInvitation;
        Object previousWorld;
        boolean relayOnly;

        Session(SessionMode mode, IntegratedServer offlineAuthServer,
                boolean previousOnlineMode, java.util.concurrent.Executor clientExecutor) {
            this.mode = mode;
            this.offlineAuthServer = offlineAuthServer;
            this.previousOnlineMode = previousOnlineMode;
            this.clientExecutor = clientExecutor;
        }
    }
}
