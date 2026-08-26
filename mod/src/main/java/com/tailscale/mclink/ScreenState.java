package com.tailscale.mclink;

import com.tailscale.mclink.mixin.IntegratedServerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.NetworkUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ScreenState implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    private Session session;
    private volatile String lastError;

    public synchronized CompletableFuture<String> share(MinecraftClient client) {
        stop();
        IntegratedServer server = client.getServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No integrated server is running"));
        }
        boolean previousOnlineMode = server.isOnlineMode();
        enableDevelopmentOfflineAuth(server);
        int port = ((IntegratedServerAccessor) server).mclink$getLanPort();
        if (port <= 0) {
            port = NetworkUtils.findLocalPort();
            if (!server.openToLan(server.getDefaultGameMode(), false, port)) {
                server.setOnlineMode(previousOnlineMode);
                return CompletableFuture.failedFuture(new IllegalStateException("Minecraft could not publish this world"));
            }
        }
        try {
            Session started = start(SessionMode.HOST, List.of("host", "--target", "127.0.0.1:" + port),
                    server, previousOnlineMode);
            return awaitReady(started).thenApply(HelperEvent::invite);
        } catch (Exception e) {
            stop();
            server.setOnlineMode(previousOnlineMode);
            return CompletableFuture.failedFuture(e);
        }
    }

    public synchronized CompletableFuture<Void> join(MinecraftClient client, Screen parent, String invitation) {
        stop();
        try {
            Session started = start(SessionMode.JOIN, List.of("join", "--invite", invitation.trim()), null, false);
            return awaitReady(started).thenAccept(event -> client.execute(() -> {
                ServerInfo info = new ServerInfo("Tailcat World", event.address(), ServerInfo.ServerType.OTHER);
                ConnectScreen.connect(parent, client, ServerAddress.parse(event.address()), info, false, null);
            }));
        } catch (Exception e) {
            stop();
            return CompletableFuture.failedFuture(e);
        }
    }

    public synchronized void tick(MinecraftClient client) {
        if (session == null) {
            return;
        }
        if (!session.process.isAlive()) {
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

    public synchronized void stop() {
        if (session == null) {
            return;
        }
        Session stopped = session;
        session = null;
        stopped.process.close();
        if (stopped.offlineAuthServer != null) {
            stopped.offlineAuthServer.setOnlineMode(stopped.previousOnlineMode);
        }
    }

    public String takeError() {
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
        HelperProcess process = HelperProcess.start(arguments, event -> onEvent(event));
        session = new Session(mode, process, offlineAuthServer, previousOnlineMode);
        return session;
    }

    private CompletableFuture<HelperEvent> awaitReady(Session expected) {
        return expected.process.ready(STARTUP_TIMEOUT).whenComplete((event, error) -> {
            if (error != null) {
                synchronized (this) {
                    if (session == expected) {
                        stop();
                    }
                }
            }
        });
    }

    private void onEvent(HelperEvent event) {
        if (event.type().equals("error")) {
            lastError = event.message();
            synchronized (this) {
                stop();
            }
        }
    }

    private static void enableDevelopmentOfflineAuth(IntegratedServer server) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()
                && "1".equals(System.getenv("MCLINK_DEV_OFFLINE_AUTH"))) {
            server.setOnlineMode(false);
        }
    }

    private enum SessionMode { HOST, JOIN }

    private record Session(SessionMode mode, HelperProcess process, IntegratedServer offlineAuthServer,
                           boolean previousOnlineMode) {}
}
