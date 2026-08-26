package dev.faboit.joinstats.velocity.geo;

/**
 * A resolved location for one address.
 *
 * <p>Every field is nullable: providers differ in what they return, a GeoLite2 City database has
 * no ASN data, and an ASN database has no city. {@link #merge(GeoData)} exists so a partial answer
 * from one provider can be completed by another rather than discarded.
 */
public record GeoData(
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
        String source) {

    /** The answer for an address no provider could place. */
    public static GeoData empty(String source) {
        return new GeoData(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, false, false, false, source);
    }

    /** True when the lookup produced nothing worth storing. */
    public boolean isEmpty() {
        return countryCode == null && city == null && asn == null && isp == null && !anonymised();
    }

    public boolean anonymised() {
        return proxy || hosting || tor;
    }

    /**
     * Fills this record's gaps from {@code other}, keeping whatever this one already has.
     *
     * <p>The boolean flags are OR-ed rather than overwritten: if either the City database or the
     * REST provider says an address is a VPN, that is the answer worth keeping.
     */
    public GeoData merge(GeoData other) {
        if (other == null) {
            return this;
        }
        return new GeoData(
                hostname != null ? hostname : other.hostname,
                continent != null ? continent : other.continent,
                country != null ? country : other.country,
                countryCode != null ? countryCode : other.countryCode,
                region != null ? region : other.region,
                city != null ? city : other.city,
                postal != null ? postal : other.postal,
                latitude != null ? latitude : other.latitude,
                longitude != null ? longitude : other.longitude,
                accuracyKm != null ? accuracyKm : other.accuracyKm,
                timezone != null ? timezone : other.timezone,
                isp != null ? isp : other.isp,
                organisation != null ? organisation : other.organisation,
                asn != null ? asn : other.asn,
                asName != null ? asName : other.asName,
                mobile || other.mobile,
                proxy || other.proxy,
                hosting || other.hosting,
                tor || other.tor,
                source == null ? other.source
                        : (other.source == null || other.source.equals(source)
                                ? source : source + "+" + other.source));
    }

    /** Returns a copy with the hostname set, for the reverse-DNS pass. */
    public GeoData withHostname(String value) {
        return new GeoData(value, continent, country, countryCode, region, city, postal, latitude,
                longitude, accuracyKm, timezone, isp, organisation, asn, asName, mobile, proxy,
                hosting, tor, source);
    }

    /**
     * Great-circle distance to another point in kilometres, or {@code -1} when either side has
     * no coordinates. Used by the impossible-travel check.
     */
    public double distanceKm(Double otherLat, Double otherLon) {
        if (latitude == null || longitude == null || otherLat == null || otherLon == null) {
            return -1;
        }
        return haversineKm(latitude, longitude, otherLat, otherLon);
    }

    /** Haversine distance between two points on the earth's surface, in kilometres. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusKm = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
