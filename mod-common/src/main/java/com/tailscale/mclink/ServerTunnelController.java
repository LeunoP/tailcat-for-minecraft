package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ServerTunnelController<P extends AutoCloseable> implements AutoCloseable {
    @FunctionalInterface public interface Launcher<P> {
        P start(List<String> arguments, boolean relayOnly, Consumer<HelperEvent> events, Consumer<String> logs) throws Exception;
    }
    private final Executor main;
    private final Launcher<P> launcher;
    private final Predicate<P> alive;
    private final Function<P, CompletableFuture<Void>> termination;
    private final Function<P, CompletableFuture<HelperEvent>> readiness;
    private final Path configFile;
    private final Path inviteFile;
    private final Path diagnosticDirectory;
    private final Consumer<String> info;
    private final Consumer<String> warning;
    private final Map<String, String> metadata;
    private final TunnelDiagnostics diagnostics = new TunnelDiagnostics();
    private CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);
    private AsyncProcessSession<P, HelperEvent> session;
    private ServerTunnelConfig config = ServerTunnelConfig.defaults();
    private String invite;
    private long generation;
    private boolean disposed;
    private Consumer<Predicate<SocketAddress>> beforeStop = owns -> {};

    public synchronized void onStop(Consumer<Predicate<SocketAddress>> callback) { beforeStop = callback; }

    public ServerTunnelController(Executor main, Launcher<P> launcher, Predicate<P> alive,
            Function<P, CompletableFuture<Void>> termination, Function<P, CompletableFuture<HelperEvent>> readiness,
            Path configFile, Path inviteFile, Path diagnosticDirectory,
            Map<String, String> metadata, Consumer<String> info, Consumer<String> warning) {
        this.main = main; this.launcher = launcher; this.alive = alive; this.termination = termination;
        this.readiness = readiness; this.configFile = configFile; this.inviteFile = inviteFile;
        this.diagnosticDirectory = diagnosticDirectory; this.metadata = Map.copyOf(metadata);
        this.info = info; this.warning = warning;
        removeInvite();
    }

    public synchronized void initialize(int port) {
        config = ServerTunnelConfig.load(configFile, warning);
        if (config.autoShare()) start(port);
    }

    public synchronized CompletableFuture<String> start(int port) {
        if (disposed) return CompletableFuture.failedFuture(new IllegalStateException("server stopped"));
        if (session != null && session.isActive()) return session.ready().thenApply(HelperEvent::invite);
        if (session != null) stop();
        if (port < 1 || port > 65535) return CompletableFuture.failedFuture(new IllegalArgumentException("invalid server port"));
        config = ServerTunnelConfig.load(configFile, warning);
        ServerTunnelConfig requested = config;
        long request = ++generation;
        CompletableFuture<String> result = new CompletableFuture<>();
        stopping.whenComplete((ignored, stopError) -> main.execute(() -> {
            synchronized (ServerTunnelController.this) {
                if (disposed || generation != request) { result.cancel(false); return; }
                if (stopError != null) { result.completeExceptionally(stopError); return; }
                diagnostics.reset(); removeInvite();
                AsyncProcessSession<P, HelperEvent> expected = new AsyncProcessSession<>(alive, termination);
                session = expected;
                info.accept("Starting Tailcat tunnel; TCP resource limit " + requested.tcpConnectionLimit() + ". Minecraft max-players is unchanged.");
                expected.start(requested.timeout(), (events, logs) -> launcher.start(
                        List.of("host", "--target", "127.0.0.1:" + port, "--max-connections", Integer.toString(requested.tcpConnectionLimit())),
                        requested.relayOnly(), events, logs), readiness,
                        event -> main.execute(() -> {
                            synchronized (ServerTunnelController.this) {
                                if (session != expected) return;
                                diagnostics.accept(event);
                                if ("error".equals(event.type()) || "stopped".equals(event.type())) fail(expected,
                                        new IOException(event.code() + ": " + event.message()));
                            }
                        }), line -> {
                            synchronized (ServerTunnelController.this) { if (session == expected) diagnostics.log(line); }
                        }, error -> main.execute(() -> fail(expected, error)));
                expected.ready().whenComplete((event, error) -> main.execute(() -> {
                    synchronized (ServerTunnelController.this) {
                        if (session != expected || !expected.isActive()) { result.cancel(false); return; }
                        if (error != null) { result.completeExceptionally(error); fail(expected, error); return; }
                        invite = event.invite();
                        try { Files.writeString(inviteFile, invite, StandardCharsets.UTF_8); info.accept("Tailcat invitation saved to " + inviteFile.toAbsolutePath()); }
                        catch (IOException failure) { warning.accept("Tailcat is running, but invitation file could not be written: " + failure.getMessage()); }
                        result.complete(invite);
                    }
                }));
            }
        }));
        return result;
    }

    public synchronized CompletableFuture<String> restart(int port) { stop(); return start(port); }

    public synchronized CompletableFuture<Void> stop() {
        generation++;
        AsyncProcessSession<P, HelperEvent> old = session;
        if (old != null) {
            try { beforeStop.accept(diagnostics::owns); }
            catch (RuntimeException error) { warning.accept("Could not notify Tailcat players: " + error.getMessage()); }
        }
        session = null; invite = null; removeInvite(); diagnostics.stopped();
        if (old != null) {
            CompletableFuture.runAsync(old::close, CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS, HelperTasks.executor()));
            stopping = stopping.handle((ignored, error) -> null).thenCompose(ignored -> old.closed());
        }
        return stopping;
    }

    private synchronized void fail(AsyncProcessSession<P, HelperEvent> expected, Throwable error) {
        if (session != expected) return;
        diagnostics.failed(error);
        warning.accept("Tailcat tunnel failed: " + TunnelDiagnostics.redact(error.getMessage()));
        stop();
    }

    private void removeInvite() {
        try { Files.deleteIfExists(inviteFile); }
        catch (IOException error) { warning.accept("Could not remove stale Tailcat invitation: " + error.getMessage()); }
    }

    public synchronized String invite() { return session != null && session.isAlive() ? invite : null; }
    public synchronized boolean owns(SocketAddress address) { return session != null && session.isAlive() && diagnostics.owns(address); }
    public synchronized String route(SocketAddress address) { return owns(address) ? diagnostics.route(address) : "not_tailcat"; }
    public synchronized int connectionStatus(SocketAddress address) {
        return RouteStatus.encode(owns(address), route(address));
    }
    public synchronized ServerTunnelConfig config() { return config; }
    public CompletableFuture<Path> diagnose() { return CompletableFuture.supplyAsync(() -> {
        try { return diagnostics.export(diagnosticDirectory, metadata); }
        catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
    }, HelperTasks.executor()); }
    public synchronized String phase() { return diagnostics.phase(); }
    @Override public synchronized void close() { disposed = true; stop(); }
}
