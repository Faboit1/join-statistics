package dev.faboit.joinstats.velocity.tracking;

import java.util.UUID;

/**
 * Hooks fired as sessions begin, resume and end.
 *
 * <p>Exists so the alerting and notification code can react to session boundaries without
 * {@link SessionManager} having to know either of them.
 */
public interface SessionEvents {

    /** A brand new session was opened. */
    default void onSessionStarted(UUID uuid, String username, long sessionId, long startedAt) {
    }

    /** A lingering session was picked back up inside the grace window. */
    default void onSessionResumed(UUID uuid, String username, long sessionId, long gapMillis,
                                  int connections) {
    }

    /** A session was written out for good. */
    default void onSessionEnded(UUID uuid, String username, long sessionId, long duration,
                                long endedAt, boolean crashed) {
    }

    /** A no-op implementation, so callers never have to null-check. */
    SessionEvents NONE = new SessionEvents() {
    };
}
