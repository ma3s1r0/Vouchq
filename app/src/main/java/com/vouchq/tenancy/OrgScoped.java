package com.vouchq.tenancy;

/**
 * Shared constants for the Hibernate tenant filter (MA3-70). The filter named
 * {@link #FILTER} restricts every org-scoped entity to rows whose {@code org_id}
 * equals the {@link #PARAM} parameter; it is declared via {@code @FilterDef} in
 * {@code package-info.java} and applied per entity with {@code @Filter}.
 */
public final class OrgScoped {

    /** Hibernate filter name. */
    public static final String FILTER = "orgFilter";

    /** Filter parameter name (bound to the current org). */
    public static final String PARAM = "orgId";

    /** Filter condition; {@code org_id} matches the column on every scoped table. */
    public static final String CONDITION = "org_id = :" + PARAM;

    private OrgScoped() {}
}
