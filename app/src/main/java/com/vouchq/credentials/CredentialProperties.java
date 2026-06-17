package com.vouchq.credentials;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vouchq.credentials.*}. The {@code key} is a base64-encoded AES key
 * (16/24/32 bytes → AES-128/192/256) used by {@link AesGcmCredentialCipher} to
 * encrypt source tokens at rest. Override in any real deployment via
 * the {@code VOUCHQ_CREDENTIALS_KEY} env var; the dev fallback is logged loudly so
 * it can never be mistaken for production-safe.
 */
@ConfigurationProperties(prefix = "vouchq.credentials")
public record CredentialProperties(String key) {
}
