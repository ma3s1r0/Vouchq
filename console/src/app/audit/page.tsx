import { AppShell } from "@/components/shell/AppShell";
import { PageHeading, AuditSubtitle } from "@/components/shell/PageHeading";
import { AuditLog } from "@/components/audit/AuditLog";

// The audit log is unbounded, so it paginates/filters server-side (MA3-118):
// the client component fetches one page at a time rather than the whole chain.
export default function AuditPage() {
  return (
    <AppShell crumb="Audit log">
      <PageHeading titleKey="page.audit.title" subtitle={<AuditSubtitle />} />
      <AuditLog />
    </AppShell>
  );
}
