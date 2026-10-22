package io.blipit.nuggets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.blipit.Level;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NuggetsTest {
    static final Set<String> TOP = Set.of("event_id", "timestamp", "platform", "level", "message", "exception", "security", "stuck", "fingerprint", "release", "environment", "user", "tags", "extra", "breadcrumbs", "request", "contexts", "sdk");
    static final Set<String> CATEGORIES = Set.of("ui.click", "ui.input", "navigation", "http", "console", "query", "custom");

    @TempDir
    File dir;

    Config config(String url, File folder) {
        Config c = new Config();
        c.key = "blipit_pk_test";
        c.endpoint = url;
        c.release = "app@1.0.0";
        c.environment = "test";
        c.sdkName = "blipit-java";
        c.sdkVersion = "0.2.0";
        c.dir = folder;
        c.sendCrashNow = false;
        return c;
    }

    @SuppressWarnings("unchecked")
    static void assertBlipitShape(Map<String, Object> e) {
        assertTrue(TOP.containsAll(e.keySet()), "unknown keys " + e.keySet());
        assertTrue(Pattern.matches("^[0-9a-f]{32}$", (String) e.get("event_id")));
        assertTrue(Pattern.matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z$", (String) e.get("timestamp")));
        assertEquals("java", e.get("platform"));
        assertTrue(e.containsKey("message") || e.containsKey("exception") || e.containsKey("security"));
        Map<String, Object> sdk = (Map<String, Object>) e.get("sdk");
        assertEquals(Set.of("name", "version"), sdk.keySet());
        Map<String, Object> tags = (Map<String, Object>) e.get("tags");
        if (tags != null) {
            assertTrue(tags.size() <= 32);
            for (String k : tags.keySet()) assertTrue(Pattern.matches("^[a-zA-Z0-9_.-]{1,64}$", k), k);
        }
        List<Map<String, Object>> crumbs = (List<Map<String, Object>>) e.get("breadcrumbs");
        if (crumbs != null) {
            assertTrue(crumbs.size() <= 100);
            for (Map<String, Object> b : crumbs) {
                assertTrue(CATEGORIES.contains(b.get("category")), String.valueOf(b));
                assertTrue(Set.of("timestamp", "category", "message", "level", "data").containsAll(b.keySet()));
            }
        }
        Map<String, Object> ex = (Map<String, Object>) e.get("exception");
        if (ex != null) {
            assertTrue(Set.of("type", "value", "stacktrace", "handled").containsAll(ex.keySet()));
            Map<String, Object> st = (Map<String, Object>) ex.get("stacktrace");
            if (st != null) {
                List<Map<String, Object>> frames = (List<Map<String, Object>>) st.get("frames");
                assertTrue(frames.size() <= 128);
                for (Map<String, Object> f : frames) {
                    assertNotNull(f.get("filename"));
                    assertTrue(Set.of("filename", "function", "lineno", "colno", "in_app").containsAll(f.keySet()));
                }
            }
        }
        Map<String, Object> contexts = (Map<String, Object>) e.get("contexts");
        if (contexts != null) assertTrue(Set.of("browser", "os", "runtime", "device").containsAll(contexts.keySet()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exceptionPayloadIsBlipitShaped() throws Exception {
        try (Ingest ingest = new Ingest()) {
            Config c = config(ingest.url(), null);
            c.notInApp = new String[] {"java.", "jdk.", "org.junit.", "org.gradle."};
            Nuggets n = new Nuggets(c);
            n.setUser("42", "ana@example.com", null);
            assertTrue(n.setTag("region", "ap-southeast-1"));
            assertFalse(n.setTag("bad key", "x"));
            n.setExtra("cart", Map.of("items", 3));
            n.addBreadcrumb("ui", "opened checkout", Level.INFO, null);
            Exception cause = new IllegalStateException("db down");
            String id = n.captureException(new RuntimeException("checkout failed", cause), true, Level.ERROR);
            assertTrue(n.flush(5000));
            assertEquals(1, ingest.hits.size());
            Ingest.Hit hit = ingest.hits.get(0);
            assertEquals("/api/v1/events", hit.path());
            assertEquals("blipit_pk_test", hit.key());
            Map<String, Object> e = hit.json();
            assertBlipitShape(e);
            assertEquals(id, e.get("event_id"));
            assertEquals("error", e.get("level"));
            assertEquals("app@1.0.0", e.get("release"));
            assertEquals("test", e.get("environment"));
            assertEquals(Map.of("id", "42", "email", "ana@example.com"), e.get("user"));
            assertEquals(Map.of("region", "ap-southeast-1"), e.get("tags"));
            Map<String, Object> ex = (Map<String, Object>) e.get("exception");
            assertEquals("java.lang.RuntimeException", ex.get("type"));
            assertEquals("checkout failed", ex.get("value"));
            assertEquals(true, ex.get("handled"));
            List<Map<String, Object>> frames = (List<Map<String, Object>>) ((Map<String, Object>) ex.get("stacktrace")).get("frames");
            Map<String, Object> last = frames.get(frames.size() - 1);
            assertEquals("NuggetsTest.java", last.get("filename"));
            assertEquals("exceptionPayloadIsBlipitShaped", last.get("function"));
            assertEquals(true, last.get("in_app"));
            assertTrue(frames.stream().anyMatch(f -> Boolean.FALSE.equals(f.get("in_app")) && String.valueOf(f.get("filename")).startsWith("Method")));
            assertEquals(List.of("java.lang.IllegalStateException: db down"), ((Map<String, Object>) e.get("extra")).get("caused_by"));
            List<Map<String, Object>> crumbs = (List<Map<String, Object>>) e.get("breadcrumbs");
            assertEquals("custom", crumbs.get(0).get("category"));
            assertEquals(Map.of("category", "ui"), crumbs.get(0).get("data"));
            assertEquals(Map.of("name", "blipit-java", "version", "0.2.0"), e.get("sdk"));
            n.close();
        }
    }

    @Test
    void breadcrumbsKeepTheLatestHundred() {
        Nuggets n = new Nuggets(config("http://127.0.0.1:9", null));
        for (int i = 0; i < 150; i++) n.addBreadcrumb(i % 2 == 0 ? "navigation" : "http", "step " + i, Level.DEBUG, null);
        List<Map<String, Object>> crumbs = n.breadcrumbs();
        assertEquals(100, crumbs.size());
        assertEquals("step 50", crumbs.get(0).get("message"));
        assertEquals("step 149", crumbs.get(99).get("message"));
        assertEquals("debug", crumbs.get(0).get("level"));
        n.close();
    }

    @Test
    void offlineQueueSurvivesARestart() throws Exception {
        Nuggets first = new Nuggets(config("http://127.0.0.1:9", dir));
        first.outbox.persist("/api/v1/events", first.event(Level.INFO));
        first.crash(new IllegalStateException("boom"), "uncaught");
        first.close();
        File outbox = new File(dir, "outbox");
        assertEquals(2, outbox.listFiles((d, name) -> name.endsWith(".json")).length);
        try (Ingest ingest = new Ingest()) {
            Nuggets second = new Nuggets(config(ingest.url(), dir));
            assertTrue(second.flush(5000));
            assertEquals(2, ingest.hits.size());
            assertEquals("fatal", ingest.hits.get(1).json().get("level"));
            assertBlipitShape(ingest.hits.get(1).json());
            assertEquals(0, outbox.listFiles((d, name) -> name.endsWith(".json")).length);
            second.close();
        }
    }

    @Test
    void queueDropsTheOldestBeyondItsCap() {
        Config c = config("http://127.0.0.1:9", dir);
        c.maxQueued = 5;
        Nuggets n = new Nuggets(c);
        for (int i = 0; i < 12; i++) n.outbox.persist("/api/v1/events", Map.of("n", i));
        assertEquals(5, n.outbox.size());
        n.close();
    }

    @Test
    void rateLimitPausesThenResumes() throws Exception {
        try (Ingest ingest = new Ingest()) {
            ingest.script.add(new Ingest.Answer(429, "{\"error\":\"slow down\",\"retry_after\":1}", "1"));
            Nuggets n = new Nuggets(config(ingest.url(), dir));
            long before = System.currentTimeMillis();
            n.captureMessage("one", Level.INFO);
            ingest.await(1, 5000);
            Thread.sleep(100);
            assertTrue(n.outbox.pausedUntil() >= before + 1000);
            assertEquals(1, n.outbox.size());
            n.captureMessage("two", Level.INFO);
            Thread.sleep(200);
            assertEquals(1, ingest.hits.size());
            ingest.await(3, 5000);
            assertEquals(3, ingest.hits.size());
            assertTrue(System.currentTimeMillis() - before >= 1000);
            Thread.sleep(100);
            assertEquals(0, n.outbox.size());
            n.close();
        }
    }

    @Test
    void retryAfterIsClamped() {
        assertEquals(60_000, Outbox.retryAfterMs(null));
        assertEquals(60_000, Outbox.retryAfterMs("soon"));
        assertEquals(1_000, Outbox.retryAfterMs("0"));
        assertEquals(3_600_000, Outbox.retryAfterMs("999999"));
    }

    @Test
    void serverErrorsKeepTheEventAndRejectionsDropIt() throws Exception {
        try (Ingest ingest = new Ingest()) {
            ingest.script.add(new Ingest.Answer(503, "{}", null));
            Nuggets n = new Nuggets(config(ingest.url(), dir));
            n.captureMessage("kept", Level.INFO);
            ingest.await(1, 5000);
            Thread.sleep(100);
            assertEquals(1, n.outbox.size());
            ingest.await(2, 5000);
            Thread.sleep(100);
            assertEquals(0, n.outbox.size());
            ingest.script.add(new Ingest.Answer(400, "{\"error\":\"event failed schema validation\"}", null));
            n.captureMessage("rejected", Level.INFO);
            ingest.await(3, 5000);
            Thread.sleep(100);
            assertEquals(0, n.outbox.size());
            n.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionsEndAsCrashedAbnormalOrExited() throws Exception {
        try (Ingest ingest = new Ingest()) {
            Nuggets n = new Nuggets(config(ingest.url(), dir));
            n.startSession("install-1");
            n.captureMessage("bad", Level.ERROR);
            n.endSession("exited", true);
            n.startSession("install-1");
            assertTrue(n.flush(5000));
            n.close();
            Nuggets next = new Nuggets(config(ingest.url(), dir));
            next.recoverSession("abnormal");
            assertTrue(next.flush(5000));
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (Ingest.Hit h : ingest.hits) {
                if (h.path().equals("/api/v1/sessions")) sessions.addAll((List<Map<String, Object>>) h.json().get("sessions"));
            }
            assertEquals(2, sessions.size());
            assertEquals("exited", sessions.get(0).get("status"));
            assertEquals(1L, sessions.get(0).get("errors"));
            assertEquals("install-1", sessions.get(0).get("did"));
            assertEquals(Map.of("release", "app@1.0.0", "environment", "test"), sessions.get(0).get("attrs"));
            assertTrue(Pattern.matches("^[0-9a-zA-Z-]{8,64}$", (String) sessions.get(0).get("sid")));
            assertEquals("abnormal", sessions.get(1).get("status"));
            assertFalse(new File(dir, "session.json").exists());
            next.close();
        }
    }

    @Test
    void hookCanTagEvents() throws Exception {
        try (Ingest ingest = new Ingest()) {
            Config c = config(ingest.url(), null);
            c.hook = (event, error) -> event.put("fingerprint", List.of(error ? "error" : "info"));
            Nuggets n = new Nuggets(c);
            n.captureMessage("x", Level.INFO);
            assertTrue(n.flush(5000));
            assertEquals(List.of("info"), ingest.hits.get(0).json().get("fingerprint"));
            n.close();
        }
    }

    @Test
    void framesKeepTheInnermostHundredTwentyEight() {
        StackTraceElement[] stack = new StackTraceElement[300];
        for (int i = 0; i < stack.length; i++) stack[i] = new StackTraceElement("com.example.Deep", "f" + i, "Deep.kt", i + 1);
        List<Map<String, Object>> frames = Nuggets.frames(stack, new Config().notInApp);
        assertEquals(128, frames.size());
        assertEquals("f0", frames.get(127).get("function"));
        assertEquals("f127", frames.get(0).get("function"));
        assertNull(Nuggets.frames(null, null).isEmpty() ? null : "x");
    }

    @Test
    void jsonRoundTrips() {
        Object parsed = Json.parse(Json.encode(Map.of("a", List.of(1, 2.5, "x\n\"y\"\u0001", true), "b", Map.of())));
        assertEquals(Map.of("a", List.of(1L, 2.5, "x\n\"y\"\u0001", true), "b", Map.of()), parsed);
    }

    @Test
    void outboxFilesAreWrittenWhole() throws Exception {
        Nuggets n = new Nuggets(config("http://127.0.0.1:9", dir));
        n.outbox.persist("/api/v1/events", n.event(Level.INFO));
        File[] files = new File(dir, "outbox").listFiles();
        assertEquals(1, files.length);
        String text = Files.readString(files[0].toPath());
        assertTrue(text.startsWith("/api/v1/events\n{"));
        n.close();
    }
}
