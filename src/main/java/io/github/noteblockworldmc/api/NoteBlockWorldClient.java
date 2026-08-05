package io.github.noteblockworldmc.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.noteblockworldmc.api.model.SongPage;

import java.net.URI;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Thin async wrapper around the Note Block World REST API.
 *
 * All methods return CompletableFutures that complete on HttpClient's
 * executor threads — NEVER touch the Bukkit API directly in a callback;
 * hop back to the main thread first (see SongsCommand for an example).
 *
 * Endpoints (base URL has a /v1 prefix, configurable in config.yml):
 *   GET /song              -> paginated song list (q, sort, order, category, page, limit)
 *   GET /song/categories   -> { "pop": 42, "rock": 38, ... } (cached; see below)
 *   GET /song/{id}/open    -> the signed URL to fetch a song's zip from
 *
 * Two things keep the load on a small volunteer-run service reasonable:
 * categories are fetched once at startup and only again on /nbw reload, and a
 * 429 or 503 puts the client into a cooldown during which requests fail
 * locally instead of being sent.
 */
public class NoteBlockWorldClient {

    /** Sort options supported by GET /song, with user-friendly aliases. */
    public enum Sort {
        RECENT("recent", "recent"),
        RANDOM("random", "random"),
        PLAY_COUNT("playCount", "popular"),
        TITLE("title", "title"),
        DURATION("duration", "duration"),
        NOTE_COUNT("noteCount", "count");

        private final String apiValue;
        private final String friendlyName;

        Sort(String apiValue, String friendlyName) {
            this.apiValue = apiValue;
            this.friendlyName = friendlyName;
        }

        public String getApiValue() {
            return apiValue;
        }

        /** Canonical name used in commands and click-event serialization. */
        public String getFriendlyName() {
            return friendlyName;
        }

        /** Parses user input, accepting canonical names, API values, and common aliases. */
        public static Sort fromUserInput(String input) {
            if (input == null) return null;
            // Locale.ROOT matters: on a Turkish-locale JVM, "TITLE".toLowerCase()
            // becomes "tıtle" (dotless ı), silently failing every match below.
            return switch (input.toLowerCase(java.util.Locale.ROOT)) {
                case "recent", "new", "newest", "createdat" -> RECENT;
                case "random", "shuffle" -> RANDOM;
                case "popular", "plays", "playcount" -> PLAY_COUNT;
                case "title", "name", "alphabetical" -> TITLE;
                case "duration", "length" -> DURATION;
                case "count", "notes", "notecount" -> NOTE_COUNT;
                default -> null;
            };
        }
    }

    /**
     * Immutable description of a song list request.
     * Use the builder; withPage() derives a copy for pagination.
     */
    public static final class SongQuery {
        private final String q;
        private final Sort sort;
        private final Boolean ascending; // null = pick a sensible default per sort
        private final String category;
        private final int page;
        private final int limit;
        /** When true, query the local downloaded library instead of the API. */
        private final boolean downloadedOnly;

        private SongQuery(Builder b) {
            this.q = b.q;
            this.sort = b.sort;
            this.ascending = b.ascending;
            this.category = b.category;
            this.page = b.page;
            this.limit = b.limit;
            this.downloadedOnly = b.downloadedOnly;
        }

        public static Builder builder() {
            return new Builder();
        }

        public SongQuery withPage(int newPage) {
            return toBuilder().page(newPage).build();
        }

        /** Copies all fields into a new builder for derived queries. */
        public Builder toBuilder() {
            return builder()
                    .query(q).sort(sort).ascending(ascending)
                    .category(category)
                    .page(page).limit(limit)
                    .downloadedOnly(downloadedOnly);
        }

        public String getQ() { return q; }
        public Sort getSort() { return sort; }
        public Boolean getAscending() { return ascending; }
        public String getCategory() { return category; }
        public int getPage() { return page; }
        public int getLimit() { return limit; }
        public boolean isDownloadedOnly() { return downloadedOnly; }

        /**
         * Effective sort direction: explicit flag wins; otherwise title sorts
         * ascending (A->Z) and everything else descending (newest/most first).
         */
        public boolean isEffectivelyAscending() {
            if (ascending != null) return ascending;
            return sort == Sort.TITLE;
        }

        public static final class Builder {
            private String q;
            private Sort sort;
            private Boolean ascending;
            private String category;
            private int page = 1;
            private int limit = 10;
            private boolean downloadedOnly = false;

            public Builder query(String q) { this.q = q; return this; }
            public Builder sort(Sort sort) { this.sort = sort; return this; }
            public Builder ascending(Boolean ascending) { this.ascending = ascending; return this; }
            public Builder category(String category) { this.category = category; return this; }
            public Builder page(int page) { this.page = page; return this; }
            public Builder limit(int limit) { this.limit = limit; return this; }
            public Builder downloadedOnly(boolean downloadedOnly) { this.downloadedOnly = downloadedOnly; return this; }

            public SongQuery build() {
                return new SongQuery(this);
            }
        }
    }

    private final String userAgent;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Gson gson = new Gson();

    public NoteBlockWorldClient(String baseUrl, String pluginVersion,
                                Duration connectTimeout, Duration requestTimeout) {
        // Normalize: no trailing slash
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // Derived, not hardcoded: a constant here sat at "/0.1" while the
        // plugin moved on — a version string that lies is worse than none.
        this.userAgent = "NoteblockWorldMC-MinecraftPlugin/" + pluginVersion;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Fetches a page of songs according to the given query. */
    public CompletableFuture<SongPage> fetchSongs(SongQuery query) {
        Map<String, String> params = new LinkedHashMap<>();

        if (query.getQ() != null && !query.getQ().isBlank()) {
            params.put("q", query.getQ());
        }
        if (query.getSort() != null) {
            params.put("sort", query.getSort().getApiValue());
        }
        // The API reads order independently of sort (sort defaults to recent
        // server-side), so an explicit direction must be sent even when no
        // sort was chosen. Random is the exception: order doesn't apply.
        if (query.getSort() != Sort.RANDOM
                && (query.getSort() != null || query.getAscending() != null)) {
            params.put("order", query.isEffectivelyAscending() ? "asc" : "desc");
        }
        if (query.getCategory() != null && !query.getCategory().isBlank()) {
            params.put("category", query.getCategory());
        }
        params.put("page", String.valueOf(Math.max(1, query.getPage())));
        // The API validates limit with @Max(100); clamp so we never send an invalid value
        params.put("limit", String.valueOf(Math.min(100, Math.max(1, query.getLimit()))));

        return getJson("/song" + toQueryString(params), SongPage.class);
    }

    /**
     * When a server-imposed cooldown expires, as System.nanoTime(); 0 = none.
     *
     * noteblock.world is a small volunteer service, and a plugin that keeps
     * asking while being told to stop is the difference between heavy traffic
     * and making an outage worse. Requests are refused locally until this
     * passes, so a rate limit costs the API one response rather than one per
     * impatient click across every server running this plugin.
     */
    private volatile long rateLimitedUntilNanos;

    /** Default cooldown when a 429/503 arrives without a Retry-After header. */
    private static final long DEFAULT_BACKOFF_SECONDS = 60;

    /** Longest cooldown we will honour, in case a header is absurd. */
    private static final long MAX_BACKOFF_SECONDS = 3600;

    /**
     * Category list, fetched once at startup and only re-fetched on
     * /nbw reload.
     *
     * The categories are a near-static list -- they change when the site adds
     * one, not while a server is running -- so refetching them every time an
     * admin opens the browser was a call per GUI open for nothing. Caching on
     * an explicit refresh rather than a timer means the value is never
     * mysteriously stale for a few more minutes: an admin who wants it current
     * runs /nbw reload, and otherwise it costs zero requests.
     */
    private volatile CompletableFuture<Map<String, Integer>> categoryCache;

    /** Seconds remaining on a server-imposed cooldown, or 0 if not limited. */
    private long rateLimitSecondsRemaining() {
        long until = rateLimitedUntilNanos;
        if (until == 0) return 0;
        long remaining = until - System.nanoTime();
        return remaining <= 0 ? 0 : (remaining / 1_000_000_000L) + 1;
    }

    /**
     * Discards the cached categories and fetches them again.
     *
     * Called at startup and from /nbw reload, so the cache has exactly two
     * moments where it can change and an admin can always force one.
     */
    public CompletableFuture<Map<String, Integer>> refreshCategories() {
        CompletableFuture<Map<String, Integer>> fresh = fetchCategoriesUncached();
        categoryCache = fresh;
        // A failed fetch must not become a permanently cached failure: clear
        // the slot so the next caller retries (the backoff above still stops
        // that from turning into a hammer).
        fresh.whenComplete((value, error) -> {
            if (error != null && categoryCache == fresh) categoryCache = null;
        });
        return fresh;
    }

    /** Fetches available categories with their song counts. */
    public CompletableFuture<Map<String, Integer>> fetchCategories() {
        CompletableFuture<Map<String, Integer>> cached = categoryCache;
        // An in-flight fetch is shared rather than duplicated, so several
        // players opening the GUI at once still produce one request.
        if (cached != null) return cached;
        return refreshCategories();
    }

    private CompletableFuture<Map<String, Integer>> fetchCategoriesUncached() {
        return getJsonRaw("/song/categories").thenApply(body ->
                gson.fromJson(body, new TypeToken<Map<String, Integer>>() {}.getType()));
    }

    /**
     * Resolves the signed download URL for a song's packed zip
     * (song.nbs + custom instrument sounds).
     *
     * Mirrors the website's "Open in NBS" button: GET /song/{id}/open with
     * the header "src: downloadButton" (the API rejects the request with 401
     * "Invalid source" otherwise). The response body is the URL as plain text.
     */
    public CompletableFuture<String> fetchOpenUrl(String publicId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/song/" + encode(publicId) + "/open"))
                .timeout(requestTimeout)
                .header("Accept", "text/plain, application/json")
                .header("src", "downloadButton")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        CompletableFuture<String> refusedOpen = refuseIfRateLimited();
        if (refusedOpen != null) return refusedOpen;

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (isRateLimit(response.statusCode())) {
                        throw new ApiException(noteBackoff(response));
                    }
                    if (response.statusCode() == 401) {
                        throw new ApiException("Song is private or the request was rejected (HTTP 401)");
                    }
                    if (response.statusCode() == 404) {
                        throw new ApiException("No song found with that ID (HTTP 404)");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new ApiException("API returned HTTP " + response.statusCode()
                                + " while resolving the download URL");
                    }
                    String body = response.body().trim();
                    // Defensive: strip quotes in case the body is JSON-encoded
                    if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) {
                        body = body.substring(1, body.length() - 1);
                    }
                    if (!body.startsWith("https://")) {
                        throw new ApiException("API returned an unexpected download URL");
                    }
                    return body;
                });
    }

    /**
     * Downloads a file (e.g. the signed Backblaze URL from fetchOpenUrl)
     * into memory, enforcing a size cap.
     */
    public CompletableFuture<byte[]> downloadFile(String url, long maxBytes) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent)
                .GET()
                .build();

        // Stream to a byte[] but abort as soon as the cap is exceeded, so a
        // hostile or misbehaving host can't OOM us by sending a huge body
        // before we get to check its length.
        CompletableFuture<byte[]> refusedDownload = refuseIfRateLimited();
        if (refusedDownload != null) return refusedDownload;

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (isRateLimit(response.statusCode())) {
                        try {
                            response.body().close();
                        } catch (IOException ignored) {
                            // best effort; the backoff matters more
                        }
                        throw new ApiException(noteBackoff(response));
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        // The body is a live InputStream here (unlike the
                        // ofString handlers): abandoning it on the error path
                        // leaks the connection until GC.
                        try {
                            response.body().close();
                        } catch (IOException ignored) {
                            // closing best-effort; the status error matters more
                        }
                        throw new ApiException("File host returned HTTP " + response.statusCode());
                    }
                    try (var in = response.body()) {
                        return readCapped(in, maxBytes);
                    } catch (IOException e) {
                        throw new ApiException("Download failed: " + e.getMessage(), e);
                    }
                });
    }

    /** Reads at most {@code maxBytes}, throwing if the stream exceeds it. */
    private static byte[] readCapped(java.io.InputStream in, long maxBytes) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("file exceeds the size limit of " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------

    private <T> CompletableFuture<T> getJson(String path, Class<T> type) {
        return getJsonRaw(path).thenApply(body -> gson.fromJson(body, type));
    }

    private CompletableFuture<String> getJsonRaw(String path) {
        URI uri;
        try {
            uri = URI.create(baseUrl + path);
        } catch (IllegalArgumentException e) {
            // A malformed api-base-url in config.yml would otherwise throw
            // synchronously out of an async-looking method.
            return CompletableFuture.failedFuture(
                    new ApiException("Invalid API URL (check api-base-url): " + e.getMessage()));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        CompletableFuture<String> refused = refuseIfRateLimited();
        if (refused != null) return refused;

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (isRateLimit(response.statusCode())) {
                        throw new ApiException(noteBackoff(response));
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new ApiException("API returned HTTP " + response.statusCode()
                                + " for " + path);
                    }
                    return response.body();
                });
    }

    /** 429 Too Many Requests, or 503 while the service sheds load. */
    private static boolean isRateLimit(int status) {
        return status == 429 || status == 503;
    }

    /**
     * Fails immediately, without sending anything, while a cooldown is active.
     *
     * This is the part that actually protects the API: the error screen in the
     * browser doubles as a retry button, so without a local gate an admin
     * clicking it repeatedly during an outage would send a request every time.
     *
     * @return a failed future to return, or null to proceed with the request
     */
    private <T> CompletableFuture<T> refuseIfRateLimited() {
        long seconds = rateLimitSecondsRemaining();
        if (seconds <= 0) return null;
        return CompletableFuture.failedFuture(new ApiException(
                "noteblock.world asked us to wait; try again in " + seconds + "s"));
    }

    /**
     * Records the cooldown a 429/503 asked for and builds the message shown.
     *
     * Retry-After may be a number of seconds or an HTTP date; both are
     * accepted, and anything unparseable falls back to a conservative default
     * rather than being ignored.
     */
    private String noteBackoff(HttpResponse<?> response) {
        long seconds = response.headers().firstValue("Retry-After")
                .map(NoteBlockWorldClient::parseRetryAfter)
                .orElse(DEFAULT_BACKOFF_SECONDS);
        seconds = Math.max(1, Math.min(MAX_BACKOFF_SECONDS, seconds));
        rateLimitedUntilNanos = System.nanoTime() + seconds * 1_000_000_000L;
        return "noteblock.world is rate limiting requests; waiting " + seconds + "s";
    }

    /** Retry-After as delta-seconds or an HTTP date, in seconds from now. */
    private static long parseRetryAfter(String header) {
        String value = header.trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // Not a delta; try the HTTP-date form.
        }
        try {
            java.time.ZonedDateTime when = java.time.ZonedDateTime.parse(
                    value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = java.time.Duration.between(
                    java.time.ZonedDateTime.now(when.getZone()), when).toSeconds();
            return Math.max(0, seconds);
        } catch (Exception ignored) {
            return DEFAULT_BACKOFF_SECONDS;
        }
    }

    private static String toQueryString(Map<String, String> params) {
        if (params.isEmpty()) return "";
        return "?" + params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Releases the HTTP client's threads (call on plugin disable). */
    public void close() {
        httpClient.close();
    }

    /** Thrown when the API responds with a non-2xx status. */
    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
