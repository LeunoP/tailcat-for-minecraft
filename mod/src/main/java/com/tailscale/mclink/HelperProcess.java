package com.tailscale.mclink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public final class HelperProcess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("mclink/helper");
    private final Process process;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<HelperEvent> ready = new CompletableFuture<>();

    private HelperProcess(Process process, Consumer<HelperEvent> events) {
        this.process = process;
        Thread.ofVirtual().name("mclink-helper-stdout").start(() -> readStdout(events));
        Thread.ofVirtual().name("mclink-helper-stderr").start(this::drainStderr);
        process.onExit().thenAccept(p -> {
            if (!closing.get()) {
                IOException failure = new IOException("helper exited unexpectedly (status " + p.exitValue() + ")");
                ready.completeExceptionally(failure);
                events.accept(new HelperEvent("error", null, null, null, "unexpected_exit", failure.getMessage()));
            }
        });
    }

    public static HelperProcess start(List<String> arguments, Consumer<HelperEvent> events) throws IOException {
        Path executable = NativeHelper.extract();
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        return new HelperProcess(new ProcessBuilder(command).start(), events);
    }

    public CompletableFuture<HelperEvent> ready(Duration timeout) {
        return ready.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void readStdout(Consumer<HelperEvent> events) {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                HelperEvent event = HelperEvent.parse(line);
                if (event.type().equals("ready")) {
                    ready.complete(event);
                } else if (event.type().equals("error")) {
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
                LOG.info("{}", line);
            }
        } catch (IOException e) {
            if (!closing.get()) {
                LOG.warn("Could not drain helper stderr", e);
            }
        }
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
            LOG.warn("Could not close helper stdin", e);
        }
    }
}
