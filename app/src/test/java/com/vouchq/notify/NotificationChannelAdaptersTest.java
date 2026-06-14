package com.vouchq.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Each adapter formats + posts correctly against a stub transport — no real
 * network send / SMTP connection is made.
 */
class NotificationChannelAdaptersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static DriftNotification sample() {
        return new DriftNotification(UUID.randomUUID(), UUID.randomUUID(), "greeter",
                UUID.randomUUID(), "CRITICAL",
                "approvedhash0000", "observedhash1111");
    }

    @Test
    void webhookPostsJsonPayloadToConfiguredUrl() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpPoster stub = (u, ct, b) -> { url.set(u); body.set(b); };

        WebhookNotificationChannel channel = new WebhookNotificationChannel(
                new WebhookProperties(true, "https://example.test/hook"), mapper, stub);

        DriftNotification n = sample();
        channel.send(n);

        assertThat(channel.name()).isEqualTo("webhook");
        assertThat(url.get()).isEqualTo("https://example.test/hook");
        JsonNode json = mapper.readTree(body.get());
        assertThat(json.path("event").asText()).isEqualTo("drift_detected");
        assertThat(json.path("toolName").asText()).isEqualTo("greeter");
        assertThat(json.path("severity").asText()).isEqualTo("CRITICAL");
        assertThat(json.path("driftEventId").asText()).isEqualTo(n.driftEventId().toString());
    }

    @Test
    void webhookEnabledWithoutUrlFailsFast() {
        assertThatThrownBy(() -> new WebhookNotificationChannel(
                new WebhookProperties(true, "  "), mapper, (u, ct, b) -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void slackPostsTextMessageToWebhookUrl() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpPoster stub = (u, ct, b) -> { url.set(u); body.set(b); };

        SlackNotificationChannel channel = new SlackNotificationChannel(
                new SlackProperties(true, "https://hooks.slack.test/abc"), mapper, stub);

        channel.send(sample());

        assertThat(channel.name()).isEqualTo("slack");
        assertThat(url.get()).isEqualTo("https://hooks.slack.test/abc");
        JsonNode json = mapper.readTree(body.get());
        assertThat(json.has("text")).isTrue();
        assertThat(json.path("text").asText())
                .contains("greeter")
                .contains("CRITICAL")
                .contains("approvedhash"); // short hash prefix
    }

    @Test
    void emailSendsFormattedMessageViaMailSender() {
        MailSender mailSender = mock(MailSender.class);
        EmailNotificationChannel channel = new EmailNotificationChannel(mailSender,
                new EmailProperties(true, "alerts@vouchq.test", new String[]{"sec@vouchq.test"}));

        DriftNotification n = sample();
        channel.send(n);

        assertThat(channel.name()).isEqualTo("email");
        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getFrom()).isEqualTo("alerts@vouchq.test");
        assertThat(msg.getTo()).containsExactly("sec@vouchq.test");
        assertThat(msg.getSubject()).contains("greeter").contains("CRITICAL");
        assertThat(msg.getText()).contains(n.driftEventId().toString());
    }

    @Test
    void emailEnabledWithoutRecipientsFailsFast() {
        assertThatThrownBy(() -> new EmailNotificationChannel(mock(MailSender.class),
                new EmailProperties(true, "from@vouchq.test", new String[]{})))
                .isInstanceOf(IllegalStateException.class);
    }
}
