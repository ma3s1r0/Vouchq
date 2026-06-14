package com.vouchq.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the current request's organization to {@link CurrentOrgContext} for the
 * duration of the request (MA3-70). Resolves the org from the authenticated
 * principal via {@link OrgResolver}, then clears the binding in {@code finally}
 * so a pooled thread never carries a stale tenant into the next request.
 *
 * <p>Ordered after Spring Security (which runs at {@code SecurityProperties
 * .DEFAULT_FILTER_ORDER == -100}) so the {@link Authentication} is populated by
 * the time this filter runs. Unauthenticated/public requests leave no binding —
 * those paths perform no tenant-scoped reads.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class OrgContextFilter extends OncePerRequestFilter {

    private final OrgResolver orgResolver;

    public OrgContextFilter(OrgResolver orgResolver) {
        this.orgResolver = orgResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            orgResolver.resolve(auth).ifPresent(CurrentOrgContext::set);
            chain.doFilter(request, response);
        } finally {
            CurrentOrgContext.clear();
        }
    }
}
