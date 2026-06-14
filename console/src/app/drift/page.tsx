import { AppShell } from "@/components/shell/AppShell";
import { PageHeading, DriftSubtitle } from "@/components/shell/PageHeading";
import { DriftList } from "@/components/drift/DriftList";
import { getDriftAlerts } from "@/lib/mock-drift";

export default async function DriftPage() {
  const alerts = await getDriftAlerts();

  return (
    <AppShell crumb="Drift">
      <PageHeading
        titleKey="page.drift.title"
        subtitle={<DriftSubtitle count={alerts.length} />}
      />
      <DriftList alerts={alerts} />
    </AppShell>
  );
}
