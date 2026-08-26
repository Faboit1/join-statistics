package dev.faboit.joinstats.velocity.util;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import java.util.function.Supplier;

/**
 * Applies the privacy settings at the point where data is about to be stored or shown.
 *
 * <p>Centralised deliberately. Address hashing is only worth anything if it is impossible to
 * forget it somewhere, and "the raw address is written by exactly one code path" is far easier to
 * verify than the same check repeated across a dozen listeners.
 */
public final class Privacy {

    private final Supplier<PluginConfig> config;

    public Privacy(Supplier<PluginConfig> config) {
        this.config = config;
    }

    /** The form of an address that goes into the database. */
    public String storedAddress(String ip) {
        if (ip == null) {
            return null;
        }
        PluginConfig.Privacy settings = config.get().privacy;
        if (!settings.hashAddresses) {
            return ip;
        }
        return Addresses.pseudonymise(ip, settings.addressSalt);
    }

    /** The subnet key for an address, hashed to match when hashing is on. */
    public String storedSubnet(String ip) {
        if (ip == null) {
            return null;
        }
        String subnet = Addresses.subnetKey(ip);
        PluginConfig.Privacy settings = config.get().privacy;
        if (!settings.hashAddresses) {
            return subnet;
        }
        return Addresses.pseudonymise(subnet, settings.addressSalt);
    }

    /** The form of an address shown to a viewer, honouring their permission to see it in full. */
    public String displayAddress(String stored, boolean viewerMaySeeAddresses) {
        if (stored == null || stored.isBlank()) {
            return "unknown";
        }
        if (viewerMaySeeAddresses || !config.get().privacy.maskAddressesInCommands) {
            return stored;
        }
        return Addresses.mask(stored);
    }

    /** Whether the body of a chat message should be stored alongside the fact of it. */
    public boolean storeChatContent() {
        return config.get().privacy.storeChatContent;
    }

    /**
     * Whether a command's arguments should be stored.
     *
     * <p>The sensitive list always wins: {@code /login hunter2} must never reach the database,
     * whatever the general setting says, because a statistics plugin that harvests passwords is
     * a far worse problem than one that under-reports command usage.
     */
    public boolean storeCommandArguments(String commandName) {
        PluginConfig settings = config.get();
        if (PluginConfig.containsIgnoreCase(settings.tracking.sensitiveCommands, commandName)) {
            return false;
        }
        return settings.privacy.storeCommandContent;
    }
}
