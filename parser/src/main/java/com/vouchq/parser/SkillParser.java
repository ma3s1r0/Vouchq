package com.vouchq.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses Claude Code Skill folders into normalized {@link ParsedSkill} objects.
 * A Skill folder is any directory containing a {@code SKILL.md}.
 *
 * <p>The MCP tool parser (Phase 1) will produce the same {@link ParsedSkill}
 * shape so the registry / scan / pin / drift pipeline is reused unchanged.
 */
public final class SkillParser {

    public static final String SKILL_FILE = "SKILL.md";

    /** Leading YAML frontmatter fenced by {@code ---} lines (optional BOM). */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A\\uFEFF?---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", Pattern.DOTALL);

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Parse a single Skill folder.
     *
     * @throws IllegalArgumentException if the folder has no {@code SKILL.md}
     */
    public ParsedSkill parseSkill(Path skillDir) {
        Path skillMd = skillDir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            throw new IllegalArgumentException("No " + SKILL_FILE + " in " + skillDir);
        }

        Map<String, Object> frontmatter = parseFrontmatter(readUtf8(skillMd));
        String name = stringOr(frontmatter.get("name"), skillDir.getFileName().toString());
        String description = stringOr(frontmatter.get("description"), "");

        List<SkillFile> files = collectFiles(skillDir);
        return new ParsedSkill(name, description, files, definitionHash(name, description, files));
    }

    /**
     * Walk a repository and parse every Skill folder (one per {@code SKILL.md}).
     * Results are ordered by skill name for deterministic output.
     */
    public List<ParsedSkill> parseRepository(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(SkillParser::isSkillManifest)
                    .map(p -> parseSkill(p.getParent()))
                    .sorted(Comparator.comparing(ParsedSkill::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    // --- internals --------------------------------------------------------

    private static boolean isSkillManifest(Path p) {
        Path fileName = p.getFileName();
        return fileName != null && SKILL_FILE.equals(fileName.toString()) && Files.isRegularFile(p);
    }

    private List<SkillFile> collectFiles(Path skillDir) {
        try (Stream<Path> walk = Files.walk(skillDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(p -> toSkillFile(skillDir, p))
                    .sorted(Comparator.comparing(SkillFile::path))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill dir " + skillDir, e);
        }
    }

    private SkillFile toSkillFile(Path skillDir, Path file) {
        byte[] bytes = readBytes(file);
        String relative = skillDir.relativize(file).toString().replace('\\', '/');
        return new SkillFile(relative, sha256Hex(bytes), new String(bytes, StandardCharsets.UTF_8));
    }

    Map<String, Object> parseFrontmatter(String markdown) {
        Matcher m = FRONTMATTER.matcher(markdown);
        if (!m.find()) {
            return Map.of();
        }
        Object loaded = new Yaml().load(m.group(1));
        if (!(loaded instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * Stable SHA-256 over the canonical definition: keys in fixed order, files
     * sorted by path, each file reduced to {@code {path, sha256}} (content is
     * captured via its hash). Same definition in → same digest out, regardless
     * of OS, filesystem order, or run.
     */
    String definitionHash(String name, String description, List<SkillFile> files) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("name", name);
        canonical.put("description", description);
        canonical.put("files", files.stream()
                .sorted(Comparator.comparing(SkillFile::path))
                .map(f -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("path", f.path());
                    entry.put("sha256", f.sha256());
                    return entry;
                })
                .toList());
        try {
            return sha256Hex(json.writeValueAsBytes(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize definition", e);
        }
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String readUtf8(Path p) {
        return new String(readBytes(p), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + p, e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
