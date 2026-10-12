package com.tailscale.mclink;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class AsyncProcessSession<P extends AutoCloseable, E> implements AutoCloseable {
    @FunctionalInterface
    public interface Starter<P, E> {
        P start(Consumer<E> events, Consumer<String> logs) throws Exception;
    }

    private final Predicate<P> alive;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean launched = new AtomicBoolean();
    private final AtomicReference<P> process = new AtomicReference<>();
    private final CompletableFuture<E> ready = new CompletableFuture<>();
    private final CompletableFuture<P> created = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Function<P, CompletableFuture<Void>> termination;

    public AsyncProcessSession(Predicate<P> alive) {
        this(alive, process -> CompletableFuture.completedFuture(null));
    }

    public AsyncProcessSession(Predicate<P> alive, Function<P, CompletableFuture<Void>> termination) {
        this.alive = Objects.requireNonNull(alive);
        this.termination = Objects.requireNonNull(termination);
    }

    public void start(Duration timeout, Starter<P, E> starter,
                      Function<P, CompletableFuture<E>> readiness,
                      Consumer<E> events, Consumer<String> logs, Consumer<Throwable> failures) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("startup timeout must be positive");
        }
        if (!launched.compareAndSet(false, true)) {
            throw new IllegalStateException("session has already started");
        }
        ready.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((event, error) -> {
                    if (error != null && active.get()) {
                        try {
                            if (!(error instanceof CancellationException)) failures.accept(error);
                        } finally {
                            close();
                        }
                    }
                });
        HelperTasks.execute(() -> {
            try {
                if (!active.get()) { created.complete(null); return; }
                P started = starter.start(event -> {
                    if (active.get()) events.accept(event);
                }, line -> {
                    if (active.get()) logs.accept(line);
                });
                process.set(started);
                created.complete(started);
                if (!active.get()) {
                    return;
                }
                readiness.apply(started).whenComplete((event, error) -> {
                    if (!active.get()) return;
                    if (error == null) ready.complete(event);
                    else ready.completeExceptionally(error);
                });
            } catch (Exception error) {
                created.complete(null);
                if (active.get()) ready.completeExceptionally(error);
            }
        });
    }

    public CompletableFuture<E> ready() {
        return ready;
    }

    public CompletableFuture<Void> closed() { return closed; }

    public boolean isActive() {
        return active.get();
    }

    public boolean isStarting() {
        return active.get() && !ready.isDone();
    }

    public boolean isAlive() {
        P current = process.get();
        return active.get() && current != null && alive.test(current);
    }

    @Override
    public void close() {
        if (!active.getAndSet(false)) return;
        ready.cancel(false);
        if (!launched.get()) created.complete(null);
        created.thenAccept(stopped -> {
            if (stopped == null) { closed.complete(null); return; }
            HelperTasks.execute(() -> {
                try {
                    stopped.close();
                    termination.apply(stopped).whenComplete((ignored, error) -> {
                        if (error == null) closed.complete(null); else closed.completeExceptionally(error);
                    });
                } catch (Exception error) { closed.completeExceptionally(error); }
            });
        });
    }

}
