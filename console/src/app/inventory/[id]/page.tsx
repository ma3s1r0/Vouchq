import { notFound } from "next/navigation";
import { AppShell } from "@/components/shell/AppShell";
import { DetailBreadcrumb } from "@/components/shell/DetailBreadcrumb";
import { StatusBadge } from "@/components/inventory/StatusBadge";
import { DiffView } from "@/components/inventory/DiffView";
import { FindingsPanel } from "@/components/inventory/FindingsPanel";
import { VersionHistory } from "@/components/inventory/VersionHistory";
import { DecisionActions } from "@/components/inventory/DecisionActions";
import { AddToClaude } from "@/components/inventory/AddToClaude";
import { McpInstall } from "@/components/inventory/McpInstall";
import { getToolDetail } from "@/lib/mock-tool-detail";

export default async function ToolDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const tool = await getToolDetail(decodeURIComponent(id));
  if (!tool) notFound();

  return (
    <AppShell crumbKey="nav.inventory">
      <DetailBreadcrumb leaf={tool.name} />

      <div className="flex flex-wrap items-center gap-3">
        <h1 className="font-mono text-[20px] font-semibold">{tool.name}</h1>
        <span className="rounded border border-border px-1.5 py-0.5 font-mono text-[11px] text-muted">
          {tool.kind}
        </span>
        <StatusBadge status={tool.status} />
      </div>
      <p className="mb-[18px] mt-1 font-mono text-[12px] text-dim">
        {tool.source} · sha256 {tool.shaShort}…
      </p>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-[1fr_minmax(0,360px)]">
        <div className="flex flex-col gap-3">
          {tool.drift && <DiffView name={tool.name} diff={tool.drift} />}
          <FindingsPanel
            effectiveRisk={tool.effectiveRisk}
            rawRisk={tool.rawRisk}
            findings={tool.findings}
            toolId={tool.id}
            scanTarget={tool.name}
            scannedSha={tool.shaShort}
            scannedAt={tool.scannedAt ?? "—"}
          />
          <DecisionActions status={tool.status} toolId={tool.id} />
        </div>
        <div className="flex flex-col gap-3">
          {tool.kind === "SKILL" &&
            (tool.status === "APPROVED" || tool.status === "DRIFTED") && (
              <AddToClaude toolId={tool.id} />
            )}
          {tool.kind === "MCP_TOOL" &&
            (tool.status === "APPROVED" || tool.status === "DRIFTED") && (
              <McpInstall toolId={tool.id} />
            )}
          <VersionHistory name={tool.name} history={tool.history} />
        </div>
      </div>
    </AppShell>
  );
}
