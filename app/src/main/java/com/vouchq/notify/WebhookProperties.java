package com.vouchq.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vouchq.notify.webhook.*}. Disabled by default: with
 * {@code enabled=false} no {@link WebhookNotificationChannel} bean is created.
 */
@ConfigurationProperties(prefix = "vouchq.notify.webhook")
public record WebhookProperties(boolean enabled, String url) {
}
