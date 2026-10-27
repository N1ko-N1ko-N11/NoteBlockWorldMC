package io.github.noteblockworldmc.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Bounded resource-pack HTTP transport.
 *
 * The pack itself stays on disk. Linux and other platforms use the bounded
 * selector transport so slow readers do not occupy worker threads. Windows
 * uses the JDK HttpServer transport: the original release used that listener,
 * and delegating socket handling back to the JDK avoids platform-specific
 * SocketChannel stalls seen on older Windows Server/JDK combinations.
 */
final class PackHttpServer implements AutoCloseable {
    private interface Transport extends AutoCloseable {
        InetSocketAddress address() throws IOException;
        String name();
        @Override void close();
    }

    private final Transport transport;

    PackHttpServer(InetSocketAddress bind, Function<String, FileChannel> lookup,
                   Predicate<String> rateLimited, IntSupplier limit) throws IOException {
        this(bind, lookup, rateLimited, limit, 60_000);
    }

    PackHttpServer(InetSocketAddress bind, Function<String, FileChannel> lookup,
                   Predicate<String> rateLimited, IntSupplier limit, long transferMillis) throws IOException {
        this(bind, lookup, rateLimited, ip -> true, limit, transferMillis);
    }

    /**
     * @param trusted sources (online players' addresses, a local reverse
     *                proxy) that may use the whole transfer pool. Anyone else
     *                shares at most half of it, a few slots per address, so
     *                a stranger holding slow downloads open cannot lock the
     *                joining players out of their required pack.
     */
    PackHttpServer(InetSocketAddress bind, Function<String, FileChannel> lookup, Predicate<String> rateLimited,
                   Predicate<String> trusted, IntSupplier limit, long transferMillis) throws IOException {
        Admission admission = new Admission(trusted);
        this.transport = useJdkTransport()
                ? new JdkTransport(bind, lookup, rateLimited, admission, limit, transferMillis)
                : new NioTransport(bind, lookup, rateLimited, admission, limit, transferMillis);
    }

    /** One bucket per IPv4 address or IPv6 /64 (see ResourcePackService#rateLimitKey). */
    static String sourceKey(String ip) {
        if (ip == null || ip.indexOf(':') < 0) return ip;
        try {
            byte[] address = java.net.InetAddress.ofLiteral(ip.replaceFirst("%.*$", "")).getAddress();
            if (address.length != 16) return ip;
            return java.util.HexFormat.of().formatHex(address, 0, 8) + "::/64";
        } catch (RuntimeException e) {
            return ip;
        }
    }

    /** Transfer slots for untrusted sources: half the pool, and a few per address or IPv6 /64. */
    static int untrustedLimit(int maximum) { return Math.max(1, maximum / 2); }
    static int perSourceLimit(int maximum) { return Math.max(1, Math.min(untrustedLimit(maximum), Math.max(2, maximum / 4))); }

    /** Thread-safe bookkeeping of untrusted transfers. */
    static final class Admission {
        private final Predicate<String> trusted;
        private final java.util.Map<String, Integer> perSource = new java.util.HashMap<>();
        private int untrusted;

        Admission(Predicate<String> trusted) { this.trusted = trusted; }

        boolean trusted(String ip) {
            try { return trusted.test(ip); } catch (RuntimeException e) { return false; }
        }

        /** Returns false when an untrusted source must wait (503). */
        synchronized boolean acquire(String key, int maximum) {
            if (untrusted >= untrustedLimit(maximum) || perSource.getOrDefault(key, 0) >= perSourceLimit(maximum)) return false;
            untrusted++;
            perSource.merge(key, 1, Integer::sum);
            return true;
        }

        synchronized void release(String key) {
            untrusted--;
            perSource.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
        }

        synchronized int active() { return untrusted; }
    }

    private static boolean useJdkTransport() {
        String forced = System.getProperty("noteblockworldmc.pack-http-transport", "").trim();
        if (forced.equalsIgnoreCase("jdk")) return true;
        if (forced.equalsIgnoreCase("nio")) return false;
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    InetSocketAddress address() throws IOException {
        return transport.address();
    }

    String name() {
        return transport.name();
    }

    @Override
    public void close() {
        transport.close();
    }

    /**
     * Windows keeps JDK HTTP handling behind a bounded blocking-socket gateway.
     * Only complete, size-limited requests reach the loopback HTTP listener;
     * incomplete public headers cannot occupy the JDK's HTTP worker threads.
     */
    private static final class JdkTransport implements Transport {
        private static final int CHUNK_BYTES = 64 * 1024;
        private static final int MAX_WORKERS = 64;

        private final HttpServer server;
        private final HeaderGate gateway;
        private final Function<String, FileChannel> lookup;
        private final Predicate<String> rateLimited;
        private final Admission admission;
        private final IntSupplier limit;
        private final ExecutorService executor;
        private final ScheduledExecutorService deadlines;
        private final long transferMillis;
        private final AtomicInteger transfers = new AtomicInteger();
        private volatile boolean closed;

        JdkTransport(InetSocketAddress bind, Function<String, FileChannel> lookup, Predicate<String> rateLimited,
                     Admission admission, IntSupplier limit, long transferMillis) throws IOException {
            this.lookup = lookup;
            this.rateLimited = rateLimited;
            this.admission = admission;
            this.limit = limit;
            this.transferMillis = Math.max(1, transferMillis);

            HttpServer created = null;
            ExecutorService workers = null;
            ScheduledExecutorService timers = null;
            HeaderGate gate = null;
            try {
                created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
                workers = new ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS,
                        new SynchronousQueue<>(), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
                var timer = new ScheduledThreadPoolExecutor(1, daemonFactory());
                timer.setRemoveOnCancelPolicy(true);
                timers = timer;
                created.createContext("/pack.zip", this::handle);
                created.setExecutor(workers);
                server = created;
                executor = workers;
                deadlines = timers;
                gate = new HeaderGate(bind);
                gateway = gate;
                created.start();
                gate.start();
            } catch (IOException | RuntimeException e) {
                if (gate != null) gate.close();
                if (created != null) created.stop(0);
                if (workers != null) workers.shutdownNow();
                if (timers != null) timers.shutdownNow();
                throw e;
            }
        }

        @Override
        public InetSocketAddress address() {
            return gateway.address();
        }

        @Override
        public String name() {
            return "JDK HttpServer";
        }

        /** No selector/sendfile path is used on Windows, including at admission. */
        private final class HeaderGate implements AutoCloseable {
            private final ServerSocket listener = new ServerSocket();
            private final Set<Bridge> clients = ConcurrentHashMap.newKeySet();
            private final ThreadPoolExecutor readers = new ThreadPoolExecutor(0, 2 * (MAX_WORKERS + 16), 30,
                    TimeUnit.SECONDS, new SynchronousQueue<>(), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
            private final AtomicInteger forwarding = new AtomicInteger();

            HeaderGate(InetSocketAddress bind) throws IOException {
                try { listener.setReuseAddress(true); listener.bind(bind, 32); }
                catch (IOException | RuntimeException e) { listener.close(); readers.shutdownNow(); throw e; }
            }

            InetSocketAddress address() { return (InetSocketAddress) listener.getLocalSocketAddress(); }
            void start() { daemonFactory().newThread(this::accept).start(); }

            private void accept() {
                while (!closed && !listener.isClosed()) {
                    Socket socket = null;
                    try {
                        socket = listener.accept();
                        int maximum = Math.min(MAX_WORKERS, Math.max(1, limit.getAsInt()));
                        String ip = socket.getInetAddress().getHostAddress();
                        boolean trusted = admission.trusted(ip);
                        // Applied before header parsing, with a small bounded allowance for
                        // readers; trusted and untrusted sources each have their own allowance.
                        long sameKind = clients.stream().filter(b -> b.trusted == trusted).count();
                        if (sameKind >= maximum + 16 || rateLimited.test(ip)) {
                            socket.close(); continue;
                        }
                        Bridge bridge = new Bridge(socket, trusted, sourceKey(ip));
                        clients.add(bridge);
                        try { readers.execute(bridge); }
                        catch (RejectedExecutionException e) { bridge.close(); }
                    } catch (IOException | RuntimeException e) {
                        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                    }
                }
            }

            private final class Bridge implements Runnable, AutoCloseable {
                final Socket client;
                final Socket upstream = new Socket();
                final long accepted = System.nanoTime();
                final ScheduledFuture<?> expiry;
                final ScheduledFuture<?> headerExpiry;
                final boolean trusted;
                final String source;
                volatile boolean headersComplete;

                Bridge(Socket client, boolean trusted, String source) {
                    this.client = client;
                    this.trusted = trusted;
                    this.source = source;
                    expiry = deadlines.schedule(this::close, transferMillis, TimeUnit.MILLISECONDS);
                    headerExpiry = deadlines.schedule(() -> { if (!headersComplete) close(); },
                            Math.min(5000, transferMillis), TimeUnit.MILLISECONDS);
                }

                @Override public void run() {
                    boolean admitted = false, reserved = false;
                    try {
                        client.setSoTimeout((int) Math.min(5000, transferMillis));
                        InputStream request = new java.io.BufferedInputStream(client.getInputStream(), 4096);
                        byte[] header = new byte[16_384];
                        int size = 0;
                        while (true) {
                            if (size == header.length) { reject(431); return; }
                            int next = request.read(); if (next < 0) return;
                            header[size++] = (byte) next;
                            if (size >= 4 && header[size - 4] == 13 && header[size - 3] == 10
                                    && header[size - 2] == 13 && header[size - 1] == 10) break;
                        }
                        headersComplete = true; headerExpiry.cancel(false);
                        String raw = new String(header, 0, size, StandardCharsets.US_ASCII);
                        String[] first = raw.substring(0, raw.indexOf("\r\n")).split(" ", -1);
                        if (first.length != 3 || !first[2].matches("HTTP/1\\.[01]")
                                || !first[1].startsWith("/") || first[1].startsWith("//")
                                || first[1].chars().anyMatch(c -> c <= 32 || c > 126)) { reject(400); return; }
                        if (!first[0].equals("GET") && !first[0].equals("HEAD")) { reject(405); return; }
                        int maximum = Math.min(MAX_WORKERS, Math.max(1, limit.getAsInt()));
                        if (!trusted && !admission.acquire(source, maximum)) { reject(503); return; }
                        reserved = !trusted;
                        if (forwarding.incrementAndGet() > maximum) {
                            forwarding.decrementAndGet(); reject(503); return;
                        }
                        admitted = true;
                        long remaining = transferMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - accepted);
                        if (remaining <= 0) return;
                        upstream.connect(server.getAddress(), (int) Math.min(5000, remaining));
                        upstream.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining));
                        // Discard caller headers and bodies; one origin-form GET/HEAD per connection.
                        upstream.getOutputStream().write((first[0] + " " + first[1] + " HTTP/1.1\r\n"
                                + "Host: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        byte[] chunk = new byte[CHUNK_BYTES];
                        InputStream response = upstream.getInputStream();
                        OutputStream destination = client.getOutputStream();
                        for (int count; (count = response.read(chunk)) >= 0;) destination.write(chunk, 0, count);
                    } catch (IOException | RuntimeException ignored) {
                        // Disconnects and deadline socket closures release admission below.
                    } finally {
                        if (admitted) forwarding.decrementAndGet();
                        if (reserved) admission.release(source);
                        close();
                    }
                }

                private void reject(int status) throws IOException {
                    client.getOutputStream().write(("HTTP/1.1 " + status + " Error\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                }

                @Override public void close() {
                    try { client.close(); } catch (IOException ignored) { }
                    try { upstream.close(); } catch (IOException ignored) { }
                    // Timers can fire during construction with very short test deadlines.
                    if (expiry != null) expiry.cancel(false);
                    if (headerExpiry != null) headerExpiry.cancel(false);
                    clients.remove(this);
                }
            }

            @Override public void close() {
                try { listener.close(); } catch (IOException ignored) { }
                for (Bridge client : clients) client.close();
                readers.shutdownNow();
            }
        }

        private void handle(HttpExchange exchange) {
            FileChannel body = null;
            boolean admitted = false;
            try {
                URI uri = exchange.getRequestURI();
                String path = uri.getRawPath();
                if (!"/pack.zip".equals(path)) {
                    respond(exchange, 404);
                    return;
                }

                String method = exchange.getRequestMethod();
                boolean head = "HEAD".equalsIgnoreCase(method);
                if (!head && !"GET".equalsIgnoreCase(method)) {
                    respond(exchange, 405);
                    return;
                }

                String target = uri.getRawQuery() == null
                        ? path : path + "?" + uri.getRawQuery();

                body = lookup.apply(target);
                if (body == null) {
                    respond(exchange, 404);
                    return;
                }

                int maximum = Math.min(MAX_WORKERS, Math.max(1, limit.getAsInt()));
                if (transfers.incrementAndGet() > maximum) {
                    transfers.decrementAndGet();
                    respond(exchange, 503);
                    return;
                }
                admitted = true;

                long length = body.size();
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=600, immutable");
                exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
                exchange.getResponseHeaders().set("Connection", "close");
                if (head) {
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }

                exchange.sendResponseHeaders(200, length);
                try (OutputStream output = exchange.getResponseBody()) {
                    copy(body, output, length);
                }
            } catch (IOException | RuntimeException ignored) {
                // A client disconnecting midway through a multi-megabyte
                // download is routine; finally releases every resource.
            } finally {
                if (admitted) transfers.decrementAndGet();
                if (body != null) {
                    try { body.close(); } catch (IOException ignored) { }
                }
                exchange.close();
            }
        }

        private static void respond(HttpExchange exchange, int status) throws IOException {
            exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, -1);
        }

        private static void copy(FileChannel source, OutputStream output, long length) throws IOException {
            WritableByteChannel destination = Channels.newChannel(output);
            ByteBuffer chunk = ByteBuffer.allocate(CHUNK_BYTES);
            long position = 0;
            while (position < length) {
                chunk.clear();
                int wanted = (int) Math.min(chunk.capacity(), length - position);
                chunk.limit(wanted);
                int read = source.read(chunk, position);
                if (read < 0) throw new IOException("Pack generation ended before Content-Length");
                if (read == 0) {
                    Thread.yield();
                    continue;
                }
                position += read;
                chunk.flip();
                while (chunk.hasRemaining()) destination.write(chunk);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            gateway.close();
            server.stop(0);
            executor.shutdownNow();
            deadlines.shutdownNow();
        }
    }

    /** Bounded single-request selector transport used off Windows. */
    private static final class NioTransport implements Transport {
        private final Selector selector;
        private final ServerSocketChannel listener;
        private final Function<String, FileChannel> lookup;
        private final Predicate<String> rateLimited;
        private final Admission admission;
        private final IntSupplier limit;
        private final long transferMillis;
        private volatile boolean closed;
        private final Set<Connection> connections = new HashSet<>();
        private int transfers;
        private final Thread thread;

        NioTransport(InetSocketAddress bind, Function<String, FileChannel> lookup, Predicate<String> rateLimited,
                     Admission admission, IntSupplier limit, long transferMillis) throws IOException {
            this.lookup = lookup;
            this.rateLimited = rateLimited;
            this.admission = admission;
            this.limit = limit;
            this.transferMillis = transferMillis;
            selector = Selector.open();
            listener = ServerSocketChannel.open();
            try {
                listener.configureBlocking(false);
                listener.bind(bind, 32);
                listener.register(selector, SelectionKey.OP_ACCEPT);
            } catch (IOException | RuntimeException e) {
                listener.close();
                selector.close();
                throw e;
            }
            thread = new Thread(this::run, "NoteblockWorldMC-pack-http");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public InetSocketAddress address() throws IOException {
            return (InetSocketAddress) listener.getLocalAddress();
        }

        @Override
        public String name() {
            return "NIO selector";
        }

        private final class Connection {
            final java.nio.channels.SocketChannel socket;
            final ByteBuffer request = ByteBuffer.allocate(16_384);
            final long accepted = System.nanoTime();
            ByteBuffer headers;
            FileChannel body;
            final ByteBuffer chunk = emptyChunk();
            final boolean trusted;
            final String source;
            long position, length;
            boolean transfer, reserved;
            Connection(java.nio.channels.SocketChannel socket, boolean trusted, String source) {
                this.socket = socket; this.trusted = trusted; this.source = source;
            }
        }

        private static ByteBuffer emptyChunk() {
            ByteBuffer buffer = ByteBuffer.allocate(65_536);
            buffer.limit(0);
            return buffer;
        }

        private void run() {
            try {
                while (!closed) {
                    selector.select(250);
                    var iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        try {
                            if (!key.isValid()) continue;
                            if (key.isAcceptable()) {
                                accept();
                                continue;
                            }
                            Connection connection = (Connection) key.attachment();
                            if (key.isReadable()) read(key, connection);
                            if (key.isValid() && key.isWritable()) write(key, connection);
                        } catch (IOException | RuntimeException e) {
                            if (key.attachment() instanceof Connection c) drop(c);
                        }
                    }
                    long now = System.nanoTime();
                    for (Connection c : new ArrayList<>(connections)) {
                        long allowed = c.headers == null ? Math.min(5000, transferMillis) : transferMillis;
                        if (now - c.accepted >= allowed * 1_000_000L) drop(c);
                    }
                }
            } catch (IOException | ClosedSelectorException ignored) {
            } finally {
                for (Connection c : new ArrayList<>(connections)) drop(c);
                try { listener.close(); selector.close(); } catch (IOException ignored) { }
            }
        }

        private void accept() throws IOException {
            for (int i = 0; i < 16; i++) {
                java.nio.channels.SocketChannel socket = listener.accept();
                if (socket == null) return;
                try {
                    int maximum = Math.min(64, Math.max(1, limit.getAsInt()));
                    String ip = ((InetSocketAddress) socket.getRemoteAddress()).getAddress().getHostAddress();
                    boolean trusted = admission.trusted(ip);
                    long sameKind = connections.stream().filter(c -> c.trusted == trusted).count();
                    if (sameKind >= maximum + 16 || rateLimited.test(ip)) {
                        socket.close();
                        continue;
                    }
                    socket.configureBlocking(false);
                    socket.socket().setSendBufferSize(32_768);
                    Connection connection = new Connection(socket, trusted, sourceKey(ip));
                    connections.add(connection);
                    socket.register(selector, SelectionKey.OP_READ, connection);
                } catch (IOException | RuntimeException e) {
                    // A peer that reset before admission must not leak its
                    // descriptor; the listener keeps accepting others.
                    for (Connection c : connections) if (c.socket == socket) { drop(c); break; }
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
        }

        private void read(SelectionKey key, Connection c) throws IOException {
            if (c.socket.read(c.request) < 0) {
                drop(c);
                return;
            }
            int end = c.request.position();
            byte[] data = c.request.array();
            boolean complete = false;
            for (int i = 3; i < end; i++) {
                if (data[i - 3] == 13 && data[i - 2] == 10 && data[i - 1] == 13 && data[i] == 10) {
                    complete = true;
                    break;
                }
            }
            if (!complete) {
                if (!c.request.hasRemaining()) respond(key, c, 431, null, false);
                return;
            }
            String request = new String(data, 0, end, StandardCharsets.US_ASCII);
            String[] first = request.substring(0, request.indexOf("\r\n")).split(" ", -1);
            if (first.length != 3 || !first[2].matches("HTTP/1\\.[01]")) {
                respond(key, c, 400, null, false);
                return;
            }
            boolean head = first[0].equals("HEAD");
            if (!head && !first[0].equals("GET")) {
                respond(key, c, 405, null, false);
                return;
            }
            FileChannel body = lookup.apply(first[1]);
            if (body == null) {
                respond(key, c, 404, null, false);
                return;
            }
            c.body = body;
            int maximum = Math.min(64, Math.max(1, limit.getAsInt()));
            if (transfers >= maximum || (!c.trusted && !admission.acquire(c.source, maximum))) {
                body.close();
                c.body = null;
                respond(key, c, 503, null, false);
                return;
            }
            c.reserved = !c.trusted;
            c.transfer = true;
            transfers++;
            respond(key, c, 200, body, head);
        }

        private void respond(SelectionKey key, Connection c, int status, FileChannel body, boolean head)
                throws IOException {
            long length = body == null ? 0 : body.size();
            c.headers = StandardCharsets.US_ASCII.encode("HTTP/1.1 " + status + " "
                    + (status == 200 ? "OK" : "Error")
                    + "\r\nContent-Length: " + length
                    + "\r\nContent-Type: application/zip"
                    + "\r\nX-Content-Type-Options: nosniff"
                    + "\r\nCache-Control: " + (status == 200 ? "public, max-age=600, immutable" : "no-store")
                    + "\r\nConnection: close\r\n\r\n");
            c.length = head ? 0 : length;
            key.interestOps(SelectionKey.OP_WRITE);
        }

        private void write(SelectionKey key, Connection c) throws IOException {
            if (c.headers.hasRemaining()) c.socket.write(c.headers);
            if (c.headers.hasRemaining()) return;
            if (c.position >= c.length) {
                drop(c);
                return;
            }
            if (!c.chunk.hasRemaining()) {
                c.chunk.clear();
                int wanted = (int) Math.min(c.chunk.capacity(), c.length - c.position);
                c.chunk.limit(wanted);
                int read = c.body.read(c.chunk, c.position);
                if (read < 0) {
                    drop(c);
                    return;
                }
                if (read == 0) {
                    c.chunk.limit(0);
                    return;
                }
                c.chunk.flip();
            }
            int before = c.chunk.remaining();
            c.socket.write(c.chunk);
            c.position += before - c.chunk.remaining();
            if (c.position >= c.length && !c.chunk.hasRemaining()) drop(c);
        }

        private void drop(Connection c) {
            if (connections.remove(c) && c.transfer) {
                transfers--;
                if (c.reserved) admission.release(c.source);
            }
            if (c.body != null) {
                try { c.body.close(); } catch (IOException ignored) { }
                c.body = null;
            }
            try { c.socket.close(); } catch (IOException ignored) { }
        }

        /**
         * Returns once the port is released. A channel registered with a
         * selector is only really closed when the selector deregisters it,
         * so closing from another thread left the socket bound for a moment
         * and an immediate rebind of the same port failed.
         */
        @Override
        public void close() {
            closed = true;
            selector.wakeup();
            try { listener.close(); } catch (IOException ignored) { }
            if (Thread.currentThread() != thread) {
                try { thread.join(2_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "NoteblockWorldMC-pack-http");
            thread.setDaemon(true);
            return thread;
        };
    }
}
