package dev.faboit.joinstats.velocity.storage.model;

/** An address the proxy has seen, together with whatever geolocation resolved for it. */
public record AddressRecord(
        String address,
        String subnet,
        long firstSeen,
        long lastSeen,
        long hits,
        String hostname,
        String continent,
        String country,
        String countryCode,
        String region,
        String city,
        String postal,
        Double latitude,
        Double longitude,
        Integer accuracyKm,
        String timezone,
        String isp,
        String organisation,
        Integer asn,
        String asName,
        boolean mobile,
        boolean proxy,
        boolean hosting,
        boolean tor,
        String geoSource,
        long geoUpdated) {

    /** True when any provider flagged the address as not a residential connection. */
    public boolean anonymised() {
        return proxy || hosting || tor;
    }

    /** A short human label: {@code "Berlin, Germany"}, or the country alone, or {@code "unknown"}. */
    public String describeLocation() {
        if (city != null && !city.isBlank() && country != null && !country.isBlank()) {
            return city + ", " + country;
        }
        if (country != null && !country.isBlank()) {
            return country;
        }
        return "unknown";
    }

    /** The kind of network, for alert text: {@code VPN/proxy}, {@code datacentre}, {@code Tor}. */
    public String networkKind() {
        if (tor) {
            return "Tor exit node";
        }
        if (proxy) {
            return "VPN or proxy";
        }
        if (hosting) {
            return "datacentre range";
        }
        return mobile ? "mobile network" : "residential connection";
    }
}
