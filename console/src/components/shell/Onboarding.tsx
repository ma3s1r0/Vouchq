"use client";

import Link from "next/link";
import { useT } from "@/lib/i18n";

/**
 * First-run empty state (MA3-114 UX). Shown on the dashboard / inventory when an
 * org has no assets yet, so a new user has an obvious next step instead of a
 * blank screen: the connect → scan → approve loop + a CTA to add a source.
 */
export function Onboarding() {
  const { t } = useT();
  const steps = [
    t("onboarding.step1"),
    t("onboarding.step2"),
    t("onboarding.step3"),
  ];
  return (
    <div className="flex flex-col items-center rounded-[14px] border border-border bg-surface px-6 py-12 text-center">
      <span className="mb-4 grid h-12 w-12 place-items-center rounded-xl bg-gradient-to-br from-primary to-approved font-mono text-[20px] font-bold text-[#06121F]">
        V
      </span>
      <h2 className="text-[17px] font-semibold text-text">{t("onboarding.title")}</h2>
      <p className="mt-2 max-w-[460px] text-[13px] leading-relaxed text-muted">
        {t("onboarding.desc")}
      </p>

      <div className="mt-5 flex flex-wrap items-center justify-center gap-2 text-[12px]">
        {steps.map((s, i) => (
          <div key={i} className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-surface-2 px-3 py-1 font-medium text-text">
              <span className="font-mono text-[11px] text-primary">{i + 1}</span>
              {s}
            </span>
            {i < steps.length - 1 && <span className="text-dim">→</span>}
          </div>
        ))}
      </div>

      <Link
        href="/settings"
        className="mt-6 rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#04101F] transition-opacity hover:opacity-90"
      >
        {t("onboarding.cta")}
      </Link>
    </div>
  );
}
