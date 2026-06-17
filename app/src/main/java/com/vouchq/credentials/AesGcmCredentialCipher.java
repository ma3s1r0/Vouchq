package com.vouchq.credentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Default {@link CredentialCipher}: AES-GCM (authenticated encryption) over the
 * JDK's standard crypto provider — no third-party dependency, so it runs anywhere
 * a self-hosted instance does.
 *
 * <h2>Format</h2>
 * Ciphertext strings are {@code "enc:gcm:" + base64(iv ‖ ciphertext+tag)}. The
 * {@code enc:gcm:} prefix makes a stored credential trivially distinguishable from
 * a legacy {@code token:in-memory} placeholder or an accidentally-stored plaintext,
 * and lets {@link #decrypt} reject anything it did not produce. A fresh random
 * 12-byte IV is generated per encryption (GCM's nonce-reuse requirement).
 *
 * <h2>Key</h2>
 * Base64 of a 16/24/32-byte AES key from {@code vouchq.credentials.key}. When unset,
 * a fixed dev key is used and a prominent warning is logged once — never acceptable
 * in production, but it keeps local/compose dev frictionless.
 */
@Component
public class AesGcmCredentialCipher implements CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmCredentialCipher.class);

    private static final String PREFIX = "enc:gcm:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /**
     * Fixed dev-only key (base64 of 32 zero-ish bytes). Used only when no key is
     * configured; its use is logged as a warning. NEVER ship this in production.
     */
    private static final String DEV_KEY_BASE64 =
            "ZGV2LW9ubHkta2V5LWRvLW5vdC11c2UtaW4tcHJvZCE="; // 32 bytes

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCredentialCipher(CredentialProperties props) {
        String configured = props == null ? null : props.key();
        byte[] keyBytes;
        if (configured == null || configured.isBlank()) {
            log.warn("vouchq.credentials.key is not set — using an INSECURE built-in DEV key "
                    + "to encrypt source credentials. Set VOUCHQ_CREDENTIALS_KEY "
                    + "(base64 of a 32-byte key) before any real deployment.");
            keyBytes = Base64.getDecoder().decode(DEV_KEY_BASE64);
        } else {
            try {
                keyBytes = Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "vouchq.credentials.key must be valid base64", e);
            }
        }
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                    "vouchq.credentials.key must decode to 16, 24, or 32 bytes (got "
                            + keyBytes.length + ")");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never include the plaintext in the message.
            throw new CredentialCipherException("Failed to encrypt credential", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        if (!ciphertext.startsWith(PREFIX)) {
            throw new CredentialCipherException(
                    "Value is not a recognized vouchq credential ciphertext");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new CredentialCipherException("Ciphertext too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (CredentialCipherException e) {
            throw e;
        } catch (Exception e) {
            // Wrong key or tampered ciphertext: GCM tag verification fails here.
            throw new CredentialCipherException("Failed to decrypt credential", e);
        }
    }
}
