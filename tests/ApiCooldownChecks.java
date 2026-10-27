import io.github.noteblockworldmc.api.NoteBlockWorldClient;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Uses the actual API client in a separate classpath; no API requests are sent. */
public final class ApiCooldownChecks extends RegressionChecks {
    static HttpResponse<?> response(long seconds) {
        return (HttpResponse<?>) java.lang.reflect.Proxy.newProxyInstance(ApiCooldownChecks.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "headers" -> HttpHeaders.of(Map.of("Retry-After", List.of(Long.toString(seconds))), (k, v) -> true);
                    case "statusCode" -> 429;
                    default -> null;
                });
    }
    public static void main(String[] args) throws Exception {
        var client = new NoteBlockWorldClient("https://example.invalid/v1", "test", Duration.ofSeconds(1), Duration.ofSeconds(1));
        var workers = Executors.newFixedThreadPool(4);
        try {
            call(client, "noteBackoff", response(60)); long until = (long) field(client, "rateLimitedUntilNanos");
            call(client, "noteBackoff", response(1));
            check((long) field(client, "rateLimitedUntilNanos") == until, "A short Retry-After cannot shorten an outstanding longer cooldown");
            List<Callable<Void>> replies = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                long seconds = i % 4 == 0 ? 3600 : 1;
                replies.add(() -> { call(client, "noteBackoff", response(seconds)); return null; });
            }
            for (Future<Void> future : workers.invokeAll(replies)) future.get();
            check((long) field(client, "rateLimitedUntilNanos") - System.nanoTime() > TimeUnit.SECONDS.toNanos(3590),
                    "Concurrent rate-limit responses preserve the longest remaining wait");
            String message = (String) call(client, "noteBackoff", response(1));
            check(!message.endsWith(" 1s"), "Cooldown feedback reflects the retained wait instead of the shorter response");
            var parse = NoteBlockWorldClient.class.getDeclaredMethod("parseCategories", String.class); parse.setAccessible(true);
            Map<String, Integer> raw = new LinkedHashMap<>();
            raw.put("pop", 3); raw.put("\u00A7kspam", 1); raw.put("a\nb", 1); raw.put("x".repeat(65), 1); raw.put(null, 1);
            raw.put("rock", null); raw.put("zero\u200bwidth", 1);
            for (int i = 0; i < 500; i++) raw.put("cat" + i, i);
            com.google.gson.Gson.readResult = body -> raw;   // the harness's Gson double hands back this map
            @SuppressWarnings("unchecked") var cleaned = (Map<String, Integer>) parse.invoke(client, "{}");
            com.google.gson.Gson.readResult = body -> null;
            check(cleaned.get("pop") == 3 && cleaned.get("rock") == 0, "Ordinary categories survive; a null count reads as 0");
            check(cleaned.keySet().stream().noneMatch(k -> k == null || k.length() > 64 || k.contains("\u00A7") || k.contains("\n") || k.contains("\u200b")),
                    "Category names with formatting codes, control or format characters, or excess length are dropped");
            check(cleaned.size() == 100, "At most 100 categories are kept");
        } finally { workers.shutdownNow(); client.close(); }
        System.out.println("PASS: " + checks + " actual API-client cooldown checks.");
    }
}
