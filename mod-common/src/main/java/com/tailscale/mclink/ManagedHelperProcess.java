package com.tailscale.mclink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ManagedHelperProcess implements AutoCloseable {
    private static final int MAX_LINE_LENGTH = 16 * 1024;
    private static final Set<Process> CHILDREN = ConcurrentHashMap.newKeySet();
    private static final Object PROCESS_LOCK = new Object();
    private static boolean shuttingDown;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<Process> children;
            synchronized (PROCESS_LOCK) {
                shuttingDown = true;
                children = List.copyOf(CHILDREN);
            }
            for (Process child : children) {
                try { child.getOutputStream().close(); } catch (IOException ignored) {}
                child.destroy();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            for (Process child : children) {
                try {
                    long remaining = Math.max(0, deadline - System.nanoTime());
                    if (!child.waitFor(remaining, TimeUnit.NANOSECONDS)) child.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    child.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }, "tailcat-helper-shutdown"));
    }

    private final Process process;
    private final Consumer<HelperEvent> events;
    private final Consumer<String> logs;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    public static ManagedHelperProcess start(ProcessBuilder builder, Consumer<HelperEvent> events,
                                             Consumer<String> logs) throws IOException {
        synchronized (PROCESS_LOCK) {
            if (shuttingDown) throw new IOException("Tailcat process launcher is shutting down");
            return new ManagedHelperProcess(builder.start(), events, logs);
        }
    }

    ManagedHelperProcess(Process process, Consumer<HelperEvent> events, Consumer<String> logs) {
        this.process = process;
        this.events = events;
        this.logs = logs == null ? line -> {} : logs;
        CHILDREN.add(process);
        HelperTasks.execute(this::readStdout);
        HelperTasks.execute(this::readStderr);
        process.onExit().thenAccept(exited -> {
            CHILDREN.remove(exited);
            closed.complete(null);
        });
    }

    public CompletableFuture<HelperEvent> ready(Duration timeout) {
        return ready.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Void> closed() {
        return closed;
    }

    public boolean isAlive() {
        return !closing.get() && process.isAlive();
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLine(reader)) != null) {
                if (closing.get()) return;
                HelperEvent event = HelperEvent.parse(line);
                if ("error".equals(event.type())) {
                    reportFailure(event.code() == null ? "helper_failed" : event.code(),
                            new IOException(event.message() == null ? "helper failed" : event.message()));
                    return;
                }
                if ("ready".equals(event.type())) ready.complete(event);
                events.accept(event);
                if ("stopped".equals(event.type())) {
                    close();
                    return;
                }
            }
            if (!closing.get()) {
                if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    reportFailure("protocol_closed", new IOException("helper closed its event stream"));
                } else {
                    reportFailure("unexpected_exit", new IOException(
                            "helper exited unexpectedly (status " + process.exitValue() + ")"));
                }
            }
        } catch (Exception error) {
            reportFailure("protocol_error", error);
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLine(reader)) != null) {
                if (closing.get()) return;
                logs.accept(TunnelDiagnostics.redact(line));
            }
        } catch (Exception error) {
            if (!closing.get()) {
                System.getLogger(ManagedHelperProcess.class.getName())
                        .log(System.Logger.Level.WARNING, "Could not read helper diagnostics", error);
            }
        }
    }

    private void reportFailure(String code, Exception error) {
        if (closing.get() || !failureReported.compareAndSet(false, true)) return;
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        ready.completeExceptionally(new HelperFailure(code, message, error));
        try {
            events.accept(new HelperEvent("error", null, null, null, code, message));
        } finally {
            close();
        }
    }

    private static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (ch != '\r') line.append((char) ch);
            if (line.length() > MAX_LINE_LENGTH) throw new IOException("helper line exceeds size limit");
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        ready.cancel(false);
        try {
            process.getOutputStream().close();
        } catch (IOException error) {
            process.destroy();
        }
        HelperTasks.execute(() -> {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                }
                if (process.isAlive() && !process.waitFor(2, TimeUnit.SECONDS)) {
                    closed.completeExceptionally(new IOException("helper did not terminate after being killed"));
                    return;
                }
                CHILDREN.remove(process);
                closed.complete(null);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                closed.completeExceptionally(interrupted);
                Thread.currentThread().interrupt();
            }
        });
    }
}
