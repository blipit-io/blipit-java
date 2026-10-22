package io.blipit.nuggets;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

final class Ingest implements AutoCloseable {
    record Hit(String method, String path, String key, String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return (Map<String, Object>) Json.parse(body);
        }
    }

    record Answer(int status, String body, String retryAfter) {}

    final List<Hit> hits = new CopyOnWriteArrayList<>();
    final ConcurrentLinkedQueue<Answer> script = new ConcurrentLinkedQueue<>();
    private final HttpServer server;

    Ingest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(new Hit(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-Blipit-Key"), body));
            Answer a = script.poll();
            if (a == null) a = new Answer(202, "{\"id\":\"x\"}", null);
            if (a.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", a.retryAfter());
            byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(a.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void await(int count, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (hits.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
