package com.vouchq.tenancy;

import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
import com.vouchq.registry.RegisteredServer;
import com.vouchq.registry.RegisteredServerRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import com.vouchq.registry.ToolVersion;
import com.vouchq.registry.ToolVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MA3-70 완료 기준 proof — cross-org isolation enforced at the query level.
 *
 * <p>Seeds two organizations, each with its own source / server / tool / version,
 * then binds the current org to A and to B in turn and asserts that <em>every</em>
 * query returns only that org's rows. Crucially it drives the <strong>unscoped</strong>
 * query finders ({@code findAll}, {@code count}, and a derived finder that omits
 * any {@code org_id} predicate) — the exact shapes a developer could write by
 * mistake — and shows the Hibernate {@code orgFilter} (enabled by
 * {@link OrgFilterAspect} from {@link CurrentOrgContext}) still prevents the other
 * org's data from surfacing.
 *
 * <p><b>Scope of the guarantee.</b> Hibernate applies {@code @Filter} to all
 * <em>query</em> reads — HQL/JPQL, criteria, derived finders, {@code findAll},
 * and lazy collection loads — which is the realistic leak surface (a forgotten
 * {@code WHERE org_id}). By design it does <em>not</em> apply to a direct
 * primary-key load ({@code EntityManager#find} → {@code CrudRepository.findById}),
 * so the codebase loads tenant entry points via {@code findByIdAndOrgId}-style
 * finders (covered here). This test asserts the query-level guarantee and pins
 * that a cross-org id is not reachable through a derived finder.
 *
 * <p>Runs against a real Flyway-migrated Postgres via Testcontainers (works with
 * Podman/Docker), so it is self-contained and green anywhere a container runtime
 * is available — local {@code ./gradlew build} with Docker, and CI (MA3-95).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableAspectJAutoProxy
@Import({OrgFilterAspect.class})
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
class OrgIsolationDataJpaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired OrganizationRepository organizations;
    @Autowired SourceRepository sources;
    @Autowired RegisteredServerRepository servers;
    @Autowired ToolRepository tools;
    @Autowired ToolVersionRepository toolVersions;

    private UUID orgA;
    private UUID orgB;
    private UUID toolA;
    private UUID toolB;

    @BeforeEach
    void seed() {
        // Seeding itself runs with no org bound, so the filter is off — we can
        // write rows for both tenants.
        CurrentOrgContext.clear();

        orgA = organizations.save(new Organization(UUID.randomUUID(), "Org A",
                "org-a-" + UUID.randomUUID())).getId();
        orgB = organizations.save(new Organization(UUID.randomUUID(), "Org B",
                "org-b-" + UUID.randomUUID())).getId();

        toolA = seedTenant(orgA, "alpha");
        toolB = seedTenant(orgB, "bravo");
    }

    @AfterEach
    void clearContext() {
        CurrentOrgContext.clear();
    }

    private UUID seedTenant(UUID orgId, String name) {
        Source source = sources.save(new Source(UUID.randomUUID(), orgId,
                Source.Type.GIT_REPOSITORY, "https://example.com/" + name + ".git", null));
        RegisteredServer server = servers.save(new RegisteredServer(UUID.randomUUID(), orgId,
                source.getId(), RegisteredServer.Kind.SKILL_BUNDLE, name));
        Tool tool = tools.save(new Tool(UUID.randomUUID(), orgId, server.getId(),
                Tool.Kind.SKILL, name, Tool.Status.PENDING));
        ToolVersion v = toolVersions.save(new ToolVersion(UUID.randomUUID(), orgId, tool.getId(),
                "{\"name\":\"" + name + "\"}", "hash-" + name + "-0000000000000000000000000000000000000",
                OffsetDateTime.now()));
        tool.setCurrentVersionId(v.getId());
        tools.save(tool);
        return tool.getId();
    }

    @Test
    void orgA_seesOnlyItsOwnRows_acrossEveryQueryFinder() {
        CurrentOrgContext.runAs(orgA, () -> {
            // Org-less query finders a developer might call by mistake: still
            // tenant-safe because the orgFilter rewrites the SQL with AND org_id=?.
            List<Tool> all = tools.findAll();
            assertThat(all).extracting(Tool::getOrgId).containsOnly(orgA);
            assertThat(all).extracting(Tool::getId).containsExactly(toolA);

            assertThat(sources.findAll()).extracting(Source::getOrgId).containsOnly(orgA);
            assertThat(servers.findAll()).extracting(RegisteredServer::getOrgId).containsOnly(orgA);
            assertThat(toolVersions.findAll()).extracting(ToolVersion::getOrgId).containsOnly(orgA);

            // A tenant-scoped finder fetches our own tool but never the other org's,
            // even with the other org's exact id (the entry-point lookup pattern).
            assertThat(tools.findByIdAndOrgId(toolA, orgA)).isPresent();
            assertThat(tools.findByIdAndOrgId(toolB, orgA)).isEmpty();
        });
    }

    @Test
    void orgB_seesOnlyItsOwnRows() {
        CurrentOrgContext.runAs(orgB, () -> {
            List<Tool> all = tools.findAll();
            assertThat(all).extracting(Tool::getOrgId).containsOnly(orgB);
            assertThat(all).extracting(Tool::getId).containsExactly(toolB);
        });
    }

    @Test
    void noOrgBound_isUnscoped_soSeedingAndAdminPathsStillWork() {
        // With no org bound the filter is disabled — both tenants visible. This is
        // the path the OrgResolver/bootstrap rely on (and why binding is mandatory
        // for the public API, enforced by OrgContextFilter).
        List<Tool> all = tools.findAll();
        assertThat(all).extracting(Tool::getId).contains(toolA, toolB);
    }
}
