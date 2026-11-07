package kakha.kudava.onionp2p;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import static java.nio.charset.StandardCharsets.UTF_8;

/** A loopback web server exposed by the bundled Windows Tor process. */
public final class OnionServer implements AutoCloseable {
    private HttpServer http;
    private Process tor;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closed;

    public void start() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")
                || !System.getProperty("os.arch").matches("amd64|x86_64")) {
            throw new IOException("The bundled Tor runtime requires Windows x64.");
        }
        Path data = AppData.directory();
        CompletableFuture<Void> bootstrapped = new CompletableFuture<>();
        synchronized (this) {
            if (closed) return;
            Files.createDirectories(data);
            lockChannel = FileChannel.open(data.resolve("app.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Another app instance is using " + data);
            Path runtime = data.resolve("tor-16.0");
            extractRuntime(runtime);
            http = createHttpServer();
            http.start();
            Path config = data.resolve("torrc");
            Files.writeString(config, "DataDirectory " + quoted(data.resolve("tor-data")) + "\n"
                    + "GeoIPFile " + quoted(runtime.resolve("data/geoip")) + "\n"
                    + "GeoIPv6File " + quoted(runtime.resolve("data/geoip6")) + "\n"
                    + "HiddenServiceDir " + quoted(data.resolve("service")) + "\n"
                    + "HiddenServicePort 80 127.0.0.1:" + http.getAddress().getPort() + "\n"
                    + "SocksPort 0\nLog notice stdout\n", UTF_8);
            tor = new ProcessBuilder(runtime.resolve("tor/tor.exe").toString(), "-f", config.toString())
                    .directory(runtime.resolve("tor").toFile()).redirectErrorStream(true).start();
            Process child = tor;
            Thread.ofPlatform().daemon().name("tor-output").start(() -> {
                try (var reader = child.inputReader(UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Tor] " + line);
                        if (line.contains("Bootstrapped 100%")) bootstrapped.complete(null);
                    }
                    bootstrapped.completeExceptionally(new IOException("Tor exited before connecting."));
                } catch (IOException e) {
                    bootstrapped.completeExceptionally(e);
                }
            });
            child.onExit().thenRun(() -> {
                synchronized (this) {
                    if (!closed) {
                        System.err.println("Tor stopped unexpectedly (exit " + child.exitValue() + ").");
                        close();
                    }
                }
            });
        }
        System.out.println("Connecting to Tor; initial startup may take a few minutes...");
        try {
            bootstrapped.get(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Tor did not connect within three minutes. Check the Tor log above.", e);
        }
        Path hostname = data.resolve("service/hostname");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(hostname) && System.nanoTime() < deadline) {
            synchronized (this) { if (closed) return; }
            Thread.sleep(100);
        }
        String address = Files.readString(hostname, UTF_8).trim();
        if (!address.matches("[a-z2-7]{56}\\.onion")) throw new IOException("Invalid onion hostname.");
        synchronized (this) {
            if (!closed) System.out.println("\nOnion address: http://" + address + "/\n"
                    + "Open in Tor Browser. Publication may take a little longer.\n");
        }
    }

    static HttpServer createHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = "Hello world!\n".getBytes(UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        return server;
    }

    private static String quoted(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void extractRuntime(Path directory) throws IOException {
        if (Files.exists(directory.resolve(".complete"))) return;
        Files.createDirectories(directory);
        try (InputStream resource = OnionServer.class.getResourceAsStream("/tor/windows-x64.zip")) {
            if (resource == null) throw new IOException("Bundled Tor runtime is missing.");
            try (var zip = new ZipInputStream(resource)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = directory.resolve(entry.getName()).normalize();
                    if (!target.startsWith(directory)) throw new IOException("Invalid runtime archive path.");
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Files.writeString(directory.resolve(".complete"), "16.0\n");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (tor != null) {
            tor.destroy();
            try {
                if (!tor.waitFor(5, TimeUnit.SECONDS)) {
                    tor.destroyForcibly();
                    tor.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                tor.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (http != null) http.stop(0);
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException e) {
            System.err.println("Could not release app lock: " + e.getMessage());
        }
    }
}
