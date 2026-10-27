package io.github.noteblockworldmc.util;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shared admission for disk, parsing, import and analysis work. No common-pool spillover. */
public final class WorkPool implements AutoCloseable {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), r -> {
        Thread t = new Thread(r, "NoteblockWorldMC-work"); t.setDaemon(true); return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Set<Object> owners = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public <T> CompletableFuture<T> submit(Supplier<T> action) { return submit(null, action); }

    /** A non-null owner may have only one admitted job, including queued work. */
    public <T> CompletableFuture<T> submit(Object owner, Supplier<T> action) {
        if (owner != null && !owners.add(owner)) return CompletableFuture.failedFuture(
                new RejectedExecutionException("A request is already in progress; please wait"));
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((v, e) -> { pending.remove(result); if (owner != null) owners.remove(owner); });
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                try { result.complete(action.get()); }
                catch (Throwable e) { result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new RejectedExecutionException("Music service is busy; try again shortly", e));
        }
        return result;
    }

    public CompletableFuture<Void> run(Runnable action) { return submit(() -> { action.run(); return null; }); }
    public CompletableFuture<Void> run(Object owner, Runnable action) { return submit(owner, () -> { action.run(); return null; }); }

    @Override public void close() {
        executor.shutdownNow();
        pending.forEach(f -> f.cancel(true));
        owners.clear();
    }
}
