package com.vouchq.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vouchq.notify.slack.*}. Disabled by default (기획서 §7): with
 * {@code enabled=false} no {@link SlackNotificationChannel} bean is created.
 */
@ConfigurationProperties(prefix = "vouchq.notify.slack")
public record SlackProperties(boolean enabled, String webhookUrl) {
}
