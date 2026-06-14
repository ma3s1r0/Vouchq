"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type { AssetStatus } from "@/lib/mock-inventory";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n";
import { useToast, useConfirm } from "@/lib/feedback";

type Decision = "approved" | "blocked";

/**
 * Approve&pin / Block action bar for the detail page (POST /api/tools/{id}/approve
 * | /block). Awaits the API and only confirms on success — a failed call shows an
 * error, never a false "pinned". Approve/Block require MEMBER or ADMIN (VIEWER is
 * install-only). On success it refreshes so the real new status is shown.
 */
export function DecisionActions({
  status,
  toolId,
}: {
  status: AssetStatus;
  toolId: string;
}) {
  const router = useRouter();
  const { me } = useAuth();
  const { t } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [decision, setDecision] = useState<Decision | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const drifted = status === "DRIFTED";
  const canDecide = me?.role === "ADMIN" || me?.role === "MEMBER";

  const run = async (kind: Decision) => {
    if (kind === "blocked" && !(await confirm(t("decision.confirm.block")))) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (kind === "approved") await api.approveTool(toolId);
      else await api.blockTool(toolId);
      setDecision(kind);
      toast(
        "success",
        kind === "approved"
          ? drifted
            ? t("decision.result.reapproved")
            : t("decision.result.approved")
          : t("decision.result.blocked"),
      );
      router.refresh();
    } catch {
      const msg =
        kind === "approved" ? t("decision.error.approve") : t("decision.error.block");
      setError(msg);
      toast("error", msg);
    } finally {
      setBusy(false);
    }
  };

  if (decision) {
    return (
      <div
        className={`rounded-[11px] border px-4 py-3 text-[13px] ${
          decision === "approved"
            ? "border-approved/40 bg-approved/[0.10] text-approved"
            : "border-blocked/40 bg-blocked/[0.10] text-blocked"
        }`}
      >
        {decision === "approved"
          ? drifted
            ? t("decision.result.reapproved")
            : t("decision.result.approved")
          : t("decision.result.blocked")}
      </div>
    );
  }

  if (!canDecide) {
    return (
      <p className="rounded-[11px] border border-border bg-surface px-4 py-3 text-[12px] text-muted">
        {t("decision.roleGate")}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2.5">
        <button
          type="button"
          onClick={() => run("approved")}
          disabled={busy}
          className="rounded-lg bg-approved px-3.5 py-2 text-[13px] font-semibold text-[#04130A] disabled:opacity-60"
        >
          {busy ? "…" : drifted ? t("decision.approvePinDrifted") : t("decision.approvePin")}
        </button>
        <button
          type="button"
          onClick={() => run("blocked")}
          disabled={busy}
          className="rounded-lg border border-blocked/[0.45] bg-transparent px-3.5 py-2 text-[13px] font-semibold text-blocked disabled:opacity-60"
        >
          {t("action.block")}
        </button>
      </div>
      {error && <p className="text-[12px] text-crit">{error}</p>}
    </div>
  );
}
