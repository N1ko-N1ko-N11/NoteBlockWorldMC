import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** The Windows JDK transport is selected explicitly; all traffic remains on loopback. */
public final class WindowsHttpChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        System.setProperty("noteblockworldmc.pack-http-transport", "jdk");
        Path file = Files.createTempFile(Path.of(args[0]), "windows-pack-", ".zip");
        byte[] body = new byte[128 * 1024]; for (int i = 0; i < body.length; i++) body[i] = (byte)(i * 37);
        Files.write(file, body);
        Function<String, FileChannel> lookup = target -> {
            if (!target.equals("/pack.zip?v=test")) return null;
            try { return FileChannel.open(file, StandardOpenOption.READ); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        AtomicBoolean refuse = new AtomicBoolean();
        var type = Class.forName("io.github.noteblockworldmc.pack.PackHttpServer");
        var ctor = type.getDeclaredConstructor(InetSocketAddress.class, Function.class, Predicate.class, IntSupplier.class, long.class);
        ctor.setAccessible(true);
        var server = (AutoCloseable) ctor.newInstance(new InetSocketAddress("127.0.0.1", 0), lookup,
                (Predicate<String>) ip -> refuse.get(), (IntSupplier) () -> 8, 1500L);
        List<Socket> partial = new ArrayList<>();
        try {
            int port = ((InetSocketAddress) call(server, "address")).getPort();
            Object transport = field(server, "transport"), gate = field(transport, "gateway");
            var clients = (Set<?>) field(gate, "clients");
            for (int i = 0; i < 8; i++) {
                Socket socket = NetworkChecks.connection(port); partial.add(socket);
                socket.getOutputStream().write("GET /pack.zip?v=test HTTP/1.1\r\nHost: localhost\r\nX-Unfinished: ".getBytes(StandardCharsets.US_ASCII));
            }
            long until = System.nanoTime() + 1_000_000_000L;
            while (clients.size() < 8 && System.nanoTime() < until) Thread.sleep(5);
            check(clients.size() == 8, "JDK header test has eight admitted incomplete requests");
            check(((java.util.concurrent.ThreadPoolExecutor)field(transport, "executor")).getActiveCount() == 0,
                    "Incomplete public headers never occupy JDK HTTP workers");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            URI uri = URI.create("http://127.0.0.1:" + port + "/pack.zip?v=test");
            var get = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofByteArray());
            check(get.statusCode() == 200 && Arrays.equals(get.body(), body), "A valid JDK pack download succeeds while incomplete headers are held open");
            var head = http.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode() == 200 && head.body().length == 0 && head.headers().firstValueAsLong("Content-Length").orElse(-1) == body.length,
                    "Windows HEAD preserves the advertised length without a response body");
            Socket held = partial.get(0); held.setSoTimeout(2500);
            check(held.getInputStream().read() == -1, "Incomplete headers are closed by the absolute deadline");
            until = System.nanoTime() + 1_000_000_000L;
            while (!clients.isEmpty() && System.nanoTime() < until) Thread.sleep(5);
            check(clients.isEmpty(), "Expired header connections release all admission slots");
            refuse.set(true);
            try (Socket rejected = NetworkChecks.connection(port)) {
                check(rejected.getInputStream().read() == -1, "Per-IP refusal happens before any request bytes are required");
            }
            refuse.set(false);
            check(http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode() == 200,
                    "Windows downloads still work after header expiry and admission rejection");
            server.close();
            check(clients.isEmpty(), "Closing Windows hosting releases public connection state");
        } finally { for (Socket socket : partial) socket.close(); server.close(); Files.deleteIfExists(file); }
        System.out.println("PASS: " + checks + " Windows/JDK HTTP checks.");
    }
}
