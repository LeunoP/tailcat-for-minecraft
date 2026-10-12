package com.tailscale.mclink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class HelperProcess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("mclink/helper");
    private final ManagedHelperProcess process;

    private HelperProcess(ProcessBuilder builder, Consumer<HelperEvent> events, Consumer<String> logs) throws IOException {
        this.process = ManagedHelperProcess.start(builder, events, line -> {
            if (!line.contains("Receiving keepalive") && !line.contains("wg: [v2] peer")) {
                LOG.debug("{}", line);
            }
            if (logs != null) logs.accept(line);
        });
    }

    public static HelperProcess start(List<String> arguments, Consumer<HelperEvent> events) throws IOException {
        return start(arguments, events, null, false);
    }

    public static HelperProcess start(List<String> arguments, Consumer<HelperEvent> events,
                                      Consumer<String> logs) throws IOException {
        return start(arguments, events, logs, false);
    }

    public static HelperProcess start(List<String> arguments, Consumer<HelperEvent> events,
                                      Consumer<String> logs, boolean relayOnly) throws IOException {
        Path executable = NativeHelper.extract();
        if (logs != null) logs.accept(HelperIdentity.describe(executable));
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        command.addAll(List.of("--protocol", "2"));
        command.add("--relay-only=" + relayOnly);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("TS_DEBUG_ALWAYS_USE_DERP");
        return new HelperProcess(builder, events, logs);
    }

    public CompletableFuture<HelperEvent> ready(Duration timeout) {
        return process.ready(timeout);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public CompletableFuture<Void> closed() { return process.closed(); }

    @Override
    public void close() {
        process.close();
    }
}
