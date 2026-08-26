package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The population time series: raw samples, per-scope breakdowns, and their rollups. */
public final class PopulationDao {

    /** Scope values for {@code js_population_breakdown} and {@code js_population_rollup}. */
    public static final String SCOPE_PROXY = "proxy";
    public static final String SCOPE_SERVER = "server";
    public static final String SCOPE_VERSION = "version";
    public static final String SCOPE_COUNTRY = "country";

    private final Database database;
    private final WriteQueue writes;

    public PopulationDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    /**
     * Stores one sample of the proxy-wide count.
     *
     * <p>{@code INSERT OR REPLACE} rather than a plain insert: the sampler is driven by a
     * scheduler that can fire twice inside the same millisecond after the process is paused, and
     * a duplicate primary key would otherwise abort the whole batch it landed in.
     */
    public void sample(long at, int total) {
        writes.execute("INSERT OR REPLACE INTO js_population (at, total) VALUES (?, ?)", at, total);
    }

    /** Stores one scope's breakdown for a sample instant. */
    public void breakdown(long at, String scope, Map<String, Integer> counts) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            writes.execute("""
                    INSERT OR REPLACE INTO js_population_breakdown (at, scope, key, count)
                    VALUES (?, ?, ?, ?)
                    """, at, scope, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Folds raw samples into fixed-width buckets.
     *
     * <p>Done in SQL rather than by reading rows into the JVM: a day of one-second samples is
     * 86 400 rows per scope, and shipping those across the JDBC boundary every minute would cost
     * far more than the aggregate itself.
     */
    public CompletableFuture<Integer> rollup(long widthMillis, long fromInclusive,
                                             long toExclusive) {
        return writes.submit(connection -> {
            int rows = 0;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO js_population_rollup (width, bucket, scope, key, samples, total,
                            minimum, maximum)
                    SELECT ?, (at / ?) * ?, 'proxy', '', COUNT(*), SUM(total), MIN(total), MAX(total)
                      FROM js_population
                     WHERE at >= ? AND at < ?
                     GROUP BY (at / ?)
                    ON CONFLICT(width, scope, key, bucket) DO UPDATE SET
                        samples = excluded.samples,
                        total   = excluded.total,
                        minimum = excluded.minimum,
                        maximum = excluded.maximum
                    """)) {
                Database.bind(statement, widthMillis, widthMillis, widthMillis,
                        fromInclusive, toExclusive, widthMillis);
                rows += statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO js_population_rollup (width, bucket, scope, key, samples, total,
                            minimum, maximum)
                    SELECT ?, (at / ?) * ?, scope, key, COUNT(*), SUM(count), MIN(count), MAX(count)
                      FROM js_population_breakdown
                     WHERE at >= ? AND at < ?
                     GROUP BY scope, key, (at / ?)
                    ON CONFLICT(width, scope, key, bucket) DO UPDATE SET
                        samples = excluded.samples,
                        total   = excluded.total,
                        minimum = excluded.minimum,
                        maximum = excluded.maximum
                    """)) {
                Database.bind(statement, widthMillis, widthMillis, widthMillis,
                        fromInclusive, toExclusive, widthMillis);
                rows += statement.executeUpdate();
            }
            return rows;
        });
    }

    /** Raw samples in a window, newest last. */
    public CompletableFuture<List<Records.PopulationSample>> samples(long from, long to,
                                                                     int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT at, total FROM js_population WHERE at >= ? AND at <= ? "
                            + "ORDER BY at DESC LIMIT ?")) {
                Database.bind(statement, from, to, limit);
                List<Records.PopulationSample> rows = Database.list(statement, result ->
                        new Records.PopulationSample(result.getLong("at"), result.getInt("total")));
                java.util.Collections.reverse(rows);
                return rows;
            }
        });
    }

    /** Rolled-up buckets in a window. */
    public CompletableFuture<List<Records.PopulationBucket>> buckets(long widthMillis, String scope,
                                                                     String key, long from, long to,
                                                                     int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT bucket, width, scope, key, samples, total, minimum, maximum
                      FROM js_population_rollup
                     WHERE width = ? AND scope = ? AND key = ? AND bucket >= ? AND bucket <= ?
                     ORDER BY bucket DESC LIMIT ?
                    """)) {
                Database.bind(statement, widthMillis, scope, key, from, to, limit);
                List<Records.PopulationBucket> rows = Database.list(statement, result ->
                        new Records.PopulationBucket(result.getLong("bucket"),
                                result.getLong("width"), result.getString("scope"),
                                result.getString("key"), result.getInt("samples"),
                                result.getLong("total"), result.getInt("minimum"),
                                result.getInt("maximum")));
                java.util.Collections.reverse(rows);
                return rows;
            }
        });
    }

    /** The highest concurrent count ever sampled, and when it happened. */
    public CompletableFuture<Records.PopulationSample> peak() {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT at, total FROM js_population ORDER BY total DESC, at ASC LIMIT 1")) {
                List<Records.PopulationSample> rows = Database.list(statement, result ->
                        new Records.PopulationSample(result.getLong("at"), result.getInt("total")));
                return rows.isEmpty() ? new Records.PopulationSample(0L, 0) : rows.get(0);
            }
        });
    }

    /** Average, minimum and peak over a window, computed in SQL. */
    public CompletableFuture<Summary> summary(long from, long to) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) AS samples, COALESCE(AVG(total), 0) AS mean,
                           COALESCE(MIN(total), 0) AS low, COALESCE(MAX(total), 0) AS high
                      FROM js_population WHERE at >= ? AND at <= ?
                    """)) {
                Database.bind(statement, from, to);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return new Summary(0, 0, 0, 0);
                    }
                    return new Summary(rows.getInt("samples"), rows.getDouble("mean"),
                            rows.getInt("low"), rows.getInt("high"));
                }
            }
        });
    }

    /** The most recent per-scope breakdown, for the live view. */
    public CompletableFuture<List<Records.Tally>> latestBreakdown(String scope) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT key AS k, count AS c FROM js_population_breakdown
                     WHERE scope = ? AND at = (SELECT MAX(at) FROM js_population_breakdown
                                                WHERE scope = ?)
                     ORDER BY c DESC
                    """)) {
                Database.bind(statement, scope, scope);
                return Database.list(statement, rows -> new Records.Tally(
                        rows.getString("k"), rows.getLong("c"), rows.getString("k")));
            }
        });
    }

    /** Aggregate statistics for a window of raw samples. */
    public record Summary(int samples, double average, int minimum, int maximum) {
    }
}
