package io.blipit;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.User;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Blipit {
    public static final String DEFAULT_ENDPOINT = "https://in.blipit.io";

    private Blipit() {}

    public static String dsn(String key, String project, String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = DEFAULT_ENDPOINT;
        }
        String scheme = endpoint.startsWith("http://") ? "http" : "https";
        String host = endpoint;
        int i = host.indexOf("://");
        if (i >= 0) {
            host = host.substring(i + 3);
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return scheme + "://" + key + "@" + host + "/" + project;
    }

    public static void init(BlipitOptions o) {
        if (o.key == null || o.key.isEmpty()) {
            throw new IllegalArgumentException("Blipit.init needs the project's public key");
        }
        if (o.project == null || o.project.isEmpty()) {
            throw new IllegalArgumentException("Blipit.init needs the project id");
        }
        Sentry.init(options -> {
            options.setDsn(dsn(o.key, o.project, o.endpoint));
            options.setEnvironment(o.environment);
            options.setRelease(o.release);
            options.setTracesSampleRate(o.tracesSampleRate);
            options.setSendDefaultPii(false);
        });
    }

    public static void captureException(Throwable throwable) {
        Sentry.captureException(throwable);
    }

    public static void captureMessage(String message) {
        Sentry.captureMessage(message);
    }

    public static void captureMessage(String message, SentryLevel level) {
        Sentry.captureMessage(message, level);
    }

    public static void setUser(User user) {
        Sentry.setUser(user);
    }

    public static void setUser(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        Sentry.setUser(user);
    }

    public static void setTag(String key, String value) {
        Sentry.setTag(key, value);
    }

    public static void addBreadcrumb(Breadcrumb breadcrumb) {
        Sentry.addBreadcrumb(breadcrumb);
    }

    public static void addBreadcrumb(String message) {
        Sentry.addBreadcrumb(message);
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
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("kind", kind);
        context.put("actor", actor);
        put(context, "outcome", outcome);
        put(context, "actor_id", actorId);
        put(context, "ip", ip);
        put(context, "user_agent", userAgent);
        put(context, "target", target);
        SentryLevel level = "login_failed".equals(kind) || "login_blocked".equals(kind)
                ? SentryLevel.WARNING
                : SentryLevel.INFO;
        Sentry.withScope(scope -> {
            scope.setContexts("security", context);
            scope.setTag("security.kind", kind);
            Sentry.captureMessage(kind + " for " + actor, level);
        });
    }

    private static void put(Map<String, Object> context, String key, String value) {
        if (value != null && !value.isEmpty()) {
            context.put(key, value);
        }
    }

    public static void flush(long timeoutMillis) {
        Sentry.flush(timeoutMillis);
    }

    public static void flush() {
        flush(2000);
    }

    public static void close() {
        Sentry.close();
    }
}
