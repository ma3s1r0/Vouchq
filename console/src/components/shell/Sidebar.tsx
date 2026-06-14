"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n";
import { api } from "@/lib/api";
import {
  ApprovalsIcon,
  AuditIcon,
  DashboardIcon,
  DriftIcon,
  InventoryIcon,
  SettingsIcon,
} from "./icons";

type NavItem = {
  href: string;
  label: string;
  Icon: (p: { className?: string }) => React.ReactElement;
};

const NAV: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", Icon: DashboardIcon },
  { href: "/inventory", label: "Inventory", Icon: InventoryIcon },
  { href: "/approvals", label: "Approvals", Icon: ApprovalsIcon },
  { href: "/drift", label: "Drift", Icon: DriftIcon },
  { href: "/audit", label: "Audit log", Icon: AuditIcon },
  { href: "/settings", label: "Settings", Icon: SettingsIcon },
];

/** Two-letter initials from a display name or email. */
function initials(displayName: string, email: string): string {
  const name = displayName.trim() || email;
  const parts = name.split(/[\s@.]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

export function Sidebar() {
  const pathname = usePathname();
  const { me, logout } = useAuth();
  const { t } = useT();

  // Live badge counts: pending approvals + unresolved drift, from the dashboard
  // summary. Keyed by nav href; only these two show a badge.
  const [badges, setBadges] = useState<Record<string, number>>({});
  useEffect(() => {
    let cancelled = false;
    api
      .getDashboardSummary()
      .then((s) => {
        if (!cancelled)
          setBadges({
            "/approvals": s.pendingApproval,
            "/drift": s.unresolvedDrift,
          });
      })
      .catch(() => {
        /* leave badges empty on error */
      });
    return () => {
      cancelled = true;
    };
  }, [pathname]);

  return (
    <aside className="flex w-[212px] flex-none flex-col border-r border-border bg-sidebar p-3">
      <div className="flex items-center gap-[9px] px-2 pb-[18px] pt-[6px] text-[15px] font-bold tracking-tight">
        <span className="grid h-[22px] w-[22px] place-items-center rounded-md bg-gradient-to-br from-primary to-approved font-mono text-[13px] font-bold text-[#06121F]">
          V
        </span>
        vouchq
      </div>

      <nav className="flex flex-col gap-0.5">
        {NAV.map(({ href, label, Icon }) => {
          const active =
            pathname === href || pathname.startsWith(`${href}/`);
          const count = badges[href];
          const navLabel = t(`nav.${href.slice(1)}`) || label;
          return (
            <Link
              key={href}
              href={href}
              aria-current={active ? "page" : undefined}
              className={[
                "flex items-center gap-2.5 rounded-[7px] px-2.5 py-2 text-[13px] font-medium transition-colors",
                active
                  ? "bg-primary/[0.12] text-[#9DC3FF]"
                  : "text-muted hover:bg-surface hover:text-text",
              ].join(" ")}
            >
              <Icon />
              {navLabel}
              {(count ?? 0) > 0 && (
                <span className="ml-auto font-mono text-[11px] text-drift">
                  {count}
                </span>
              )}
            </Link>
          );
        })}
      </nav>

      <div className="flex-1" />

      {/* User footer: shows logged-in user + logout */}
      <div className="mt-2 border-t border-border pt-2">
        {me ? (
          <div className="flex flex-col gap-1.5">
            <div className="flex items-center gap-[9px] px-2.5 py-1 text-[12px] text-muted">
              <span className="grid h-6 w-6 flex-none place-items-center rounded-full border border-border-strong bg-surface-2 font-mono text-[11px] font-semibold text-text">
                {initials(me.displayName, me.email)}
              </span>
              <div className="min-w-0 flex-1">
                <div className="truncate text-[12px] font-medium text-text">
                  {me.displayName || me.email}
                </div>
                <div className="truncate font-mono text-[10px] text-dim">
                  {t(`role.${me.role}`)}
                </div>
              </div>
            </div>
            <button
              type="button"
              onClick={() => void logout()}
              className="mx-1 rounded-[7px] border border-border px-2.5 py-1.5 text-[12px] font-medium text-muted transition-colors hover:border-border-strong hover:text-text"
            >
              {t("shell.signOut")}
            </button>
          </div>
        ) : (
          <div className="flex items-center gap-[9px] px-2.5 py-2 text-[12px] text-muted">
            <span className="grid h-6 w-6 place-items-center rounded-full border border-border-strong bg-surface-2 font-mono text-[11px] text-text">
              --
            </span>
            connecting…
          </div>
        )}
      </div>
    </aside>
  );
}
