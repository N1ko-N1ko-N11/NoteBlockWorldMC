package io.github.noteblockworldmc.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded complete bodies: the explicit timer covers headers AND body on Java 25. */
public final class BoundedHttp implements AutoCloseable {
    private final HttpClient client;
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(8);
    private volatile boolean closed;

    public BoundedHttp(HttpClient client) { this.client = client; }

    public CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, long limit, Duration timeout) {
        if (closed || !permits.tryAcquire()) return CompletableFuture.failedFuture(
                new IOException("HTTP service busy or stopped; try again shortly"));
        CompletableFuture<HttpResponse<byte[]>> wire;
        try { wire = client.sendAsync(request, info -> new CappedBody(limit)); }
        catch (RuntimeException e) { permits.release(); return CompletableFuture.failedFuture(e); }
        requests.add(wire);
        CompletableFuture<HttpResponse<byte[]>> result = new CompletableFuture<>();
        var timer = DeadlineTimer.schedule(() -> {
            result.completeExceptionally(new HttpTimeoutException("Response body deadline exceeded"));
            wire.cancel(true);
        }, timeout.toMillis());
        wire.whenComplete((response, error) -> {
            timer.cancel(false); requests.remove(wire); permits.release();
            if (error == null) result.complete(response); else result.completeExceptionally(error);
        });
        result.whenComplete((r, e) -> { if (result.isCancelled()) wire.cancel(true); });
        if (closed) wire.cancel(true);
        return result;
    }

    @Override public void close() {
        closed = true;
        requests.forEach(f -> f.cancel(true));
        client.shutdownNow(); // nonblocking, including incomplete responses
    }

    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final long limit;
        private Flow.Subscription subscription;
        CappedBody(long limit) { this.limit = Math.max(0, Math.min(limit, Integer.MAX_VALUE - 8)); }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > limit - out.size()) throw new IOException("Response exceeds size limit");
                    byte[] chunk = new byte[Math.min(8192, buffer.remaining())];
                    while (buffer.hasRemaining()) {
                        int n = Math.min(chunk.length, buffer.remaining()); buffer.get(chunk, 0, n); out.write(chunk, 0, n);
                    }
                }
                subscription.request(1);
            } catch (Exception e) { subscription.cancel(); body.completeExceptionally(e); }
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(out.toByteArray()); }
    }
}
