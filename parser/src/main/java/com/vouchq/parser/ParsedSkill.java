package com.vouchq.parser;

import java.util.List;

/**
 * A parsed Skill: display metadata, its files (content + per-file hash), and a
 * stable {@code definitionHash} over the canonical definition.
 *
 * <p>The {@code definitionHash} is the pin (박제) anchor: any change to the name,
 * description, or the content of any file changes it. Comparing a re-parsed hash
 * against the pinned one is how drift (rug-pull) is detected downstream.
 *
 * @param name           skill name (from {@code SKILL.md} frontmatter)
 * @param description    skill description (from frontmatter)
 * @param files          all files in the skill, sorted by path
 * @param definitionHash hex SHA-256 of the canonical definition
 */
public record ParsedSkill(
        String name,
        String description,
        List<SkillFile> files,
        String definitionHash) {}
