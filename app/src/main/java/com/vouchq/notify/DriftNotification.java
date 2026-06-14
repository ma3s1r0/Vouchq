package com.vouchq.notify;

import java.util.UUID;

/**
 * A self-contained, channel-agnostic description of a newly detected drift event
 * (기획서 §5.2 알림 채널). Built by the registry/ingestion layer and handed to
 * {@link NotificationService}; adapters render it into their own wire format.
 *
 * <p>Deliberately a flat record of already-resolved values (no entities) so the
 * notify module stays free of JPA/registry types and can be unit-tested in
 * isolation against a stub transport.
 */
public record DriftNotification(
        UUID orgId,
        UUID toolId,
        String toolName,
        UUID driftEventId,
        String severity,
        String approvedHash,
        String observedHash) {

    /** One-line human summary used as a subject / fallback body. */
    public String summary() {
        return "[vouchq] Drift detected on \"" + toolName + "\" (severity " + severity + ")";
    }
}
