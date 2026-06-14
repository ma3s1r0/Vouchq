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
 * Generic webhook channel: POSTs a JSON drift payload to a configured URL using
 * the JDK {@link HttpClient} (no extra dependency — 기획서 §7 표준 의존성).
 *
 * <p>A plain stateless adapter (MA3-92): built per-send from a DB channel row by
 * {@link ChannelAdapterFactory}, not a Spring bean. With no enabled channel row
 * {@link NotificationService} builds none, so no URL is ever contacted (default-off).
 */
public class WebhookNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationChannel.class);

    private final String url;
    private final ObjectMapper objectMapper;
    private final HttpPoster poster;

    public WebhookNotificationChannel(WebhookProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, defaultPoster());
    }

    /** Test seam: inject a stub transport so unit tests make no real HTTP call. */
    WebhookNotificationChannel(WebhookProperties props, ObjectMapper objectMapper, HttpPoster poster) {
        if (props.url() == null || props.url().isBlank()) {
            throw new IllegalStateException(
                    "vouchq.notify.webhook.enabled=true but vouchq.notify.webhook.url is not set");
        }
        this.url = props.url();
        this.objectMapper = objectMapper;
        this.poster = poster;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void send(DriftNotification n) {
        poster.post(url, "application/json", payload(n));
    }

    String payload(DriftNotification n) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("event", "drift_detected");
        body.put("summary", n.summary());
        body.put("orgId", str(n.orgId()));
        body.put("toolId", str(n.toolId()));
        body.put("toolName", n.toolName());
        body.put("driftEventId", str(n.driftEventId()));
        body.put("severity", n.severity());
        body.put("approvedHash", n.approvedHash());
        body.put("observedHash", n.observedHash());
        return body.toString();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
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
                    log.warn("Webhook POST to {} returned {}", url, resp.statusCode());
                }
            } catch (Exception e) {
                throw new RuntimeException("Webhook POST failed: " + e.getMessage(), e);
            }
        };
    }
}
