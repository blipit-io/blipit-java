package io.blipit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class BlipitTest {
    record Received(String path, String body) {}

    @Test
    void eventsReachBlipit() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            InputStream in = exchange.getRequestBody();
            if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                in = new GZIPInputStream(in);
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestURI().getPath(), body));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            assertEquals("https://blipit_pk_abc@in.blipit.io/7", Blipit.dsn("blipit_pk_abc", "7", null));
            assertThrows(IllegalArgumentException.class, () -> Blipit.init(BlipitOptions.builder().project(7).build()));

            Blipit.init(BlipitOptions.builder()
                    .key("blipit_pk_abc")
                    .project(7)
                    .environment("test")
                    .endpoint("http://127.0.0.1:" + port)
                    .build());
            Blipit.captureMessage("hello from java");
            Blipit.flush(5000);

            long deadline = System.currentTimeMillis() + 5000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(received.isEmpty(), "nothing was sent");
            Received first = received.get(0);
            assertEquals("/api/7/envelope/", first.path());
            String[] lines = first.body().split("\n");
            assertTrue(lines.length >= 3, "envelope has " + lines.length + " lines");
            assertTrue(lines[2].contains("\"formatted\":\"hello from java\""), lines[2]);
            assertTrue(lines[2].contains("\"environment\":\"test\""), lines[2]);
        } finally {
            Blipit.close();
            server.stop(0);
        }
    }
}
