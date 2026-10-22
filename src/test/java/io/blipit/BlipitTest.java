package io.blipit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.blipit.nuggets.Json;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlipitTest {
    record Received(String path, String key, String body) {}

    @TempDir
    File dir;

    @Test
    @SuppressWarnings("unchecked")
    void eventsReachBlipitInItsOwnFormat() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-Blipit-Key"), body));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            assertThrows(IllegalArgumentException.class, () -> Blipit.init(BlipitOptions.builder().project(7).build()));

            Blipit.init(BlipitOptions.builder()
                    .key("blipit_sk_abc")
                    .project(7)
                    .environment("test")
                    .release("svc@2.0.0")
                    .endpoint("http://127.0.0.1:" + port)
                    .cacheDir(dir)
                    .build());
            Blipit.setUser("42", "ana@example.com");
            Blipit.setTag("region", "ap-southeast-1");
            Blipit.addBreadcrumb("warmed 120 cache keys");
            Blipit.captureMessage("hello from java", Level.WARNING);
            Blipit.captureSecurity("login_failed", "ana@example.com", "wrong_password", null, "10.0.0.1", null, null);
            assertTrue(Blipit.flush(5000));

            assertEquals(2, received.size());
            Received first = received.get(0);
            assertEquals("/api/v1/events", first.path());
            assertEquals("blipit_sk_abc", first.key());
            Map<String, Object> e = (Map<String, Object>) Json.parse(first.body());
            assertEquals("hello from java", e.get("message"));
            assertEquals("warning", e.get("level"));
            assertEquals("test", e.get("environment"));
            assertEquals("svc@2.0.0", e.get("release"));
            assertEquals(Map.of("name", "blipit-java", "version", Blipit.VERSION), e.get("sdk"));
            assertEquals("java", ((Map<String, Object>) ((Map<String, Object>) e.get("contexts")).get("runtime")).get("name"));
            Map<String, Object> s = (Map<String, Object>) Json.parse(received.get(1).body());
            assertEquals(Map.of("kind", "login_failed", "actor", "ana@example.com", "outcome", "wrong_password", "ip", "10.0.0.1"), s.get("security"));
        } finally {
            Blipit.close();
            server.stop(0);
        }
    }

    @Test
    void publicKeySendsNoSecurityEvents() {
        Blipit.init(BlipitOptions.builder().key("blipit_pk_abc").project("7").endpoint("http://127.0.0.1:9").cacheDir(dir).build());
        Blipit.captureSecurity("login_failed", "ana@example.com");
        Blipit.close();
        assertEquals(0, new File(dir, "outbox").listFiles().length);
    }
}
