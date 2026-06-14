"use client";

import { useAuth } from "@/lib/auth-context";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";

/**
 * App shell: fixed left sidebar nav + topbar, scrollable content area.
 * Faithful to design-system/components/app-shell.html.
 *
 * Auth guard: on mount, AuthProvider (in root layout) fetches GET /api/auth/me.
 * If 401 it redirects to /login. While loading we show a minimal skeleton
 * rather than a flash of content.
 */
export function AppShell({
  crumb,
  children,
}: {
  crumb: string;
  children: React.ReactNode;
}) {
  const { loading } = useAuth();

  // While auth check is in flight, render a blank dark screen (no redirect yet).
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-bg">
        <span className="font-mono text-[13px] text-dim">Loading…</span>
      </div>
    );
  }

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <section className="flex min-w-0 flex-1 flex-col">
        <Topbar crumb={crumb} />
        <div className="flex-1 overflow-auto p-[22px]">{children}</div>
      </section>
    </div>
  );
}
