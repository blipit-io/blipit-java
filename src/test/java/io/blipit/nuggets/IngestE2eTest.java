package io.blipit.nuggets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.blipit.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestE2eTest {
    static final String URL = System.getenv("BLIPIT_E2E_URL");
    static final String PUBLIC = System.getenv("BLIPIT_E2E_PUBLIC_KEY");
    static final String SECRET = System.getenv("BLIPIT_E2E_SECRET_KEY");

    Nuggets client(String key, List<Map<String, Object>> seen) {
        Config c = new Config();
        c.key = key;
        c.endpoint = URL;
        c.release = "e2e@1.0.0";
        c.environment = "e2e";
        c.sdkName = "blipit-java";
        c.sdkVersion = "0.2.0";
        c.contexts.put("os", Map.of("name", "Linux", "version", "6.1"));
        c.contexts.put("runtime", Map.of("name", "java", "version", "21"));
        c.contexts.put("device", Map.of("model", "Pixel 8", "screen", "1080x2400"));
        c.hook = (event, error) -> seen.add(event);
        return new Nuggets(c);
    }

    Outbox.Reply post(Nuggets n, String path, Object body) throws Exception {
        return n.outbox.post(path, Json.encode(body).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void localIngestAcceptsEveryPayloadTheSdkBuilds() throws Exception {
        assumeTrue(URL != null && PUBLIC != null && SECRET != null, "set BLIPIT_E2E_URL, BLIPIT_E2E_PUBLIC_KEY and BLIPIT_E2E_SECRET_KEY");
        List<Map<String, Object>> seen = new ArrayList<>();
        Nuggets n = client(PUBLIC, seen);
        n.outbox.close();
        n.setUser("42", "ana@example.com", "ana");
        n.setTag("region", "ap-southeast-1");
        n.setExtra("cart", Map.of("items", 3, "total", 12.5));
        for (int i = 0; i < 120; i++) n.addBreadcrumb(i % 3 == 0 ? "http" : "ui.lifecycle", "step " + i, Level.INFO, Map.of("i", i));
        n.captureException(new IllegalStateException("checkout failed", new RuntimeException("db")), true, Level.ERROR);
        n.captureMessage("cache miss rate above 50%", Level.WARNING);
        Map<String, Object> crash = n.event(Level.FATAL);
        crash.put("exception", Nuggets.exception(new OutOfMemoryError("heap"), false, n.config.notInApp));
        seen.add(crash);
        Map<String, Object> anr = n.event(Level.ERROR);
        Map<String, Object> anrEx = new LinkedHashMap<>();
        anrEx.put("type", "ApplicationNotResponding");
        anrEx.put("value", "Application Not Responding for at least 5000 ms.");
        anrEx.put("stacktrace", Map.of("frames", Nuggets.frames(Thread.currentThread().getStackTrace(), n.config.notInApp)));
        anrEx.put("handled", false);
        anr.put("exception", anrEx);
        seen.add(anr);
        Map<String, Object> nativeCrash = n.event(Level.FATAL);
        nativeCrash.put("exception", Map.of("type", "SIGSEGV", "value", "Segmentation violation (code 1) at 0x0", "handled", false,
                "stacktrace", Map.of("frames", List.of(Map.of("filename", "libgame.so", "function", "0x2234", "in_app", true)))));
        nativeCrash.put("extra", Map.of("native", Map.of("registers", Map.of("pc", "0x1"), "libraries", List.of("/system/lib64/libc.so"))));
        seen.add(nativeCrash);
        for (Map<String, Object> event : seen) {
            Outbox.Reply r = post(n, "/api/v1/events", event);
            assertEquals(202, r.status, r.body);
            assertEquals(event.get("event_id"), ((Map<String, Object>) Json.parse(r.body)).get("id"), r.body);
        }

        n.startSession("install-e2e");
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("sid", java.util.UUID.randomUUID().toString());
        session.put("did", "install-e2e");
        session.put("started", Nuggets.iso(System.currentTimeMillis()));
        session.put("status", "crashed");
        session.put("errors", 1);
        session.put("attrs", Map.of("release", "e2e@1.0.0", "environment", "e2e"));
        Outbox.Reply s = post(n, "/api/v1/sessions", Map.of("sessions", List.of(session)));
        assertEquals(202, s.status, s.body);
        assertEquals(1L, ((Map<String, Object>) Json.parse(s.body)).get("accepted"));

        Outbox.Reply refused = post(n, "/api/v1/events", Map.of("event_id", Nuggets.id(), "timestamp", Nuggets.iso(System.currentTimeMillis()), "platform", "java", "sdk", Map.of("name", "blipit-java", "version", "0.2.0"), "message", "x", "bogus", 1));
        assertEquals(400, refused.status, "the strict schema still rejects unknown keys");

        List<Map<String, Object>> secretSeen = new ArrayList<>();
        Nuggets server = client(SECRET, secretSeen);
        server.outbox.close();
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("kind", "login_failed");
        security.put("actor", "ana@example.com");
        security.put("ip", "10.0.0.1");
        server.captureSecurity(security, "login_failed for ana@example.com", Level.WARNING);
        Outbox.Reply sec = post(server, "/api/v1/events", secretSeen.get(0));
        assertTrue(sec.status == 202 || sec.body.contains("security_monitoring_not_in_plan"), sec.status + " " + sec.body);
    }
}
