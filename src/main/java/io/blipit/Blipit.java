package io.blipit;

import io.blipit.nuggets.Config;
import io.blipit.nuggets.Nuggets;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Blipit {
    public static final String DEFAULT_ENDPOINT = "https://in.blipit.io";
    public static final String VERSION = "0.2.0";
    public static final String SDK_NAME = "blipit-java";
    public static final String PACKAGE_NAME = "maven:io.blipit:blipit";
    public static final String PUBLIC_KEY_WARNING =
            "[blipit] login attempts need the project's secret key (blipit_sk_...). This SDK was started with the public key, so captureSecurity sends nothing. Use the secret key on the server.";

    private static volatile Nuggets client;
    private static volatile boolean publicKey;
    private static final AtomicBoolean publicKeyWarned = new AtomicBoolean();
    private static Thread shutdownHook;

    private Blipit() {}

    public static synchronized void init(BlipitOptions o) {
        if (o.key == null || o.key.isEmpty()) {
            throw new IllegalArgumentException("Blipit.init needs the project's public key");
        }
        if (o.project == null || o.project.isEmpty()) {
            throw new IllegalArgumentException("Blipit.init needs the project id");
        }
        close();
        publicKey = o.key.startsWith("blipit_pk_");
        Config c = new Config();
        c.key = o.key;
        c.endpoint = o.endpoint;
        c.release = o.release;
        c.environment = o.environment;
        c.sdkName = SDK_NAME;
        c.sdkVersion = VERSION;
        c.platform = "java";
        c.dir = o.cacheDir == null ? defaultDir(o.project) : o.cacheDir;
        c.contexts.put("os", pair(System.getProperty("os.name"), System.getProperty("os.version")));
        c.contexts.put("runtime", pair("java", System.getProperty("java.version")));
        Nuggets n;
        try {
            n = new Nuggets(c);
        } catch (IllegalStateException e) {
            c.dir = null;
            n = new Nuggets(c);
        }
        client = n;
        n.installCrashHandler();
        n.outbox.kick();
        final Nuggets hooked = n;
        shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                hooked.flush(2000);
            }
        }, "blipit-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
        }
    }

    static File defaultDir(String project) {
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp == null ? null : new File(tmp, "blipit-" + project.replaceAll("[^A-Za-z0-9_-]", ""));
    }

    private static Map<String, Object> pair(String name, String version) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (name != null) m.put("name", name);
        if (version != null) m.put("version", version);
        return m;
    }

    static Nuggets client() {
        return client;
    }

    public static String captureException(Throwable throwable) {
        Nuggets n = client;
        return n == null ? null : n.captureException(throwable, true, Level.ERROR);
    }

    public static String captureMessage(String message) {
        return captureMessage(message, Level.INFO);
    }

    public static String captureMessage(String message, Level level) {
        Nuggets n = client;
        return n == null ? null : n.captureMessage(message, level);
    }

    public static void setUser(String id, String email) {
        setUser(id, email, null);
    }

    public static void setUser(String id, String email, String username) {
        Nuggets n = client;
        if (n != null) n.setUser(id, email, username);
    }

    public static void setTag(String key, String value) {
        Nuggets n = client;
        if (n != null) n.setTag(key, value);
    }

    public static void setExtra(String key, Object value) {
        Nuggets n = client;
        if (n != null) n.setExtra(key, value);
    }

    public static void setContext(String name, Map<String, Object> values) {
        setExtra(name, values);
    }

    public static void addBreadcrumb(String message) {
        addBreadcrumb(message, null, Level.INFO);
    }

    public static void addBreadcrumb(String message, String category) {
        addBreadcrumb(message, category, Level.INFO);
    }

    public static void addBreadcrumb(String message, String category, Level level) {
        Nuggets n = client;
        if (n != null) n.addBreadcrumb(category, message, level, null);
    }

    public static void captureSecurity(String kind, String actor) {
        captureSecurity(kind, actor, null, null, null, null, null);
    }

    public static void captureSecurity(
            String kind,
            String actor,
            String outcome,
            String actorId,
            String ip,
            String userAgent,
            String target) {
        if (publicKey) {
            if (publicKeyWarned.compareAndSet(false, true)) {
                System.err.println(PUBLIC_KEY_WARNING);
            }
            return;
        }
        Nuggets n = client;
        if (n == null || kind == null || actor == null || actor.trim().isEmpty()) return;
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("kind", kind);
        context.put("actor", cut(actor.trim(), 256));
        put(context, "outcome", outcome, 64);
        put(context, "actor_id", actorId, 128);
        put(context, "ip", ip, 45);
        put(context, "user_agent", userAgent, 512);
        put(context, "target", target, 256);
        Level level = "login_failed".equals(kind) || "login_blocked".equals(kind) || "privilege_granted".equals(kind)
                ? Level.WARNING
                : Level.INFO;
        n.captureSecurity(context, kind + " for " + actor, level);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void put(Map<String, Object> context, String key, String value, int max) {
        if (value != null && !value.isEmpty()) {
            context.put(key, cut(value, max));
        }
    }

    public static boolean flush(long timeoutMillis) {
        Nuggets n = client;
        return n == null || n.flush(timeoutMillis);
    }

    public static boolean flush() {
        return flush(2000);
    }

    public static synchronized void close() {
        Nuggets n = client;
        client = null;
        if (n == null) return;
        n.flush(2000);
        n.close();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            shutdownHook = null;
        }
    }
}
