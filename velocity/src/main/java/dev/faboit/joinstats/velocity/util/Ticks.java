package dev.faboit.joinstats.velocity.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** Time helpers shared by the sampler, the rollup job and the retention sweeper. */
public final class Ticks {

    private Ticks() {
    }

    /** Floors an epoch-milli instant to the start of the bucket of the given width. */
    public static long floorTo(long epochMillis, Duration bucket) {
        long width = bucket.toMillis();
        if (width <= 0) {
            return epochMillis;
        }
        return Math.floorDiv(epochMillis, width) * width;
    }

    /** Start-of-day in the configured zone, as epoch millis. */
    public static long startOfDay(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).truncatedTo(ChronoUnit.DAYS)
                .toInstant().toEpochMilli();
    }

    /** The local date key ({@code yyyy-MM-dd}) an instant falls on. */
    public static String dayKey(long epochMillis, ZoneId zone) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toString();
    }

    /** Local hour-of-day, 0-23. */
    public static int hourOfDay(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).getHour();
    }

    /** Local day-of-week, 1 (Monday) through 7 (Sunday). */
    public static int dayOfWeek(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).getDayOfWeek().getValue();
    }

    /**
     * Splits a span across the local hour boundaries it crosses.
     *
     * <p>A session that runs from 22:50 to 00:10 contributes to three hour buckets, not one.
     * The activity heatmap is only honest if we attribute the time that way.
     */
    public static void forEachHourSlice(long startMillis, long endMillis, ZoneId zone, HourSlice sink) {
        if (endMillis <= startMillis) {
            return;
        }
        long cursor = startMillis;
        // Guard against a pathological span (a stuck session, a clock jump) fanning out
        // into millions of buckets.
        int guard = 0;
        while (cursor < endMillis && guard++ < 24 * 400) {
            ZonedDateTime at = Instant.ofEpochMilli(cursor).atZone(zone);
            long hourEnd = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli();
            long sliceEnd = Math.min(hourEnd, endMillis);
            sink.accept(at.getDayOfWeek().getValue(), at.getHour(),
                    LocalDate.from(at).toString(), sliceEnd - cursor);
            cursor = sliceEnd;
        }
    }

    /** Receives one hour-aligned slice of a longer span. */
    @FunctionalInterface
    public interface HourSlice {
        void accept(int dayOfWeek, int hourOfDay, String dayKey, long millis);
    }
}
