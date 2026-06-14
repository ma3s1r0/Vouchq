package com.vouchq.credentials;

/**
 * Symmetric encryption boundary for source credentials at rest (기획서 §10: source
 * tokens encrypted, never stored or logged in the clear).
 *
 * <p>Deliberately an interface so the default in-process {@link AesGcmCredentialCipher}
 * can be swapped for a KMS/Vault-backed adapter without touching callers — honouring
 * the self-hosted constraint (기획서 §7) that cloud-specific services (a managed secret
 * manager) sit behind a replaceable adapter while the standard build stays dependency-free.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #encrypt(String)} of a non-blank plaintext returns an opaque,
 *       self-describing ciphertext string safe to persist in {@code source.auth_ref}.
 *   <li>{@link #decrypt(String)} reverses it; a value not produced by this cipher
 *       (e.g. a legacy placeholder) or encrypted under a different key must fail
 *       with {@link CredentialCipherException} rather than return garbage.
 *   <li>Implementations never log the plaintext.
 * </ul>
 */
public interface CredentialCipher {

    /**
     * Encrypt {@code plaintext}, returning an opaque ciphertext string. Returns
     * {@code null} for a {@code null}/blank input so callers can treat
     * "no credential" uniformly.
     */
    String encrypt(String plaintext);

    /**
     * Decrypt a ciphertext previously produced by {@link #encrypt(String)}.
     * Returns {@code null} for a {@code null}/blank input. Throws
     * {@link CredentialCipherException} if the value is not a recognized ciphertext
     * or cannot be decrypted with the configured key.
     */
    String decrypt(String ciphertext);

    /** Raised when decryption fails (wrong key, corrupt/foreign ciphertext). */
    class CredentialCipherException extends RuntimeException {
        public CredentialCipherException(String message) {
            super(message);
        }

        public CredentialCipherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
