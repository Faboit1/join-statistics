package dev.faboit.joinstats.velocity.geo;

/** One way of turning an address into a location. */
public interface GeoResolver extends AutoCloseable {

    /** Short identifier stored alongside the result, so a bad provider can be traced later. */
    String name();

    /** False when the provider cannot answer at all — no database file, no configured endpoint. */
    boolean available();

    /**
     * Resolves an address.
     *
     * @return what could be determined, possibly {@link GeoData#empty}; never {@code null}
     * @throws Exception if the lookup failed outright, so the chain moves to the next provider
     */
    GeoData resolve(String address) throws Exception;

    @Override
    default void close() {
    }
}
