"use client";

import { useEffect, useMemo, useState } from "react";
import type { InventoryItem } from "@/lib/mock-inventory";
import { api, ApiError, type ApiMcpInstall } from "@/lib/api";
import { useToast } from "@/lib/feedback";
import { useT } from "@/lib/i18n";
import {
  buildMcpConfig,
  mcpDestFile,
  mcpScopeAvailable,
  type McpScope,
  type McpTarget,
} from "@/lib/mcp-config";

/** Which agent to install into, and project- vs user-global scope. */
type Target = "claude" | "cursor";
type Scope = "project" | "user";

/**
 * Install directory per (target, scope). Claude installs as files in either
 * scope; Cursor's file-based rules are project-only (user rules live in Cursor
 * settings, not on disk), so its scope is fixed to project.
 */
function destDir(target: Target, scope: Scope): string {
  if (target === "cursor") return ".cursor/rules/";
  return scope === "user" ? "~/.claude/skills/" : ".claude/skills/";
}

/**
 * Group one-click install modal (MA3-139). Shows the copy-paste `curl | sh`
 * one-liner for a source group and an honest preview: which approved skills will
 * install, where, and what's excluded (pending/drifted/blocked, plus MCP tools
 * which aren't installable here yet). Skills only — the backend manifest serves
 * APPROVED + pinned skills verified against vouchq-stored bytes.
 */
export function InstallModal({
  group,
  onClose,
}: {
  group: { label: string; sourceId: string | null; items: InventoryItem[] };
  onClose: () => void;
}) {
  const { t } = useT();
  const toast = useToast();
  const [target, setTarget] = useState<Target>("claude");
  const [scope, setScope] = useState<Scope>("project");

  // Cursor has no file-based user scope — fall back to project if it was selected.
  const pickTarget = (tg: Target) => {
    setTarget(tg);
    if (tg === "cursor") setScope("project");
  };

  const skills = group.items.filter((i) => i.kind === "SKILL");
  const approved = skills.filter((i) => i.status === "APPROVED");
  const pending = skills.filter((i) => i.status === "PENDING").length;
  const drifted = skills.filter((i) => i.status === "DRIFTED").length;
  const blocked = skills.filter((i) => i.status === "BLOCKED").length;
  const mcp = group.items.filter((i) => i.kind === "MCP_TOOL").length;
  const excluded = pending + drifted + blocked;
  // A source is either a Skill repo or an MCP server — branch the whole body.
  const isMcp = mcp > 0 && skills.length === 0;

  // Browser origin works as the API host: the console proxies /api → backend.
  const command = useMemo(() => {
    if (!group.sourceId) return null;
    const origin = typeof window !== "undefined" ? window.location.origin : "";
    const url = `${origin}/api/sources/${group.sourceId}/install.sh?target=${target}&scope=${scope}`;
    return `curl -fsSL -u 'EMAIL:PASSWORD' '${url}' \\\n  | VOUCHQ_AUTH='EMAIL:PASSWORD' sh`;
  }, [group.sourceId, target, scope]);

  // Escape closes; lock background scroll while open.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prev;
    };
  }, [onClose]);

  const copy = async () => {
    if (!command) return;
    try {
      await navigator.clipboard.writeText(command);
      toast("success", t("install.copied"));
    } catch {
      toast("error", t("install.copyFailed"));
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-label={t("install.title")}
      onClick={onClose}
    >
      <div
        className="w-full max-w-[640px] overflow-hidden rounded-[14px] border border-border-strong bg-surface shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="border-b border-border px-5 py-4">
          <h2 className="text-[15px] font-semibold text-text">
            {isMcp ? t("install.mcpTitle") : t("install.title")}
          </h2>
          <p className="mt-1 text-[12.5px] text-muted">
            {isMcp ? t("install.mcpSubtitle") : t("install.subtitle")}{" "}
            <span className="font-mono text-text">{group.label}</span>
          </p>
        </div>

        <div className="flex flex-col gap-4 px-5 py-4">
          {!group.sourceId ? (
            <p className="text-[13px] text-warn">{t("install.unresolved")}</p>
          ) : isMcp ? (
            <McpInstallBody sourceId={group.sourceId} />
          ) : approved.length === 0 ? (
            <p className="text-[13px] text-dim">{t("install.none")}</p>
          ) : (
            <>
              {/* target picker — which agent to install into */}
              <div>
                <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
                  {t("install.targetLabel")}
                </span>
                <div
                  className="mt-1.5 inline-flex w-full rounded-lg border border-border bg-surface-2 p-0.5 text-[13px] font-semibold"
                  role="group"
                  aria-label={t("install.targetLabel")}
                >
                  {(["claude", "cursor"] as const).map((tg) => (
                    <button
                      key={tg}
                      type="button"
                      onClick={() => pickTarget(tg)}
                      aria-pressed={target === tg}
                      className={`flex-1 rounded-md px-3 py-1.5 transition-colors ${
                        target === tg
                          ? "bg-primary/[0.14] text-[#9DC3FF]"
                          : "text-muted hover:text-text"
                      }`}
                    >
                      {tg === "claude" ? "Claude" : "Cursor"}
                      <span className="ml-1.5 font-mono text-[11px] font-normal opacity-70">
                        {destDir(tg, scope)}
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              {/* scope picker — project (./.claude) vs user (~/.claude) */}
              <div>
                <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
                  {t("install.scopeLabel")}
                </span>
                <div
                  className="mt-1.5 inline-flex w-full rounded-lg border border-border bg-surface-2 p-0.5 text-[13px] font-semibold"
                  role="group"
                  aria-label={t("install.scopeLabel")}
                >
                  {(["project", "user"] as const).map((sc) => {
                    // Cursor file rules are project-only; disable user there.
                    const disabled = target === "cursor" && sc === "user";
                    return (
                      <button
                        key={sc}
                        type="button"
                        disabled={disabled}
                        onClick={() => setScope(sc)}
                        aria-pressed={scope === sc}
                        title={disabled ? t("install.cursorUserNote") : undefined}
                        className={`flex-1 rounded-md px-3 py-1.5 transition-colors ${
                          scope === sc && !disabled
                            ? "bg-primary/[0.14] text-[#9DC3FF]"
                            : disabled
                              ? "cursor-not-allowed text-dim/50"
                              : "text-muted hover:text-text"
                        }`}
                      >
                        {sc === "project" ? t("install.scopeProject") : t("install.scopeUser")}
                      </button>
                    );
                  })}
                </div>
                {target === "cursor" && (
                  <p className="mt-1 text-[11.5px] text-dim">{t("install.cursorUserNote")}</p>
                )}
              </div>

              {/* the one-liner */}
              <div>
                <div className="mb-1.5 flex items-center justify-between">
                  <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
                    {scope === "user" ? t("install.runUser") : t("install.run")}
                  </span>
                  <button
                    type="button"
                    onClick={copy}
                    className="rounded-md border border-border-strong px-2.5 py-1 text-[12px] font-semibold text-text hover:border-primary"
                  >
                    {t("install.copy")}
                  </button>
                </div>
                <pre className="overflow-x-auto rounded-lg border border-border bg-surface-2 px-3 py-2.5 font-mono text-[12px] leading-relaxed text-text">
                  {command}
                </pre>
                <p className="mt-1 text-[11.5px] text-dim">{t("install.authNote")}</p>
              </div>

              {/* what installs */}
              <div className="rounded-lg border border-border bg-surface-2 px-3.5 py-3 text-[12.5px]">
                <p className="text-muted">
                  {t("install.willInstall")}{" "}
                  <b className="text-text">{approved.length}</b> {t("install.skills")}{" "}
                  {target === "cursor" ? t("install.targetAs") : t("install.target")}{" "}
                  <span className="font-mono text-text">{destDir(target, scope)}</span>
                  {target === "cursor" && (
                    <span className="font-mono text-text">{`<name>.mdc`}</span>
                  )}
                </p>
                <p className="mt-1.5 flex flex-wrap gap-x-2 gap-y-1 font-mono text-[11.5px] text-dim">
                  {approved.map((s) => (
                    <span key={s.id}>{s.name}</span>
                  ))}
                </p>
                <p className="mt-2 text-[11.5px] text-dim">{t("install.verifyNote")}</p>
                {target === "cursor" && (
                  <p className="mt-1 text-[11.5px] text-warn/90">{t("install.cursorNote")}</p>
                )}
              </div>

              {/* honesty: excluded + mcp */}
              {(excluded > 0 || mcp > 0) && (
                <div className="text-[12px] text-dim">
                  {excluded > 0 && (
                    <p>
                      <b className="text-warn">{excluded}</b> {t("install.excluded")}{" "}
                      {[
                        pending && `${pending} pending`,
                        drifted && `${drifted} drifted`,
                        blocked && `${blocked} blocked`,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                  )}
                  {mcp > 0 && <p className="mt-1">{t("install.mcpNote")}</p>}
                </div>
              )}
            </>
          )}
        </div>

        <div className="flex justify-end border-t border-border px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-border-strong px-3.5 py-2 text-[13px] font-semibold text-text hover:border-primary"
          >
            {t("install.close")}
          </button>
        </div>
      </div>
    </div>
  );
}

const MCP_TARGETS: McpTarget[] = ["claude", "cursor", "codex"];
const MCP_TARGET_LABEL: Record<McpTarget, string> = {
  claude: "Claude",
  cursor: "Cursor",
  codex: "Codex",
};

/**
 * MCP group install body (v1: remote servers). An MCP source is one server, so
 * this issues that server's vouched connection config and renders a per-target,
 * per-scope, copy-paste snippet to MERGE into the agent's MCP config file. A 400
 * means the server isn't in good standing — shown as a governance "withheld"
 * state, not a generic error. vouchq is the issuer, never in the data path.
 */
function McpInstallBody({ sourceId }: { sourceId: string }) {
  const { t } = useT();
  const toast = useToast();
  const [target, setTarget] = useState<McpTarget>("claude");
  const [scope, setScope] = useState<McpScope>("project");
  const [cfg, setCfg] = useState<ApiMcpInstall | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [withheld, setWithheld] = useState<string | null>(null);

  // Issue once when the modal opens (the GET records the distribution on audit).
  useEffect(() => {
    let active = true;
    setLoading(true);
    api
      .getSourceMcpInstall(sourceId)
      .then((v) => active && setCfg(v))
      .catch((e) => {
        if (!active) return;
        // 400 = not vouched (no approved tools / blocked / drifted) — the signal.
        if (e instanceof ApiError && e.status === 400) setWithheld(e.message);
        else setError(t("install.mcp.error"));
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [sourceId, t]);

  // Codex MCP config is global only; force user scope there.
  const pickTarget = (tg: McpTarget) => {
    setTarget(tg);
    if (!mcpScopeAvailable(tg, scope)) setScope("user");
  };

  const destFile = mcpDestFile(target, scope);
  const snippet = cfg ? buildMcpConfig(cfg, target) : "";

  const copy = async () => {
    if (!snippet) return;
    try {
      await navigator.clipboard.writeText(snippet);
      toast("success", t("install.copied"));
    } catch {
      toast("error", t("install.copyFailed"));
    }
  };

  if (loading) return <p className="text-[13px] text-dim">{t("install.mcp.loading")}</p>;
  if (error) return <p className="text-[13px] text-crit">{error}</p>;
  if (withheld) {
    return (
      <div className="rounded-lg border border-warn/40 bg-warn/[0.07] p-3.5">
        <div className="flex items-center gap-2 text-[13px] font-semibold text-warn">
          <span aria-hidden>⚠</span>
          {t("install.mcp.withheldTitle")}
        </div>
        <p className="mt-1.5 text-[12px] leading-relaxed text-muted">
          {t("install.mcp.withheldDesc")}
        </p>
        <p className="mt-1 font-mono text-[12px] text-text">{withheld}</p>
      </div>
    );
  }
  if (!cfg) return null;

  return (
    <>
      {/* target — which agent's MCP config */}
      <div>
        <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
          {t("install.targetLabel")}
        </span>
        <div
          className="mt-1.5 inline-flex w-full rounded-lg border border-border bg-surface-2 p-0.5 text-[13px] font-semibold"
          role="group"
          aria-label={t("install.targetLabel")}
        >
          {MCP_TARGETS.map((tg) => (
            <button
              key={tg}
              type="button"
              onClick={() => pickTarget(tg)}
              aria-pressed={target === tg}
              className={`flex-1 rounded-md px-3 py-1.5 transition-colors ${
                target === tg ? "bg-primary/[0.14] text-[#9DC3FF]" : "text-muted hover:text-text"
              }`}
            >
              {MCP_TARGET_LABEL[tg]}
            </button>
          ))}
        </div>
      </div>

      {/* scope — project vs user/global config file */}
      <div>
        <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
          {t("install.scopeLabel")}
        </span>
        <div
          className="mt-1.5 inline-flex w-full rounded-lg border border-border bg-surface-2 p-0.5 text-[13px] font-semibold"
          role="group"
          aria-label={t("install.scopeLabel")}
        >
          {(["project", "user"] as const).map((sc) => {
            const disabled = !mcpScopeAvailable(target, sc);
            return (
              <button
                key={sc}
                type="button"
                disabled={disabled}
                onClick={() => setScope(sc)}
                aria-pressed={scope === sc}
                title={disabled ? t("install.codexGlobalNote") : undefined}
                className={`flex-1 rounded-md px-3 py-1.5 transition-colors ${
                  scope === sc && !disabled
                    ? "bg-primary/[0.14] text-[#9DC3FF]"
                    : disabled
                      ? "cursor-not-allowed text-dim/50"
                      : "text-muted hover:text-text"
                }`}
              >
                {sc === "project" ? t("install.scopeProject") : t("install.scopeUser")}
              </button>
            );
          })}
        </div>
        {target === "codex" && (
          <p className="mt-1 text-[11.5px] text-dim">{t("install.codexGlobalNote")}</p>
        )}
      </div>

      {/* vouched summary */}
      <p className="text-[12.5px] leading-relaxed text-muted">
        {t("install.mcp.vouchedFor")} <b className="text-text">{cfg.name}</b> —{" "}
        <span className="text-approved">
          {cfg.approvedTools}/{cfg.totalTools} {t("install.mcp.toolsApproved")}
        </span>
        . {t("install.mcp.addNote")}
      </p>

      {/* the snippet to merge */}
      <div>
        <div className="mb-1.5 flex items-center justify-between">
          <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
            {t("install.mergeInto")} <span className="font-mono normal-case text-text">{destFile}</span>
          </span>
          <button
            type="button"
            onClick={copy}
            className="rounded-md border border-border-strong px-2.5 py-1 text-[12px] font-semibold text-text hover:border-primary"
          >
            {t("install.copy")}
          </button>
        </div>
        <pre className="max-h-56 overflow-auto rounded-lg border border-border bg-surface-2 px-3 py-2.5 font-mono text-[11.5px] leading-relaxed text-text">
          {snippet}
        </pre>
        <p className="mt-1 text-[11.5px] text-dim">{t("install.mcp.vouchedNote")}</p>
      </div>
    </>
  );
}
