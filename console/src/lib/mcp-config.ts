import type { ApiMcpInstall } from "@/lib/api";

/**
 * MCP install is config-not-bytes: an MCP server is a running endpoint, so the
 * artifact is a connection config the user merges into their agent's MCP config
 * file. vouchq issues it only for a vouched (good-standing) server; this module
 * renders that one vouched view into a per-target, per-scope snippet.
 *
 * v1 covers remote (URL) servers. Local stdio servers (command/args + pinned
 * image digest) come with the stdio-transport work (B).
 */
export type McpTarget = "claude" | "cursor" | "codex";
export type McpScope = "project" | "user";

/** Whether a target supports a given scope as a real config file. */
export function mcpScopeAvailable(target: McpTarget, scope: McpScope): boolean {
  // Codex MCP config is global only (~/.codex/config.toml). Claude & Cursor have both.
  if (target === "codex") return scope === "user";
  return true;
}

/** The config file an agent merges this snippet into, per target + scope. */
export function mcpDestFile(target: McpTarget, scope: McpScope): string {
  switch (target) {
    case "codex":
      return "~/.codex/config.toml";
    case "cursor":
      return scope === "user" ? "~/.cursor/mcp.json" : ".cursor/mcp.json";
    case "claude":
    default:
      return scope === "user" ? "~/.claude.json" : ".mcp.json";
  }
}

/** Safe MCP server key: agents key servers by name, so keep it identifier-ish. */
function safeName(name: string): string {
  return name.replace(/[^a-zA-Z0-9._-]/g, "-");
}

/**
 * Render the vouched MCP server as a merge-ready config snippet. The snippet body
 * is the same regardless of scope (scope only changes the destination file); the
 * token is always a placeholder the user fills in.
 */
export function buildMcpConfig(view: ApiMcpInstall, target: McpTarget): string {
  const name = safeName(view.name);
  const date = view.vouchedAt.slice(0, 10);
  const header = `vouched by vouchq @ ${date} · ${view.approvedTools}/${view.totalTools} approved`;

  if (target === "codex") {
    return [
      `# ${header}`,
      `[mcp_servers.${name}]`,
      `url = "${view.url}"`,
      `# Set your own token:`,
      `# bearer_token = "YOUR_TOKEN"`,
    ].join("\n");
  }

  // Claude (.mcp.json / ~/.claude.json) and Cursor (.cursor/mcp.json) share the
  // mcpServers JSON shape. Claude tags the transport type; Cursor reads url+headers.
  const server =
    target === "claude"
      ? { type: "http", url: view.url, headers: { Authorization: "Bearer YOUR_TOKEN" } }
      : { url: view.url, headers: { Authorization: "Bearer YOUR_TOKEN" } };
  const obj = { mcpServers: { [name]: server } };
  return `// ${header}\n${JSON.stringify(obj, null, 2)}`;
}
