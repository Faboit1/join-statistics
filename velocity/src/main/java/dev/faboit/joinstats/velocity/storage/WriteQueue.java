package dev.faboit.joinstats.velocity.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * The single writer.
 *
 * <p>Every mutation the plugin performs is appended here and applied on one background thread,
 * which keeps SQLite's one-writer rule from becoming visible as lock contention on the proxy's
 * event loop. Nothing the tracking code calls blocks: it enqueues and returns.
 *
 * <p>Runs of consecutive statements sharing the same SQL are collapsed into a JDBC batch and the
 * whole drained chunk is committed as one transaction. On a busy proxy that turns a per-second
 * population sample plus a burst of joins into a couple of commits rather than dozens.
 */
public final class WriteQueue implements AutoCloseable {

    private final Database database;
    private final Logger logger;
    private final int batchSize;
    private final long flushIntervalMillis;
    private final int maxQueued;

    private final LinkedBlockingQueue<Op> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong commits = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastDropWarning;

    private final Thread worker;

    public WriteQueue(Database database, Logger logger, int batchSize, long flushIntervalMillis,
                      int maxQueued) {
        this.database = database;
        this.logger = logger;
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMillis = Math.max(50L, flushIntervalMillis);
        this.maxQueued = maxQueued;

        this.worker = new Thread(this::run, "joinstats-write");
        this.worker.setDaemon(false);
        this.worker.setUncaughtExceptionHandler((thread, error) ->
                logger.error("The statistics writer died; data will stop being recorded.", error));
        this.worker.start();
    }

    /** Queues a statement. Returns immediately; failures are logged, not thrown at the caller. */
    public void execute(String sql, Object... params) {
        if (!running.get()) {
            return;
        }
        if (maxQueued > 0 && queue.size() >= maxQueued) {
            long total = dropped.incrementAndGet();
            long now = System.currentTimeMillis();
            // Throttled, because whatever is causing the backlog will also cause the log to
            // be the second thing to fall over.
            if (now - lastDropWarning > 30_000L) {
                lastDropWarning = now;
                logger.warn("Write queue is full ({} pending); {} statements dropped so far. "
                        + "The disk is likely too slow for the configured sampling rate.",
                        queue.size(), total);
            }
            return;
        }
        queue.add(new Statement(sql, params));
    }

    /**
     * Runs arbitrary work on the writer thread, inside its own transaction.
     *
     * <p>Use this when the write needs a value back — a generated session id, say — or when a
     * group of statements has to be atomic with respect to a read taken in the middle. Anything
     * already queued is applied first, so the callback sees a consistent view.
     */
    public <T> CompletableFuture<T> submit(ConnectionTask<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!running.get()) {
            future.completeExceptionally(new IllegalStateException("The writer is shut down"));
            return future;
        }
        queue.add(new Task<>(work, future));
        return future;
    }

    /** Completes once everything queued before the call has been committed. */
    public CompletableFuture<Void> flush() {
        return submit(connection -> null);
    }

    private void run() {
        List<Op> chunk = new ArrayList<>(batchSize);
        while (running.get() || !queue.isEmpty()) {
            try {
                Op first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                chunk.clear();
                chunk.add(first);
                queue.drainTo(chunk, batchSize - 1);
                apply(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                logger.error("Unexpected failure in the statistics writer.", e);
            }
        }
        drainRemaining();
    }

    /** After shutdown is signalled, apply whatever is still queued so nothing is lost. */
    private void drainRemaining() {
        List<Op> chunk = new ArrayList<>(batchSize);
        while (!queue.isEmpty()) {
            chunk.clear();
            queue.drainTo(chunk, batchSize);
            if (chunk.isEmpty()) {
                break;
            }
            apply(chunk);
        }
    }

    private void apply(List<Op> chunk) {
        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                PreparedStatement pending = null;
                String pendingSql = null;
                int pendingCount = 0;

                for (Op op : chunk) {
                    if (op instanceof Statement statement) {
                        if (pending != null && !statement.sql.equals(pendingSql)) {
                            executeBatch(pending, pendingSql, pendingCount);
                            pending.close();
                            pending = null;
                            pendingCount = 0;
                        }
                        if (pending == null) {
                            pendingSql = statement.sql;
                            pending = connection.prepareStatement(pendingSql);
                        }
                        Database.bind(pending, statement.params);
                        pending.addBatch();
                        pendingCount++;
                        continue;
                    }

                    // A callback may read rows the queued statements just wrote, so flush first.
                    if (pending != null) {
                        executeBatch(pending, pendingSql, pendingCount);
                        pending.close();
                        pending = null;
                        pendingCount = 0;
                    }
                    runTask(connection, (Task<?>) op);
                }

                if (pending != null) {
                    executeBatch(pending, pendingSql, pendingCount);
                    pending.close();
                }
                connection.commit();
                commits.incrementAndGet();
                applied.addAndGet(chunk.size());
            } catch (SQLException | RuntimeException e) {
                failures.incrementAndGet();
                safeRollback(connection);
                logger.error("Rolled back a batch of {} statistics writes.", chunk.size(), e);
                failPendingTasks(chunk, e);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            failures.incrementAndGet();
            logger.error("Could not obtain a connection to flush {} writes.", chunk.size(), e);
            failPendingTasks(chunk, e);
        }
    }

    private void executeBatch(PreparedStatement statement, String sql, int count)
            throws SQLException {
        if (count == 0) {
            return;
        }
        try {
            statement.executeBatch();
        } catch (SQLException e) {
            throw new SQLException("Batch of " + count + " failed for: " + summarise(sql), e);
        }
    }

    private <T> void runTask(Connection connection, Task<T> task) {
        try {
            T result = task.work.run(connection);
            // Completed after the commit rather than here would be more correct in the abstract,
            // but callers only ever use the value to build further queued writes, which are
            // ordered behind this transaction anyway.
            task.future.complete(result);
        } catch (SQLException | RuntimeException e) {
            task.future.completeExceptionally(e);
            logger.error("A statistics write task failed.", e);
        }
    }

    private void failPendingTasks(List<Op> chunk, Exception cause) {
        for (Op op : chunk) {
            if (op instanceof Task<?> task && !task.future.isDone()) {
                task.future.completeExceptionally(cause);
            }
        }
    }

    private void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.debug("Rollback failed.", e);
        }
    }

    private static String summarise(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 157) + "..." : flat;
    }

    /** Snapshot of writer health, surfaced by {@code /joinstats status} and the metrics endpoint. */
    public Stats stats() {
        return new Stats(queue.size(), applied.get(), commits.get(), dropped.get(), failures.get());
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            // Long enough for a large backlog to land, short enough not to hang a restart.
            worker.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            logger.warn("The statistics writer did not finish within 30s; {} writes were lost.",
                    queue.size());
        }
    }

    /** Work to run on the writer thread with a transactional connection. */
    @FunctionalInterface
    public interface ConnectionTask<T> {
        T run(Connection connection) throws SQLException;
    }

    private sealed interface Op permits Statement, Task {
    }

    private record Statement(String sql, Object[] params) implements Op {
    }

    private record Task<T>(ConnectionTask<T> work, CompletableFuture<T> future) implements Op {
    }

    /** Counters describing how the writer is keeping up. */
    public record Stats(int queued, long applied, long commits, long dropped, long failures) {
    }
}
