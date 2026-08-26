package dev.faboit.joinstats.velocity.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.MaintenanceDao;
import dev.faboit.joinstats.velocity.storage.dao.PopulationDao;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Checks the per-second population series and the rollups that keep it queryable. */
class PopulationTest {

    private static final long MINUTE = 60_000L;

    @TempDir
    Path directory;

    private Database database;
    private WriteQueue writes;
    private PopulationDao population;
    private MaintenanceDao maintenance;

    @BeforeEach
    void setUp() throws Exception {
        PluginConfig.Storage settings = new PluginConfig.Storage();
        settings.file = "population.db";
        var logger = LoggerFactory.getLogger(PopulationTest.class);
        database = new Database(directory, settings, logger);
        writes = new WriteQueue(database, logger, 500, 50L, 0);
        population = new PopulationDao(database, writes);
        maintenance = new MaintenanceDao(database, writes);
    }

    @AfterEach
    void tearDown() {
        writes.close();
        database.close();
    }

    @Test
    void storesEverySampleAndReadsThemBackInOrder() {
        long start = 1_700_000_000_000L;
        for (int i = 0; i < 120; i++) {
            population.sample(start + i * 1000L, 10 + i);
        }
        settle();

        List<Records.PopulationSample> samples =
                population.samples(start, start + 120_000L, 500).join();
        assertEquals(120, samples.size());
        assertEquals(start, samples.get(0).at(), "samples must come back oldest first");
        assertEquals(10, samples.get(0).total());
        assertEquals(129, samples.get(119).total());
    }

    @Test
    void aDuplicateTimestampReplacesRatherThanAbortingTheBatch() {
        long at = 1_700_000_000_000L;
        population.sample(at, 5);
        population.sample(at, 7);
        // Anything queued behind the duplicate must still land.
        population.sample(at + 1000L, 9);
        settle();

        List<Records.PopulationSample> samples = population.samples(at, at + 2000L, 10).join();
        assertEquals(2, samples.size());
        assertEquals(7, samples.get(0).total(), "the later value wins");
        assertEquals(9, samples.get(1).total());
    }

    @Test
    void summarisesAWindowWithoutReadingEveryRow() {
        long start = 1_700_000_000_000L;
        int[] counts = {4, 9, 2, 7, 8};
        for (int i = 0; i < counts.length; i++) {
            population.sample(start + i * 1000L, counts[i]);
        }
        settle();

        PopulationDao.Summary summary = population.summary(start, start + 5000L).join();
        assertEquals(5, summary.samples());
        assertEquals(2, summary.minimum());
        assertEquals(9, summary.maximum());
        assertEquals(6.0, summary.average(), 0.0001);
    }

    @Test
    void rollsRawSamplesIntoMinuteBuckets() {
        long start = 1_700_000_040_000L;
        long firstMinute = start - (start % MINUTE);
        // Two full minutes of one-second samples, second minute busier than the first.
        for (int i = 0; i < 60; i++) {
            population.sample(firstMinute + i * 1000L, 10);
        }
        for (int i = 0; i < 60; i++) {
            population.sample(firstMinute + MINUTE + i * 1000L, i < 30 ? 20 : 40);
        }
        settle();

        population.rollup(MINUTE, firstMinute, firstMinute + 2 * MINUTE).join();
        settle();

        List<Records.PopulationBucket> buckets = population.buckets(MINUTE,
                PopulationDao.SCOPE_PROXY, "", firstMinute, firstMinute + 2 * MINUTE, 10).join();
        assertEquals(2, buckets.size());

        assertEquals(60, buckets.get(0).samples());
        assertEquals(10.0, buckets.get(0).average(), 0.0001);
        assertEquals(10, buckets.get(0).minimum());
        assertEquals(10, buckets.get(0).maximum());

        assertEquals(60, buckets.get(1).samples());
        assertEquals(30.0, buckets.get(1).average(), 0.0001);
        assertEquals(20, buckets.get(1).minimum());
        assertEquals(40, buckets.get(1).maximum());
    }

    @Test
    void reRunningARollupOverTheSameWindowIsIdempotent() {
        long start = 1_700_000_040_000L;
        long minute = start - (start % MINUTE);
        for (int i = 0; i < 60; i++) {
            population.sample(minute + i * 1000L, 12);
        }
        settle();

        population.rollup(MINUTE, minute, minute + MINUTE).join();
        settle();
        population.rollup(MINUTE, minute, minute + MINUTE).join();
        settle();

        List<Records.PopulationBucket> buckets = population.buckets(MINUTE,
                PopulationDao.SCOPE_PROXY, "", minute, minute + MINUTE, 10).join();
        assertEquals(1, buckets.size(), "a re-run must replace the bucket, not duplicate it");
        assertEquals(60, buckets.get(0).samples(), "and must not double the sample count");
    }

    @Test
    void rollsUpPerServerBreakdownsAlongsideTheProxyTotal() {
        long minute = 1_700_000_040_000L - (1_700_000_040_000L % MINUTE);
        for (int i = 0; i < 10; i++) {
            population.sample(minute + i * 1000L, 30);
            population.breakdown(minute + i * 1000L, PopulationDao.SCOPE_SERVER,
                    Map.of("lobby", 10, "survival", 20));
        }
        settle();

        population.rollup(MINUTE, minute, minute + MINUTE).join();
        settle();

        List<Records.PopulationBucket> lobby = population.buckets(MINUTE,
                PopulationDao.SCOPE_SERVER, "lobby", minute, minute + MINUTE, 10).join();
        assertEquals(1, lobby.size());
        assertEquals(10.0, lobby.get(0).average(), 0.0001);

        List<Records.PopulationBucket> survival = population.buckets(MINUTE,
                PopulationDao.SCOPE_SERVER, "survival", minute, minute + MINUTE, 10).join();
        assertEquals(20.0, survival.get(0).average(), 0.0001);
    }

    @Test
    void retentionRemovesRawSamplesButLeavesRollupsAlone() {
        long minute = 1_700_000_040_000L - (1_700_000_040_000L % MINUTE);
        for (int i = 0; i < 60; i++) {
            population.sample(minute + i * 1000L, 15);
        }
        settle();
        population.rollup(MINUTE, minute, minute + MINUTE).join();
        settle();

        int deleted = maintenance.prune("population-samples", minute + MINUTE).join();
        settle();
        assertEquals(60, deleted);

        assertTrue(population.samples(minute, minute + MINUTE, 100).join().isEmpty());
        assertEquals(1, population.buckets(MINUTE, PopulationDao.SCOPE_PROXY, "",
                minute, minute + MINUTE, 10).join().size(),
                "the rollup is the whole point of being able to prune the raw samples");
    }

    @Test
    void findsTheAllTimePeak() {
        long start = 1_700_000_000_000L;
        population.sample(start, 12);
        population.sample(start + 1000L, 41);
        population.sample(start + 2000L, 8);
        settle();

        Records.PopulationSample peak = population.peak().join();
        assertEquals(41, peak.total());
        assertEquals(start + 1000L, peak.at());
    }

    private void settle() {
        writes.flush().orTimeout(10, TimeUnit.SECONDS).join();
    }
}
