import { AppShell } from "@/components/shell/AppShell";
import { PageHeading, ApprovalsSubtitle } from "@/components/shell/PageHeading";
import { ApprovalQueue } from "@/components/approvals/ApprovalQueue";
import { getApprovalQueue } from "@/lib/mock-approvals";

export default async function ApprovalsPage() {
  const items = await getApprovalQueue();

  return (
    <AppShell crumb="Approvals">
      <PageHeading
        titleKey="page.approvals.title"
        subtitle={<ApprovalsSubtitle count={items.length} />}
      />
      <ApprovalQueue items={items} />
    </AppShell>
  );
}
