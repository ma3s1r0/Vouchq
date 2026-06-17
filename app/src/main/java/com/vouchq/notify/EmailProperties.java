package com.vouchq.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vouchq.notify.email.*}. Disabled by default: with
 * {@code enabled=false} no {@link EmailNotificationChannel} bean is created.
 * SMTP transport itself is configured via the standard {@code spring.mail.*}.
 */
@ConfigurationProperties(prefix = "vouchq.notify.email")
public record EmailProperties(boolean enabled, String from, String[] to) {
}
