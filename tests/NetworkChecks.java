import io.github.noteblockworldmc.util.*;
import io.github.noteblockworldmc.download.SecureUrlFetcher;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;

public class NetworkChecks extends RegressionChecks {
    static void fails(CompletableFuture<?> future,String message)throws Exception {
        try{future.get(2,TimeUnit.SECONDS);throw new AssertionError(message);}
        catch(ExecutionException|CancellationException expected){check(true,message);}
    }
    static Socket connection(int port)throws IOException {
        Socket socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",port),1000);socket.setSoTimeout(2000);return socket;
    }
    static void get(Socket socket)throws IOException {
        socket.getOutputStream().write("GET /pack.zip?v=test HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }
    public static void main(String[] args)throws Exception {
        byte[] payload=new byte[8*1024*1024];
        Class<?> serverType=Class.forName("io.github.noteblockworldmc.pack.PackHttpServer");
        var ctor=serverType.getDeclaredConstructor(InetSocketAddress.class,Function.class,Predicate.class,IntSupplier.class,long.class);ctor.setAccessible(true);
        Path payloadFile=Files.createTempFile("nbw-pack-http-", ".zip");Files.write(payloadFile,payload);
        Function<String,FileChannel> lookup=path->{
            if(!path.equals("/pack.zip?v=test"))return null;
            try{return FileChannel.open(payloadFile,StandardOpenOption.READ);}catch(IOException e){return null;}
        };
        AutoCloseable server=(AutoCloseable)ctor.newInstance(new InetSocketAddress("127.0.0.1",0),lookup,(Predicate<String>)ip->false,(IntSupplier)()->16,700L);
        int port=((InetSocketAddress)call(server,"address")).getPort();List<Socket> sockets=new ArrayList<>();
        try {
            for(int i=0;i<8;i++){Socket socket=connection(port);sockets.add(socket);get(socket);}
            try(Socket ninth=connection(port)){
                ninth.setSoTimeout(500);get(ninth);
                check(ninth.getInputStream().read()=='H',"A01: eight slow readers do not prevent a ninth request receiving headers");
            }
            Thread.sleep(1100);
            try(Socket healthy=connection(port)){get(healthy);check(healthy.getInputStream().read()=='H',"Pack service remains responsive after transfer deadlines");}
            Socket first=sockets.get(0);byte[] delivered=first.getInputStream().readAllBytes();
            check(delivered.length<payload.length,"A01: an expired slow transfer closes before sending the entire pack");
            try(Socket oversized=connection(port)){
                oversized.getOutputStream().write(("GET / HTTP/1.1\r\nX: "+"a".repeat(17000)).getBytes(StandardCharsets.US_ASCII));
                String response=new String(oversized.getInputStream().readNBytes(22),StandardCharsets.US_ASCII);
                check(response.startsWith("HTTP/1.1 431"),"Oversized request headers are rejected within the header budget");
            }
        }finally{for(Socket socket:sockets)socket.close();server.close(); Files.deleteIfExists(payloadFile);}

        var bodyRelease=new CountDownLatch(1);var headersSent=new CountDownLatch(1);
        var origin=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        var threads=Executors.newFixedThreadPool(2);origin.setExecutor(threads);
        origin.createContext("/stall",exchange->{try{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();headersSent.countDown();try{bodyRelease.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}finally{exchange.close();}});
        origin.createContext("/large",exchange->{try{exchange.sendResponseHeaders(200,1024);exchange.getResponseBody().write(new byte[1024]);}finally{exchange.close();}});
        origin.start();String base="http://127.0.0.1:"+origin.getAddress().getPort();
        var bounded=new BoundedHttp(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        try {
            var stalled=bounded.send(HttpRequest.newBuilder(URI.create(base+"/stall")).build(),1024,Duration.ofMillis(250));
            check(headersSent.await(1,TimeUnit.SECONDS),"Body-timeout test received headers before stalling");
            fails(stalled,"A02: response-body deadline applies after headers");
            fails(bounded.send(HttpRequest.newBuilder(URI.create(base+"/large")).build(),32,Duration.ofSeconds(1)),"A02: response bodies are rejected as soon as the byte cap is exceeded");
            var outstanding=bounded.send(HttpRequest.newBuilder(URI.create(base+"/stall")).build(),1024,Duration.ofSeconds(2));
            long before=System.nanoTime();bounded.close();check(System.nanoTime()-before<200_000_000L,"Client shutdown does not wait for a stalled body");
            fails(outstanding,"Shutdown cancels outstanding responses");
        }finally{bounded.close();bodyRelease.countDown();origin.stop(0);threads.shutdownNow();}
        for(String url:List.of("http://example.test/file.nbs","https://127.0.0.1/file.nbs","https://[::1]/file.nbs","https://user:pass@example.test/file.nbs")){
            try(var secure=new SecureUrlFetcher(1024)){try{secure.fetch(url);throw new AssertionError("Unsafe URL accepted");}catch(IOException expected){check(true,"URL importer rejects unsafe scheme/address/credentials: "+url);}}
        }
        var blocked=SecureUrlFetcher.class.getDeclaredMethod("isBlocked",InetAddress.class);blocked.setAccessible(true);
        for(String ip:List.of("10.0.0.1","169.254.169.254","100.64.0.1","192.0.2.1","fc00::1","2002:7f00:1::","64:ff9b::7f00:1"))
            check((boolean)blocked.invoke(null,InetAddress.getByName(ip)),"SSRF address filter rejects "+ip);
        check(!(boolean)blocked.invoke(null,InetAddress.getByName("1.1.1.1")),"Public destinations remain eligible for pinned HTTPS connections");

        var pool=new WorkPool();var release=new CountDownLatch(1);var started=new CountDownLatch(4);
        try {
            List<CompletableFuture<Integer>> pending=new ArrayList<>();
            for(int i=0;i<4;i++)pending.add(pool.submit(()->{started.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return 1;}));
            check(started.await(1,TimeUnit.SECONDS),"Work admission test occupies the bounded worker pool");
            for(int i=0;i<32;i++)pending.add(pool.submit(()->1));
            fails(pool.submit(()->1),"A03: the global work queue rejects excess jobs instead of growing");
            pool.close();check(pending.stream().allMatch(CompletableFuture::isDone),"Shutdown resolves every queued/admitted job");
        }finally{release.countDown();pool.close();}
        var actorPool=new WorkPool();try{
            var latch=new CountDownLatch(1);var task=actorPool.run("actor",()->{try{latch.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            fails(actorPool.run("actor",()->{}),"A03: one actor cannot accumulate import or analysis jobs");latch.countDown();task.get(1,TimeUnit.SECONDS);
        }finally{actorPool.close();}
        var gate=new RequestGate();try{
            UUID id=UUID.randomUUID();var pending=new CompletableFuture<String>();gate.submit(id,()->pending);
            fails(gate.submit(id,()->CompletableFuture.completedFuture("second")),"A03: reopening the browser cannot bypass per-player admission");pending.complete("first");
        }finally{gate.close();}
        System.out.println("PASS: "+checks+" bounded HTTP, SSRF-filter, and admission checks.");
    }
}
