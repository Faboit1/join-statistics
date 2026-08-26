package dev.faboit.joinstats.velocity.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.util.Threads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Owns the SQLite file: the connection pool, the PRAGMAs, and the read executor.
 *
 * <p>SQLite permits exactly one writer at a time. Rather than let that surface as lock
 * contention under load, every mutation is funnelled through {@link WriteQueue}'s single thread
 * and the pool here is used for reads, which WAL mode lets run concurrently with the writer.
 */
public final class Database implements AutoCloseable {

    private final Logger logger;
    private final Path file;
    private final HikariDataSource dataSource;
    private final ExecutorService readExecutor;

    public Database(Path dataDirectory, PluginConfig.Storage settings, Logger logger) throws Exception {
        this.logger = logger;

        Path configured = Path.of(settings.file);
        this.file = configured.isAbsolute() ? configured : dataDirectory.resolve(configured);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // sqlite-jdbc reads PRAGMA values straight out of the URL query string, which applies
        // them to every pooled connection without a per-checkout round trip.
        StringBuilder url = new StringBuilder("jdbc:sqlite:").append(file.toAbsolutePath());
        url.append("?busy_timeout=").append(settings.busy().toMillis());
        url.append("&journal_mode=").append(settings.walMode ? "WAL" : "DELETE");
        url.append("&synchronous=").append(sanitizeSynchronous(settings.synchronousMode));
        // A negative cache_size is interpreted by SQLite as kibibytes rather than pages.
        url.append("&cache_size=").append(-Math.max(1, settings.cacheSizeMb) * 1024);
        url.append("&foreign_keys=false");

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("joinstats-sqlite");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl(url.toString());
        // One spare beyond the reader count for the writer thread's own checkout.
        hikari.setMaximumPoolSize(Math.max(2, settings.readPoolSize + 1));
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(Math.max(2_000L, settings.busy().toMillis()));
        hikari.setLeakDetectionThreshold(TimeUnit.MINUTES.toMillis(2));
        hikari.setAutoCommit(true);
        this.dataSource = new HikariDataSource(hikari);

        this.readExecutor = Executors.newFixedThreadPool(
                Math.max(1, settings.readPoolSize), Threads.factory("read", logger));

        try (Connection connection = dataSource.getConnection()) {
            Schema.apply(connection, logger);
            if (settings.vacuumThreshold > 0) {
                maybeVacuum(connection, settings.vacuumThreshold);
            }
        }
    }

    /** SQLite rejects an unknown synchronous mode outright, so clamp to the three valid ones. */
    private static String sanitizeSynchronous(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OFF", "FULL", "EXTRA", "NORMAL" -> value;
            default -> "NORMAL";
        };
    }

    /**
     * Reclaims space when deletions have left the file mostly holes.
     *
     * <p>Retention pruning frees pages but never shrinks the file, so a busy proxy's database
     * creeps upward forever without this. VACUUM rewrites the whole file, so it only runs at
     * startup and only once the waste is worth the pause.
     */
    private void maybeVacuum(Connection connection, double threshold) {
        try (Statement statement = connection.createStatement()) {
            long freeList = scalar(statement, "PRAGMA freelist_count");
            long pageCount = scalar(statement, "PRAGMA page_count");
            if (pageCount <= 0) {
                return;
            }
            double waste = (double) freeList / (double) pageCount;
            if (waste < threshold) {
                return;
            }
            long started = System.nanoTime();
            statement.execute("VACUUM");
            logger.info("Compacted the database ({}% free pages) in {}ms.",
                    Math.round(waste * 100), (System.nanoTime() - started) / 1_000_000L);
        } catch (SQLException e) {
            logger.warn("Could not compact the database.", e);
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public Path file() {
        return file;
    }

    /** Runs a read off the proxy's threads and completes with its result. */
    public <T> CompletableFuture<T> query(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new StorageException("Query failed", e);
            }
        }, readExecutor);
    }

    /** Runs a read on the calling thread. Only for code already off the event loop. */
    public <T> T queryNow(SqlFunction<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new StorageException("Query failed", e);
        }
    }

    /** Collects the rows of a query into a list, mapping each with {@code mapper}. */
    public static <T> List<T> list(PreparedStatement statement, RowMapper<T> mapper)
            throws SQLException {
        List<T> out = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                out.add(mapper.map(rows));
            }
        }
        return out;
    }

    /** Binds positional parameters, translating the handful of types we actually store. */
    public static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value == null) {
                statement.setObject(index, null);
            } else if (value instanceof String text) {
                statement.setString(index, text);
            } else if (value instanceof Integer number) {
                statement.setInt(index, number);
            } else if (value instanceof Long number) {
                statement.setLong(index, number);
            } else if (value instanceof Double number) {
                statement.setDouble(index, number);
            } else if (value instanceof Float number) {
                statement.setDouble(index, number);
            } else if (value instanceof Boolean flag) {
                statement.setInt(index, flag ? 1 : 0);
            } else if (value instanceof java.util.UUID uuid) {
                statement.setString(index, uuid.toString());
            } else if (value instanceof Enum<?> constant) {
                statement.setString(index, constant.name());
            } else {
                statement.setString(index, value.toString());
            }
        }
    }

    @Override
    public void close() {
        readExecutor.shutdown();
        try {
            if (!readExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                readExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            readExecutor.shutdownNow();
        }
        // A final checkpoint folds the WAL back into the main file, so the database is a single
        // self-contained artifact once the proxy is down — which is what a backup script expects.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("PRAGMA optimize");
        } catch (SQLException e) {
            logger.debug("Final checkpoint failed; the WAL will be replayed on next start.", e);
        }
        dataSource.close();
    }

    /** A unit of read work against a borrowed connection. */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Maps the current row of a result set. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    /** Unchecked wrapper so storage failures can cross {@link CompletableFuture} boundaries. */
    public static final class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
