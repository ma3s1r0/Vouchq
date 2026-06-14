package com.vouchq.credentials;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MA3-89 cipher: encrypt→decrypt round-trips; ciphertext is opaque (neither the
 * plaintext nor the legacy placeholder); a different key cannot decrypt it.
 */
class AesGcmCredentialCipherTest {

    private static String key() {
        byte[] k = new byte[32];
        new java.security.SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private static AesGcmCredentialCipher cipher(String base64Key) {
        return new AesGcmCredentialCipher(new CredentialProperties(base64Key));
    }

    @Test
    void roundTripRecoversPlaintext() {
        var c = cipher(key());
        String token = "ghp_supersecrettoken123";

        String ct = c.encrypt(token);

        assertThat(ct).doesNotContain(token);
        assertThat(ct).isNotEqualTo("token:in-memory");
        assertThat(ct).startsWith("enc:gcm:");
        assertThat(c.decrypt(ct)).isEqualTo(token);
    }

    @Test
    void eachEncryptionUsesAFreshIv() {
        var c = cipher(key());
        // Same plaintext -> different ciphertext (random IV), but both decrypt back.
        String a = c.encrypt("same");
        String b = c.encrypt("same");
        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("same");
        assertThat(c.decrypt(b)).isEqualTo("same");
    }

    @Test
    void blankInputIsTreatedAsNoCredential() {
        var c = cipher(key());
        assertThat(c.encrypt(null)).isNull();
        assertThat(c.encrypt("")).isNull();
        assertThat(c.encrypt("   ")).isNull();
        assertThat(c.decrypt(null)).isNull();
        assertThat(c.decrypt("")).isNull();
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String ct = cipher(key()).encrypt("ghp_secret");
        var other = cipher(key()); // independent random key

        assertThatThrownBy(() -> other.decrypt(ct))
                .isInstanceOf(CredentialCipher.CredentialCipherException.class);
    }

    @Test
    void legacyPlaceholderIsRejectedNotSilentlyReturned() {
        var c = cipher(key());
        assertThatThrownBy(() -> c.decrypt("token:in-memory"))
                .isInstanceOf(CredentialCipher.CredentialCipherException.class);
    }

    @Test
    void devKeyFallbackStillRoundTrips() {
        // No configured key -> built-in dev key (warns); must still work for dev.
        var c = cipher(null);
        String ct = c.encrypt("devtoken");
        assertThat(c.decrypt(ct)).isEqualTo("devtoken");
    }

    @Test
    void invalidKeyLengthIsRejected() {
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[10])))
                .isInstanceOf(IllegalStateException.class);
    }
}
