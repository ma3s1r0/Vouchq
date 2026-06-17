package com.vouchq.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the organization that the current unit of work is scoped to (MA3-70).
 * The value is set once per request (or per test/bootstrap
 * unit of work) and read by {@link OrgFilterAspect} to enable Hibernate's
 * {@code orgFilter} on every session, so a forgotten {@code org_id} predicate in
 * a repository finder cannot leak another tenant's rows.
 *
 * <p>Implementation: a plain {@link ThreadLocal}. A request lives on one thread,
 * and {@link OrgContextFilter} clears it in a {@code finally} so threads in the
 * Tomcat pool never carry a stale tenant. Non-web paths (the {@code demo}
 * internal controllers run on request threads; tests, bootstrap) use
 * {@link #runAs(UUID, Runnable)} / {@link #callAs} to scope a block explicitly.
 */
public final class CurrentOrgContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CurrentOrgContext() {}

    /** Bind the current thread to {@code orgId} for the rest of this unit of work. */
    public static void set(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("orgId must not be null");
        }
        CURRENT.set(orgId);
    }

    /** The bound org, or empty if none is set (e.g. an unauthenticated path). */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The bound org or throw — use where a tenant is required to proceed. */
    public static UUID require() {
        UUID orgId = CURRENT.get();
        if (orgId == null) {
            throw new IllegalStateException("No current organization bound to this request");
        }
        return orgId;
    }

    /** Clear the binding. Always called by the request filter in a {@code finally}. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Run {@code body} with {@code orgId} bound, restoring the prior binding
     * afterwards. For non-request units of work (tests, bootstrap).
     */
    public static void runAs(UUID orgId, Runnable body) {
        callAs(orgId, () -> {
            body.run();
            return null;
        });
    }

    /** {@link #runAs} with a return value. */
    public static <T> T callAs(UUID orgId, java.util.function.Supplier<T> body) {
        UUID prior = CURRENT.get();
        CURRENT.set(orgId);
        try {
            return body.get();
        } finally {
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
            }
        }
    }
}
