"use client";

import { useT } from "@/lib/i18n";

/**
 * Route error boundary (MA3-119). A server-component data fetch failure (e.g. the
 * backend is unreachable) now shows an explicit error with retry — instead of a
 * crash, or worse, looking like an empty/zero result on a trust product.
 */
export default function RouteError({ reset }: { error: Error; reset: () => void }) {
  const { t } = useT();
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center px-6 text-center">
      <span className="mb-3 grid h-11 w-11 place-items-center rounded-xl border border-crit/40 bg-crit/[0.08] text-[20px] text-crit">
        !
      </span>
      <h1 className="text-[17px] font-semibold text-text">{t("error.title")}</h1>
      <p className="mt-2 max-w-[420px] text-[13px] leading-relaxed text-muted">
        {t("error.desc")}
      </p>
      <button
        type="button"
        onClick={reset}
        className="mt-5 rounded-lg bg-primary px-4 py-2 text-[13px] font-semibold text-[#04101F] transition-opacity hover:opacity-90"
      >
        {t("error.retry")}
      </button>
    </div>
  );
}
