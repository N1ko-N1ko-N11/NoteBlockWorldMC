package io.github.noteblockworldmc.download;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches a user-supplied URL with the paranoia that requires.
 *
 * A server fetching an arbitrary URL on a user's behalf is a Server-Side
 * Request Forgery (SSRF) risk: the request originates INSIDE the server's
 * network, so a hostile URL can reach things the user never could —
 * localhost admin panels, LAN devices, or a cloud provider's metadata
 * endpoint (169.254.169.254) that hands out credentials.
 *
 * Defenses, all mandatory:
 *   - https only (no http, file, ftp, gopher, ...)
 *   - the resolved IP must be public — loopback, private, link-local,
 *     multicast, wildcard and unique-local ranges are all rejected
 *   - redirects are followed MANUALLY so each hop is re-validated (a public
 *     URL that 302s to http://localhost would otherwise slip straight past)
 *   - hard caps on redirects, response size and time
 *
 * This is intentionally separate from the API client, which is allowed to
 * follow redirects to trusted storage; nothing here is trusted.
 */
public final class SecureUrlFetcher implements AutoCloseable {

    private static final int MAX_REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    // Built without automatic redirect handling: we do it ourselves so every
    // hop passes through checkAddress().
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final long maxBytes;

    public SecureUrlFetcher(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Releases the client's connection pool and selector thread. */
    @Override
    public void close() {
        httpClient.close();
    }

    /** Blocking; run off the main thread. */
    public byte[] fetch(String rawUrl) throws IOException {
        URI uri = parseAndValidate(rawUrl);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAddress(uri);   // re-validated on every hop

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "NoteblockWorldMC")
                    .GET()
                    .build();

            HttpResponse<java.io.InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted");
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();   // discard the redirect body
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("Redirect without a location"));
                try {
                    uri = parseAndValidate(uri.resolve(location).toString());
                } catch (IllegalArgumentException e) {
                    // A malformed Location header must fail as an IOException,
                    // not escape as an unchecked exception
                    throw new IOException("Redirect to an invalid URL");
                }
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("Server returned HTTP " + status);
            }

            // If the server declares an oversized body, fail without
            // streaming maxBytes of it first. The header can lie or be
            // absent, so readCapped below remains the authoritative guard.
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) {
                response.body().close();
                throw new IOException("File is too large (" + (declared / 1024)
                        + " KB; limit " + (maxBytes / 1024) + " KB)");
            }

            // Abort mid-stream if the body exceeds the cap, rather than
            // buffering a potentially huge response and checking afterwards.
            try (var in = response.body()) {
                return readCapped(in, maxBytes);
            }
        }
        throw new IOException("Too many redirects (limit " + MAX_REDIRECTS + ")");
    }

    private static byte[] readCapped(java.io.InputStream in, long maxBytes) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("File is too large (limit "
                        + (maxBytes / 1024) + " KB)");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private URI parseAndValidate(String rawUrl) throws IOException {
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only https:// URLs are allowed");
        }
        if (uri.getHost() == null) {
            throw new IOException("URL has no host");
        }
        return uri;
    }

    /**
     * Rejects the URL unless its host resolves ONLY to public addresses.
     *
     * KNOWN RESIDUAL (DNS rebinding): the HttpClient resolves the hostname
     * again when it connects, so a hostile DNS server could return a public
     * IP here and a private one to the actual connection. Fully closing this
     * needs pinning the connection to the vetted IP (a custom address
     * resolver), which the JDK client doesn't expose cleanly. The exposure is
     * limited: this command is op-only, so it is not an open relay -- an
     * attacker would already need operator trust. Documented rather than
     * silently ignored.
     */
    private void checkAddress(URI uri) throws IOException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IOException("Could not resolve host: " + uri.getHost());
        }
        if (addresses.length == 0) {
            throw new IOException("Host resolved to no address");
        }
        // ALL resolved addresses must be public: a name resolving to both a
        // public and a private IP must not squeak through on the public one.
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new IOException("URL resolves to a non-public address ("
                        + address.getHostAddress() + ") and was blocked for safety");
            }
        }
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
            if ((bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x64
                    && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B
                    && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0
                    && bytes[7] == 0 && bytes[8] == 0 && bytes[9] == 0
                    && bytes[10] == 0 && bytes[11] == 0) {
                if (embeddedV4Blocked(bytes, 12)) {
                    return true;
                }
            }
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
