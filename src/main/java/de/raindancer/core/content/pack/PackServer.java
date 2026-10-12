package de.raindancer.core.content.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small read-only web server for the built pack.
 *
 * <h2>Why the library serves it at all</h2>
 * Because the pack is built here, on this machine, and the client has to download it from somewhere.
 * The alternative is telling every server owner to set up a web server and keep a URL in step with a
 * file that changes whenever a plugin is added — which is exactly the sort of manual step that is
 * wrong six months later and produces a "pack failed to download" that nobody can trace.
 *
 * <p>An owner who already has a web server or a CDN can still use it: set a public address, or do
 * not start this at all and hand out your own URL.
 *
 * <h2>What it will not do</h2>
 * {@code GET} and {@code HEAD}, for one file name, under one prefix. No listing, no writes, and a
 * name containing a slash or a {@code ..} is refused before the filesystem is touched — then the
 * resolved path is checked to be inside the folder anyway. Besides the signed server link
 * ({@code platform.link}), this is the only part of the library that opens a port; serving files by name from a
 * request is how a pack server becomes a way to read
 * {@code server.properties}, so it is deliberately dull.
 *
 * <h2>Where this came from</h2>
 * Adapted from {@code RainsResourcepackManager}, which had it first and had it right. Changed here:
 * the logger, port 0 support so a test can have the operating system pick a free port, and a public
 * address separate from the bind address — because binding to {@code 0.0.0.0} must not become the
 * URL a client is told to download from.
 */
public final class PackServer {

    private static final LogChannel log = Log.of("pack");

    /** The one path prefix packs are published under. */
    public static final String PREFIX = "/packs/";

    private final Path folder;
    private final String bind;
    private final int wantedPort;

    private volatile String publicAddress = "";
    private volatile HttpServer server;
    private volatile ExecutorService threads;
    private volatile int actualPort;

    /**
     * @param folder where the built packs are; the only directory this will ever serve
     * @param bind   the address to listen on — {@code 0.0.0.0} for everything
     * @param port   the port, or {@code 0} to let the operating system pick one
     */
    public PackServer(Path folder, String bind, int port) {
        this.folder = folder.toAbsolutePath().normalize();
        this.bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind.trim();
        this.wantedPort = port;
    }

    /**
     * The address clients are told to download from, when it is not the one this binds to.
     *
     * <p>For a server behind a reverse proxy, or one bound to {@code 0.0.0.0}: without this the URL
     * handed to a client is whatever this listens on, which from the client's side is either
     * meaningless or their own machine.
     *
     * @param address a scheme and host, with a port if it needs one — {@code https://play.example.com}
     */
    public void publicAddress(String address) {
        this.publicAddress = address == null ? "" : address.trim().replaceAll("/+$", "");
    }

    /**
     * Starts listening.
     *
     * <p>Synchronized, and not merely tidy: with port 0 two callers racing here would each bind a
     * different free port, and the second would overwrite the field holding the first — leaving a
     * server and two threads listening for the life of the JVM that {@link #stop()} cannot see.
     *
     * @throws IOException when the port is taken or the address cannot be bound — deliberately
     *                     thrown rather than logged, because a pack server that silently failed to
     *                     start is indistinguishable from one nobody configured
     */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        Files.createDirectories(folder);
        HttpServer starting = HttpServer.create(new InetSocketAddress(bind, wantedPort), 0);
        starting.createContext(PREFIX, this::handle);
        ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "rainscore-pack-http");
            // Daemon, so a stop that somehow does not run cannot keep the JVM alive after the
            // server has shut down.
            thread.setDaemon(true);
            return thread;
        });
        starting.setExecutor(pool);
        starting.start();

        this.threads = pool;
        this.server = starting;
        this.actualPort = starting.getAddress().getPort();
        log.info("Serving resource packs on http://{}:{}{}", bind, actualPort, PREFIX);
    }

    /** Stops listening. Doing it twice, or before starting, is not an error. */
    public synchronized void stop() {
        HttpServer running = server;
        if (running != null) {
            running.stop(0);
            server = null;
        }
        // The pool is ours, not the server's: stopping an HttpServer does not touch its executor, so
        // without this every reload left two more threads behind for the life of the JVM.
        ExecutorService pool = threads;
        if (pool != null) {
            pool.shutdownNow();
            threads = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    /** The port actually in use, which is not the one asked for when that was {@code 0}. */
    public int port() {
        return isRunning() ? actualPort : wantedPort;
    }

    /**
     * Whether the address clients are told is one they can reach. Not when nothing public is set and
     * the server is bound to every interface (or only to this machine): clients are then sent
     * {@code http://0.0.0.0:port/...}, which works on the server's own machine and nowhere else.
     */
    public boolean givesClientsAReachableAddress() {
        if (!publicAddress.isEmpty()) {
            return true;
        }
        String host = bind == null ? "" : bind.trim();
        return !(host.isEmpty() || host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")
                || host.equals("localhost") || host.startsWith("127."));
    }

    /** Where a client should download one built pack from. */
    public String urlFor(String fileName) {
        String host = publicAddress.isEmpty() ? "http://" + bind + ":" + port() : publicAddress;
        return host + PREFIX + fileName;
    }

    // ---------------------------------------------------------------------------- the one handler

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD")) {
                empty(exchange, 405);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(PREFIX)) {
                empty(exchange, 404);
                return;
            }
            String name = path.substring(PREFIX.length());
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                empty(exchange, 404);
                return;
            }
            Path file = folder.resolve(name).normalize();
            // Belt and braces. The checks above should make this impossible; this is what actually
            // guarantees nothing outside the folder is ever served.
            if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
                empty(exchange, 404);
                return;
            }

            long size = Files.size(file);
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            // A built pack's name contains its own hash, so its contents can never change. Telling
            // the client that is what stops it revalidating a hundred megabytes on every join.
            exchange.getResponseHeaders().add("Cache-Control",
                    "public, max-age=31536000, immutable");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().add("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
        } catch (IOException | RuntimeException failure) {
            // A client that hung up mid-download is ordinary and must not take the server with it.
            log.warn("Serving a pack failed: {}", failure.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
