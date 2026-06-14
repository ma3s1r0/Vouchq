package com.vouchq.notify;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSender;

/**
 * Email channel: sends a plain-text drift alert via Spring's {@link MailSender}
 * (spring-boot-starter-mail).
 *
 * <p>A plain stateless adapter (MA3-92): built from a DB channel row by
 * {@link ChannelAdapterFactory} only when a {@link MailSender} ({@code spring.mail.*})
 * is configured; otherwise the channel is unbuildable and is skipped.
 */
public class EmailNotificationChannel implements NotificationChannel {

    private final MailSender mailSender;
    private final EmailProperties props;

    public EmailNotificationChannel(MailSender mailSender, EmailProperties props) {
        if (props.from() == null || props.from().isBlank()
                || props.to() == null || props.to().length == 0) {
            throw new IllegalStateException(
                    "vouchq.notify.email.enabled=true but vouchq.notify.email.from / .to are not set");
        }
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(DriftNotification n) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.from());
        msg.setTo(props.to());
        msg.setSubject(n.summary());
        msg.setText(body(n));
        mailSender.send(msg);
    }

    String body(DriftNotification n) {
        return n.summary() + "\n\n"
                + "Tool: " + n.toolName() + " (" + n.toolId() + ")\n"
                + "Org: " + n.orgId() + "\n"
                + "Severity: " + n.severity() + "\n"
                + "Drift event: " + n.driftEventId() + "\n"
                + "Approved hash: " + n.approvedHash() + "\n"
                + "Observed hash: " + n.observedHash() + "\n";
    }
}
