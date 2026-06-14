"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useT } from "@/lib/i18n";

/**
 * Login page — session-based auth (MA3-93).
 * POST /api/auth/login {email, password} → sets JSESSIONID cookie.
 * On success: navigate to dashboard.
 * On 401: show inline error.
 *
 * Dev bootstrap: admin@vouchq.local / admin
 */
export default function LoginPage() {
  const { t } = useT();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) return;
    setError(null);
    setSubmitting(true);
    try {
      await api.login({ email, password });
      // Full navigation (not router.replace): AuthProvider only fetches /me on
      // mount, so a client-side transition would leave the app in a stale
      // unauthenticated state (sidebar "connecting…", role-gated) until a manual
      // refresh. A real load re-establishes the session cleanly.
      window.location.assign("/dashboard");
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      if (msg.includes("401")) {
        setError(t("login.error"));
      } else {
        setError(t("login.serverError"));
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="flex min-h-screen items-center justify-center px-6">
      <div className="w-full max-w-sm rounded-xl border border-border bg-surface p-8 shadow-xl">
        <div className="mb-8 flex flex-col items-center text-center">
          <span className="mb-3 grid h-9 w-9 place-items-center rounded-lg bg-gradient-to-br from-primary to-approved font-mono text-base font-bold text-[#06121F]">
            V
          </span>
          <h1 className="text-2xl font-semibold tracking-tight">vouchq</h1>
          <p className="mt-1 text-sm text-muted">{t("login.subtitle")}</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label
              htmlFor="email"
              className="mb-1 block text-xs font-medium text-muted"
            >
              {t("login.email")}
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="admin@vouchq.local"
              className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm text-text outline-none placeholder:text-dim focus:border-primary"
            />
          </div>
          <div>
            <label
              htmlFor="password"
              className="mb-1 block text-xs font-medium text-muted"
            >
              {t("login.password")}
            </label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm text-text outline-none focus:border-primary"
            />
          </div>

          {error && (
            <p
              role="alert"
              className="rounded-lg border border-crit/30 bg-crit/[0.08] px-3 py-2 text-[12px] text-crit"
            >
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="block w-full rounded-lg bg-primary px-4 py-2 text-center text-sm font-semibold text-[#04101F] transition hover:opacity-90 disabled:opacity-50"
          >
            {submitting ? "…" : t("login.submit")}
          </button>
        </form>

        <p className="mt-6 text-center text-xs text-dim">{t("login.tagline")}</p>
      </div>
    </main>
  );
}
