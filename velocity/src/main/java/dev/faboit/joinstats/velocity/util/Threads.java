package dev.faboit.joinstats.velocity.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/** Names our worker threads so a stuck plugin is obvious in a thread dump. */
public final class Threads {

    private Threads() {
    }

    public static ThreadFactory factory(String name, Logger logger) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            int id = counter.incrementAndGet();
            thread.setName("joinstats-" + name + (id == 1 ? "" : "-" + id));
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) ->
                    logger.error("Uncaught error on {}", t.getName(), error));
            return thread;
        };
    }
}
