package com.vouchq.notify;

/**
 * One outbound delivery channel for drift alerts (Email / Slack / Webhook).
 *
 * <p><b>Self-hosted constraint:</b> a channel adapter is only registered
 * as a Spring bean when it is explicitly enabled <em>and</em> configured via
 * properties (each adapter is {@code @ConditionalOnProperty}). When nothing is
 * configured no implementation exists, so {@link NotificationService} has an
 * empty channel list and makes zero outbound calls.
 */
public interface NotificationChannel {

    /** Short channel id for logging / identification (e.g. {@code "slack"}). */
    String name();

    /**
     * Deliver one drift notification. Implementations must not throw on transport
     * failure — {@link NotificationService} isolates channels — but may log.
     */
    void send(DriftNotification notification);
}
