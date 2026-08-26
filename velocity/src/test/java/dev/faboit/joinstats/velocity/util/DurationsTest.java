package dev.faboit.joinstats.velocity.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s", Duration.ZERO));
        assertEquals(Duration.ofMinutes(5), Durations.parse("5m", Duration.ZERO));
        assertEquals(Duration.ofHours(2), Durations.parse("2h", Duration.ZERO));
        assertEquals(Duration.ofDays(7), Durations.parse("7d", Duration.ZERO));
        assertEquals(Duration.ofDays(14), Durations.parse("2w", Duration.ZERO));
    }

    @Test
    void parsesCompoundValues() {
        assertEquals(Duration.ofMinutes(150), Durations.parse("2h30m", Duration.ZERO));
        assertEquals(Duration.ofSeconds(90061), Durations.parse("1d1h1m1s", Duration.ZERO));
    }

    @Test
    void treatsABareNumberAsSeconds() {
        assertEquals(Duration.ofSeconds(45), Durations.parse("45", Duration.ZERO));
    }

    @Test
    void isForgivingAboutCaseAndSpacing() {
        assertEquals(Duration.ofHours(2), Durations.parse("  2H  ", Duration.ZERO));
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h 30m", Duration.ZERO));
    }

    @Test
    void fallsBackRatherThanThrowingOnGarbage() {
        Duration fallback = Duration.ofSeconds(30);
        // A typo in the config must degrade to the documented default, not stop the plugin.
        assertEquals(fallback, Durations.parse("soon", fallback));
        assertEquals(fallback, Durations.parse("5x", fallback));
        assertEquals(fallback, Durations.parse("", fallback));
        assertEquals(fallback, Durations.parse(null, fallback));
        assertEquals(fallback, Durations.parse("h", fallback));
    }

    @Test
    void formatsAtAUsefulPrecision() {
        assertEquals("0s", Durations.format(0));
        assertEquals("47s", Durations.format(47_000));
        assertEquals("5m 0s", Durations.format(300_000));
        assertEquals("2h 30m", Durations.format(9_000_000));
        assertEquals("3d 4h 12m", Durations.format(274_320_000L));
    }

    @Test
    void formatsNegativeInputAsZeroRatherThanNonsense() {
        assertEquals("0s", Durations.format(-5000));
    }

    @Test
    void describesTheDistantPastRelatively() {
        long now = 1_000_000_000L;
        assertEquals("never", Durations.ago(0, now));
        assertEquals("just now", Durations.ago(now - 500, now));
        assertEquals("1h 0m", Durations.ago(now - 3_600_000, now).replace(" ago", ""));
    }
}
