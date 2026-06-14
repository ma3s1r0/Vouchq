"use client";

import { useState, useEffect } from "react";
import type {
  ConnectedSource,
  Member,
  MemberRole,
  NotificationChannel,
  ChannelKind,
  PolicyRule,
  PolicyAction,
  PolicyCondition,
  Suppression,
} from "@/lib/mock-settings";
import { connectSource, rescanSource } from "@/lib/mock-settings";
import { api, type ApiRole } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n";
import { useToast, useConfirm } from "@/lib/feedback";
import { SkeletonRows } from "@/components/shell/Skeleton";
import { useRouter } from "next/navigation";

type Tab = "sources" | "channels" | "members" | "policy" | "suppressions" | "account";

const TABS: { id: Tab; labelKey: string }[] = [
  { id: "sources", labelKey: "settings.tab.sources" },
  { id: "channels", labelKey: "settings.tab.channels" },
  { id: "members", labelKey: "settings.tab.members" },
  { id: "policy", labelKey: "settings.tab.policy" },
  { id: "suppressions", labelKey: "settings.tab.suppressions" },
  { id: "account", labelKey: "settings.tab.account" },
];

/**
 * Settings: tabbed sections for connected sources, notification channels
 * (off by default), members & roles (MA3-93 API-backed), and policy rules.
 * Channels + policy rules are now API-backed (MA3-92).
 * Members section is fully client-side (fetches on mount, ADMIN-only CRUD).
 */
export function SettingsView({
  sources,
  channels: initialChannels,
  policyRules: initialPolicyRules,
  suppressions: initialSuppressions,
}: {
  sources: ConnectedSource[];
  channels: NotificationChannel[];
  policyRules: PolicyRule[];
  suppressions: Suppression[];
}) {
  const [tab, setTab] = useState<Tab>("sources");
  const { t } = useT();

  return (
    <div className="flex max-w-[760px] flex-col gap-4">
      <div role="tablist" aria-label={t("settings.tablist")} className="flex gap-1 border-b border-border">
        {TABS.map((tabDef) => (
          <button
            key={tabDef.id}
            type="button"
            role="tab"
            id={`settings-tab-${tabDef.id}`}
            aria-selected={tab === tabDef.id}
            aria-controls={`settings-panel-${tabDef.id}`}
            onClick={() => setTab(tabDef.id)}
            className={[
              "-mb-px border-b-2 px-3.5 py-2.5 text-[13px] font-medium transition-colors",
              tab === tabDef.id
                ? "border-primary text-text"
                : "border-transparent text-muted hover:text-text",
            ].join(" ")}
          >
            {t(tabDef.labelKey)}
          </button>
        ))}
      </div>

      <div
        role="tabpanel"
        id={`settings-panel-${tab}`}
        aria-labelledby={`settings-tab-${tab}`}
      >
        {tab === "sources" && <SourcesSection sources={sources} />}
        {tab === "channels" && <ChannelsSection initialChannels={initialChannels} />}
        {tab === "members" && <MembersSection />}
        {tab === "policy" && <PolicySection initialRules={initialPolicyRules} />}
        {tab === "suppressions" && (
          <SuppressionsSection initialSuppressions={initialSuppressions} />
        )}
        {tab === "account" && <AccountSection />}
      </div>
    </div>
  );
}

/* ── Account (self-service password change, MA3-102) ─────────────────── */

function AccountSection() {
  const { me } = useAuth();
  const { t } = useT();
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const inputCls =
    "w-full rounded-lg border border-border bg-bg px-3 py-2.5 font-mono text-[13px] text-text outline-none placeholder:text-dim focus:border-primary";
  const labelCls =
    "mb-[7px] block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted";

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const current = (form.elements.namedItem("cur") as HTMLInputElement).value;
    const next = (form.elements.namedItem("new") as HTMLInputElement).value;
    const confirmVal = (form.elements.namedItem("confirm") as HTMLInputElement).value;
    setMsg(null);
    if (next.length < 8) {
      setMsg({ ok: false, text: t("settings.account.err.minLength") });
      return;
    }
    if (next !== confirmVal) {
      setMsg({ ok: false, text: t("settings.account.err.mismatch") });
      return;
    }
    setSubmitting(true);
    try {
      await api.changePassword({ currentPassword: current, newPassword: next });
      setMsg({ ok: true, text: t("settings.account.success") });
      form.reset();
    } catch {
      setMsg({
        ok: false,
        text: t("settings.account.err.failed"),
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">
        {t("settings.account.heading")}
      </h2>
      <div className="rounded-[11px] border border-border bg-surface p-5">
        <div className="mb-4 text-[12px] text-muted">
          {t("settings.account.signedInAs")}{" "}
          <span className="font-mono text-text">{me?.email ?? "—"}</span>
          {me?.role && (
            <span className="ml-1.5 text-dim">({t(`role.${me.role}`)})</span>
          )}
        </div>
        <form onSubmit={handleSubmit} className="flex max-w-[380px] flex-col gap-3.5">
          <div>
            <label htmlFor="cur" className={labelCls}>{t("settings.account.curPassword")}</label>
            <input id="cur" name="cur" type="password" required className={inputCls} />
          </div>
          <div>
            <label htmlFor="new" className={labelCls}>{t("settings.account.newPassword")}</label>
            <input id="new" name="new" type="password" required minLength={8} className={inputCls} />
          </div>
          <div>
            <label htmlFor="confirm" className={labelCls}>{t("settings.account.confirmPassword")}</label>
            <input id="confirm" name="confirm" type="password" required className={inputCls} />
          </div>
          {msg && (
            <p
              role={msg.ok ? "status" : "alert"}
              className={`text-[12px] ${msg.ok ? "text-approved" : "text-crit"}`}
            >
              {msg.text}
            </p>
          )}
          <button
            type="submit"
            disabled={submitting}
            className="self-start rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F] disabled:opacity-60"
          >
            {submitting ? t("settings.account.changing") : t("account.changePassword")}
          </button>
        </form>
      </div>
    </section>
  );
}

/* ── Sources ─────────────────────────────────────────────────────────── */

function SourcesSection({ sources }: { sources: ConnectedSource[] }) {
  const router = useRouter();
  const { t } = useT();
  const { me } = useAuth();
  const toast = useToast();
  const confirm = useConfirm();
  const canManage = me?.role === "ADMIN" || me?.role === "MEMBER";
  const [connecting, setConnecting] = useState(false);
  const [scanning, setScanning] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);

  const handleRescan = async (id: string) => {
    setScanning(id);
    try {
      await rescanSource(id);
      // Re-pull the SSR data so new versions / drift / asset counts show up
      // without a manual page refresh.
      router.refresh();
      toast("success", t("settings.sources.toast.rescanned"));
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setScanning(null);
    }
  };

  const handleRemove = async (id: string) => {
    if (!(await confirm(t("settings.sources.confirm.delete")))) return;
    setRemoving(id);
    try {
      await api.deleteSource(id);
      router.refresh();
      toast("success", t("common.removed"));
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setRemoving(null);
    }
  };

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center">
        <h2 className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">
          {t("settings.sources.heading")} · {sources.length}
        </h2>
        <button
          type="button"
          onClick={() => setConnecting((v) => !v)}
          className="ml-auto rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F]"
        >
          {connecting ? t("settings.sources.close") : t("settings.sources.connect")}
        </button>
      </div>

      {connecting && <ConnectSourceForm onCancel={() => setConnecting(false)} />}

      <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
        {sources.map((s, i) => (
          <div
            key={s.id}
            className={`flex items-center gap-3 px-4 py-3 ${
              i < sources.length - 1 ? "border-b border-border" : ""
            }`}
          >
            <div className="min-w-0">
              <div className="truncate font-mono text-[13px] text-text">
                {s.url}
              </div>
              <div className="mt-0.5 font-mono text-[11px] text-dim">
                {t("settings.sources.branch")} {s.branch} · {s.assetCount} {t("settings.sources.assets")} · {t("settings.sources.scanned")} {s.lastScan}
              </div>
            </div>
            <button
              type="button"
              disabled={scanning === s.id}
              onClick={() => handleRescan(s.id)}
              className="ml-auto rounded-lg border border-border-strong px-3 py-1.5 text-[12px] font-semibold text-text disabled:opacity-50"
            >
              {scanning === s.id ? t("settings.sources.scanning") : t("settings.sources.rescan")}
            </button>
            {canManage && (
              <button
                type="button"
                disabled={removing === s.id}
                onClick={() => handleRemove(s.id)}
                className="rounded-lg border border-border px-3 py-1.5 text-[12px] font-semibold text-crit/70 hover:text-crit disabled:opacity-50"
              >
                {removing === s.id ? t("settings.sources.removing") : t("settings.sources.remove")}
              </button>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}

function ConnectSourceForm({ onCancel }: { onCancel: () => void }) {
  const router = useRouter();
  const { t } = useT();
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputCls =
    "w-full rounded-lg border border-border bg-bg px-3 py-2.5 font-mono text-[13px] text-text outline-none placeholder:text-dim focus:border-primary";
  const labelCls =
    "mb-[7px] block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted";

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const url = (form.elements.namedItem("src-url") as HTMLInputElement).value.trim();
    const token = (form.elements.namedItem("src-token") as HTMLInputElement).value.trim();
    if (!url) return;
    setSubmitting(true);
    setError(null);
    try {
      await connectSource(url, token || undefined);
      onCancel();
      toast("success", t("settings.sources.toast.connected"));
      // Re-pull SSR data so the new source + ingested assets appear immediately.
      router.refresh();
    } catch {
      // Surface the failure instead of silently spinning to a stop.
      setError(t("settings.sources.toast.connectFailed"));
      toast("error", t("settings.sources.toast.connectFailed"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="overflow-hidden rounded-xl border border-border bg-surface"
    >
      <div className="border-b border-border px-5 py-4">
        <div className="text-[15px] font-semibold">{t("settings.sources.form.title")}</div>
        <div className="mt-0.5 text-[12px] text-muted">
          {t("settings.sources.form.subtitle")}
        </div>
      </div>
      <div className="flex flex-col gap-4 p-5">
        <div>
          <label htmlFor="src-url" className={labelCls}>
            {t("settings.sources.form.url")}
          </label>
          <input
            id="src-url"
            name="src-url"
            className={inputCls}
            placeholder="https://github.com/acme/agent-tools.git"
          />
        </div>
        <div>
          <label htmlFor="src-token" className={labelCls}>
            {t("settings.sources.form.token")}
          </label>
          <input
            id="src-token"
            name="src-token"
            type="password"
            className={`${inputCls} tracking-[0.18em]`}
            placeholder="ghp_…"
          />
        </div>
        <p className="text-[11px] leading-relaxed text-dim">
          <b className="text-approved">{t("settings.sources.form.note")}</b>{" "}
          {t("settings.sources.form.noteDetail")}
        </p>
        {error && <p className="text-[12px] text-crit">{error}</p>}
        <div className="flex gap-2.5">
          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#06121F] disabled:opacity-50"
          >
            {submitting ? t("settings.sources.form.submitting") : t("settings.sources.form.submit")}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-border-strong px-4 py-2 text-[13px] font-semibold text-text"
          >
            {t("common.cancel")}
          </button>
        </div>
      </div>
    </form>
  );
}

/* ── Notification channels (MA3-92: API-backed) ──────────────────────── */

const CHANNEL_KIND_LABELS: Record<ChannelKind, string> = {
  EMAIL: "E",
  SLACK: "S",
  WEBHOOK: "W",
};

function ChannelsSection({
  initialChannels,
}: {
  initialChannels: NotificationChannel[];
}) {
  const { t } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [channels, setChannels] = useState<NotificationChannel[]>(initialChannels);
  const [adding, setAdding] = useState(false);
  const [toggling, setToggling] = useState<string | null>(null);
  const [testing, setTesting] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<Record<string, "ok" | "err">>({});

  const handleToggle = async (c: NotificationChannel) => {
    setToggling(c.id);
    try {
      const updated = await api.patchNotificationChannel(c.id, { enabled: !c.enabled });
      setChannels((prev) =>
        prev.map((ch) => (ch.id === c.id ? { ...ch, enabled: updated.enabled } : ch)),
      );
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setToggling(null);
    }
  };

  const handleTest = async (c: NotificationChannel) => {
    setTesting(c.id);
    setTestResult((prev) => ({ ...prev, [c.id]: "ok" }));
    try {
      await api.testNotificationChannel(c.id);
      setTestResult((prev) => ({ ...prev, [c.id]: "ok" }));
    } catch {
      setTestResult((prev) => ({ ...prev, [c.id]: "err" }));
    } finally {
      setTesting(null);
    }
  };

  const handleDelete = async (id: string) => {
    if (!(await confirm(t("settings.channels.confirm.delete")))) return;
    setDeleting(id);
    try {
      await api.deleteNotificationChannel(id);
      setChannels((prev) => prev.filter((c) => c.id !== id));
      toast("success", t("common.deleted"));
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setDeleting(null);
    }
  };

  const handleCreated = (c: NotificationChannel) => {
    setChannels((prev) => [...prev, c]);
    setAdding(false);
  };

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">
            {t("settings.channels.heading")}
          </h2>
          <p className="mt-1.5 max-w-[460px] text-[12px] leading-relaxed text-dim">
            {t("settings.channels.selfHostedNote")}{" "}
            <b className="text-text">{t("settings.channels.optIn")}</b>
            {t("settings.channels.optInNote")}
          </p>
        </div>
        <button
          type="button"
          onClick={() => setAdding((v) => !v)}
          className="ml-4 flex-none rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F]"
        >
          {adding ? t("common.close") : t("settings.channels.add")}
        </button>
      </div>

      {adding && <AddChannelForm onCreated={handleCreated} onCancel={() => setAdding(false)} />}

      <div className="flex flex-col gap-2.5">
        {channels.map((c) => {
          const on = c.enabled;
          const isBusy = toggling === c.id || testing === c.id || deleting === c.id;
          const testOk = testResult[c.id];
          return (
            <div
              key={c.id}
              className={`flex items-center gap-3.5 rounded-[11px] border border-border bg-surface px-[15px] py-3 ${
                on ? "" : "opacity-[0.62]"
              }`}
            >
              <span className="grid h-[34px] w-[34px] flex-none place-items-center rounded-[9px] border border-border-strong bg-surface-2 font-mono text-[13px] text-muted">
                {CHANNEL_KIND_LABELS[c.kind]}
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-[13.5px] font-semibold">{c.label}</div>
                <div className="mt-0.5 truncate font-mono text-[11.5px] text-muted">
                  {c.target}
                  {testOk && (
                    <span
                      className={`ml-2 ${testOk === "ok" ? "text-approved" : "text-crit"}`}
                    >
                      {testOk === "ok" ? t("settings.channels.testOk") : t("settings.channels.testFail")}
                    </span>
                  )}
                </div>
              </div>
              <button
                type="button"
                disabled={!on || isBusy}
                onClick={() => handleTest(c)}
                className="rounded-[7px] border border-border-strong bg-transparent px-3 py-1.5 text-[12px] font-semibold text-text disabled:cursor-not-allowed disabled:border-border disabled:text-dim"
              >
                {testing === c.id ? t("settings.channels.testing") : t("settings.channels.test")}
              </button>
              <button
                type="button"
                disabled={isBusy}
                onClick={() => handleDelete(c.id)}
                className="rounded-[7px] border border-border bg-transparent px-2.5 py-1.5 text-[12px] font-semibold text-crit/70 hover:text-crit disabled:cursor-not-allowed disabled:opacity-40"
                aria-label={`Delete ${c.label}`}
              >
                {deleting === c.id ? "…" : t("common.delete")}
              </button>
              {/* Toggle */}
              <button
                type="button"
                role="switch"
                aria-checked={on}
                aria-label={`Toggle ${c.label}`}
                disabled={isBusy}
                onClick={() => handleToggle(c)}
                className={`relative h-[21px] w-[38px] flex-none rounded-full border transition-colors disabled:opacity-50 ${
                  on
                    ? "border-approved/50 bg-approved/[0.22]"
                    : "border-border-strong bg-surface-2"
                }`}
              >
                <span
                  className={`absolute top-[2px] h-[15px] w-[15px] rounded-full transition-all ${
                    on ? "left-[19px] bg-approved" : "left-[2px] bg-dim"
                  }`}
                />
              </button>
            </div>
          );
        })}

        {channels.length === 0 && !adding && (
          <div className="rounded-[11px] border border-border bg-surface px-5 py-6 text-center text-[13px] text-dim">
            {t("settings.channels.empty")}
          </div>
        )}
      </div>
    </section>
  );
}

function AddChannelForm({
  onCreated,
  onCancel,
}: {
  onCreated: (c: NotificationChannel) => void;
  onCancel: () => void;
}) {
  const { t } = useT();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputCls =
    "w-full rounded-lg border border-border bg-bg px-3 py-2.5 font-mono text-[13px] text-text outline-none placeholder:text-dim focus:border-primary";
  const labelCls =
    "mb-[7px] block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted";

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const type = (form.elements.namedItem("ch-type") as HTMLSelectElement).value as ChannelKind;
    const name = (form.elements.namedItem("ch-name") as HTMLInputElement).value.trim();
    const target = (form.elements.namedItem("ch-target") as HTMLInputElement).value.trim();
    if (!name || !target) return;
    setError(null);
    setSubmitting(true);
    try {
      const created = await api.createNotificationChannel({
        type,
        name,
        target,
        enabled: false,
      });
      onCreated({
        id: created.id,
        kind: created.type as ChannelKind,
        label: created.name,
        target: created.target,
        config: created.config,
        enabled: created.enabled,
      });
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : t("common.failed"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="overflow-hidden rounded-xl border border-border bg-surface"
    >
      <div className="border-b border-border px-5 py-4">
        <div className="text-[15px] font-semibold">{t("settings.channels.form.title")}</div>
        <div className="mt-0.5 text-[12px] text-muted">
          {t("settings.channels.form.subtitle")}
        </div>
      </div>
      <div className="flex flex-col gap-4 p-5">
        <div className="grid grid-cols-[1fr_1.8fr_2fr] gap-3.5">
          <div>
            <label htmlFor="ch-type" className={labelCls}>
              {t("settings.channels.form.type")}
            </label>
            <select
              id="ch-type"
              name="ch-type"
              className={inputCls}
              defaultValue="WEBHOOK"
            >
              <option value="EMAIL">{t("channel.EMAIL")}</option>
              <option value="SLACK">{t("channel.SLACK")}</option>
              <option value="WEBHOOK">{t("channel.WEBHOOK")}</option>
            </select>
          </div>
          <div>
            <label htmlFor="ch-name" className={labelCls}>
              {t("settings.channels.form.name")}
            </label>
            <input
              id="ch-name"
              name="ch-name"
              className={inputCls}
              placeholder="Security Alerts"
            />
          </div>
          <div>
            <label htmlFor="ch-target" className={labelCls}>
              {t("settings.channels.form.target")}
            </label>
            <input
              id="ch-target"
              name="ch-target"
              className={inputCls}
              placeholder="#sec-alerts or https://…"
            />
          </div>
        </div>
        {error && (
          <p role="alert" className="text-[12px] text-crit">{error}</p>
        )}
        <div className="flex gap-2.5">
          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#06121F] disabled:opacity-50"
          >
            {submitting ? t("common.saving") : t("settings.channels.form.submit")}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-border-strong px-4 py-2 text-[13px] font-semibold text-text"
          >
            {t("common.cancel")}
          </button>
        </div>
      </div>
    </form>
  );
}

/* ── Members & roles (MA3-93: API-backed, ADMIN-only CRUD) ───────────── */

const ROLE_CHIP: Record<MemberRole, string> = {
  ADMIN: "bg-primary/[0.13] text-primary",
  MEMBER: "bg-muted/[0.12] text-muted",
  VIEWER: "bg-dim/[0.14] text-dim",
};

const ALL_ROLES: MemberRole[] = ["ADMIN", "MEMBER", "VIEWER"];

function memberInitials(displayName: string, email: string): string {
  const name = (displayName || email).trim();
  const parts = name.split(/[\s@.]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

/**
 * MembersSection — fully client-side (MA3-93).
 * Fetches GET /api/settings/members on mount.
 * ADMIN users get: Invite (create), change role (PATCH), deactivate (DELETE).
 * Non-admin users see the read-only table.
 */
function MembersSection() {
  const { me } = useAuth();
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const isAdmin = me?.role === "ADMIN";

  const [members, setMembers] = useState<Member[]>([]);
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [inviting, setInviting] = useState(false);
  const [roleChanging, setRoleChanging] = useState<string | null>(null);
  const [deactivating, setDeactivating] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getMembers()
      .then((data) => {
        if (!cancelled) {
          setMembers(
            data.map((m) => ({
              id: m.id,
              email: m.email,
              displayName: m.displayName,
              initials: memberInitials(m.displayName, m.email),
              role: m.role as MemberRole,
              active: m.active,
              createdAt: m.createdAt,
            })),
          );
        }
      })
      .catch(() => {
        if (!cancelled) setFetchError(t("settings.members.error"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  // t is stable — eslint-disable-next-line exhaustive-deps is intentional
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleRoleChange = async (m: Member, newRole: MemberRole) => {
    if (newRole === m.role) return;
    // RBAC change — confirm before applying. On cancel/failure, force a
    // re-render so the controlled <select> snaps back to the real role.
    if (!(await confirm(`${m.email}: ${m.role} → ${newRole}? ${t("settings.members.confirm.role")}`))) {
      setMembers((prev) => [...prev]);
      return;
    }
    setRoleChanging(m.id);
    try {
      const updated = await api.patchMember(m.id, { role: newRole as ApiRole });
      setMembers((prev) =>
        prev.map((mem) =>
          mem.id === m.id ? { ...mem, role: updated.role as MemberRole } : mem,
        ),
      );
      toast("success", t("common.saved"));
    } catch {
      toast("error", t("common.failed"));
      setMembers((prev) => [...prev]);
    } finally {
      setRoleChanging(null);
    }
  };

  const handleDeactivate = async (m: Member) => {
    if (!(await confirm(`${m.email} — ${t("settings.members.confirm.deactivate")}`))) return;
    setDeactivating(m.id);
    try {
      await api.deleteMember(m.id);
      setMembers((prev) => prev.filter((mem) => mem.id !== m.id));
      toast("success", t("common.removed"));
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setDeactivating(null);
    }
  };

  const handleInvited = (m: Member) => {
    setMembers((prev) => [...prev, m]);
    setInviting(false);
  };

  const active = members.filter((m) => m.active);

  return (
    <section className="flex flex-col gap-3">
      <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
        <div className="flex items-center gap-2.5 border-b border-border bg-sidebar px-4 py-3">
          <span className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">
            {t("settings.members.heading")} · <b className="text-text">{active.length}</b>
          </span>
          {isAdmin && (
            <button
              type="button"
              onClick={() => setInviting((v) => !v)}
              className="ml-auto rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F]"
            >
              {inviting ? t("common.close") : t("settings.members.invite")}
            </button>
          )}
        </div>

        {inviting && (
          <div className="border-b border-border p-4">
            <InviteMemberForm
              onInvited={handleInvited}
              onCancel={() => setInviting(false)}
            />
          </div>
        )}

        {loading ? (
          <div className="p-3">
            <SkeletonRows rows={3} />
          </div>
        ) : fetchError ? (
          <div className="px-5 py-6 text-center text-[13px] text-crit">
            {fetchError}
          </div>
        ) : active.length === 0 ? (
          <div className="px-5 py-6 text-center text-[13px] text-dim">
            {t("settings.members.empty")}
          </div>
        ) : (
          <table className="w-full border-collapse text-[13px]">
            <thead>
              <tr className="border-b border-border">
                <th scope="col" className="px-4 py-2 text-left text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                  {t("settings.members.col.member")}
                </th>
                <th scope="col" className="px-4 py-2 text-left text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                  {t("settings.members.col.role")}
                </th>
                <th scope="col" className="px-4 py-2 text-left text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                  {t("settings.members.col.joined")}
                </th>
                {isAdmin && (
                  <th scope="col" className="px-4 py-2 text-right text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                    {t("settings.members.col.actions")}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {active.map((m, i) => (
                <tr
                  key={m.id}
                  className={`hover:bg-surface-2 ${
                    i < active.length - 1 ? "border-b border-border" : ""
                  }`}
                >
                  <td className="px-4 py-[11px]">
                    <div className="flex items-center gap-2.5">
                      <span className="grid h-[30px] w-[30px] flex-none place-items-center rounded-full border border-border-strong bg-surface-2 font-mono text-[11px] font-semibold text-text">
                        {m.initials}
                      </span>
                      <div className="min-w-0">
                        <div className="font-mono text-[12.5px] text-text">
                          {m.email}
                        </div>
                        {m.displayName && m.displayName !== m.email && (
                          <div className="text-[11px] text-dim">
                            {m.displayName}
                          </div>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-[11px]">
                    {isAdmin ? (
                      <select
                        value={m.role}
                        disabled={roleChanging === m.id}
                        aria-label={`${t("settings.members.col.role")} — ${m.email}`}
                        onChange={(e) =>
                          void handleRoleChange(m, e.target.value as MemberRole)
                        }
                        className="rounded-full border-0 bg-transparent px-[9px] py-0.5 text-[11px] font-semibold outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
                        style={{ color: "inherit" }}
                      >
                        {ALL_ROLES.map((r) => (
                          <option key={r} value={r}>
                            {t(`role.${r}`)}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <span
                        className={`inline-flex items-center rounded-full px-[9px] py-0.5 text-[11px] font-semibold tracking-[0.04em] ${ROLE_CHIP[m.role]}`}
                      >
                        {t(`role.${m.role}`)}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-[11px] font-mono text-[12px] text-dim">
                    {m.createdAt
                      ? new Date(m.createdAt).toLocaleDateString(lang === "ko" ? "ko-KR" : "en-US")
                      : "—"}
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-[11px] text-right">
                      <button
                        type="button"
                        disabled={deactivating === m.id || me?.email === m.email}
                        onClick={() => void handleDeactivate(m)}
                        title={
                          me?.email === m.email
                            ? t("settings.members.cannotSelf")
                            : t("settings.members.deactivate")
                        }
                        className="rounded-[7px] border border-border px-2.5 py-1 text-[12px] font-semibold text-crit/70 hover:text-crit disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {deactivating === m.id ? "…" : t("settings.members.deactivate")}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}

function InviteMemberForm({
  onInvited,
  onCancel,
}: {
  onInvited: (m: Member) => void;
  onCancel: () => void;
}) {
  const { t } = useT();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inputCls =
    "w-full rounded-lg border border-border bg-bg px-3 py-2.5 font-mono text-[13px] text-text outline-none placeholder:text-dim focus:border-primary";
  const labelCls =
    "mb-[7px] block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted";

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const email = (form.elements.namedItem("inv-email") as HTMLInputElement).value.trim();
    const displayName = (form.elements.namedItem("inv-name") as HTMLInputElement).value.trim();
    const role = (form.elements.namedItem("inv-role") as HTMLSelectElement).value as ApiRole;
    const password = (form.elements.namedItem("inv-password") as HTMLInputElement).value;
    if (!email || !password) return;
    setError(null);
    setSubmitting(true);
    try {
      const created = await api.createMember({ email, displayName: displayName || email, role, password });
      onInvited({
        id: created.id,
        email: created.email,
        displayName: created.displayName,
        initials: memberInitials(created.displayName, created.email),
        role: created.role as MemberRole,
        active: created.active,
        createdAt: created.createdAt,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : t("settings.members.form.error"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <div className="text-[13px] font-semibold">{t("settings.members.form.title")}</div>
      <div className="grid grid-cols-2 gap-3.5">
        <div>
          <label htmlFor="inv-email" className={labelCls}>
            {t("settings.members.form.email")}
          </label>
          <input
            id="inv-email"
            name="inv-email"
            type="email"
            required
            className={inputCls}
            placeholder="user@example.com"
          />
        </div>
        <div>
          <label htmlFor="inv-name" className={labelCls}>
            {t("settings.members.form.displayName")}
          </label>
          <input
            id="inv-name"
            name="inv-name"
            className={inputCls}
            placeholder="Jane Smith"
          />
        </div>
        <div>
          <label htmlFor="inv-password" className={labelCls}>
            {t("settings.members.form.tempPassword")}
          </label>
          <input
            id="inv-password"
            name="inv-password"
            type="password"
            required
            className={inputCls}
            placeholder="••••••••"
          />
        </div>
        <div>
          <label htmlFor="inv-role" className={labelCls}>
            {t("settings.members.form.role")}
          </label>
          <select
            id="inv-role"
            name="inv-role"
            className={inputCls}
            defaultValue="VIEWER"
          >
            {ALL_ROLES.map((r) => (
              <option key={r} value={r}>
                {t(`role.${r}`)}
              </option>
            ))}
          </select>
        </div>
      </div>
      {error && (
        <p className="rounded-lg border border-crit/30 bg-crit/[0.08] px-3 py-2 text-[12px] text-crit">
          {error}
        </p>
      )}
      <div className="flex gap-2.5">
        <button
          type="submit"
          disabled={submitting}
          className="rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#06121F] disabled:opacity-50"
        >
          {submitting ? t("settings.members.form.creating") : t("settings.members.form.submit")}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-border-strong px-4 py-2 text-[13px] font-semibold text-text"
        >
          {t("common.cancel")}
        </button>
      </div>
    </form>
  );
}

/* ── Policy rules (MA3-92: API-backed) ───────────────────────────────── */

function PolicySection({ initialRules }: { initialRules: PolicyRule[] }) {
  const { t } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [rules, setRules] = useState<PolicyRule[]>(initialRules);
  const [adding, setAdding] = useState(false);
  const [toggling, setToggling] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);

  const ACTION_LABEL: Record<PolicyAction, string> = {
    AUTO_BLOCK: t("settings.policy.action.autoBlock"),
    HOLD: t("settings.policy.action.hold"),
  };

  const handleToggle = async (r: PolicyRule) => {
    setToggling(r.id);
    try {
      const updated = await api.patchPolicyRule(r.id, { enabled: !r.enabled });
      setRules((prev) =>
        prev.map((rule) => (rule.id === r.id ? { ...rule, enabled: updated.enabled } : rule)),
      );
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setToggling(null);
    }
  };

  const handleDelete = async (id: string) => {
    if (!(await confirm(t("settings.policy.confirm.delete")))) return;
    setDeleting(id);
    try {
      await api.deletePolicyRule(id);
      setRules((prev) => prev.filter((r) => r.id !== id));
      toast("success", t("common.deleted"));
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setDeleting(null);
    }
  };

  const handleCreated = (r: PolicyRule) => {
    setRules((prev) => [...prev, r]);
    setAdding(false);
  };

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">
            {t("settings.policy.heading")}
          </h2>
          <p className="mt-1.5 max-w-[460px] text-[12px] leading-relaxed text-dim">
            {t("settings.policy.subtitle")}
          </p>
        </div>
        <button
          type="button"
          onClick={() => setAdding((v) => !v)}
          className="ml-4 flex-none rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F]"
        >
          {adding ? t("common.close") : t("settings.policy.addRule")}
        </button>
      </div>

      {adding && <AddPolicyRuleForm onCreated={handleCreated} onCancel={() => setAdding(false)} />}

      <div className="flex flex-col gap-2.5">
        {rules
          .slice()
          .sort((a, b) => a.priority - b.priority)
          .map((r) => {
            const isBusy = toggling === r.id || deleting === r.id;
            return (
              <div
                key={r.id}
                className="flex items-start gap-3.5 rounded-[11px] border border-border bg-surface px-[15px] py-3"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-[10px] text-dim">#{r.priority}</span>
                    <div className="text-[13.5px] font-semibold">{r.name}</div>
                  </div>
                  <div className="mt-0.5 text-[12px] leading-relaxed text-muted">
                    {t("settings.policy.action")}{" "}
                    <span
                      className={
                        r.action === "AUTO_BLOCK" ? "text-crit" : "text-warn"
                      }
                    >
                      {ACTION_LABEL[r.action]}
                    </span>
                    {r.condition.severity && (
                      <> · {t("settings.policy.severity")} <span className="font-mono">{r.condition.severity}</span></>
                    )}
                    {r.condition.minRiskScore !== undefined && (
                      <> · {t("settings.policy.risk")} {r.condition.minRiskScore}</>
                    )}
                    {r.condition.findingCategory && (
                      <> · {t("settings.policy.category")} <span className="font-mono">{r.condition.findingCategory}</span></>
                    )}
                    {r.condition.nameRegex && (
                      <> · {t("settings.policy.nameMatches")} <span className="font-mono">{r.condition.nameRegex}</span></>
                    )}
                  </div>
                </div>

                {/* Enabled badge */}
                <span
                  className={`mt-0.5 inline-flex flex-none items-center gap-1.5 rounded-full px-[9px] py-0.5 text-[11px] font-semibold ${
                    r.enabled
                      ? "bg-approved/[0.13] text-approved"
                      : "bg-muted/[0.12] text-dim"
                  }`}
                >
                  <span
                    aria-hidden
                    className={`h-1.5 w-1.5 rounded-full ${
                      r.enabled ? "bg-approved" : "bg-dim"
                    }`}
                  />
                  {r.enabled ? t("common.on") : t("common.off")}
                </span>

                {/* Toggle */}
                <button
                  type="button"
                  role="switch"
                  aria-checked={r.enabled}
                  aria-label={`Toggle ${r.name}`}
                  disabled={isBusy}
                  onClick={() => handleToggle(r)}
                  className={`relative h-[21px] w-[38px] flex-none rounded-full border transition-colors disabled:opacity-50 ${
                    r.enabled
                      ? "border-approved/50 bg-approved/[0.22]"
                      : "border-border-strong bg-surface-2"
                  }`}
                >
                  <span
                    className={`absolute top-[2px] h-[15px] w-[15px] rounded-full transition-all ${
                      r.enabled ? "left-[19px] bg-approved" : "left-[2px] bg-dim"
                    }`}
                  />
                </button>

                {/* Delete */}
                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => handleDelete(r.id)}
                  className="rounded-[7px] border border-border bg-transparent px-2.5 py-1.5 text-[12px] font-semibold text-crit/70 hover:text-crit disabled:cursor-not-allowed disabled:opacity-40"
                  aria-label={`Delete rule ${r.name}`}
                >
                  {deleting === r.id ? "…" : t("common.delete")}
                </button>
              </div>
            );
          })}

        {rules.length === 0 && !adding && (
          <div className="rounded-[11px] border border-border bg-surface px-5 py-6 text-center text-[13px] text-dim">
            {t("settings.policy.empty")}
          </div>
        )}
      </div>
    </section>
  );
}

/* ── Suppressions (MA3-94: API-backed) ───────────────────────────────── */

/**
 * SuppressionsSection — lists org scanner suppressions with scope labels.
 * - GET /api/settings/suppressions on mount.
 * - ADMIN users get DELETE per row.
 * - ADMIN users also get an "Add suppression" form (manual org-wide or tool-scoped rule).
 */
function SuppressionsSection({
  initialSuppressions,
}: {
  initialSuppressions: Suppression[];
}) {
  const { me } = useAuth();
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const isAdmin = me?.role === "ADMIN";

  const [suppressions, setSuppressions] = useState<Suppression[]>(initialSuppressions);
  const [loading, setLoading] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  // Reload from API (called after create or to refresh stale SSR data)
  const reload = async () => {
    setLoading(true);
    try {
      const data = await api.getSuppressions();
      setSuppressions(
        data.map((s) => ({
          id: s.id,
          ruleId: s.ruleId,
          toolId: s.toolId,
          fingerprint: s.fingerprint,
          reason: s.reason,
          createdBy: s.createdBy,
          createdAt: s.createdAt,
        })),
      );
    } catch {
      // Keep existing list; user can retry.
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!(await confirm(t("settings.suppress.confirm.delete")))) return;
    setDeleting(id);
    try {
      await api.deleteSuppression(id);
      setSuppressions((prev) => prev.filter((s) => s.id !== id));
      toast("success", t("common.removed"));
    } catch {
      toast("error", t("common.failed"));
    } finally {
      setDeleting(null);
    }
  };

  const handleCreated = (s: Suppression) => {
    setSuppressions((prev) => [s, ...prev]);
    setAdding(false);
  };

  function scopeLabel(s: Suppression): string {
    const tool = s.toolId
      ? `${t("settings.suppress.scope.tool")}${s.toolId.slice(0, 8)}`
      : t("settings.suppress.scope.orgWide");
    const fp = s.fingerprint
      ? t("settings.suppress.scope.fingerprint")
      : t("settings.suppress.scope.wholeRule");
    return `${tool} · ${fp}`;
  }

  const SUPP_COLS = [
    t("settings.suppress.col.ruleId"),
    t("settings.suppress.col.scope"),
    t("settings.suppress.col.reason"),
    t("settings.suppress.col.createdBy"),
    t("settings.suppress.col.createdAt"),
  ];

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">
            {t("settings.suppress.heading")}
          </h2>
          <p className="mt-1.5 max-w-[460px] text-[12px] leading-relaxed text-dim">
            {t("settings.suppress.subtitle")}
          </p>
        </div>
        <div className="ml-4 flex gap-2">
          <button
            type="button"
            onClick={() => void reload()}
            disabled={loading}
            className="flex-none rounded-lg border border-border-strong px-3.5 py-2 text-[13px] font-semibold text-text disabled:opacity-50"
          >
            {loading ? t("common.loading") : t("common.refresh")}
          </button>
          {isAdmin && (
            <button
              type="button"
              onClick={() => setAdding((v) => !v)}
              className="flex-none rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F]"
            >
              {adding ? t("common.close") : t("settings.suppress.add")}
            </button>
          )}
        </div>
      </div>

      {adding && isAdmin && (
        <AddSuppressionForm onCreated={handleCreated} onCancel={() => setAdding(false)} />
      )}

      <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
        {suppressions.length === 0 ? (
          <div className="px-5 py-6 text-center text-[13px] text-dim">
            {t("settings.suppress.empty")}
          </div>
        ) : (
          <table className="w-full border-collapse text-[13px]">
            <thead>
              <tr className="border-b border-border">
                {SUPP_COLS.map((h) =>
                  h === t("settings.suppress.col.createdAt") && !isAdmin ? null : (
                    <th
                      key={h}
                      scope="col"
                      className="px-4 py-2.5 text-left text-[11px] font-semibold uppercase tracking-[0.08em] text-dim"
                    >
                      {h}
                    </th>
                  ),
                )}
                {isAdmin && (
                  <th scope="col" className="px-4 py-2.5 text-right text-[11px] font-semibold uppercase tracking-[0.08em] text-dim">
                    {t("settings.members.col.actions")}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {suppressions.map((s, i) => (
                <tr
                  key={s.id}
                  className={`hover:bg-surface-2 ${
                    i < suppressions.length - 1 ? "border-b border-border" : ""
                  }`}
                >
                  <td className="px-4 py-[11px] font-mono text-[12.5px] text-text">
                    {s.ruleId}
                    {s.fingerprint && (
                      <div className="mt-0.5 font-mono text-[10px] text-dim">
                        fp:{s.fingerprint.slice(0, 12)}…
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-[11px]">
                    <span
                      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                        s.toolId
                          ? "bg-warn/[0.12] text-warn"
                          : "bg-primary/[0.12] text-primary"
                      }`}
                    >
                      {scopeLabel(s)}
                    </span>
                  </td>
                  <td className="px-4 py-[11px] font-mono text-[12px] text-muted">
                    {s.reason ?? <span className="text-dim">—</span>}
                  </td>
                  <td className="px-4 py-[11px] font-mono text-[12px] text-dim">
                    {s.createdBy ?? "—"}
                  </td>
                  <td className="px-4 py-[11px] font-mono text-[12px] text-dim">
                    {new Date(s.createdAt).toLocaleDateString(lang === "ko" ? "ko-KR" : "en-US")}
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-[11px] text-right">
                      <button
                        type="button"
                        disabled={deleting === s.id}
                        onClick={() => void handleDelete(s.id)}
                        className="rounded-[7px] border border-border px-2.5 py-1 text-[12px] font-semibold text-crit/70 hover:text-crit disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {deleting === s.id ? "…" : t("common.delete")}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}

function AddSuppressionForm({
  onCreated,
  onCancel,
}: {
  onCreated: (s: Suppression) => void;
  onCancel: () => void;
}) {
  const { t } = useT();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inputCls =
    "w-full rounded-lg border border-border bg-bg px-3 py-2.5 font-mono text-[13px] text-text outline-none placeholder:text-dim focus:border-primary";
  const labelCls =
    "mb-[7px] block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted";

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const ruleId = (form.elements.namedItem("sup-ruleId") as HTMLInputElement).value.trim();
    const toolId = (form.elements.namedItem("sup-toolId") as HTMLInputElement).value.trim() || null;
    const fingerprint = (form.elements.namedItem("sup-fingerprint") as HTMLInputElement).value.trim() || null;
    const reason = (form.elements.namedItem("sup-reason") as HTMLInputElement).value.trim() || undefined;

    if (!ruleId) return;
    setError(null);
    setSubmitting(true);
    try {
      const created = await api.createSuppression({ ruleId, toolId, fingerprint, reason });
      onCreated({
        id: created.id,
        ruleId: created.ruleId,
        toolId: created.toolId,
        fingerprint: created.fingerprint,
        reason: created.reason,
        createdBy: created.createdBy,
        createdAt: created.createdAt,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : t("settings.suppress.form.error"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="overflow-hidden rounded-xl border border-border bg-surface"
    >
      <div className="border-b border-border px-5 py-4">
        <div className="text-[15px] font-semibold">{t("settings.suppress.form.title")}</div>
        <div className="mt-0.5 text-[12px] text-muted">
          {t("settings.suppress.form.subtitle")}
        </div>
      </div>
      <div className="flex flex-col gap-4 p-5">
        <div className="grid grid-cols-2 gap-3.5">
          <div>
            <label htmlFor="sup-ruleId" className={labelCls}>
              {t("settings.suppress.form.ruleId")}
            </label>
            <input
              id="sup-ruleId"
              name="sup-ruleId"
              required
              className={inputCls}
              placeholder="SEC-001"
            />
          </div>
          <div>
            <label htmlFor="sup-reason" className={labelCls}>
              {t("settings.suppress.form.reason")}
            </label>
            <input
              id="sup-reason"
              name="sup-reason"
              className={inputCls}
              placeholder="Accepted risk — tracked in JIRA-1234"
            />
          </div>
          <div>
            <label htmlFor="sup-toolId" className={labelCls}>
              {t("settings.suppress.form.toolId")}
            </label>
            <input
              id="sup-toolId"
              name="sup-toolId"
              className={inputCls}
              placeholder="UUID of the tool"
            />
          </div>
          <div>
            <label htmlFor="sup-fingerprint" className={labelCls}>
              {t("settings.suppress.form.fingerprint")}
            </label>
            <input
              id="sup-fingerprint"
              name="sup-fingerprint"
              className={inputCls}
              placeholder="sha256 fingerprint of the finding"
            />
          </div>
        </div>
        {error && (
          <p className="rounded-lg border border-crit/30 bg-crit/[0.08] px-3 py-2 text-[12px] text-crit">
            {error}
          </p>
        )}
        <div className="flex gap-2.5">
          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#06121F] disabled:opacity-50"
          >
            {submitting ? t("common.saving") : t("settings.suppress.form.submit")}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-border-strong px-4 py-2 text-[13px] font-semibold text-text"
          >
            {t("common.cancel")}
          </button>
        </div>
      </div>
    </form>
  );
}

function AddPolicyRuleForm({
  onCreated,
  onCancel,
}: {
  onCreated: (r: PolicyRule) => void;
  onCancel: () => void;
}) {
  const { t } = useT();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputCls =
    "w-full rounded-lg border border-border bg-bg px-3 py-2.5 font-mono text-[13px] text-text outline-none placeholder:text-dim focus:border-primary";
  const labelCls =
    "mb-[7px] block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted";

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const name = (form.elements.namedItem("pr-name") as HTMLInputElement).value.trim();
    const priority = parseInt(
      (form.elements.namedItem("pr-priority") as HTMLInputElement).value,
      10,
    );
    const action = (form.elements.namedItem("pr-action") as HTMLSelectElement).value as PolicyAction;
    const severity = (form.elements.namedItem("pr-severity") as HTMLInputElement).value.trim();
    const minRiskScoreRaw = (form.elements.namedItem("pr-risk") as HTMLInputElement).value.trim();
    const minRiskScore = minRiskScoreRaw ? parseFloat(minRiskScoreRaw) : undefined;

    if (!name || isNaN(priority)) return;

    const condition: PolicyCondition = {};
    if (severity) condition.severity = severity;
    if (minRiskScore !== undefined) condition.minRiskScore = minRiskScore;

    setError(null);
    setSubmitting(true);
    try {
      const created = await api.createPolicyRule({
        name,
        priority,
        condition,
        action,
        enabled: true,
      });
      onCreated({
        id: created.id,
        name: created.name,
        priority: created.priority,
        condition: created.condition,
        action: created.action,
        enabled: created.enabled,
      });
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : t("common.failed"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="overflow-hidden rounded-xl border border-border bg-surface"
    >
      <div className="border-b border-border px-5 py-4">
        <div className="text-[15px] font-semibold">{t("settings.policy.form.title")}</div>
        <div className="mt-0.5 text-[12px] text-muted">
          {t("settings.policy.form.subtitle")}
        </div>
      </div>
      <div className="flex flex-col gap-4 p-5">
        <div className="grid grid-cols-[2fr_1fr_1fr] gap-3.5">
          <div>
            <label htmlFor="pr-name" className={labelCls}>
              {t("settings.policy.form.name")}
            </label>
            <input
              id="pr-name"
              name="pr-name"
              className={inputCls}
              placeholder="Auto-block CRITICAL"
            />
          </div>
          <div>
            <label htmlFor="pr-priority" className={labelCls}>
              {t("settings.policy.form.priority")}
            </label>
            <input
              id="pr-priority"
              name="pr-priority"
              type="number"
              min={1}
              defaultValue={10}
              className={inputCls}
            />
          </div>
          <div>
            <label htmlFor="pr-action" className={labelCls}>
              {t("settings.policy.form.action")}
            </label>
            <select id="pr-action" name="pr-action" className={inputCls} defaultValue="AUTO_BLOCK">
              <option value="AUTO_BLOCK">{t("settings.policy.action.autoBlock")}</option>
              <option value="HOLD">{t("settings.policy.action.hold")}</option>
            </select>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3.5">
          <div>
            <label htmlFor="pr-severity" className={labelCls}>
              {t("settings.policy.form.severityFilter")}
            </label>
            <input
              id="pr-severity"
              name="pr-severity"
              className={inputCls}
              placeholder="CRITICAL"
            />
          </div>
          <div>
            <label htmlFor="pr-risk" className={labelCls}>
              {t("settings.policy.form.minRisk")}
            </label>
            <input
              id="pr-risk"
              name="pr-risk"
              type="number"
              min={0}
              max={100}
              step={1}
              className={inputCls}
              placeholder="80"
            />
          </div>
        </div>
        {error && <p role="alert" className="text-[12px] text-crit">{error}</p>}
        <div className="flex gap-2.5">
          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#06121F] disabled:opacity-50"
          >
            {submitting ? t("common.saving") : t("settings.policy.form.submit")}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-border-strong px-4 py-2 text-[13px] font-semibold text-text"
          >
            {t("common.cancel")}
          </button>
        </div>
      </div>
    </form>
  );
}
