package io.blipit.nuggets;

import io.blipit.Level;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class Nuggets {
    public static final int MAX_BREADCRUMBS = 100;
    public static final int MAX_FRAMES = 128;
    static final Pattern TAG_KEY = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");
    static final Set<String> CATEGORIES = new HashSet<String>(Arrays.asList("ui.click", "ui.input", "navigation", "http", "console", "query", "custom"));

    public final Config config;
    public final Outbox outbox;
    private final File sessionFile;
    private final AtomicBoolean crashing = new AtomicBoolean();
    private Map<String, Object> user;
    private final Map<String, Object> tags = new LinkedHashMap<String, Object>();
    private final Map<String, Object> extra = new LinkedHashMap<String, Object>();
    private final ArrayDeque<Map<String, Object>> crumbs = new ArrayDeque<Map<String, Object>>();
    private Map<String, Object> session;

    public Nuggets(Config config) {
        if (config.key == null || config.key.isEmpty()) throw new IllegalArgumentException("blipit-nuggets needs the project key");
        this.config = config;
        this.outbox = new Outbox(config);
        this.sessionFile = config.dir == null ? null : new File(config.dir, "session.json");
    }

    public static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public synchronized void setUser(String id, String email, String username) {
        Map<String, Object> u = new LinkedHashMap<String, Object>();
        if (id != null && !id.isEmpty()) u.put("id", cut(id, 128));
        if (email != null && !email.isEmpty()) u.put("email", cut(email, 256));
        if (username != null && !username.isEmpty()) u.put("username", cut(username, 128));
        user = u.isEmpty() ? null : u;
    }

    public synchronized boolean setTag(String key, String value) {
        if (key == null || !TAG_KEY.matcher(key).matches()) return false;
        if (value == null) {
            tags.remove(key);
            return true;
        }
        if (!tags.containsKey(key) && tags.size() >= 32) return false;
        tags.put(key, cut(value, 256));
        return true;
    }

    public synchronized void setExtra(String key, Object value) {
        if (key == null) return;
        if (value == null) extra.remove(key);
        else extra.put(cut(key, 128), value);
    }

    public synchronized void addBreadcrumb(String category, String message, Level level, Map<String, Object> data) {
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("timestamp", iso(System.currentTimeMillis()));
        Map<String, Object> d = data == null ? null : new LinkedHashMap<String, Object>(data);
        if (category == null || CATEGORIES.contains(category)) {
            b.put("category", category == null ? "custom" : category);
        } else {
            b.put("category", "custom");
            if (d == null) d = new LinkedHashMap<String, Object>();
            d.put("category", cut(category, 64));
        }
        if (message != null) b.put("message", cut(message, 1024));
        Level l = level == null ? Level.INFO : level;
        b.put("level", l == Level.FATAL ? "error" : l.wire);
        if (d != null && !d.isEmpty()) b.put("data", d);
        crumbs.addLast(b);
        while (crumbs.size() > MAX_BREADCRUMBS) crumbs.removeFirst();
    }

    public synchronized List<Map<String, Object>> breadcrumbs() {
        return new ArrayList<Map<String, Object>>(crumbs);
    }

    public synchronized Map<String, Object> event(Level level) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("event_id", id());
        e.put("timestamp", iso(System.currentTimeMillis()));
        e.put("platform", config.platform);
        e.put("level", (level == null ? Level.ERROR : level).wire);
        if (config.release != null && !config.release.isEmpty()) e.put("release", cut(config.release, 256));
        e.put("environment", cut(config.environment == null || config.environment.isEmpty() ? "production" : config.environment, 64));
        if (user != null) e.put("user", new LinkedHashMap<String, Object>(user));
        if (!tags.isEmpty()) e.put("tags", new LinkedHashMap<String, Object>(tags));
        if (!extra.isEmpty()) e.put("extra", new LinkedHashMap<String, Object>(extra));
        if (!crumbs.isEmpty()) e.put("breadcrumbs", new ArrayList<Map<String, Object>>(crumbs));
        if (!config.contexts.isEmpty()) e.put("contexts", new LinkedHashMap<String, Object>(config.contexts));
        Map<String, Object> sdk = new LinkedHashMap<String, Object>();
        sdk.put("name", config.sdkName);
        sdk.put("version", config.sdkVersion);
        e.put("sdk", sdk);
        return e;
    }

    public String captureException(Throwable t, boolean handled, Level level) {
        if (t == null) return null;
        Map<String, Object> e = event(level == null ? Level.ERROR : level);
        e.put("exception", exception(t, handled, config.notInApp));
        causes(e, t);
        return send(e, true);
    }

    public String captureMessage(String message, Level level) {
        if (message == null) return null;
        Map<String, Object> e = event(level == null ? Level.INFO : level);
        e.put("message", cut(message, 8192));
        return send(e, level == Level.ERROR || level == Level.FATAL);
    }

    public String captureSecurity(Map<String, Object> security, String message, Level level) {
        Map<String, Object> e = event(level);
        e.put("message", cut(message, 8192));
        e.put("security", security);
        return send(e, false);
    }

    public String send(Map<String, Object> event, boolean error) {
        if (config.hook != null) {
            try {
                config.hook.onEvent(event, error);
            } catch (RuntimeException ignored) {
            }
        }
        if (error) countError();
        outbox.add("/api/v1/events", event);
        return (String) event.get("event_id");
    }

    public String crash(Throwable t, String mechanism) {
        Map<String, Object> e = event(Level.FATAL);
        e.put("exception", exception(t, false, config.notInApp));
        causes(e, t);
        if (mechanism != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tagMap = (Map<String, Object>) e.get("tags");
            if (tagMap == null) {
                tagMap = new LinkedHashMap<String, Object>();
                e.put("tags", tagMap);
            }
            if (tagMap.size() < 32 || tagMap.containsKey("mechanism")) tagMap.put("mechanism", mechanism);
        }
        if (config.hook != null) {
            try {
                config.hook.onEvent(e, true);
            } catch (RuntimeException ignored) {
            }
        }
        outbox.persist("/api/v1/events", e);
        endSession("crashed", false);
        if (config.sendCrashNow) outbox.flush(2000);
        return (String) e.get("event_id");
    }

    private static volatile Nuggets current;
    private static boolean installed;

    public void installCrashHandler() {
        current = this;
        synchronized (Nuggets.class) {
            if (installed) return;
            installed = true;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                Nuggets n = current;
                if (n != null && n.crashing.compareAndSet(false, true)) {
                    try {
                        n.crash(error, "uncaught");
                    } catch (Throwable ignored) {
                    }
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                    error.printStackTrace(System.err);
                }
            }
        });
    }

    private static void causes(Map<String, Object> e, Throwable t) {
        List<String> chain = new ArrayList<String>();
        Set<Throwable> seen = new HashSet<Throwable>();
        seen.add(t);
        Throwable c = t.getCause();
        while (c != null && seen.add(c) && chain.size() < 10) {
            chain.add(cut(c.getClass().getName() + ": " + c.getMessage(), 1024));
            c = c.getCause();
        }
        if (chain.isEmpty()) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> ex = (Map<String, Object>) e.get("extra");
        if (ex == null) {
            ex = new LinkedHashMap<String, Object>();
            e.put("extra", ex);
        }
        ex.put("caused_by", chain);
    }

    public static Map<String, Object> exception(Throwable t, boolean handled, String[] notInApp) {
        Map<String, Object> ex = new LinkedHashMap<String, Object>();
        ex.put("type", cut(t.getClass().getName(), 256));
        String msg = t.getMessage();
        ex.put("value", cut(msg == null ? "" : msg, 4096));
        List<Map<String, Object>> frames = frames(t.getStackTrace(), notInApp);
        if (!frames.isEmpty()) {
            Map<String, Object> st = new LinkedHashMap<String, Object>();
            st.put("frames", frames);
            ex.put("stacktrace", st);
        }
        ex.put("handled", handled);
        return ex;
    }

    public static List<Map<String, Object>> frames(StackTraceElement[] stack, String[] notInApp) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (stack == null) return out;
        for (int i = Math.min(stack.length, MAX_FRAMES) - 1; i >= 0; i--) {
            StackTraceElement s = stack[i];
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            String file = s.getFileName();
            f.put("filename", cut(file == null || file.isEmpty() ? s.getClassName() : file, 1024));
            f.put("function", cut(s.getMethodName(), 512));
            if (s.getLineNumber() >= 0) f.put("lineno", s.getLineNumber());
            f.put("in_app", inApp(s.getClassName(), notInApp));
            out.add(f);
        }
        return out;
    }

    static boolean inApp(String className, String[] notInApp) {
        if (notInApp == null) return true;
        for (String p : notInApp) {
            if (className.startsWith(p)) return false;
        }
        return true;
    }

    public synchronized void startSession(String distinctId) {
        if (config.release == null || config.release.isEmpty()) return;
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("sid", UUID.randomUUID().toString());
        if (distinctId != null) s.put("did", distinctId);
        s.put("started", iso(System.currentTimeMillis()));
        s.put("status", "ok");
        s.put("errors", 0);
        Map<String, Object> attrs = new LinkedHashMap<String, Object>();
        attrs.put("release", cut(config.release, 256));
        attrs.put("environment", cut(config.environment == null || config.environment.isEmpty() ? "production" : config.environment, 64));
        s.put("attrs", attrs);
        session = s;
        saveSession();
    }

    public synchronized boolean hasSession() {
        return session != null;
    }

    public synchronized void endSession(String status, boolean send) {
        Map<String, Object> s = session;
        if (s == null) return;
        session = null;
        s.remove("paused");
        s.put("status", status);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        List<Object> list = new ArrayList<Object>();
        list.add(s);
        body.put("sessions", list);
        if (send) outbox.add("/api/v1/sessions", body);
        else outbox.persist("/api/v1/sessions", body);
        if (sessionFile != null) sessionFile.delete();
    }

    public synchronized void recoverSession(String status) {
        if (sessionFile == null || !sessionFile.isFile()) return;
        try {
            Object parsed = Json.parse(new String(Outbox.read(sessionFile), Outbox.UTF8));
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> old = (Map<String, Object>) parsed;
                Map<String, Object> keep = session;
                boolean paused = Boolean.TRUE.equals(old.remove("paused"));
                session = old;
                endSession("crashed".equals(status) || !paused ? status : "exited", false);
                session = keep;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        sessionFile.delete();
    }

    public synchronized void markSession(String key, Object value) {
        if (session == null) return;
        session.put(key, value);
        saveSession();
    }

    private synchronized void countError() {
        if (session == null) return;
        Object n = session.get("errors");
        session.put("errors", (n instanceof Number ? ((Number) n).intValue() : 0) + 1);
        saveSession();
    }

    private void saveSession() {
        if (sessionFile == null || session == null) return;
        File tmp = new File(sessionFile.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(Json.encode(session).getBytes(Outbox.UTF8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(sessionFile)) tmp.delete();
        } catch (IOException e) {
            tmp.delete();
        }
    }

    public boolean flush(long timeoutMillis) {
        return outbox.flush(timeoutMillis);
    }

    public void close() {
        if (current == this) current = null;
        outbox.close();
    }
}
