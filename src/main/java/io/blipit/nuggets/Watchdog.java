package io.blipit.nuggets;

public final class Watchdog {
    public interface Target {
        void post(Runnable ping);
    }

    public interface Listener {
        void blocked(long waitedMs);
    }

    private final Target target;
    private final Listener listener;
    private final long timeoutMs;
    private long postedAt = -1;
    private volatile boolean answered;
    private boolean reported;
    private volatile boolean running;

    public Watchdog(Target target, long timeoutMs, Listener listener) {
        this.target = target;
        this.timeoutMs = timeoutMs;
        this.listener = listener;
    }

    public synchronized void step(long now) {
        if (postedAt < 0 || answered) {
            if (answered) reported = false;
            postedAt = now;
            answered = false;
            target.post(new Runnable() {
                @Override
                public void run() {
                    answered = true;
                }
            });
            return;
        }
        long waited = now - postedAt;
        if (!reported && waited >= timeoutMs) {
            reported = true;
            listener.blocked(waited);
        }
    }

    public void start(final long intervalMs) {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    step(System.nanoTime() / 1000000L);
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "blipit-anr");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }
}
