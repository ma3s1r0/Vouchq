package com.vouchq.verify;

import com.vouchq.api.ApiDtos;
import com.vouchq.parser.ParsedSkill;
import com.vouchq.parser.SkillParser;
import com.vouchq.registry.ApprovedVersionRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CI verify / build gate (MA3-98): given a consumer's checked-out repo, report
 * per Skill whether its <em>current</em> definition is an APPROVED + pinned
 * version in this org. This is a read-only query against the registry — vouchq
 * stays the issuer, never in the agent's data path; the CI is just another reader.
 *
 * <p>Identity is the {@code definitionHash} (the same pin anchor used everywhere):
 * the repo is parsed with the authoritative {@link SkillParser} server-side, so a
 * client never has to reproduce the hash recipe. A Skill passes only when its hash
 * is in the org's set of approved-version hashes; otherwise it is classified by
 * name for a useful CI message (CHANGED / BLOCKED / UNKNOWN).
 */
@Service
public class VerifyService {

    private final ApprovedVersionRepository approvedVersions;
    private final ToolRepository tools;
    private final SkillParser parser = new SkillParser();

    public VerifyService(ApprovedVersionRepository approvedVersions, ToolRepository tools) {
        this.approvedVersions = approvedVersions;
        this.tools = tools;
    }

    /** Parse the repo at {@code repoRoot} and verify each Skill against approved pins. */
    @Transactional(readOnly = true)
    public ApiDtos.VerifyResult verify(UUID orgId, Path repoRoot) {
        Set<String> approvedHashes = approvedVersions.findByOrgId(orgId).stream()
                .map(av -> av.getHash().toLowerCase())
                .collect(Collectors.toSet());

        List<Tool> skillTools = tools.findByOrgId(orgId).stream()
                .filter(t -> t.getKind() == Tool.Kind.SKILL)
                .toList();
        Set<String> approvedNames = skillTools.stream()
                .filter(t -> t.getStatus() == Tool.Status.APPROVED)
                .map(Tool::getName).collect(Collectors.toSet());
        Set<String> blockedNames = skillTools.stream()
                .filter(t -> t.getStatus() == Tool.Status.BLOCKED)
                .map(Tool::getName).collect(Collectors.toSet());

        List<ParsedSkill> parsed = parser.parseRepository(repoRoot);
        List<ApiDtos.VerifyItem> items = new ArrayList<>();
        int approved = 0;
        for (ParsedSkill skill : parsed) {
            String verdict = verdict(skill, approvedHashes, approvedNames, blockedNames);
            if ("APPROVED".equals(verdict)) {
                approved++;
            }
            items.add(new ApiDtos.VerifyItem(skill.name(), skill.definitionHash(), verdict));
        }
        items.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        boolean pass = !items.isEmpty() && approved == items.size();
        return new ApiDtos.VerifyResult(pass, items.size(), approved, items);
    }

    private static String verdict(ParsedSkill skill, Set<String> approvedHashes,
                                  Set<String> approvedNames, Set<String> blockedNames) {
        if (approvedHashes.contains(skill.definitionHash().toLowerCase())) {
            return "APPROVED";
        }
        if (blockedNames.contains(skill.name())) {
            return "BLOCKED";
        }
        if (approvedNames.contains(skill.name())) {
            // A Skill of this name is approved, but at a different hash — the
            // working tree diverges from the pinned version (unreviewed change).
            return "CHANGED";
        }
        return "UNKNOWN";
    }
}
