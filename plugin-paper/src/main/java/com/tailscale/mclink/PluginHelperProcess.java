package com.tailscale.mclink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class PluginHelperProcess implements AutoCloseable {
    private final ManagedHelperProcess process;

    private PluginHelperProcess(ProcessBuilder builder, Consumer<HelperEvent> events, Consumer<String> logs) throws IOException {
        this.process = ManagedHelperProcess.start(builder, events, logs);
    }

    public static PluginHelperProcess start(Path dataFolder, String version, List<String> arguments,
                                            Consumer<HelperEvent> events) throws IOException {
        return start(dataFolder, version, arguments, events, null);
    }

    public static PluginHelperProcess start(Path dataFolder, String version, List<String> arguments,
                                            Consumer<HelperEvent> events, Consumer<String> logs) throws IOException {
        Path executable = PluginNativeHelper.extract(dataFolder, version);
        if (logs != null) logs.accept(HelperIdentity.describe(executable));
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        command.addAll(List.of("--protocol", "2"));
        command.add("--relay-only=false");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("TS_DEBUG_ALWAYS_USE_DERP");
        return new PluginHelperProcess(builder, events, logs);
    }

    public CompletableFuture<HelperEvent> ready(Duration timeout) {
        return process.ready(timeout);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public CompletableFuture<Void> closed() { return process.closed(); }

    public static PluginHelperProcess start(Path dataFolder, String version, List<String> arguments,
            Consumer<HelperEvent> events, Consumer<String> logs, boolean relayOnly) throws IOException {
        Path executable = PluginNativeHelper.extract(dataFolder, version);
        if (logs != null) logs.accept(HelperIdentity.describe(executable));
        List<String> command = new ArrayList<>();
        command.add(executable.toString()); command.addAll(arguments); command.addAll(List.of("--protocol", "2"));
        command.add("--relay-only=" + relayOnly);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("TS_DEBUG_ALWAYS_USE_DERP");
        return new PluginHelperProcess(builder, events, logs);
    }

    @Override
    public void close() {
        process.close();
    }
}
