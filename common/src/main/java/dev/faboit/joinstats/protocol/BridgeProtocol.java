package dev.faboit.joinstats.protocol;

/**
 * Wire constants shared by the proxy plugin and the backend companion.
 *
 * <p>Both sides speak a tiny length-prefixed binary protocol over a single plugin message
 * channel. Every frame starts with a {@code byte} opcode followed by an {@code int} protocol
 * revision, which lets either side refuse to talk to an incompatible peer instead of
 * misreading its payload.
 */
public final class BridgeProtocol {

    /** The plugin message channel both sides register. */
    public static final String CHANNEL = "joinstats:bridge";

    /** Bumped whenever the frame layout changes in a backwards-incompatible way. */
    public static final int REVISION = 1;

    // ---- proxy -> backend -------------------------------------------------

    /** Ask the backend to resolve a batch of placeholders for one player. */
    public static final byte REQUEST_PLACEHOLDERS = 0x01;

    /** Ask the backend to describe itself (version, TPS, worlds, plugins). */
    public static final byte REQUEST_SERVER_INFO = 0x02;

    /** Sent right after a backend announces itself, acknowledging the handshake. */
    public static final byte WELCOME = 0x03;

    // ---- backend -> proxy -------------------------------------------------

    /** Unsolicited greeting sent when the companion sees its first player. */
    public static final byte HELLO = 0x11;

    /** Resolved placeholder values, keyed by the request id the proxy chose. */
    public static final byte RESPONSE_PLACEHOLDERS = 0x12;

    /** Server description payload. */
    public static final byte RESPONSE_SERVER_INFO = 0x13;

    private BridgeProtocol() {
    }
}
