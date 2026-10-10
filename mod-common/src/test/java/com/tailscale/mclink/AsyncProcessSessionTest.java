package com.tailscale.mclink;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AsyncProcessSessionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void cancelDuringStartupClosesLateProcessAndDropsCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeProcess process = new FakeProcess();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Consumer<String>> events = new AtomicReference<>();
        AtomicReference<Consumer<String>> logs = new AtomicReference<>();
        AsyncProcessSession<FakeProcess, String> session = session();
        session.start(TIMEOUT, (eventSink, logSink) -> {
            events.set(eventSink);
            logs.set(logSink);
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return process;
        }, p -> p.ready, event -> callbacks.incrementAndGet(), line -> callbacks.incrementAndGet(),
                error -> callbacks.incrementAndGet());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            session.close();
            events.get().accept("late ready");
            logs.get().accept("late log");
        } finally {
            session.close();
            release.countDown();
        }
        assertTrue(process.closed.await(5, TimeUnit.SECONDS));
        assertTrue(session.ready().isCancelled());
        assertEquals(0, callbacks.get());
        assertEquals(1, process.closes.get());
    }

    @Test
    void timeoutIncludesSlowExtractionAndClosesLateProcess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        FakeProcess process = new FakeProcess();
        AsyncProcessSession<FakeProcess, String> session = session();
        session.start(Duration.ofMillis(150), (events, logs) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return process;
        }, p -> p.ready, event -> {}, line -> {}, failure::complete);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.get(5, TimeUnit.SECONDS));
        } finally {
            session.close();
            release.countDown();
        }
        assertTrue(process.closed.await(5, TimeUnit.SECONDS));
        assertFalse(session.isActive());
    }

    @Test
    void oldSessionCannotAffectReplacement() throws Exception {
        FakeProcess old = new FakeProcess();
        FakeProcess current = new FakeProcess();
        AtomicReference<Consumer<String>> oldSink = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        AsyncProcessSession<FakeProcess, String> first = session();
        AsyncProcessSession<FakeProcess, String> second = session();
        first.start(TIMEOUT, (events, logs) -> { oldSink.set(events); return old; }, p -> p.ready,
                event -> callbacks.incrementAndGet(), line -> callbacks.incrementAndGet(), error -> failures.incrementAndGet());
        old.ready.complete("first");
        assertEquals("first", first.ready().get(5, TimeUnit.SECONDS));
        first.close();
        second.start(TIMEOUT, (events, logs) -> current, p -> p.ready, event -> callbacks.incrementAndGet(),
                line -> {}, error -> failures.incrementAndGet());
        try {
            current.ready.complete("second");
            assertEquals("second", second.ready().get(5, TimeUnit.SECONDS));
            oldSink.get().accept("late error");
            oldSink.get().accept("late exit");
            assertTrue(second.isAlive());
            assertEquals(0, callbacks.get());
            assertEquals(0, failures.get());
        } finally {
            second.close();
        }
        assertTrue(old.closed.await(5, TimeUnit.SECONDS));
        assertTrue(current.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void startAndCloseNeverWaitForSlowProcess() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        FakeProcess process = new FakeProcess() {
            @Override public void close() throws InterruptedException {
                closeEntered.countDown();
                release.await(5, TimeUnit.SECONDS);
                super.close();
            }
        };
        AsyncProcessSession<FakeProcess, String> session = session();
        process.ready.complete("ready");
        session.start(TIMEOUT, (events, logs) -> process, p -> p.ready, event -> {}, line -> {}, error -> fail(error));
        session.ready().get(5, TimeUnit.SECONDS);
        try {
            assertTimeout(Duration.ofMillis(250), session::close);
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            assertFalse(session.isAlive());
        } finally {
            release.countDown();
            session.close();
        }
        assertTrue(process.closed.await(5, TimeUnit.SECONDS));
        assertEquals(1, process.closes.get());
    }

    @Test
    void startupFailureIsReportedOnce() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        AsyncProcessSession<FakeProcess, String> session = session();
        session.start(TIMEOUT, (events, logs) -> { throw new java.io.IOException("cannot extract"); },
                p -> p.ready, event -> {}, line -> {}, error -> { failures.incrementAndGet(); failure.complete(error); });
        assertEquals("cannot extract", failure.get(5, TimeUnit.SECONDS).getMessage());
        session.close();
        assertFalse(session.isActive());
        assertEquals(1, failures.get());
    }

    private static AsyncProcessSession<FakeProcess, String> session() {
        return new AsyncProcessSession<>(p -> p.closes.get() == 0);
    }

    private static class FakeProcess implements AutoCloseable {
        final CompletableFuture<String> ready = new CompletableFuture<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();

        @Override public void close() throws InterruptedException {
            closes.incrementAndGet();
            closed.countDown();
        }
    }
}
