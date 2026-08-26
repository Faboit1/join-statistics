package dev.faboit.joinstats.velocity.util;

import java.util.Locale;
import java.util.UUID;

/** Parsing helpers for the player identifiers that arrive from command arguments. */
public final class Names {

    private Names() {
    }

    /** Parses a UUID in either dashed or undashed form, or returns {@code null}. */
    public static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.length() == 32) {
                value = value.substring(0, 8) + '-' + value.substring(8, 12) + '-'
                        + value.substring(12, 16) + '-' + value.substring(16, 20) + '-'
                        + value.substring(20);
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Lower-cases a username for the case-insensitive lookup column. */
    public static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }

    /** True when a string is plausibly a Minecraft username rather than a UUID. */
    public static boolean looksLikeUsername(String raw) {
        return raw != null && !raw.isBlank() && raw.length() <= 16 && parseUuid(raw) == null;
    }
}
