package com.vouchq.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Fans a {@link DriftNotification} out to every <em>enabled</em> notification
 * channel for the event's org (기획서 §5.2 알림 채널: 이메일 / Slack / Webhook).
 *
 * <p><b>DB-backed (MA3-92):</b> channels are now rows in {@code notification_channel}
 * editable at runtime via {@code /api/settings/notification-channels} — no redeploy.
 * On dispatch we load the org's enabled rows and build a live adapter per row via
 * {@link ChannelAdapterFactory}. Properties ({@code vouchq.notify.*}) only seed an
 * empty DB on first boot ({@link NotificationChannelSeeder}).
 *
 * <p><b>Default-off (기획서 §7):</b> with no enabled channel row for the org the
 * loaded list is empty, so {@link #dispatch} short-circuits and <em>no outbound
 * network call is made</em> — the self-hosted default.
 *
 * <p>Channels are isolated: a failure in one (transport error, unbuildable row)
 * is logged and does not prevent the others from being attempted.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationChannelRepository channels;
    private final ChannelAdapterFactory adapters;

    public NotificationService(NotificationChannelRepository channels, ChannelAdapterFactory adapters) {
        this.channels = channels;
        this.adapters = adapters;
    }

    /** Deliver one notification to all enabled channels for its org. No-op when none are enabled. */
    @Transactional(readOnly = true)
    public void dispatch(DriftNotification notification) {
        List<NotificationChannelEntity> enabled =
                channels.findByOrgIdAndEnabledTrueOrderByCreatedAtAsc(notification.orgId());
        if (enabled.isEmpty()) {
            return; // default-off: nothing leaves the box.
        }
        for (NotificationChannelEntity channel : enabled) {
            try {
                adapters.build(channel).send(notification);
            } catch (RuntimeException e) {
                log.warn("Notification channel '{}' ({}) failed for drift event {}: {}",
                        channel.getName(), channel.getType(), notification.driftEventId(), e.toString());
            }
        }
    }

    /**
     * Send a one-off test notification through a single channel (the
     * {@code POST /api/settings/notification-channels/{id}/test} endpoint). Unlike
     * {@link #dispatch} this surfaces build/transport failures to the caller so the
     * UI can report whether the channel works.
     */
    public void sendTest(NotificationChannelEntity channel) {
        adapters.build(channel).send(testNotification(channel.getOrgId()));
    }

    /** Number of enabled channels for an org — used by tests / diagnostics. */
    @Transactional(readOnly = true)
    public int enabledChannelCount(UUID orgId) {
        return channels.findByOrgIdAndEnabledTrueOrderByCreatedAtAsc(orgId).size();
    }

    private static DriftNotification testNotification(UUID orgId) {
        return new DriftNotification(orgId, UUID.randomUUID(), "vouchq-test-tool",
                UUID.randomUUID(), "INFO", "0".repeat(64), "1".repeat(64));
    }
}
