"use client";

import { useRef, useState } from "react";
import { api, type ApiApprovedArtifact } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useToast } from "@/lib/feedback";

/**
 * Install the APPROVED (pinned) version of a skill into an agent (MA3-103) — not
 * the live upstream. Supports multiple targets (Claude Code, Codex): each shows a
 * copy-paste install command writing the governed files into that agent's skills
 * dir, plus a SKILL.md download.
 */
type Target = { key: string; labelKey: string; dir: string };

const TARGETS: Target[] = [
  { key: "claude", labelKey: "action.addToClaude", dir: "~/.claude/skills" },
  { key: "codex", labelKey: "action.addToCodex", dir: "~/.codex/skills" },
];

export function AddToClaude({ toolId }: { toolId: string }) {
  const { t } = useT();
  const toast = useToast();
  const preRef = useRef<HTMLPreElement>(null);
  const [active, setActive] = useState<string | null>(null);
  const [artifact, setArtifact] = useState<ApiApprovedArtifact | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const ensureLoaded = async () => {
    if (artifact || loading) return;
    setLoading(true);
    setError(null);
    try {
      setArtifact(await api.getApprovedArtifact(toolId));
    } catch {
      setError(t("install.skill.error"));
    } finally {
      setLoading(false);
    }
  };

  const pick = (key: string) => {
    setActive((cur) => (cur === key ? null : key));
    setCopied(false);
    void ensureLoaded();
  };

  const target = TARGETS.find((tg) => tg.key === active) ?? null;
  const snippet = artifact && target ? buildInstallSnippet(artifact, target.dir) : "";

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

  const download = () => {
    if (!artifact) return;
    const main =
      artifact.files.find((f) => f.path.toUpperCase().endsWith("SKILL.MD")) ??
      artifact.files[0];
    if (!main) return;
    // Filename: the skill name + the file's extension, always with ".md".
    let fname = main.path.split("/").pop() || "SKILL.md";
    if (!/\.[a-z0-9]+$/i.test(fname)) fname += ".md";
    const safe = artifact.name.replace(/[^a-zA-Z0-9._-]/g, "-");
    fname = `${safe}-${fname}`;
    const blob = new Blob([main.content], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = fname;
    // Must be connected to the document for the download filename to be honored
    // across browsers (Firefox); was missing → file saved without ".md".
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="rounded-[11px] border border-border bg-surface">
      <div className="flex items-center gap-2 px-4 py-3">
        <span className="grid h-5 w-5 place-items-center rounded bg-gradient-to-br from-primary to-approved font-mono text-[11px] text-[#06121F]">
          ↓
        </span>
        <span className="text-[13px] font-semibold text-text">{t("install.skill.title")}</span>
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
          {loading && <p className="text-[12px] text-dim">{t("install.skill.loading")}</p>}
          {error && <p className="text-[12px] text-crit">{error}</p>}
          {artifact && target && (
            <div className="flex flex-col gap-3">
              <p className="text-[12px] leading-relaxed text-muted">
                {t("install.skill.installsTo")}{" "}
                <b className="text-approved">{t("install.skill.approved")}</b>{" "}
                {t("install.skill.versionInto")}{" "}
                <span className="font-mono text-dim">{target.dir}/{artifact.name}/</span>{" "}
                {t("install.skill.notLive")}{" "}
                <span className="font-mono text-dim">{artifact.hash.slice(0, 16)}…</span>
              </p>
              <div className="flex items-center justify-between">
                <span className="font-mono text-[10px] uppercase tracking-[0.1em] text-dim">
                  install
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
                className="max-h-52 overflow-auto rounded-lg border border-border bg-bg p-3 font-mono text-[11px] leading-relaxed text-text"
              >
                {snippet}
              </pre>
              <button
                type="button"
                onClick={download}
                className="self-start rounded-lg border border-border-strong px-3 py-1.5 text-[12px] font-semibold text-text"
              >
                {t("install.skill.download")}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Self-contained shell snippet that recreates the skill folder + files. */
function buildInstallSnippet(a: ApiApprovedArtifact, baseDir: string): string {
  const safe = a.name.replace(/[^a-zA-Z0-9._-]/g, "-");
  const dir = `${baseDir}/${safe}`;
  const lines = [`mkdir -p ${dir}`];
  for (const f of a.files) {
    const sub = f.path.includes("/")
      ? `mkdir -p ${dir}/${f.path.replace(/\/[^/]*$/, "")}\n`
      : "";
    lines.push(`${sub}cat > ${dir}/${f.path} <<'VOUCHQ_EOF'\n${f.content}\nVOUCHQ_EOF`);
  }
  return lines.join("\n");
}
