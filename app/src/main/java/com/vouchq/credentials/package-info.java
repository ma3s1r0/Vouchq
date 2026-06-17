/**
 * Credential-at-rest encryption. {@link com.vouchq.credentials.CredentialCipher}
 * is the replaceable boundary; {@link com.vouchq.credentials.AesGcmCredentialCipher}
 * is the dependency-free JDK default. Source tokens are encrypted here before being
 * persisted in {@code source.auth_ref} and decrypted only transiently for re-fetch.
 */
package com.vouchq.credentials;
