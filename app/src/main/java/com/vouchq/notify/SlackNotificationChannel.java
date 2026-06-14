package com.vouchq.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Slack channel: POSTs a Slack <em>incoming webhook</em> message
 * ({@code {"text": ...}}) to a configured webhook URL via the JDK HttpClient.
 *
 * <p>A plain stateless adapter (MA3-92): built per-send from a DB channel row by
 * {@link ChannelAdapterFactory}, not a Spring bean.
 */
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationChannel.class);

    private final String webhookUrl;
    private final ObjectMapper objectMapper;
    private final HttpPoster poster;

    public SlackNotificationChannel(SlackProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, defaultPoster());
    }

    /** Test seam: inject a stub transport so unit tests make no real HTTP call. */
    SlackNotificationChannel(SlackProperties props, ObjectMapper objectMapper, HttpPoster poster) {
        if (props.webhookUrl() == null || props.webhookUrl().isBlank()) {
            throw new IllegalStateException(
                    "vouchq.notify.slack.enabled=true but vouchq.notify.slack.webhook-url is not set");
        }
        this.webhookUrl = props.webhookUrl();
        this.objectMapper = objectMapper;
        this.poster = poster;
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public void send(DriftNotification n) {
        poster.post(webhookUrl, "application/json", payload(n));
    }

    String payload(DriftNotification n) {
        String text = ":rotating_light: " + n.summary()
                + "\n• tool: `" + n.toolName() + "` (" + n.toolId() + ")"
                + "\n• severity: *" + n.severity() + "*"
                + "\n• approved → observed: `" + shortHash(n.approvedHash())
                + "` → `" + shortHash(n.observedHash()) + "`";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        return body.toString();
    }

    private static String shortHash(String hash) {
        if (hash == null) {
            return "?";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
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
                    log.warn("Slack webhook POST returned {}", resp.statusCode());
                }
            } catch (Exception e) {
                throw new RuntimeException("Slack webhook POST failed: " + e.getMessage(), e);
            }
        };
    }
}
