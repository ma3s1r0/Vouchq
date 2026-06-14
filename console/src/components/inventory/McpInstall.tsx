"use client";

import { useRef, useState } from "react";
import { api, ApiError, type ApiMcpInstall } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useToast } from "@/lib/feedback";

/**
 * "Add MCP server to Claude/Codex" (MA3-110). vouchq issues a connection config
 * ONLY for an approved, in-good-standing MCP server — the refusal is itself the
 * signal (shown as a "config withheld" governance state, not a generic error,
 * MA3-120). vouchq is the issuer, never in the data path. The token is a
 * placeholder; the config carries a vouched-by/date header so it's traceable.
 */
type Target = { key: string; labelKey: string };

const TARGETS: Target[] = [
  { key: "claude", labelKey: "action.addToClaude" },
  { key: "codex", labelKey: "action.addToCodex" },
];

export function McpInstall({ toolId }: { toolId: string }) {
  const { t } = useT();
  const toast = useToast();
  const preRef = useRef<HTMLPreElement>(null);
  const [active, setActive] = useState<string | null>(null);
  const [cfg, setCfg] = useState<ApiMcpInstall | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [withheld, setWithheld] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const ensureLoaded = async () => {
    if (cfg || loading) return;
    setLoading(true);
    setError(null);
    setWithheld(null);
    try {
      setCfg(await api.getMcpInstall(toolId));
    } catch (e) {
      // 400 = the server isn't vouched (no approved tools / blocked / drifted).
      // That's the governance signal — present it as "withheld", not a failure.
      if (e instanceof ApiError && e.status === 400) setWithheld(e.message);
      else setError(t("install.mcp.error"));
    } finally {
      setLoading(false);
    }
  };

  const pick = (key: string) => {
    setActive((cur) => (cur === key ? null : key));
    setCopied(false);
    void ensureLoaded();
  };

  const snippet = cfg && active ? buildConfig(cfg, active) : "";

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(snippet);
      setCopied(true);
      toast("success", t("install.copyToast"));
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // No clipboard API (e.g. non-HTTPS): select the text so ⌘/Ctrl-C works.
      const el = preRef.current;
      if (el) {
        const range = document.createRange();
        range.selectNodeContents(el);
        const sel = window.getSelection();
        sel?.removeAllRanges();
        sel?.addRange(range);
      }
      toast("info", t("install.copyFallback"));
    }
  };

  return (
    <div className="rounded-[11px] border border-border bg-surface">
      <div className="flex items-center gap-2 px-4 py-3">
        <span
          aria-hidden
          className="grid h-5 w-5 place-items-center rounded bg-gradient-to-br from-primary to-approved font-mono text-[11px] text-[#06121F]"
        >
          ⇄
        </span>
        <span className="text-[13px] font-semibold text-text">{t("install.mcp.title")}</span>
      </div>
      <div className="flex gap-1.5 px-4 pb-3">
        {TARGETS.map((tg) => (
          <button
            key={tg.key}
            type="button"
            onClick={() => pick(tg.key)}
            className={`rounded-lg border px-3 py-1.5 text-[12px] font-semibold transition-colors ${
              active === tg.key
                ? "border-primary bg-primary/[0.12] text-[#9DC3FF]"
                : "border-border-strong text-text hover:border-primary"
            }`}
          >
            {t(tg.labelKey)}
          </button>
        ))}
      </div>

      {active && (
        <div className="border-t border-border p-4">
          {loading && <p className="text-[12px] text-dim">{t("install.mcp.loading")}</p>}
          {error && <p className="text-[12px] text-crit">{error}</p>}

          {withheld && (
            <div className="rounded-lg border border-warn/40 bg-warn/[0.07] p-3">
              <div className="flex items-center gap-2 text-[13px] font-semibold text-warn">
                <span aria-hidden>⚠</span>
                {t("install.mcp.withheldTitle")}
              </div>
              <p className="mt-1.5 text-[12px] leading-relaxed text-muted">
                {t("install.mcp.withheldDesc")}
              </p>
              <p className="mt-1 font-mono text-[12px] text-text">{withheld}</p>
            </div>
          )}

          {cfg && (
            <div className="flex flex-col gap-3">
              <p className="text-[12px] leading-relaxed text-muted">
                {t("install.mcp.vouchedFor")}{" "}
                <b className="text-text">{cfg.name}</b> —{" "}
                <span className="text-approved">
                  {cfg.approvedTools}/{cfg.totalTools} {t("install.mcp.toolsApproved")}
                </span>
                . {t("install.mcp.addNote")} {t("install.mcp.vouchedNote")}
              </p>
              <div className="flex items-center justify-between">
                <span className="font-mono text-[10px] uppercase tracking-[0.1em] text-dim">
                  {active === "codex" ? "config.toml" : ".mcp.json"}
                </span>
                <button
                  type="button"
                  onClick={copy}
                  className="rounded border border-border-strong bg-surface-2 px-2 py-1 text-[11px] font-semibold text-text"
                >
                  {copied ? t("common.copied") : t("common.copy")}
                </button>
              </div>
              <pre
                ref={preRef}
                className="max-h-60 overflow-auto rounded-lg border border-border bg-bg p-3 font-mono text-[11px] leading-relaxed text-text"
              >
                {snippet}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function buildConfig(c: ApiMcpInstall, target: string): string {
  const safe = c.name.replace(/[^a-zA-Z0-9._-]/g, "-");
  const date = c.vouchedAt.slice(0, 10);
  const header = `vouched by vouchq @ ${date} · ${c.approvedTools}/${c.totalTools} approved`;

  if (target === "codex") {
    // Codex: ~/.codex/config.toml
    return [
      `# ${header}`,
      `[mcp_servers.${safe}]`,
      `url = "${c.url}"`,
      `# Set your own token:`,
      `# bearer_token = "YOUR_TOKEN"`,
    ].join("\n");
  }
  // Claude: .mcp.json (project) or ~/.claude.json
  const obj = {
    mcpServers: {
      [safe]: {
        type: "http",
        url: c.url,
        headers: { Authorization: "Bearer YOUR_TOKEN" },
      },
    },
  };
  return `// ${header}\n${JSON.stringify(obj, null, 2)}`;
}
