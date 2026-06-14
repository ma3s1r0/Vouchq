package com.vouchq.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Central enforcement of tenant isolation (MA3-70, 기획서 §10). Wraps every Spring
 * Data repository call and, when an org is bound to the current unit of work
 * ({@link CurrentOrgContext}), enables Hibernate's {@code orgFilter} on the
 * current {@link Session} with that org's id.
 *
 * <p>Why an aspect over the repository layer rather than a servlet filter: with
 * {@code open-in-view: false} the Hibernate {@link Session} is opened lazily
 * inside each {@code @Transactional} boundary — there is no session to configure
 * at the HTTP filter stage. Every read, by contrast, flows through a Spring Data
 * repository method, and {@link EntityManager#unwrap(Class)} resolves the live
 * session for whatever transaction is active. Enabling the filter here therefore
 * covers {@code findById}, derived queries, JPQL, and lazy collection loads
 * uniformly. A finder that forgets its {@code org_id} predicate still cannot leak
 * cross-tenant rows, because the SQL Hibernate emits always carries the filter's
 * {@code AND org_id = ?}.
 *
 * <p>When no org is bound (e.g. an unauthenticated/public path, or a deliberate
 * cross-org bootstrap) the filter is left disabled and queries are unscoped — the
 * {@link OrgContextFilter} only binds an org for authenticated API requests.
 */
@Aspect
@Component
public class OrgFilterAspect {

    private static final Logger log = LoggerFactory.getLogger(OrgFilterAspect.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Advise all methods on Spring Data JPA repositories. The pointcut targets
     * the framework interface so it catches every concrete repository without
     * enumerating them.
     */
    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object enableOrgFilter(ProceedingJoinPoint pjp) throws Throwable {
        UUID orgId = CurrentOrgContext.current().orElse(null);
        if (orgId == null) {
            return pjp.proceed();
        }
        Session session = entityManager.unwrap(Session.class);
        Filter existing = session.getEnabledFilter(OrgScoped.FILTER);
        if (existing == null) {
            session.enableFilter(OrgScoped.FILTER).setParameter(OrgScoped.PARAM, orgId);
            if (log.isTraceEnabled()) {
                log.trace("orgFilter enabled for org={}", orgId);
            }
        }
        return pjp.proceed();
    }
}
