"use client";

import { useEffect, useMemo, useState } from "react";
import type { InventoryItem } from "@/lib/mock-inventory";
import { useToast } from "@/lib/feedback";
import { useT } from "@/lib/i18n";

/** Where an installed skill lands, per agent. */
type Target = "claude" | "cursor";
const TARGET_DIR: Record<Target, string> = {
  claude: ".claude/skills/",
  cursor: ".cursor/rules/",
};

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

  const skills = group.items.filter((i) => i.kind === "SKILL");
  const approved = skills.filter((i) => i.status === "APPROVED");
  const pending = skills.filter((i) => i.status === "PENDING").length;
  const drifted = skills.filter((i) => i.status === "DRIFTED").length;
  const blocked = skills.filter((i) => i.status === "BLOCKED").length;
  const mcp = group.items.filter((i) => i.kind === "MCP_TOOL").length;
  const excluded = pending + drifted + blocked;

  // Browser origin works as the API host: the console proxies /api → backend.
  const command = useMemo(() => {
    if (!group.sourceId) return null;
    const origin = typeof window !== "undefined" ? window.location.origin : "";
    const url = `${origin}/api/sources/${group.sourceId}/install.sh?target=${target}`;
    return `curl -fsSL -u 'EMAIL:PASSWORD' '${url}' \\\n  | VOUCHQ_AUTH='EMAIL:PASSWORD' sh`;
  }, [group.sourceId, target]);

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
          <h2 className="text-[15px] font-semibold text-text">{t("install.title")}</h2>
          <p className="mt-1 text-[12.5px] text-muted">
            {t("install.subtitle")}{" "}
            <span className="font-mono text-text">{group.label}</span>
          </p>
        </div>

        <div className="flex flex-col gap-4 px-5 py-4">
          {!group.sourceId ? (
            <p className="text-[13px] text-warn">{t("install.unresolved")}</p>
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
                      onClick={() => setTarget(tg)}
                      aria-pressed={target === tg}
                      className={`flex-1 rounded-md px-3 py-1.5 transition-colors ${
                        target === tg
                          ? "bg-primary/[0.14] text-[#9DC3FF]"
                          : "text-muted hover:text-text"
                      }`}
                    >
                      {tg === "claude" ? "Claude" : "Cursor"}
                      <span className="ml-1.5 font-mono text-[11px] font-normal opacity-70">
                        {TARGET_DIR[tg]}
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              {/* the one-liner */}
              <div>
                <div className="mb-1.5 flex items-center justify-between">
                  <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-dim">
                    {t("install.run")}
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
                  <span className="font-mono text-text">{TARGET_DIR[target]}</span>
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
