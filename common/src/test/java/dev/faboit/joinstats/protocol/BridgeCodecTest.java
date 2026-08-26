package dev.faboit.joinstats.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeCodecTest {

    @Test
    void roundTripsAFullPlaceholderFrame() throws IOException {
        UUID player = UUID.randomUUID();
        byte[] frame = BridgeCodec.frame(BridgeProtocol.RESPONSE_PLACEHOLDERS, out -> {
            out.writeLong(42L);
            BridgeCodec.writeUuid(out, player);
            out.writeInt(2);
            BridgeCodec.writeString(out, "%vault_eco_balance%");
            BridgeCodec.writeString(out, "1234.56");
            BridgeCodec.writeString(out, "%luckperms_primary_group%");
            BridgeCodec.writeString(out, "admin");
        });

        BridgeCodec.Frame decoded = BridgeCodec.decode(frame);
        assertNotNull(decoded);
        assertEquals(BridgeProtocol.RESPONSE_PLACEHOLDERS, decoded.opcode());
        assertEquals(42L, decoded.body().readLong());
        assertEquals(player, BridgeCodec.readUuid(decoded.body()));
        assertEquals(2, BridgeCodec.readCount(decoded.body()));
        assertEquals("%vault_eco_balance%", BridgeCodec.readString(decoded.body()));
        assertEquals("1234.56", BridgeCodec.readString(decoded.body()));
        assertEquals("%luckperms_primary_group%", BridgeCodec.readString(decoded.body()));
        assertEquals("admin", BridgeCodec.readString(decoded.body()));
    }

    @Test
    void survivesNonAsciiValues() throws IOException {
        String value = "Grüße, 世界 — ¡hola! 🎮";
        byte[] frame = BridgeCodec.frame(BridgeProtocol.HELLO,
                out -> BridgeCodec.writeString(out, value));
        BridgeCodec.Frame decoded = BridgeCodec.decode(frame);
        assertNotNull(decoded);
        assertEquals(value, BridgeCodec.readString(decoded.body()));
    }

    @Test
    void handlesStringsPastTheModifiedUtf8Ceiling() throws IOException {
        // writeUTF would throw above 64 KiB; a scoreboard placeholder can exceed that.
        String long_ = "x".repeat(70_000);
        byte[] frame = BridgeCodec.frame(BridgeProtocol.HELLO,
                out -> BridgeCodec.writeString(out, long_));
        BridgeCodec.Frame decoded = BridgeCodec.decode(frame);
        assertNotNull(decoded);
        assertEquals(long_, BridgeCodec.readString(decoded.body()));
    }

    @Test
    void truncatesAnOversizedStringWithoutSplittingACharacter() throws IOException {
        // Every character is three UTF-8 bytes, so the cap lands mid-sequence unless handled.
        String oversized = "世".repeat(BridgeCodec.MAX_STRING_BYTES);
        byte[] frame = BridgeCodec.frame(BridgeProtocol.HELLO,
                out -> BridgeCodec.writeString(out, oversized));
        BridgeCodec.Frame decoded = BridgeCodec.decode(frame);
        assertNotNull(decoded);

        String result = BridgeCodec.readString(decoded.body());
        assertEquals(-1, result.indexOf('�'), "truncation produced a replacement character");
        assertEquals(result, new String(result.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    @Test
    void refusesAFrameFromAnIncompatibleRevision() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(BridgeProtocol.HELLO);
            out.writeInt(BridgeProtocol.REVISION + 1);
            out.writeInt(0);
        }
        assertNull(BridgeCodec.decode(buffer.toByteArray()),
                "a frame from a future revision must not be parsed as if it were current");
    }

    @Test
    void refusesAbsurdLengthPrefixes() {
        // A hostile or corrupt frame must not be able to make the reader allocate 2GB.
        byte[] frame = BridgeCodec.frame(BridgeProtocol.HELLO,
                out -> out.writeInt(Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> {
            BridgeCodec.Frame decoded = BridgeCodec.decode(frame);
            BridgeCodec.readString(decoded.body());
        });
    }

    @Test
    void refusesAbsurdCollectionSizes() {
        byte[] frame = BridgeCodec.frame(BridgeProtocol.REQUEST_PLACEHOLDERS,
                out -> out.writeInt(BridgeCodec.MAX_ELEMENTS + 1));
        assertThrows(IOException.class, () -> {
            BridgeCodec.Frame decoded = BridgeCodec.decode(frame);
            BridgeCodec.readCount(decoded.body());
        });
    }
}
