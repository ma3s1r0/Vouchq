package com.vouchq.ingestion;

import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ensures a single default {@link Organization} exists for the demo / test
 * ingestion flow. Real tenancy provisioning lands later; this keeps MA3-75
 * runnable end-to-end without a UI.
 */
@Component
public class DefaultOrganization {

    public static final String SLUG = "default";

    private final OrganizationRepository organizations;

    public DefaultOrganization(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    /** Returns the default org's id, creating the org if needed. */
    @Transactional
    public UUID ensure() {
        return organizations.findBySlug(SLUG)
                .orElseGet(() -> organizations.save(
                        new Organization(UUID.randomUUID(), "Default Organization", SLUG)))
                .getId();
    }
}
