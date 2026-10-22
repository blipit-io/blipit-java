package io.blipit.nuggets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Outbox {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final long MAX_PAUSE_MS = 60L * 60 * 1000;
    static final long MAX_BACKOFF_MS = 5L * 60 * 1000;

    public static final class Reply {
        public final int status;
        public final String body;
        public final String retryAfter;

        public Reply(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private final Config config;
    private final File folder;
    private final ArrayDeque<String> memory = new ArrayDeque<String>();
    private final AtomicInteger seq = new AtomicInteger();
    private final ScheduledExecutorService worker;
    private ScheduledFuture<?> retry;
    private volatile long pausedUntil;
    private int failures;

    public Outbox(Config config) {
        this.config = config;
        this.folder = config.dir == null ? null : new File(config.dir, "outbox");
        if (folder != null && !folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IllegalStateException("cannot create " + folder);
        }
        this.worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "blipit-nuggets");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public long pausedUntil() {
        return pausedUntil;
    }

    public synchronized int size() {
        return folder == null ? memory.size() : files().size();
    }

    public void add(String path, Object body) {
        persist(path, body);
        kick();
    }

    public synchronized void persist(String path, Object body) {
        String item = path + "\n" + Json.encode(body);
        if (folder == null) {
            memory.addLast(item);
            while (memory.size() > config.maxQueued) memory.removeFirst();
            return;
        }
        List<File> existing = files();
        for (int i = 0; i <= existing.size() - config.maxQueued; i++) existing.get(i).delete();
        String name = String.format("%013d-%06d", System.currentTimeMillis(), seq.incrementAndGet() % 1000000);
        File tmp = new File(folder, name + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(item.getBytes(UTF8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(new File(folder, name + ".json"))) tmp.delete();
        } catch (IOException e) {
            tmp.delete();
        }
    }

    public void kick() {
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    public boolean flush(long timeoutMillis) {
        try {
            Future<?> f = worker.submit(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
            f.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return size() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void close() {
        worker.shutdownNow();
    }

    private synchronized List<File> files() {
        File[] list = folder.listFiles();
        List<File> out = new ArrayList<File>();
        if (list == null) return out;
        Arrays.sort(list);
        for (File f : list) {
            if (f.getName().endsWith(".json")) out.add(f);
        }
        return out;
    }

    private synchronized Object[] oldest() {
        if (folder == null) {
            String item = memory.peekFirst();
            return item == null ? null : new Object[] {item, null};
        }
        for (File f : files()) {
            try {
                return new Object[] {new String(read(f), UTF8), f};
            } catch (IOException e) {
                f.delete();
            }
        }
        return null;
    }

    private synchronized void remove(Object[] item) {
        if (item[1] == null) memory.remove(item[0]);
        else ((File) item[1]).delete();
    }

    void drain() {
        while (true) {
            long now = System.currentTimeMillis();
            if (now < pausedUntil) {
                schedule(pausedUntil - now);
                return;
            }
            Object[] item = oldest();
            if (item == null) return;
            String text = (String) item[0];
            int nl = text.indexOf('\n');
            if (nl <= 0) {
                remove(item);
                continue;
            }
            Reply reply;
            try {
                reply = post(text.substring(0, nl), text.substring(nl + 1).getBytes(UTF8));
            } catch (IOException e) {
                backoff();
                return;
            }
            if (reply.status == 429) {
                pausedUntil = System.currentTimeMillis() + retryAfterMs(reply.retryAfter);
                schedule(pausedUntil - System.currentTimeMillis());
                return;
            }
            if (reply.status >= 500 || reply.status == 408) {
                backoff();
                return;
            }
            failures = 0;
            remove(item);
        }
    }

    static long retryAfterMs(String header) {
        long seconds = 60;
        if (header != null) {
            try {
                seconds = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.min(Math.max(seconds, 1) * 1000, MAX_PAUSE_MS);
    }

    private void backoff() {
        failures = Math.min(failures + 1, 16);
        schedule(Math.min(1000L << failures, MAX_BACKOFF_MS));
    }

    private synchronized void schedule(long delayMs) {
        if (retry != null && !retry.isDone()) return;
        try {
            retry = worker.schedule(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            }, Math.max(delayMs, 10), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
        }
    }

    public Reply post(String path, byte[] body) throws IOException {
        return send(config, "POST", path, "application/json", body);
    }

    public static Reply send(Config config, String method, String path, String type, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(path.startsWith("http") ? path : config.base() + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setUseCaches(false);
            conn.setRequestProperty("X-Blipit-Key", config.key);
            conn.setRequestProperty("User-Agent", config.sdkName + "/" + config.sdkVersion);
            if (type != null) conn.setRequestProperty("Content-Type", type);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : new String(readAll(in), UTF8);
            return new Reply(status, text, conn.getHeaderField("Retry-After"));
        } finally {
            conn.disconnect();
        }
    }

    static byte[] read(File f) throws IOException {
        return readAll(new FileInputStream(f));
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
