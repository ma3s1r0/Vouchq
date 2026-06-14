package com.vouchq.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A normalized MCP tool definition — one entry from an MCP server's
 * {@code tools/list} result: {@code {name, description, inputSchema}}.
 *
 * <p>This is the MCP analogue of {@code ParsedSkill} (기획서 §5): the collection
 * layer differs (JSON-RPC over HTTP instead of a Git clone + Skill parse) but the
 * output is a normalized definition + a stable {@code definitionHash}, so the
 * registry / scan / pin (박제) / drift pipeline downstream is reused unchanged.
 *
 * <p>The hash recipe mirrors the Skill parser's: canonical JSON with keys in a
 * fixed top-level order ({@code name}, {@code description}, {@code inputSchema})
 * and the {@code inputSchema} object key-sorted recursively, then compact-
 * serialized and SHA-256'd. Same definition in → same digest out, regardless of
 * the order the server emitted its JSON keys.
 *
 * @param name        tool name (the {@code tools/list} entry's {@code name})
 * @param description tool description (may be empty)
 * @param inputSchema the JSON Schema for the tool's arguments (may be a null
 *                    node), captured verbatim then canonicalized for hashing
 */
public record McpToolDefinition(String name, String description, JsonNode inputSchema) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Build a definition from a single {@code tools/list} entry node, normalizing
     * missing fields ({@code description} → "", {@code inputSchema} → null node).
     *
     * @throws IllegalArgumentException if the entry has no usable {@code name}
     */
    public static McpToolDefinition fromToolNode(JsonNode tool) {
        if (tool == null || !tool.hasNonNull("name") || tool.get("name").asText().isBlank()) {
            throw new IllegalArgumentException("tools/list entry missing 'name'");
        }
        String name = tool.get("name").asText();
        String description = tool.hasNonNull("description") ? tool.get("description").asText() : "";
        JsonNode inputSchema = tool.has("inputSchema") ? tool.get("inputSchema") : NullNode.getInstance();
        return new McpToolDefinition(name, description, inputSchema);
    }

    /**
     * The canonical JSON serialization used both for the persisted {@code definition}
     * column and as the hash pre-image: fixed top-level key order, recursively
     * key-sorted {@code inputSchema}, compact (no whitespace).
     */
    public String canonicalJson() {
        ObjectNode canonical = MAPPER.createObjectNode();
        canonical.put("name", name);
        canonical.put("description", description == null ? "" : description);
        canonical.set("inputSchema",
                canonicalize(inputSchema == null ? NullNode.getInstance() : inputSchema));
        try {
            return MAPPER.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize MCP tool " + name, e);
        }
    }

    /** Hex SHA-256 over {@link #canonicalJson()} — the pin (박제) / drift anchor. */
    public String definitionHash() {
        return sha256Hex(canonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    /** Recursively rewrite object nodes with keys in sorted order; arrays keep order. */
    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            node.properties().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> sorted.set(e.getKey(), canonicalize(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var arr = MAPPER.createArrayNode();
            node.forEach(child -> arr.add(canonicalize(child)));
            return arr;
        }
        return node;
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
