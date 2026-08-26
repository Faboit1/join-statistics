package dev.faboit.joinstats.velocity.util;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Parses and renders the human-friendly durations used in config and command output. */
public final class Durations {

    private Durations() {
    }

    /**
     * Parses values like {@code 30s}, {@code 5m}, {@code 2h30m}, {@code 7d} or a bare number of
     * seconds. Returns {@code fallback} for blank or unparseable input so a typo in the config
     * degrades to the documented default instead of killing plugin startup.
     */
    public static Duration parse(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        try {
            if (value.chars().allMatch(Character::isDigit)) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            long total = 0;
            long number = 0;
            boolean sawDigit = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isDigit(c)) {
                    number = number * 10 + (c - '0');
                    sawDigit = true;
                    continue;
                }
                if (!sawDigit) {
                    return fallback;
                }
                long unit = switch (c) {
                    case 'w' -> TimeUnit.DAYS.toMillis(7);
                    case 'd' -> TimeUnit.DAYS.toMillis(1);
                    case 'h' -> TimeUnit.HOURS.toMillis(1);
                    case 'm' -> TimeUnit.MINUTES.toMillis(1);
                    case 's' -> TimeUnit.SECONDS.toMillis(1);
                    default -> -1;
                };
                if (unit < 0) {
                    return fallback;
                }
                total += number * unit;
                number = 0;
                sawDigit = false;
            }
            if (sawDigit) {
                total += number * 1000L;
            }
            return Duration.ofMillis(total);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Renders a duration compactly: {@code 3d 4h 12m}, {@code 47s}, {@code 0s}. */
    public static String format(long millis) {
        if (millis < 0) {
            return "0s";
        }
        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            out.append(minutes).append("m ");
        }
        if (days == 0 && hours == 0) {
            out.append(secs).append('s');
        }
        return out.toString().trim();
    }

    /** Renders how long ago an instant was, e.g. {@code 4h 2m ago} or {@code just now}. */
    public static String ago(long epochMillis, long now) {
        if (epochMillis <= 0) {
            return "never";
        }
        long delta = now - epochMillis;
        if (delta < 1000) {
            return "just now";
        }
        return format(delta) + " ago";
    }
}
