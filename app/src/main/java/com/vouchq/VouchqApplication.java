package com.vouchq;

import com.vouchq.credentials.CredentialProperties;
import com.vouchq.notify.EmailProperties;
import com.vouchq.notify.SlackProperties;
import com.vouchq.notify.WebhookProperties;
import com.vouchq.policy.PolicyProperties;
import com.vouchq.retention.RetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * vouchq — trust registry &amp; governance service for the MCP servers, Skills,
 * and Tools that AI agents depend on. Control-plane backend; depends on the OSS
 * {@code :parser} module, with internal boundaries under registry / audit / notify.
 *
 * <p>{@code @EnableScheduling} powers the periodic re-scan job (MA3-85). Notify
 * channel properties are bound here; the channel beans themselves are
 * {@code @ConditionalOnProperty} and stay OFF unless explicitly enabled.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({WebhookProperties.class, SlackProperties.class, EmailProperties.class,
        PolicyProperties.class, CredentialProperties.class, RetentionProperties.class})
public class VouchqApplication {
    public static void main(String[] args) {
        SpringApplication.run(VouchqApplication.class, args);
    }
}
