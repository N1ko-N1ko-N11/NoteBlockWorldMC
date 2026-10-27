package io.github.noteblockworldmc.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shared across GUI instances; reopening cannot bypass per-player admission. */
public final class RequestGate implements AutoCloseable {
    private record Entry(long started, CompletableFuture<?> future) {}
    private final Map<Object, Entry> entries = new HashMap<>();
    private boolean closed;
    /** Backwards-compatible convenience overload for the common player key. */
    public synchronized <T> CompletableFuture<T> submit(UUID owner, Supplier<CompletableFuture<T>> action) {
        return submit((Object) owner, action);
    }
    public synchronized <T> CompletableFuture<T> submit(Object owner, Supplier<CompletableFuture<T>> action) {
        if (owner == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Request owner is required"));
        long now = System.nanoTime();
        entries.entrySet().removeIf(e -> e.getValue().future().isDone() && now - e.getValue().started() > 60_000_000_000L);
        Entry previous = entries.get(owner);
        if (closed || entries.size() >= 10_000 || previous != null
                && (!previous.future().isDone() || now - previous.started() < 250_000_000L))
            return CompletableFuture.failedFuture(new RejectedExecutionException("Please wait before another request"));
        CompletableFuture<T> result;
        try { result = action.get(); } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        if (result == null) return CompletableFuture.failedFuture(
                new IllegalStateException("Request action returned no future"));
        entries.put(owner, new Entry(now, result));
        return result;
    }
    @Override public synchronized void close() { closed = true; entries.values().forEach(e -> e.future().cancel(true)); entries.clear(); }
}
