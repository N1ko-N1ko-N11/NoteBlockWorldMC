package io.github.noteblockworldmc.download;

import io.github.noteblockworldmc.util.DeadlineTimer;
import io.github.noteblockworldmc.util.Limits;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** HTTPS downloads connect to the already-vetted address, retaining hostname verification and SNI. */
public final class SecureUrlFetcher implements AutoCloseable {
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_BUFFER_BYTES = 256L * 1024 * 1024;
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 2, 5, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "NBW-DNS"); t.setDaemon(true); return t; });
    private final long maxBytes;
    private volatile Socket socket;
    private volatile boolean closed;
    public SecureUrlFetcher(long maxBytes) {
        if (maxBytes <= 0 || maxBytes > MAX_BUFFER_BYTES) {
            throw new IllegalArgumentException("Download size limit is outside the safe range");
        }
        this.maxBytes = maxBytes;
    }
    @Override public void close() {
        closed = true;
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }

    public byte[] fetch(String rawUrl) throws IOException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IOException("A download URL is required");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var expiry = DeadlineTimer.schedule(this::close, 20_000);
        try {
            URI uri = parseAndValidate(rawUrl);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                InetAddress[] addresses = checkAddress(uri, deadline);
                if (closed) throw new SocketTimeoutException("Download deadline exceeded");
                Socket transport = new Socket(); socket = transport;
                try {
                    if (closed) throw new SocketTimeoutException("Download deadline exceeded");
                    transport.connect(new InetSocketAddress(addresses[0], uri.getPort() < 0 ? 443 : uri.getPort()), remaining(deadline));
                    transport.setSoTimeout(remaining(deadline));
                    SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(transport, uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort(), true);
                    socket = tls;
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters); tls.setSoTimeout(remaining(deadline)); tls.startHandshake();
                    String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/";
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    String host = uri.getHost();
                    if (uri.getPort() >= 0) host += ":" + uri.getPort();
                    tls.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                            + "\r\nUser-Agent: NoteblockWorldMC\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    InputStream in = new BufferedInputStream(tls.getInputStream());
                    int[] headerBudget = {16_384};
                    String statusLine = line(in, headerBudget);
                    if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("Invalid HTTP status");
                    int status = Integer.parseInt(statusLine.substring(9, 12));
                    Map<String,String> headers = new HashMap<>(); String line;
                    while (!(line = line(in, headerBudget)).isEmpty()) {
                        int colon = line.indexOf(':'); if (colon < 1) throw new IOException("Invalid HTTP header");
                        String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
                        if (headers.putIfAbsent(key, line.substring(colon + 1).trim()) != null
                                && (key.equals("content-length") || key.equals("transfer-encoding")))
                            throw new IOException("Ambiguous HTTP framing");
                    }
                    if (status >= 300 && status < 400) {
                        String location = headers.get("location"); if (location == null) throw new IOException("Redirect has no location");
                        uri = parseAndValidate(uri.resolve(location).toString()); continue;
                    }
                    if (status != 200) throw new IOException("Server returned HTTP " + status);
                    if (!headers.getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity"))
                        throw new IOException("Compressed HTTP bodies are not supported");
                    String transfer = headers.get("transfer-encoding");
                    if (transfer != null) {
                        if (!transfer.equalsIgnoreCase("chunked") || headers.containsKey("content-length"))
                            throw new IOException("Unsupported HTTP framing");
                        return readChunked(in, maxBytes);
                    }
                    String length = headers.get("content-length");
                    if (length == null) return Limits.read(in, maxBytes);
                    long count;
                    try { count = Long.parseLong(length); } catch (NumberFormatException e) { throw new IOException("Invalid length", e); }
                    if (count < 0 || count > maxBytes) throw new IOException("File exceeds size limit");
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); copyExactly(in, out, count); return out.toByteArray();
                } finally { transport.close(); Socket current = socket; if (current != null) current.close(); socket = null; }
            }
            throw new IOException("Too many redirects");
        } catch (IllegalArgumentException e) { throw new IOException("Invalid URL or redirect", e); }
        finally { expiry.cancel(false); close(); }
    }

    /**
     * Decodes a chunked body. Each size line has its own small budget: a
     * budget shared by the whole body rejected legitimate responses made of
     * many small chunks (a few kilobytes each) long before the size limit.
     * Total bytes stay bounded by maxBytes, and every chunk carries at least
     * one byte, so the number of lines is bounded too.
     */
    static byte[] readChunked(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int[] sizeLine = {1024};
            String size = line(in, sizeLine).split(";", 2)[0].trim();
            long count;
            try { count = Long.parseLong(size, 16); } catch (NumberFormatException e) { throw new IOException("Invalid chunk", e); }
            if (count < 0 || count > maxBytes - out.size()) throw new IOException("File exceeds size limit");
            if (count == 0) {
                // Trailer fields are read and discarded, within their own budget.
                int[] trailers = {16_384};
                String trailer = line(in, trailers);
                while (!trailer.isEmpty()) trailer = line(in, trailers);
                return out.toByteArray();
            }
            copyExactly(in, out, count);
            if (!line(in, new int[]{2}).isEmpty()) throw new IOException("Invalid chunk ending");
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
            int n = in.read(buffer, 0, (int)Math.min(buffer.length, count));
            if (n < 0) throw new EOFException("Truncated HTTP body"); out.write(buffer, 0, n); count -= n;
        }
    }
    private static String line(InputStream in, int[] budget) throws IOException {
        StringBuilder value = new StringBuilder();
        while (--budget[0] >= 0) {
            int c = in.read(); if (c < 0) throw new EOFException("Truncated headers");
            if (c == '\r') { if (--budget[0] < 0 || in.read() != '\n') throw new IOException("Invalid headers"); return value.toString(); }
            if (c < 32 || c > 126) throw new IOException("Invalid header character"); value.append((char)c);
        }
        throw new IOException("HTTP headers exceed limit");
    }
    private static int remaining(long deadline) throws SocketTimeoutException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (ms <= 0) throw new SocketTimeoutException("Download deadline exceeded"); return (int)Math.min(ms, 10_000);
    }
    private static URI parseAndValidate(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.length() > 8192) {
            throw new IOException("An absolute HTTPS URL without credentials is required");
        }
        URI uri;
        String value = raw.trim();
        try { uri = URI.create(value); } catch (IllegalArgumentException e) { throw new IOException("Invalid URL", e); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IOException("An absolute HTTPS URL without credentials is required");
        return uri;
    }
    private static InetAddress[] checkAddress(URI uri, long deadline) throws IOException {
        Future<InetAddress[]> lookup;
        try { lookup = DNS.submit(() -> InetAddress.getAllByName(uri.getHost())); }
        catch (RejectedExecutionException e) { throw new IOException("DNS service busy", e); }
        try {
            InetAddress[] addresses = lookup.get(remaining(deadline), TimeUnit.MILLISECONDS);
            if (addresses.length == 0) throw new IOException("Host has no address");
            for (InetAddress address : addresses) if (isBlocked(address)) throw new IOException("URL resolves to a non-public address");
            return addresses;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Download cancelled"); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("DNS lookup failed or timed out", e); }
        finally { lookup.cancel(true); }
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()        // 0.0.0.0 / ::
                || address.isLoopbackAddress()  // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress() // 169.254/16 (incl. cloud metadata), fe80::
                || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            if (a >= 224 || a == 192 && (b == 0 || b == 2 || b == 168)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113) return true;
            // 0.0.0.0/8 "this network": isAnyLocalAddress() matches only
            // 0.0.0.0 exactly, but some stacks route 0.x.y.z to localhost
            if ((bytes[0] & 0xFF) == 0) {
                return true;
            }
            // Carrier-grade NAT 100.64.0.0/10
            if ((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40) {
                return true;
            }
        }
        // IPv4-mapped IPv6 (::ffff:a.b.c.d) can smuggle a private v4 address
        if (bytes.length == 16) {
            if ((bytes[0] & 0xE0) != 0x20 || (bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 2
                    || bytes[0] == 0x20 && bytes[1] == 1 && (bytes[2] & 255) < 2
                    || bytes[0] == 0x20 && bytes[1] == 1 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8) return true;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                try {
                    byte[] v4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
                    if (isBlocked(InetAddress.getByAddress(v4))) {
                        return true;
                    }
                } catch (UnknownHostException ignored) {
                    return true;   // can't tell -> refuse
                }
            }
            // Unique-local fc00::/7
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true;
            }
            // 6to4 (2002::/16) embeds an IPv4 address at bytes 2-5:
            // 2002:7f00:0001:: is 127.0.0.1 in disguise. Same rule as
            // v4-mapped: judge the embedded address.
            if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) {
                if (embeddedV4Blocked(bytes, 2)) {
                    return true;
                }
            }
            // NAT64 well-known prefix (64:ff9b::/96) carries the v4 target
            // in the last 4 bytes; an IPv6-only network's NAT64 gateway
            // would translate toward it.
            return (bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x64
                    && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B
                    && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0
                    && bytes[7] == 0 && bytes[8] == 0 && bytes[9] == 0
                    && bytes[10] == 0 && bytes[11] == 0
                    && embeddedV4Blocked(bytes, 12);
        }
        return false;
    }

    /** Re-checks an IPv4 address embedded at {@code offset} in an IPv6 one. */
    private static boolean embeddedV4Blocked(byte[] bytes, int offset) {
        try {
            byte[] v4 = {bytes[offset], bytes[offset + 1],
                    bytes[offset + 2], bytes[offset + 3]};
            return isBlocked(InetAddress.getByAddress(v4));
        } catch (UnknownHostException e) {
            return true;   // can't tell -> refuse
        }
    }
}
