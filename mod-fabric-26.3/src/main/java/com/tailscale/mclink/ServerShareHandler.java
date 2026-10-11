package com.tailscale.mclink;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ServerShareHandler {
    private static final Logger LOG = LoggerFactory.getLogger("Tailcat/Server");
    private static MinecraftServer owner;
    private static ServerTunnelController<HelperProcess> tunnel;
    private ServerShareHandler() {}

    public static synchronized void onServerStarted(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        if (owner == server && tunnel != null) return;
        if (tunnel != null) tunnel.close();
        owner = server;
        tunnel = new ServerTunnelController<>(server::execute,
                (args, relayOnly, events, logs) -> HelperProcess.start(args, events, logs, relayOnly),
                HelperProcess::isAlive, HelperProcess::closed, process -> process.ready(Duration.ofSeconds(120)),
                Path.of("config", "tailcat-server.json"), Path.of("tailcat_invite.txt"),
                Path.of("tailcat-for-minecraft", "diagnostics"),
                BuildIdentity.metadata("mod-fabric-26.3"), LOG::info, LOG::warn);
        tunnel.onStop(owns -> {
            for (var player : server.getPlayerList().getPlayers()) {
                if (player.connection != null && owns.test(player.connection.getRemoteAddress()))
                    player.connection.disconnect(net.minecraft.network.chat.Component.translatable("mclink.stopped_message"));
            }
        });
        tunnel.initialize(port(server));
        scheduleRouteRefresh(server);
    }

    public static synchronized void onServerStopped(MinecraftServer server) {
        if (owner != server) return;
        RouteStatusPublisher.clear(server);
        if (tunnel != null) tunnel.close();
        tunnel = null; owner = null;
    }

    private static void scheduleRouteRefresh(MinecraftServer server) {
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS, HelperTasks.executor()).execute(() -> {
            synchronized (ServerShareHandler.class) {
                if (owner != server) return;
            }
            try {
                server.execute(() -> {
                    synchronized (ServerShareHandler.class) {
                        if (owner != server) return;
                        refreshRoutes(server);
                        scheduleRouteRefresh(server);
                    }
                });
            } catch (RuntimeException ignored) {
                // The server may already be shutting down.
            }
        });
    }

    private static void refreshRoutes(MinecraftServer server) {
        if (owner != server || tunnel == null || tunnel.invite() == null) {
            RouteStatusPublisher.clear(server);
            return;
        }
        ServerTunnelController<HelperProcess> activeTunnel = tunnel;
        RouteStatusPublisher.publish(server, address -> RouteStatus.encode(
                activeTunnel.owns(address), activeTunnel.route(address)));
    }

    public static synchronized CompletableFuture<?> operate(MinecraftServer server, String action) {
        if (owner != server || tunnel == null) return CompletableFuture.failedFuture(new IllegalStateException("Tailcat dedicated-server service is unavailable"));
        return switch (action) {
            case "start" -> tunnel.start(port(server));
            case "stop" -> tunnel.stop();
            case "restart" -> tunnel.restart(port(server));
            case "diagnose" -> tunnel.diagnose();
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown Tailcat action"));
        };
    }

    private static int port(MinecraftServer server) { return server.getPort() > 0 ? server.getPort() : 25565; }
    public static synchronized String getCurrentInvite() { return tunnel == null ? null : tunnel.invite(); }
    public static synchronized boolean owns(SocketAddress address) { return tunnel != null && tunnel.owns(address); }
    public static synchronized int connectionStatus(SocketAddress address) {
        return tunnel == null ? RouteStatus.NOT_TAILCAT : tunnel.connectionStatus(address);
    }
    public static synchronized String route(SocketAddress address) { return tunnel == null ? "not_tailcat" : tunnel.route(address); }
    public static synchronized String language() { return tunnel == null ? "en_us" : tunnel.config().language(); }
}
