package dev.faboit.joinstats.velocity.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AddressesTest {

    @Test
    void recognisesAddressesThatCannotBeGeolocated() {
        assertTrue(Addresses.isPrivate("127.0.0.1"));
        assertTrue(Addresses.isPrivate("10.0.0.5"));
        assertTrue(Addresses.isPrivate("192.168.1.20"));
        assertTrue(Addresses.isPrivate("172.16.4.1"));
        assertTrue(Addresses.isPrivate("169.254.1.1"));
        assertTrue(Addresses.isPrivate("::1"));
        assertTrue(Addresses.isPrivate("fd00::1"), "unique local IPv6");
        // Carrier-grade NAT: a real internet path, but shared by thousands of subscribers.
        assertTrue(Addresses.isPrivate("100.64.0.1"));
        assertTrue(Addresses.isPrivate("100.127.255.254"));
    }

    @Test
    void treatsPublicAddressesAsPublic() {
        assertFalse(Addresses.isPrivate("203.0.113.7"));
        assertFalse(Addresses.isPrivate("8.8.8.8"));
        assertFalse(Addresses.isPrivate("2606:4700:4700::1111"));
        // 100.128.0.0 is just outside the CGNAT range.
        assertFalse(Addresses.isPrivate("100.128.0.1"));
    }

    @Test
    void groupsIpv6ByItsRoutedPrefix() {
        // A residential IPv6 allocation rotates its interface identifier while the /64 stays put,
        // which is what makes household grouping possible at all.
        String first = Addresses.subnetKey("2001:db8:abcd:1234:1111:2222:3333:4444");
        String second = Addresses.subnetKey("2001:db8:abcd:1234:9999:8888:7777:6666");
        assertEquals(first, second);
        assertTrue(first.endsWith("/64"));

        assertNotEquals(first, Addresses.subnetKey("2001:db8:abcd:9999::1"));
    }

    @Test
    void leavesIpv4Untouched() {
        assertEquals("203.0.113.7", Addresses.subnetKey("203.0.113.7"));
    }

    @Test
    void matchesCidrRangesForBothFamilies() {
        assertTrue(Addresses.inCidr("203.0.113.7", "203.0.113.0/24"));
        assertTrue(Addresses.inCidr("203.0.113.255", "203.0.113.0/24"));
        assertFalse(Addresses.inCidr("203.0.114.1", "203.0.113.0/24"));
        assertTrue(Addresses.inCidr("10.1.2.3", "10.0.0.0/8"));
        assertFalse(Addresses.inCidr("11.1.2.3", "10.0.0.0/8"));
        // A prefix that is not a whole number of bytes.
        assertTrue(Addresses.inCidr("192.168.1.100", "192.168.1.64/26"));
        assertFalse(Addresses.inCidr("192.168.1.200", "192.168.1.64/26"));
        assertTrue(Addresses.inCidr("2001:db8::1", "2001:db8::/32"));
        assertFalse(Addresses.inCidr("2001:dba::1", "2001:db8::/32"));
    }

    @Test
    void neverMatchesAcrossAddressFamilies() {
        assertFalse(Addresses.inCidr("203.0.113.7", "::/0"));
        assertFalse(Addresses.inCidr("2001:db8::1", "0.0.0.0/0"));
    }

    @Test
    void ignoresUnparseablePatternsRatherThanMatchingEverything() {
        // A typo in an exemption list must narrow the exemption, never widen it.
        assertFalse(Addresses.matchesAny("203.0.113.7", List.of("203.0.113.0/notanumber")));
        assertFalse(Addresses.matchesAny("203.0.113.7", List.of("203.0.113.0/99")));
        assertFalse(Addresses.matchesAny("203.0.113.7", List.of("")));
    }

    @Test
    void supportsExactWildcardAndCidrExemptions() {
        List<String> patterns = List.of("127.0.0.1", "10.0.*", "203.0.113.0/24");
        assertTrue(Addresses.matchesAny("127.0.0.1", patterns));
        assertTrue(Addresses.matchesAny("10.0.5.9", patterns));
        assertTrue(Addresses.matchesAny("203.0.113.200", patterns));
        assertFalse(Addresses.matchesAny("10.1.5.9", patterns));
        assertFalse(Addresses.matchesAny("8.8.8.8", patterns));
    }

    @Test
    void pseudonymisationIsStableAndSaltDependent() {
        String a = Addresses.pseudonymise("203.0.113.7", "salt-one");
        assertEquals(a, Addresses.pseudonymise("203.0.113.7", "salt-one"),
                "the same address and salt must group together across restarts");
        assertNotEquals(a, Addresses.pseudonymise("203.0.113.8", "salt-one"));
        assertNotEquals(a, Addresses.pseudonymise("203.0.113.7", "salt-two"));
        assertTrue(a.startsWith("anon:"));
        assertFalse(a.contains("203.0.113"));
    }

    @Test
    void masksTheIdentifyingPartOfAnAddress() {
        assertEquals("203.0.113.x", Addresses.mask("203.0.113.7"));
        assertEquals("unknown", Addresses.mask(null));
        assertTrue(Addresses.mask("2001:db8:1:2:3:4:5:6").endsWith(":x"));
        // Already pseudonymised values are left alone; there is nothing left to hide.
        assertEquals("anon:abcdef", Addresses.mask("anon:abcdef"));
    }
}
