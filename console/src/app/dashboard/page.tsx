import { AppShell } from "@/components/shell/AppShell";
import { PageHeading } from "@/components/shell/PageHeading";
import { StatCard } from "@/components/StatCard";
import { RiskDistribution } from "@/components/dashboard/RiskDistribution";
import { RecentActivityList } from "@/components/dashboard/RecentActivity";
import { Onboarding } from "@/components/shell/Onboarding";
import { getDashboardSummary } from "@/lib/mock-dashboard";

export default async function DashboardPage() {
  const s = await getDashboardSummary();

  if (s.totalAssets === 0) {
    return (
      <AppShell crumb="Dashboard">
        <PageHeading titleKey="page.dashboard.title" />
        <Onboarding />
      </AppShell>
    );
  }

  return (
    <AppShell crumb="Dashboard">
      <PageHeading
        titleKey="page.dashboard.title"
        subtitle={
          <>
            {s.totalAssets} assets · {s.pendingApprovals} pending approval ·{" "}
            {s.unresolvedDrift} unresolved drift
          </>
        }
      />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          labelKey="dash.stat.assets"
          value={s.totalAssets}
          accent="primary"
          href="/inventory"
          delta={
            <>
              {s.lifecycle.approved} approved · {s.lifecycle.pending} pending ·{" "}
              {s.lifecycle.blocked} blocked
            </>
          }
        />
        <StatCard
          labelKey="dash.stat.approvals"
          value={s.pendingApprovals}
          accent="warn"
          deltaAccent="warn"
          href="/approvals"
          delta="awaiting review"
        />
        <StatCard
          labelKey="dash.stat.drift"
          value={s.unresolvedDrift}
          accent="drift"
          deltaAccent="blocked"
          href="/drift"
          delta={
            <>
              <b className="font-semibold">
                {s.unresolvedDriftBySeverity.critical} critical
              </b>{" "}
              · {s.unresolvedDriftBySeverity.warn} warn
            </>
          }
        />
        <StatCard
          labelKey="dash.stat.blocked"
          value={s.lifecycle.blocked}
          accent="blocked"
          href="/inventory"
          delta="currently blocked"
        />
      </div>

      <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2">
        <RiskDistribution distribution={s.riskDistribution} />
        <RecentActivityList activity={s.recentActivity} />
      </div>
    </AppShell>
  );
}
