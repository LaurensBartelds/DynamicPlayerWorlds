package nl.gzmn.playerworlds.core.obs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal HTTP scrape endpoint for {@link WorldsMetrics}.
 *
 * <p>Serves {@code GET /metrics} (and {@code GET /}) with Prometheus text. Built
 * on a plain {@link ServerSocket} rather than {@code com.sun.net.httpserver} so
 * forbidden-apis' non-portable JDK ban does not block the foundation, and so the
 * endpoint has no extra dependency inside a plugin classloader.
 *
 * <p>Only the scrape path is implemented. Anything else gets {@code 404}.
 */
public final class PrometheusEndpoint implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PrometheusEndpoint.class);

    private static final byte[] NOT_FOUND = "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] METHOD_NOT_ALLOWED =
            "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);

    private final WorldsMetrics metrics;
    private final ServerSocket serverSocket;
    private final ExecutorService acceptLoop;
    private final ExecutorService workers;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final InetSocketAddress boundAddress;

    private PrometheusEndpoint(WorldsMetrics metrics, ServerSocket serverSocket, InetSocketAddress boundAddress) {
        this.metrics = metrics;
        this.serverSocket = serverSocket;
        this.boundAddress = boundAddress;
        this.acceptLoop = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "gzmn-metrics-accept");
            thread.setDaemon(true);
            return thread;
        });
        this.workers = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "gzmn-metrics-worker");
            thread.setDaemon(true);
            return thread;
        });
        acceptLoop.execute(this::acceptLoop);
    }

    /**
     * Opens the scrape socket.
     *
     * @return the endpoint, or {@code null} when {@link MetricsSettings#endpointEnabled()}
     *     is false
     */
    public static @Nullable PrometheusEndpoint start(WorldsMetrics metrics, MetricsSettings settings)
            throws IOException {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(settings, "settings");
        if (!settings.endpointEnabled()) {
            return null;
        }
        InetAddress address = InetAddress.getByName(settings.bindAddress());
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(address, settings.port()));
        InetSocketAddress bound = new InetSocketAddress(socket.getInetAddress(), socket.getLocalPort());
        log.info(
                "prometheus scrape endpoint listening on http://{}:{}/metrics", bound.getHostString(), bound.getPort());
        return new PrometheusEndpoint(metrics, socket, bound);
    }

    /** Address the socket actually bound (port may be ephemeral if 0 were allowed). */
    public InetSocketAddress address() {
        return boundAddress;
    }

    private void acceptLoop() {
        while (open.get()) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> handle(client));
            } catch (SocketException e) {
                if (open.get()) {
                    log.warn("metrics accept socket closed unexpectedly: {}", e.toString());
                }
                return;
            } catch (IOException e) {
                if (open.get()) {
                    log.warn("metrics accept failed: {}", e.toString());
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(5_000);
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }
            // Drain headers so simple clients that write a full request do not RST.
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                // ignore
            }

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                write(client, NOT_FOUND);
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }

            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                write(client, METHOD_NOT_ALLOWED);
                return;
            }
            if (!"/metrics".equals(path) && !"/".equals(path)) {
                write(client, NOT_FOUND);
                return;
            }

            byte[] body = metrics.scrape().getBytes(StandardCharsets.UTF_8);
            boolean head = "HEAD".equalsIgnoreCase(method);
            // Built with StringBuilder rather than adjacent string-literal
            // concat: Error Prone 2.50.0's StringConcatToTextBlock crashes on
            // some multi-line concatenations in this toolchain.
            StringBuilder headerBlock = new StringBuilder(128);
            headerBlock.append("HTTP/1.1 200 OK\r\n");
            headerBlock.append("Content-Type: text/plain; version=0.0.4; charset=utf-8\r\n");
            headerBlock.append("Content-Length: ");
            headerBlock.append(body.length);
            headerBlock.append("\r\n");
            headerBlock.append("Connection: close\r\n");
            headerBlock.append("\r\n");
            OutputStream out = client.getOutputStream();
            out.write(headerBlock.toString().getBytes(StandardCharsets.US_ASCII));
            if (!head) {
                out.write(body);
            }
            out.flush();
        } catch (IOException e) {
            log.debug("metrics client handling failed: {}", e.toString());
        }
    }

    private static void write(Socket client, byte[] response) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(response);
        out.flush();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.debug("closing metrics socket: {}", e.toString());
        }
        acceptLoop.shutdownNow();
        workers.shutdownNow();
        try {
            acceptLoop.awaitTermination(2, TimeUnit.SECONDS);
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
