/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.winnyking.watchmouse.net;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared session key + wire framing for the encrypted WatchMouse transport (protocol v2).
 *
 * <p>Both the watch client, the Linux host (Python) and the companion app derive the same session
 * key from the user PIN and a per-connection random salt sent by the receiver:
 *
 * <pre>key = HKDF-SHA256(ikm = PIN bytes, salt = salt, info = "watchmouse-v2", 16)</pre>
 *
 * <p>Every message after the initial <code>hello</code> is wrapped in an authenticated AES-128-GCM
 * frame: <code>base64(nonce(12B) || aes-gcm(key, nonce, plaintext))</code> with a 128-bit tag. The
 * sender proves knowledge of the PIN by sending an encrypted <code>{"t":"auth"}</code> as its very
 * first frame.
 *
 * <p>This class has no Android dependencies (HKDF copies RFC 5869), so the encode/decode logic can
 * be tested on the JVM.
 */
public final class ProtocolCrypto {

    public static final int PROTOCOL_VERSION = 2;
    public static final String KDF_INFO = "watchmouse-v2";

    /** AES-128 session key. */
    public static final int KEY_LEN = 16;

    public static final int SALT_LEN = 16;
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ProtocolCrypto() {}

    /** Derives the 16-byte AES session key from the PIN and the server-provided salt. */
    public static byte[] deriveSessionKey(String pin, byte[] salt) {
        byte[] ikm = (pin == null ? "" : pin.trim()).getBytes(StandardCharsets.UTF_8);
        byte[] prk = hmac(salt, ikm);
        return hkdfExpand(prk, KDF_INFO.getBytes(StandardCharsets.UTF_8), KEY_LEN);
    }

    /**
     * Encrypts a JSON message into a wire frame:
     * <code>base64(nonce(12B) || AES-GCM ciphertext + tag)</code>.
     */
    public static String encryptFrame(byte[] key, String json) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LEN * 8, nonce));
            byte[] ciphertext = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
            byte[] frame = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, frame, 0, nonce.length);
            System.arraycopy(ciphertext, 0, frame, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(frame);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /**
     * Decrypts a wire frame; returns the plaintext JSON or {@code null} if the frame is malformed
     * or the tag does not verify.
     */
    public static String decryptFrame(byte[] key, String frame) {
        try {
            byte[] data = Base64.getDecoder().decode(frame);
            if (data.length < NONCE_LEN + TAG_LEN) {
                return null;
            }
            byte[] nonce = Arrays.copyOfRange(data, 0, NONCE_LEN);
            byte[] ciphertext = Arrays.copyOfRange(data, NONCE_LEN, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LEN * 8, nonce));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** HMAC-SHA256 (used as the HKDF extract function). */
    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 5869 HKDF-Expand. */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            byte[] block = hmac(
                    prk,
                    concat(previous, info, new byte[] {(byte) counter}));
            int blockSize = Math.min(block.length, length - offset);
            System.arraycopy(block, 0, output, offset, blockSize);
            offset += blockSize;
            previous = block;
            counter++;
        }
        return output;
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }
}