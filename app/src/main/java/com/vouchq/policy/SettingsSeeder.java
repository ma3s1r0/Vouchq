package com.vouchq.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.ingestion.DefaultOrganization;
import com.vouchq.notify.EmailProperties;
import com.vouchq.notify.NotificationChannelEntity;
import com.vouchq.notify.NotificationChannelRepository;
import com.vouchq.notify.SlackProperties;
import com.vouchq.notify.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * One-time seed of the DB-backed settings from the legacy {@code @ConfigurationProperties}
 * (MA3-92). On first boot — when the default org has no {@code policy_rule} /
 * {@code notification_channel} rows — this copies the configured policy rules
 * ({@code vouchq.policy.rules}) and any explicitly-enabled notify channels
 * ({@code vouchq.notify.*}) into the DB, so existing config-driven behaviour and
 * the default policy rules (risk≥80 → AUTO_BLOCK, CRITICAL → HOLD) still apply out
 * of the box. Thereafter the DB is the editable source of truth and properties are
 * ignored (an empty table is only refilled if it was actually emptied).
 *
 * <p>Default-off (기획서 §7) is preserved: notify properties default to disabled,
 * so nothing is seeded and no channel exists ⇒ zero outbound.
 */
@Component
@Order(100)
public class SettingsSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsSeeder.class);

    private final DefaultOrganization defaultOrg;
    private final PolicyProperties policyProps;
    private final PolicyRuleRepository policyRules;
    private final WebhookProperties webhook;
    private final SlackProperties slack;
    private final EmailProperties email;
    private final NotificationChannelRepository channels;
    private final ObjectMapper objectMapper;

    public SettingsSeeder(DefaultOrganization defaultOrg,
                          PolicyProperties policyProps,
                          PolicyRuleRepository policyRules,
                          WebhookProperties webhook,
                          SlackProperties slack,
                          EmailProperties email,
                          NotificationChannelRepository channels,
                          ObjectMapper objectMapper) {
        this.defaultOrg = defaultOrg;
        this.policyProps = policyProps;
        this.policyRules = policyRules;
        this.webhook = webhook;
        this.slack = slack;
        this.email = email;
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID orgId = defaultOrg.ensure();
        seedPolicyRules(orgId);
        seedChannels(orgId);
    }

    private void seedPolicyRules(UUID orgId) {
        if (policyRules.countByOrgId(orgId) > 0) {
            return; // DB already owns the rules.
        }
        if (!policyProps.isEnabled() || policyProps.getRules().isEmpty()) {
            return;
        }
        int priority = 100;
        int seeded = 0;
        for (PolicyProperties.Rule r : policyProps.getRules()) {
            if (r.getAction() == null) {
                continue;
            }
            ObjectNode cond = objectMapper.createObjectNode();
            if (r.getMinRiskScore() != null) {
                cond.put("minRiskScore", r.getMinRiskScore());
            }
            if (r.getSeverity() != null && !r.getSeverity().isBlank()) {
                cond.put("severity", r.getSeverity().trim());
            }
            if (r.getFindingCategory() != null && !r.getFindingCategory().isBlank()) {
                cond.put("findingCategory", r.getFindingCategory().trim());
            }
            if (r.getNameRegex() != null && !r.getNameRegex().isBlank()) {
                cond.put("nameRegex", r.getNameRegex());
            }
            String name = r.getId() != null && !r.getId().isBlank() ? r.getId() : "rule-" + priority;
            policyRules.save(new PolicyRule(
                    UUID.randomUUID(), orgId, name, priority, cond.toString(), r.getAction(), true));
            priority += 10;
            seeded++;
        }
        if (seeded > 0) {
            log.info("Seeded {} policy rule(s) from vouchq.policy.rules into the DB (org={})", seeded, orgId);
        }
    }

    private void seedChannels(UUID orgId) {
        if (channels.countByOrgId(orgId) > 0) {
            return; // DB already owns the channels.
        }
        int seeded = 0;
        if (webhook.enabled() && notBlank(webhook.url())) {
            channels.save(new NotificationChannelEntity(UUID.randomUUID(), orgId,
                    NotificationChannelEntity.Type.WEBHOOK, "webhook", webhook.url(), "{}", true));
            seeded++;
        }
        if (slack.enabled() && notBlank(slack.webhookUrl())) {
            channels.save(new NotificationChannelEntity(UUID.randomUUID(), orgId,
                    NotificationChannelEntity.Type.SLACK, "slack", slack.webhookUrl(), "{}", true));
            seeded++;
        }
        if (email.enabled() && notBlank(email.from()) && email.to() != null && email.to().length > 0) {
            ObjectNode cfg = objectMapper.createObjectNode();
            var to = cfg.putArray("to");
            for (String addr : email.to()) {
                to.add(addr);
            }
            channels.save(new NotificationChannelEntity(UUID.randomUUID(), orgId,
                    NotificationChannelEntity.Type.EMAIL, "email", email.from(), cfg.toString(), true));
            seeded++;
        }
        if (seeded > 0) {
            log.info("Seeded {} notification channel(s) from vouchq.notify.* into the DB (org={})", seeded, orgId);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
