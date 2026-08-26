package dev.faboit.joinstats.velocity.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Normalises, classifies and (optionally) pseudonymises player addresses. */
public final class Addresses {

    private Addresses() {
    }

    /** Extracts the bare IP literal from a socket address, or {@code "unknown"}. */
    public static String ipOf(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            InetAddress resolved = inet.getAddress();
            if (resolved != null) {
                return resolved.getHostAddress();
            }
            String host = inet.getHostString();
            return host == null ? "unknown" : host;
        }
        return address == null ? "unknown" : address.toString();
    }

    /** The port a connection came from, or {@code -1} when it is not an IP connection. */
    public static int portOf(SocketAddress address) {
        return address instanceof InetSocketAddress inet ? inet.getPort() : -1;
    }

    /**
     * True for addresses that can never be geolocated: loopback, link-local, site-local,
     * multicast, and the CGNAT range that proxies behind a load balancer often report.
     */
    public static boolean isPrivate(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return true;
            }
            if (address instanceof Inet4Address) {
                byte[] octets = address.getAddress();
                int first = octets[0] & 0xFF;
                int second = octets[1] & 0xFF;
                // 100.64.0.0/10 — carrier-grade NAT.
                return first == 100 && second >= 64 && second <= 127;
            }
            if (address instanceof Inet6Address v6) {
                return v6.isSiteLocalAddress() || (v6.getAddress()[0] & 0xFE) == 0xFC;
            }
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /**
     * Collapses an address to the block an ISP hands to one customer, so that "same household"
     * grouping keeps working when a residential IPv6 prefix rotates its interface identifier.
     * IPv4 is returned untouched; IPv6 is truncated to its /64.
     */
    public static String subnetKey(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address instanceof Inet6Address) {
                byte[] octets = address.getAddress();
                byte[] prefix = new byte[16];
                System.arraycopy(octets, 0, prefix, 0, 8);
                return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
            }
            return ip;
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /**
     * Replaces the host portion of an address with a keyed digest.
     *
     * <p>The salt matters: without one, the address space is small enough that a leaked database
     * can be reversed by brute force in minutes. With a per-install secret salt, the stored value
     * still groups the same player's connections together but no longer discloses where they live.
     */
    public static String pseudonymise(String ip, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ip.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            byte[] head = new byte[12];
            System.arraycopy(hash, 0, head, 0, head.length);
            return "anon:" + HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }


    /**
     * Tests an address against a list of exact literals, CIDR ranges and {@code *} wildcards.
     *
     * <p>Used by the exemption lists. A pattern that cannot be parsed is skipped rather than
     * treated as a match, so a typo silently narrows the exemption instead of silently disabling
     * tracking for everyone.
     */
    public static boolean matchesAny(String ip, java.util.List<String> patterns) {
        if (ip == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String candidate = pattern.trim();
            if (candidate.equals("*") || candidate.equalsIgnoreCase(ip)) {
                return true;
            }
            if (candidate.indexOf('/') >= 0) {
                if (inCidr(ip, candidate)) {
                    return true;
                }
                continue;
            }
            if (candidate.indexOf('*') >= 0 && wildcardMatches(ip, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code ip} falls inside {@code cidr}, for both IPv4 and IPv6. */
    public static boolean inCidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(cidr.substring(0, slash));
            int prefixBits = Integer.parseInt(cidr.substring(slash + 1).trim());

            byte[] addressBytes = address.getAddress();
            byte[] networkBytes = network.getAddress();
            // An IPv4 range never contains an IPv6 address, and vice versa.
            if (addressBytes.length != networkBytes.length) {
                return false;
            }
            if (prefixBits < 0 || prefixBits > addressBytes.length * 8) {
                return false;
            }

            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addressBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (addressBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (RuntimeException | UnknownHostException e) {
            return false;
        }
    }

    /** Matches a pattern such as {@code 203.0.113.*} against a literal address. */
    private static boolean wildcardMatches(String ip, String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        try {
            return ip.matches(regex.toString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Masks an address for display: {@code 203.0.113.7} becomes {@code 203.0.113.x}. */
    public static String mask(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.startsWith("anon:")) {
            return ip;
        }
        if (ip.indexOf(':') >= 0) {
            String[] groups = ip.split(":");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(4, groups.length); i++) {
                out.append(groups[i]).append(':');
            }
            return out.append(":x").toString();
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot < 0 ? ip : ip.substring(0, lastDot + 1) + "x";
    }
}
