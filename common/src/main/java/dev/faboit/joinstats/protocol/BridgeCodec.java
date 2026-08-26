package dev.faboit.joinstats.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Length-prefixed reader/writer for bridge frames.
 *
 * <p>Strings are written as an {@code int} byte-length followed by UTF-8 bytes rather than via
 * {@link DataOutputStream#writeUTF}, because resolved placeholder values regularly exceed the
 * 64 KiB modified-UTF8 ceiling once a server owner points one at a scoreboard or a list.
 */
public final class BridgeCodec {

    /** Hard ceiling on a single decoded string, to bound damage from a malformed frame. */
    public static final int MAX_STRING_BYTES = 1 << 20;

    /** Hard ceiling on the element count of any decoded collection. */
    public static final int MAX_ELEMENTS = 4096;

    private BridgeCodec() {
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            bytes = truncateUtf8(bytes);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Refusing to decode a " + length + " byte string");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    public static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ELEMENTS) {
            throw new IOException("Refusing to decode a collection of " + count + " elements");
        }
        return count;
    }

    /** Builds a frame, prefixing the opcode and revision that every message carries. */
    public static byte[] frame(byte opcode, FrameWriter body) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(opcode);
            out.writeInt(BridgeProtocol.REVISION);
            body.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode bridge frame " + opcode, e);
        }
        return buffer.toByteArray();
    }

    /** Opens a frame, returning {@code null} when the peer speaks a revision we do not. */
    public static Frame decode(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte opcode = in.readByte();
        int revision = in.readInt();
        if (revision != BridgeProtocol.REVISION) {
            return null;
        }
        return new Frame(opcode, in);
    }

    /** Trims a UTF-8 buffer to the cap without splitting a multi-byte sequence in half. */
    private static byte[] truncateUtf8(byte[] bytes) {
        int end = MAX_STRING_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        byte[] copy = new byte[end];
        System.arraycopy(bytes, 0, copy, 0, end);
        return copy;
    }

    /** A decoded frame header paired with the still-unread body. */
    public record Frame(byte opcode, DataInputStream body) {
    }

    /** Writes the body of a frame. */
    @FunctionalInterface
    public interface FrameWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
