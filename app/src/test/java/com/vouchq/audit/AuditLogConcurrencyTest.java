package com.vouchq.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for MA3-109: the hash chain must stay intact under concurrent
 * appends to the same org. Without the per-org advisory lock in
 * {@link AuditLogService#append}, two writers can read the same tail entry as
 * their {@code prev_hash} and fork the chain. Runs against a real Postgres
 * (advisory locks are a DB feature) via Testcontainers.
 */
@SpringBootTest
@Testcontainers
class AuditLogConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AuditLogService auditLogService;

    @Test
    void concurrentAppendsKeepTheChainIntact() throws Exception {
        UUID org = UUID.randomUUID();
        int n = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            final int k = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    // Each call is its own @Transactional unit → real contention.
                    auditLogService.append(org, "writer-" + k, "SCAN_RUN", null,
                            "{\"i\":" + k + "}");
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown(); // release all writers at once to maximize contention
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).as("no append should fail").isEmpty();

        AuditLogService.VerifyResult result = auditLogService.verifyChain(org);
        assertThat(result.ok())
                .as("chain must verify after %d concurrent appends (broken at %s: %s)",
                        n, result.brokenEntryId(), result.reason())
                .isTrue();
    }
}
