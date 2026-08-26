package dev.faboit.joinstats.velocity.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TicksTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private record Slice(int dayOfWeek, int hour, String day, long millis) {
    }

    private static List<Slice> slice(ZonedDateTime from, Duration length, ZoneId zone) {
        List<Slice> out = new ArrayList<>();
        long start = from.toInstant().toEpochMilli();
        Ticks.forEachHourSlice(start, start + length.toMillis(), zone,
                (dayOfWeek, hour, day, millis) -> out.add(new Slice(dayOfWeek, hour, day, millis)));
        return out;
    }

    @Test
    void aSpanInsideOneHourIsOneSlice() {
        List<Slice> slices = slice(
                ZonedDateTime.of(2024, 3, 5, 14, 10, 0, 0, UTC), Duration.ofMinutes(20), UTC);
        assertEquals(1, slices.size());
        assertEquals(14, slices.get(0).hour());
        assertEquals(Duration.ofMinutes(20).toMillis(), slices.get(0).millis());
    }

    @Test
    void aSpanCrossingHoursIsSplitAtEachBoundary() {
        // 22:50 to 00:10 the next day covers three hour buckets, not one.
        List<Slice> slices = slice(
                ZonedDateTime.of(2024, 3, 5, 22, 50, 0, 0, UTC), Duration.ofMinutes(80), UTC);

        assertEquals(3, slices.size());
        assertEquals(22, slices.get(0).hour());
        assertEquals(Duration.ofMinutes(10).toMillis(), slices.get(0).millis());
        assertEquals(23, slices.get(1).hour());
        assertEquals(Duration.ofMinutes(60).toMillis(), slices.get(1).millis());
        assertEquals(0, slices.get(2).hour());
        assertEquals(Duration.ofMinutes(10).toMillis(), slices.get(2).millis());
    }

    @Test
    void slicesAlwaysSumToTheOriginalSpan() {
        Duration length = Duration.ofHours(7).plusMinutes(37).plusSeconds(11);
        List<Slice> slices = slice(
                ZonedDateTime.of(2024, 7, 20, 19, 3, 0, 0, UTC), length, UTC);
        long total = slices.stream().mapToLong(Slice::millis).sum();
        assertEquals(length.toMillis(), total, "attributed time must equal elapsed time");
    }

    @Test
    void crossingMidnightMovesToTheNextDayAndWeekday() {
        // 2024-03-05 is a Tuesday.
        List<Slice> slices = slice(
                ZonedDateTime.of(2024, 3, 5, 23, 30, 0, 0, UTC), Duration.ofHours(1), UTC);
        assertEquals(2, slices.size());
        assertEquals(2, slices.get(0).dayOfWeek(), "Tuesday");
        assertEquals("2024-03-05", slices.get(0).day());
        assertEquals(3, slices.get(1).dayOfWeek(), "Wednesday");
        assertEquals("2024-03-06", slices.get(1).day());
    }

    @Test
    void attributesToTheConfiguredZoneNotUtc() {
        // 23:30 UTC is 00:30 the next day in Berlin (UTC+1 in March before the DST switch).
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        List<Slice> slices = slice(
                ZonedDateTime.of(2024, 3, 5, 23, 30, 0, 0, UTC), Duration.ofMinutes(20), berlin);
        assertEquals(1, slices.size());
        assertEquals(0, slices.get(0).hour(), "local hour, not UTC hour");
        assertEquals("2024-03-06", slices.get(0).day());
    }

    @Test
    void survivesADaylightSavingTransition() {
        // Europe/Berlin springs forward at 02:00 local on 2024-03-31.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Duration length = Duration.ofHours(3);
        List<Slice> slices = slice(
                ZonedDateTime.of(2024, 3, 31, 0, 30, 0, 0, berlin), length, berlin);
        long total = slices.stream().mapToLong(Slice::millis).sum();
        assertEquals(length.toMillis(), total,
                "a DST jump must not create or destroy attributed playtime");
        assertTrue(slices.size() >= 3);
    }

    @Test
    void emptyAndInvertedSpansProduceNothing() {
        long now = 1_700_000_000_000L;
        List<Slice> out = new ArrayList<>();
        Ticks.forEachHourSlice(now, now, UTC,
                (dow, hour, day, millis) -> out.add(new Slice(dow, hour, day, millis)));
        Ticks.forEachHourSlice(now, now - 1000, UTC,
                (dow, hour, day, millis) -> out.add(new Slice(dow, hour, day, millis)));
        assertTrue(out.isEmpty());
    }

    @Test
    void doesNotFanOutForeverOnAnAbsurdSpan() {
        // A stuck session or a clock jump must not spin out millions of buckets.
        long now = 1_700_000_000_000L;
        int[] count = {0};
        Ticks.forEachHourSlice(now, now + Duration.ofDays(3650).toMillis(), UTC,
                (dow, hour, day, millis) -> count[0]++);
        assertTrue(count[0] <= 24 * 400, "the guard should bound the iteration, got " + count[0]);
    }

    @Test
    void bucketFlooringIsStable() {
        long at = 1_700_000_123_456L;
        long minute = Ticks.floorTo(at, Duration.ofMinutes(1));
        assertEquals(0, minute % 60_000L);
        assertTrue(minute <= at && at - minute < 60_000L);
        assertEquals(at, Ticks.floorTo(at, Duration.ZERO), "a zero-width bucket is a no-op");
    }

    @Test
    void dayAndHourKeysFollowTheZone() {
        long at = ZonedDateTime.of(2024, 12, 31, 23, 30, 0, 0, UTC).toInstant().toEpochMilli();
        assertEquals("2024-12-31", Ticks.dayKey(at, UTC));
        assertEquals("2025-01-01", Ticks.dayKey(at, ZoneId.of("Europe/Berlin")));
        assertEquals(23, Ticks.hourOfDay(at, UTC));
        assertEquals(2, Ticks.dayOfWeek(at, UTC), "31 December 2024 is a Tuesday");
    }
}
