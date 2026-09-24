package com.winnyking.watchmouse.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.Test;

/** Cross-implementation vector + AES-GCM framing checks for {@link ProtocolCrypto}. */
public final class ProtocolCryptoTest {

    /** Known-answer vector for HKDF-SHA256, MUST equal host/ test_watchmouse_proto.py. */
    private static final String HKDF_KAT = "F3F25E46F794502E875412C222FB5079";

    private static byte[] salt() {
        byte[] salt = new byte[ProtocolCrypto.SALT_LEN];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) i;
        }
        return salt;
    }

    @Test
    public void hkdf_knownAnswer_matchesPythonHost() {
        byte[] key = ProtocolCrypto.deriveSessionKey("1234", salt());
        assertTrue(
                "HKDF must match the known-answer vector computed by host/linux/watchmouse_host.py",
                HKDF_KAT.equalsIgnoreCase(HexFormat.of().formatHex(key)));
    }

    @Test
    public void frame_roundTrip() {
        byte[] key = ProtocolCrypto.deriveSessionKey("1234", salt());
        String frame = ProtocolCrypto.encryptFrame(key, "{\"t\":\"ping\"}");
        assertEquals("{\"t\":\"ping\"}", ProtocolCrypto.decryptFrame(key, frame));
    }

    @Test
    public void frame_wrongKey_failsClosed() {
        byte[] key = ProtocolCrypto.deriveSessionKey("1234", salt());
        byte[] wrong = ProtocolCrypto.deriveSessionKey("9999", salt());
        String frame = ProtocolCrypto.encryptFrame(key, "{\"t\":\"mouse\"}");
        assertNull(ProtocolCrypto.decryptFrame(wrong, frame));
    }

    @Test
    public void frame_tampered_failsClosed() {
        byte[] key = ProtocolCrypto.deriveSessionKey("1234", salt());
        String frame = ProtocolCrypto.encryptFrame(key, "{\"t\":\"ping\"}");
        String tampered = (frame.charAt(0) == 'A' ? "B" : "A") + frame.substring(1);
        assertNull(ProtocolCrypto.decryptFrame(key, tampered));
    }

    @Test
    public void frame_garbage_failsClosed() {
        byte[] key = ProtocolCrypto.deriveSessionKey("1234", salt());
        assertNull(ProtocolCrypto.decryptFrame(key, "not-base64!@#"));
        assertNull(ProtocolCrypto.decryptFrame(key, "c2hvcnQ="));
    }

    @Test
    public void emptyPinStillDerivesDeterministically() {
        byte[] a = ProtocolCrypto.deriveSessionKey("", salt());
        byte[] b = ProtocolCrypto.deriveSessionKey("", salt());
        assertEquals(HexFormat.of().formatHex(a), HexFormat.of().formatHex(b));
        assertNotNull(ProtocolCrypto.encryptFrame(a, "{}"));

        byte[] otherSalt = new byte[ProtocolCrypto.SALT_LEN];
        assertNotEquals(
                HexFormat.of().formatHex(a),
                HexFormat.of().formatHex(ProtocolCrypto.deriveSessionKey("", otherSalt)));
    }

    @Test
    public void javaHkdf_primitiveChaining() {
        byte[] ikm = "1234".getBytes(StandardCharsets.UTF_8);
        byte[] key = ProtocolCrypto.hkdfExpand(ProtocolCrypto.hmac(salt(), ikm),
                ProtocolCrypto.KDF_INFO.getBytes(StandardCharsets.UTF_8),
                ProtocolCrypto.KEY_LEN);
        assertEquals(ProtocolCrypto.KEY_LEN, key.length);
    }
}