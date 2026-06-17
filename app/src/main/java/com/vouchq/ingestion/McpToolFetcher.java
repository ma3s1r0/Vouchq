package com.vouchq.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects to an MCP server endpoint and calls {@code tools/list}. MCP is JSON-RPC 2.0 over Streamable HTTP, so this POSTs a single
 * {@code {"jsonrpc":"2.0","id":1,"method":"tools/list"}} request and parses the
 * {@code result.tools[]} array into normalized {@link McpToolDefinition}s.
 *
 * <p>Uses the JDK's {@link HttpClient} and Jackson only (no new dependency, no
 * framework coupling on the wire). An optional bearer token is kept in memory and
 * sent as an {@code Authorization} header; it is never persisted or logged
 *.
 */
@Component
public class McpToolFetcher {

    private static final Logger log = LoggerFactory.getLogger(McpToolFetcher.class);

    /** MCP Streamable HTTP requires the client to accept both JSON and SSE. */
    private static final String ACCEPT = "application/json, text/event-stream";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    @Autowired
    public McpToolFetcher(ObjectMapper objectMapper) {
        this(objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(30));
    }

    McpToolFetcher(ObjectMapper objectMapper, HttpClient httpClient, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    /**
     * Call {@code tools/list} on the given MCP server URL and return the
     * normalized tool definitions. The {@code bearerToken} (if non-blank) is sent
     * as an {@code Authorization: Bearer ...} header and is otherwise untouched.
     */
    public List<McpToolDefinition> listTools(String serverUrl, String bearerToken) {
        String body;
        try {
            body = objectMapper.writeValueAsString(
                    objectMapper.createObjectNode()
                            .put("jsonrpc", "2.0")
                            .put("id", 1)
                            .put("method", "tools/list"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build tools/list request", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Never include the token; log only the (safe) URL.
            throw new McpFetchException("MCP tools/list request to " + serverUrl + " failed", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new McpFetchException(
                    "MCP server " + serverUrl + " returned HTTP " + response.statusCode());
        }

        List<McpToolDefinition> tools = parseToolsList(extractJsonPayload(response.body()));
        log.info("MCP tools/list url={} tools={}", serverUrl, tools.size());
        return tools;
    }

    /**
     * Parse a {@code tools/list} JSON-RPC 2.0 response body into normalized tool
     * definitions. Pure function (no I/O) so it is unit-testable without a server.
     *
     * <p>Accepts the standard envelope {@code {"jsonrpc":"2.0","id":1,"result":
     * {"tools":[...]}}}; surfaces a JSON-RPC {@code error} object as a
     * {@link McpFetchException}.
     */
    public List<McpToolDefinition> parseToolsList(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw new McpFetchException("MCP response was not valid JSON", e);
        }

        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText("unknown error");
            throw new McpFetchException("MCP server returned JSON-RPC error: " + message);
        }

        JsonNode toolsNode = root.path("result").path("tools");
        if (!toolsNode.isArray()) {
            throw new McpFetchException("MCP tools/list response had no result.tools array");
        }

        List<McpToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            tools.add(McpToolDefinition.fromToolNode(tool));
        }
        return tools;
    }

    /**
     * Streamable HTTP servers may answer either with a plain JSON body or with an
     * SSE stream ({@code text/event-stream}). For the single non-streaming
     * tools/list call, pull the JSON object out of {@code data:} lines if present;
     * otherwise treat the whole body as JSON.
     */
    private static String extractJsonPayload(String body) {
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        StringBuilder data = new StringBuilder();
        for (String line : trimmed.split("\n")) {
            String s = line.strip();
            if (s.startsWith("data:")) {
                data.append(s.substring("data:".length()).strip());
            }
        }
        return data.length() > 0 ? data.toString() : trimmed;
    }

    /** Raised when an MCP server cannot be reached or answers unusably. */
    public static class McpFetchException extends RuntimeException {
        public McpFetchException(String message) {
            super(message);
        }

        public McpFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
