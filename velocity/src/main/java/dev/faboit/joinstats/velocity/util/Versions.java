package dev.faboit.joinstats.velocity.util;

import com.velocitypowered.api.network.ProtocolVersion;

/** Turns a protocol number into something worth storing next to a session. */
public final class Versions {

    private Versions() {
    }

    /**
     * A display name for a protocol version.
     *
     * <p>Several Minecraft releases share one protocol number, so there is no single correct
     * answer; the most recent release in the group is the one players recognise. Falls back to
     * the raw protocol number for a version this build of Velocity has never heard of, which is
     * exactly the case worth recording when a new release lands.
     */
    public static String name(ProtocolVersion version) {
        if (version == null || version == ProtocolVersion.UNKNOWN) {
            return "unknown";
        }
        String recent = version.getMostRecentSupportedVersion();
        if (recent != null && !recent.isBlank()) {
            return recent;
        }
        String introduced = version.getVersionIntroducedIn();
        return introduced != null && !introduced.isBlank()
                ? introduced : "protocol " + version.getProtocol();
    }

    /** The protocol number, or {@code 0} when the connection never reported one. */
    public static int protocol(ProtocolVersion version) {
        return version == null ? 0 : version.getProtocol();
    }
}
