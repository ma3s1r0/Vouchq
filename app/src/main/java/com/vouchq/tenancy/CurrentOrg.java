package com.vouchq.tenancy;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Injectable accessor for the request's organization (MA3-93). The {@code /api}
 * controllers resolve their {@code org_id} through this instead of the bootstrap
 * {@code DefaultOrganization}, so every read/write is scoped to the
 * <em>authenticated user's</em> org (bound by {@link OrgContextFilter} from
 * {@link OrgResolver}). This makes the explicit controller {@code org_id} agree
 * with the Hibernate {@code orgFilter}, giving end-to-end cross-org isolation
 * through real auth — not just the single default tenant.
 */
@Component
public class CurrentOrg {

    /** The authenticated user's org, or throw if none is bound to the request. */
    public UUID require() {
        return CurrentOrgContext.require();
    }
}
