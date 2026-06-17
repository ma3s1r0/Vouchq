package com.vouchq.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSender;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a persisted {@link NotificationChannelEntity} into a live, send-capable
 * {@link NotificationChannel} adapter (MA3-92). Each call builds a fresh stateless
 * adapter from the row's {@code target} + jsonb {@code config}, reusing the
 * existing Webhook/Slack/Email adapters (MA3-85) so the wire format is unchanged.
 *
 * <p>The JDK {@link HttpClient} (no extra dependency) is shared across
 * webhook/Slack adapters. Email needs a Spring {@link MailSender}; if none is
 * configured ({@code spring.mail.*} absent) an EMAIL channel cannot be built and
 * {@link #build} throws — surfaced as a clear error rather than a silent no-op.
 */
@Component
public class ChannelAdapterFactory {

    private final ObjectMapper objectMapper;
    private final MailSender mailSender; // null when spring.mail.* is not configured
    private final HttpPoster poster;

    @Autowired
    public ChannelAdapterFactory(ObjectMapper objectMapper,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 MailSender mailSender) {
        this(objectMapper, mailSender, defaultPoster());
    }

    /** Test seam: inject a stub transport so unit tests make no real HTTP call. */
    ChannelAdapterFactory(ObjectMapper objectMapper, MailSender mailSender, HttpPoster poster) {
        this.objectMapper = objectMapper;
        this.mailSender = mailSender;
        this.poster = poster;
    }

    /**
     * Build a send-capable adapter for one channel row.
     *
     * @throws IllegalStateException if the row is unbuildable (e.g. EMAIL with no
     *         {@link MailSender}, or a missing target) — used by the API test call.
     */
    public NotificationChannel build(NotificationChannelEntity channel) {
        if (channel.getTarget() == null || channel.getTarget().isBlank()) {
            throw new IllegalStateException("Channel '" + channel.getName() + "' has no target set");
        }
        return switch (channel.getType()) {
            case WEBHOOK -> new WebhookNotificationChannel(
                    new WebhookProperties(true, channel.getTarget()), objectMapper, poster);
            case SLACK -> new SlackNotificationChannel(
                    new SlackProperties(true, channel.getTarget()), objectMapper, poster);
            case EMAIL -> {
                if (mailSender == null) {
                    throw new IllegalStateException(
                            "EMAIL channel '" + channel.getName()
                                    + "' requires spring.mail.* (no MailSender configured)");
                }
                String[] to = emailRecipients(channel);
                yield new EmailNotificationChannel(
                        mailSender, new EmailProperties(true, channel.getTarget(), to));
            }
        };
    }

    /** Parse {@code config.to} (array or single string) into the recipient list. */
    private String[] emailRecipients(NotificationChannelEntity channel) {
        List<String> recipients = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(channel.getConfig());
            JsonNode to = root.get("to");
            if (to != null && to.isArray()) {
                to.forEach(n -> recipients.add(n.asText()));
            } else if (to != null && to.isTextual()) {
                recipients.add(to.asText());
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "EMAIL channel '" + channel.getName() + "' has an invalid config JSON");
        }
        if (recipients.isEmpty()) {
            throw new IllegalStateException(
                    "EMAIL channel '" + channel.getName() + "' has no recipients (config.to)");
        }
        return recipients.toArray(new String[0]);
    }

    private static HttpPoster defaultPoster() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, contentType, body) -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("POST to " + url + " returned " + resp.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new UncheckedIOException(new IOException("POST failed: " + e.getMessage(), e));
            }
        };
    }
}
