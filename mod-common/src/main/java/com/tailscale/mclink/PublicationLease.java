package com.tailscale.mclink;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PublicationLease<T> {
    private final List<T> listeners;
    private final List<T> before;
    private final List<T> owned = new ArrayList<>();
    private final Consumer<T> close;
    private final Runnable restore;
    private boolean restored;

    public PublicationLease(List<T> listeners, Consumer<T> close, Runnable restore) {
        this.listeners = listeners; this.close = close; this.restore = restore;
        synchronized (listeners) { before = List.copyOf(listeners); }
    }

    public synchronized void published() {
        synchronized (listeners) {
            for (T listener : listeners) if (!before.contains(listener)) owned.add(listener);
        }
    }

    public synchronized void restore() {
        if (restored) return;
        restored = true;
        synchronized (listeners) {
            for (T listener : owned) { close.accept(listener); listeners.remove(listener); }
        }
        restore.run();
    }

    public static PublicationLease<Object> action(Runnable restore) {
        return new PublicationLease<>(new ArrayList<>(), ignored -> {}, restore);
    }
}
