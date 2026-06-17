package com.vouchq.verify;

import com.vouchq.api.ApiDtos;
import com.vouchq.parser.ParsedSkill;
import com.vouchq.parser.SkillParser;
import com.vouchq.registry.ApprovedVersion;
import com.vouchq.registry.ApprovedVersionRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link VerifyService} (MA3-98): a Skill passes only when its
 * definition hash matches a pinned approved version; otherwise it's classified
 * by name as CHANGED / BLOCKED / UNKNOWN.
 */
class VerifyServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Test
    void classifiesEachSkillAndPassesOnlyWhenAllApproved(@TempDir Path repo) throws IOException {
        // Four skills in the repo; one's hash will be "approved".
        writeSkill(repo, "alpha", "the alpha skill");
        writeSkill(repo, "beta", "an unregistered skill");
        writeSkill(repo, "gamma", "approved name, different body");
        writeSkill(repo, "delta", "a blocked skill");

        // alpha's exact pinned hash (computed by the real parser).
        Map<String, String> hashes = new SkillParser().parseRepository(repo).stream()
                .collect(Collectors.toMap(ParsedSkill::name, ParsedSkill::definitionHash));

        ApprovedVersionRepository approvedVersions = mock(ApprovedVersionRepository.class);
        ToolRepository tools = mock(ToolRepository.class);
        when(approvedVersions.findByOrgId(ORG)).thenReturn(List.of(
                new ApprovedVersion(UUID.randomUUID(), ORG, UUID.randomUUID(), UUID.randomUUID(),
                        hashes.get("alpha"), "admin", OffsetDateTime.now())));
        // gamma: approved Skill name but a hash we do NOT list → CHANGED.
        // delta: blocked Skill name → BLOCKED.
        when(tools.findByOrgId(ORG)).thenReturn(List.of(
                new Tool(UUID.randomUUID(), ORG, UUID.randomUUID(), Tool.Kind.SKILL, "gamma", Tool.Status.APPROVED),
                new Tool(UUID.randomUUID(), ORG, UUID.randomUUID(), Tool.Kind.SKILL, "delta", Tool.Status.BLOCKED)));

        ApiDtos.VerifyResult result = new VerifyService(approvedVersions, tools).verify(ORG, repo);

        Function<String, String> verdictOf = name -> result.items().stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow().verdict();
        assertThat(verdictOf.apply("alpha")).isEqualTo("APPROVED");
        assertThat(verdictOf.apply("beta")).isEqualTo("UNKNOWN");
        assertThat(verdictOf.apply("gamma")).isEqualTo("CHANGED");
        assertThat(verdictOf.apply("delta")).isEqualTo("BLOCKED");
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.approved()).isEqualTo(1);
        assertThat(result.pass()).isFalse();
    }

    @Test
    void passesWhenEverySkillApproved(@TempDir Path repo) throws IOException {
        writeSkill(repo, "only", "the only skill");
        Map<String, String> hashes = new SkillParser().parseRepository(repo).stream()
                .collect(Collectors.toMap(ParsedSkill::name, ParsedSkill::definitionHash));

        ApprovedVersionRepository approvedVersions = mock(ApprovedVersionRepository.class);
        ToolRepository tools = mock(ToolRepository.class);
        when(approvedVersions.findByOrgId(ORG)).thenReturn(List.of(
                new ApprovedVersion(UUID.randomUUID(), ORG, UUID.randomUUID(), UUID.randomUUID(),
                        hashes.get("only"), "admin", OffsetDateTime.now())));
        when(tools.findByOrgId(ORG)).thenReturn(List.of());

        ApiDtos.VerifyResult result = new VerifyService(approvedVersions, tools).verify(ORG, repo);
        assertThat(result.pass()).isTrue();
        assertThat(result.approved()).isEqualTo(1);
    }

    private static void writeSkill(Path repo, String name, String body) throws IOException {
        Path dir = Files.createDirectories(repo.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + body + "\n---\n# " + name + "\n");
    }
}
