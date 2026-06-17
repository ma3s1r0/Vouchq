/**
 * <b>tenancy</b> — query-level multitenancy (MA3-70). Every
 * org-scoped entity carries Hibernate's {@code orgFilter}; {@link
 * com.vouchq.tenancy.OrgFilterAspect} enables it with the request's org
 * ({@link com.vouchq.tenancy.CurrentOrgContext}) on every persistence call, so a
 * single tenant's data can never surface in another tenant's query — even if a
 * repository finder forgets its {@code org_id} predicate.
 *
 * <p>The {@code @FilterDef} below names the filter and declares its {@code orgId}
 * (UUID) parameter once for the whole application; individual entities apply it
 * with {@code @Filter(name = "orgFilter", condition = "org_id = :orgId")}.
 */
@org.hibernate.annotations.FilterDef(
        name = com.vouchq.tenancy.OrgScoped.FILTER,
        defaultCondition = com.vouchq.tenancy.OrgScoped.CONDITION,
        parameters = @org.hibernate.annotations.ParamDef(
                name = com.vouchq.tenancy.OrgScoped.PARAM, type = java.util.UUID.class))
package com.vouchq.tenancy;
