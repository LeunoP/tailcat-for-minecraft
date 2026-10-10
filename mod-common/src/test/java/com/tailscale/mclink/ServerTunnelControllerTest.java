package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ServerTunnelControllerTest {
    @TempDir Path directory;
    private static final class Fake implements AutoCloseable {
        final CompletableFuture<HelperEvent> ready = new CompletableFuture<>();
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        final Consumer<HelperEvent> events;
        volatile boolean closing;
        Fake(Consumer<HelperEvent> events) { this.events = events; }
        public void close() { closing = true; }
    }
    private ServerTunnelController<Fake> controller(CopyOnWriteArrayList<Fake> created) {
        return new ServerTunnelController<>(Runnable::run, (args, relay, events, logs) -> {
            Fake fake = new Fake(events); created.add(fake); return fake;
        }, fake -> !fake.closing, fake -> fake.ended, fake -> fake.ready,
                directory.resolve("config.json"), directory.resolve("invite.txt"), directory.resolve("diagnostics"), Map.of(), value -> {}, value -> {});
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test void restartWaitsForActualTerminationAndDropsOldEvents() throws Exception {
        var created = new CopyOnWriteArrayList<Fake>(); var controller = controller(created);
        var first = controller.start(25565); await(() -> created.size() == 1);
        created.get(0).ready.complete(new HelperEvent("ready", "host", "mcl1_first", null, null, null));
        assertEquals("mcl1_first", first.get(3, TimeUnit.SECONDS));
        var second = controller.restart(25565); await(() -> created.get(0).closing);
        assertEquals(1, created.size()); assertNull(controller.invite()); assertFalse(Files.exists(directory.resolve("invite.txt")));
        created.get(0).ended.complete(null); await(() -> created.size() == 2);
        created.get(1).ready.complete(new HelperEvent("ready", "host", "mcl1_second", null, null, null));
        assertEquals("mcl1_second", second.get(3, TimeUnit.SECONDS));
        created.get(0).events.accept(new HelperEvent("error", null, null, null, "helper_failed", "late"));
        assertEquals("mcl1_second", controller.invite());
        controller.close(); created.get(1).ended.complete(null);
    }
    @Test void cancelDuringStopDoesNotStartReplacement() throws Exception {
        var created = new CopyOnWriteArrayList<Fake>(); var controller = controller(created);
        controller.start(25565); await(() -> created.size() == 1);
        var restart = controller.restart(25565); controller.stop(); created.get(0).ended.complete(null);
        await(restart::isDone); assertTrue(restart.isCancelled()); assertEquals(1, created.size());
        controller.close();
    }
    @Test void disabledAutoShareDoesNotLaunch() throws Exception {
        Files.writeString(directory.resolve("config.json"), "{\"autoShare\":false}");
        var created = new CopyOnWriteArrayList<Fake>(); var controller = controller(created);
        controller.initialize(25565); assertTrue(created.isEmpty()); controller.close();
    }
}
