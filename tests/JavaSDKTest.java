import com.sun.net.httpserver.HttpServer;
import welink.WeLink;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;

public class JavaSDKTest {
    static volatile String mode = "ok", uri = "";
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            uri = exchange.getRequestURI().toString();
            exchange.getRequestBody().readAllBytes();
            String current = mode;
            int status = current.equals("http-error") ? 500 : current.equals("api-error") ? 403 : 200;
            try {
                if (current.equals("slow")) Thread.sleep(250);
                String body = current.equals("stream")
                        ? ": keep-alive\r\n\r\ndata: {\"event_id\":\"evt_你好\",\r\ndata: \"type\":\"message.received\"}\r\n\r\ndata: {\"event_id\":\"evt_two\"}\n\n"
                        : current.equals("bad") ? "null" : current.equals("missing") ? "{}"
                        : current.equals("api-error") ? "{\"code\":40301,\"message\":\"expired\",\"request_id\":\"req_test\"}"
                        : "{\"code\":0,\"data\":{\"ok\":true}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", current.equals("stream") ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            WeLink wx = new WeLink("key_test", base);
            wx.messageText("acc a/b", Map.of("to", "wxid", "content", "你好"));
            check(uri.equals("/v1/accounts/acc%20a%2Fb/messages/text"));
            wx.platformEvents(Map.of("type", List.of("message.received", "account.online")));
            check(uri.contains("type=message.received") && uri.contains("type=account.online"));
            for (String value : List.of("bad", "missing", "http-error", "api-error")) {
                mode = value;
                try { wx.call("GET", "/test", null, null); throw new AssertionError(); }
                catch (WeLink.WeLinkException e) { if (value.equals("api-error")) check(e.code == 40301 && e.requestId.equals("req_test")); }
                try { wx.upload("/test", "test.txt", new byte[]{1}, null); throw new AssertionError(); }
                catch (WeLink.WeLinkException e) { }
            }
            mode = "slow";
            WeLink fast = new WeLink("key_test", base, Duration.ofMillis(80));
            try { fast.call("GET", "/test", null, null); throw new AssertionError(); }
            catch (WeLink.WeLinkException e) { }
            mode = "stream";
            try (WeLink.EventStream stream = wx.platformStream("acc_test")) {
                check(stream.next().get("event_id").equals("evt_你好"));
                check(stream.next().get("event_id").equals("evt_two"));
                check(stream.next() == null);
            }
            byte[] body = "你好".getBytes(StandardCharsets.UTF_8);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sig = new StringBuilder("sha256=");
            for (byte b : mac.doFinal(body)) sig.append(String.format("%02x", b));
            check(WeLink.verifyWebhook("secret", body, sig.toString()));
            check(!WeLink.verifyWebhook("secret", body, "sha256=wrong"));
            Thread.currentThread().interrupt();
            try { wx.call("GET", "/test", null, null); throw new AssertionError(); }
            catch (WeLink.WeLinkException e) { check(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
            System.out.println("Java SDK: requests, filters, uploads, errors, timeout, SSE passed");
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
