package com.tailscale.mclink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class PluginHelperProcess implements AutoCloseable {
    private final Process process;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
    private final Consumer<String> logConsumer;

    private PluginHelperProcess(Process process, Consumer<HelperEvent> events, Consumer<String> logConsumer) {
        this.process = process;
        this.logConsumer = logConsumer;
        Thread stdoutThread = new Thread(() -> readStdout(events), "tailcat-plugin-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();
        Thread stderrThread = new Thread(this::drainStderr, "tailcat-plugin-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
        process.onExit().thenAccept(p -> {
            if (!closing.get()) {
                IOException failure = new IOException("helper exited unexpectedly (status " + p.exitValue() + ")");
                ready.completeExceptionally(failure);
                events.accept(new HelperEvent("error", null, null, null, "unexpected_exit", failure.getMessage()));
            }
        });
    }

    public static PluginHelperProcess start(Path dataFolder, String version, List<String> arguments,
                                            Consumer<HelperEvent> events) throws IOException {
        return start(dataFolder, version, arguments, events, null);
    }

    public static PluginHelperProcess start(Path dataFolder, String version, List<String> arguments,
                                            Consumer<HelperEvent> events, Consumer<String> logConsumer) throws IOException {
        Path executable = PluginNativeHelper.extract(dataFolder, version);
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        return new PluginHelperProcess(new ProcessBuilder(command).start(), events, logConsumer);
    }

    public CompletableFuture<HelperEvent> ready(Duration timeout) {
        return ready.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void readStdout(Consumer<HelperEvent> events) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                HelperEvent event = HelperEvent.parse(line);
                if ("ready".equals(event.type())) {
                    ready.complete(event);
                } else if ("error".equals(event.type())) {
                    ready.completeExceptionally(new IOException(event.code() + ": " + event.message()));
                }
                events.accept(event);
            }
        } catch (Exception e) {
            if (!closing.get()) {
                ready.completeExceptionally(e);
            }
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (logConsumer != null) {
                    try {
                        logConsumer.accept(line);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            process.destroyForcibly();
        }
    }
}
