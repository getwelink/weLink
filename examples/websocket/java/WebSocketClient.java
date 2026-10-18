package welink;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.*;

/** JDK 11+. Compile together with sdk/java/src/welink/WeLink.java. */
public final class WebSocketClient {
    private final String key = System.getenv("WELINK_API_KEY");
    private final Path state;
    private final String endpoint;
    private String cursor;
    private volatile boolean stopped;
    private volatile WebSocket active;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "websocket-watchdog"); t.setDaemon(true); return t; });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    WebSocketClient() throws Exception {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Set WELINK_API_KEY");
        URI base = URI.create(System.getenv("WELINK_BASE_URL"));
        String scheme = base.getScheme();
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || !("https".equals(scheme) || "http".equals(scheme) || "ws".equals(scheme) || "wss".equals(scheme))) throw new IllegalArgumentException("Invalid WELINK_BASE_URL");
        endpoint = base.toString().replaceFirst("^http", "ws").replaceFirst("/+$", "") + "/v1/ws";
        String filename = System.getenv("WELINK_CURSOR_FILE");
        byte[] sum = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder identity = new StringBuilder();
        for (int i = 0; i < 6; i++) identity.append(String.format("%02x", sum[i] & 0xff));
        state = Paths.get(filename == null || filename.isEmpty() ? ".welink-ws-user-" + identity + ".cursor" : filename).toAbsolutePath();
        Files.createDirectories(state.getParent());
        cursor = Files.exists(state) ? Files.readString(state, StandardCharsets.UTF_8).trim() : "";
        if (cursor.length() > 64) throw new IllegalArgumentException("Invalid cursor file");
    }
    public static void main(String[] args) throws Exception {
        WebSocketClient client = new WebSocketClient();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { client.stopped = true; if (client.active != null) client.active.abort(); }));
        int result = client.run();
        if (result != 0) System.exit(result);
    }
    private int run() throws Exception {
        long wait = 1000;
        while (!stopped) {
            Listener listener = new Listener();
            ScheduledFuture<?> timeout = null;
            String target = endpoint + (cursor.isEmpty() ? "" : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            try {
                active = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + key).buildAsync(URI.create(target), listener).join();
                timeout = watchdog.scheduleAtFixedRate(() -> {
                    if (System.currentTimeMillis() - listener.lastSeen > 75000) {
                        listener.closed.completeExceptionally(new TimeoutException("Heartbeat timeout"));
                        if (active != null) active.abort();
                    }
                }, 5, 5, TimeUnit.SECONDS);
                listener.closed.join();
                if (listener.processed) wait = 1000;
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof WebSocketHandshakeException) {
                    int status = ((WebSocketHandshakeException) cause).getResponse().statusCode();
                    System.err.println("Handshake HTTP " + status + "; check service version, key and account");
                    if (status == 400 || status == 401 || status == 403 || status == 404) return 2;
                } else System.err.println("Connection ended; reconnecting from saved cursor");
            } finally { if (timeout != null) timeout.cancel(false); if (active != null) active.abort(); }
            if (listener.stop) return 2;
            if (!stopped) Thread.sleep(wait + ThreadLocalRandom.current().nextInt(1000));
            wait = Math.min(wait * 2, 30000);
        }
        return 0;
    }
    private void save(String id) throws Exception {
        Path temp = state.resolveSibling(state.getFileName() + ".tmp");
        Files.writeString(temp, id + "\n", StandardCharsets.UTF_8);
        try { Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING); }
        cursor = id;
    }
    private void handleEvent(Map<?, ?> event) {
        // Replace with your business handler and deduplicate event_id in your database.
        // Throw on failure; save() runs only after successful processing.
        System.out.println(WeLink.Json.write(event));
    }
    private final class Listener implements WebSocket.Listener {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        volatile boolean stop;
        volatile boolean processed;
        volatile long lastSeen = System.currentTimeMillis();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                lastSeen = System.currentTimeMillis();
                fragments.append(data);
                if (fragments.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("Message too large");
                if (last) {
                    Object parsed = WeLink.Json.read(fragments.toString()); fragments.setLength(0);
                    if (!(parsed instanceof Map)) throw new IllegalArgumentException("Invalid event");
                    Map<?, ?> event = (Map<?, ?>) parsed;
                    String type = String.valueOf(event.get("type"));
                    if ("gap".equals(type) || "error".equals(type)) {
                        stop = true;
                        System.err.println("Stopped: " + type + " (" + event.get("reason") + "); resolve access or pull missing messages before restarting");
                        socket.abort(); closed.complete(null); return null;
                    }
                    Object id = event.get("event_id");
                    if (id instanceof String && !id.equals(cursor)) { handleEvent(event); save((String) id); processed = true; }
                }
                socket.request(1);
            } catch (Exception ex) { socket.abort(); closed.completeExceptionally(ex); }
            return null;
        }
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer data) { lastSeen = System.currentTimeMillis(); socket.request(1); return socket.sendPong(data); }
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) { lastSeen = System.currentTimeMillis(); socket.request(1); return null; }
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { stop = stop || code == 1008; closed.complete(null); return null; }
        public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
}
