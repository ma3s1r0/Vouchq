/**
 * <b>api</b> — public REST surface (MA3-80): sources, servers, tools,
 * approve/block, drift events, audit logs, dashboard. Controllers map domain
 * entities to request/response DTOs ({@link com.vouchq.api.ApiDtos}); JPA
 * entities are never serialized directly.
 *
 * <p>Org context is resolved via {@code DefaultOrganization.ensure()}.
 * Query-level multitenancy enforcement is MA3-70 (out of scope here). Spring
 * Security RBAC (MA3-71) is enforced centrally in
 * {@code com.vouchq.security.SecurityConfig}: GET open to any authenticated role,
 * mutating calls require MEMBER or ADMIN.
 */
package com.vouchq.api;
