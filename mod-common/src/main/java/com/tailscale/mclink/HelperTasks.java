package com.tailscale.mclink;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class HelperTasks {
    private static final AtomicInteger NEXT_THREAD = new AtomicInteger();
    private static final ExecutorService IO = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "tailcat-helper-io-" + NEXT_THREAD.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private HelperTasks() {}

    static void execute(Runnable task) {
        IO.execute(task);
    }

    static java.util.concurrent.Executor executor() { return IO; }
}
