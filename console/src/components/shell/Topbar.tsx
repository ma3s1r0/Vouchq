"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useT } from "@/lib/i18n";

export function Topbar({ crumb }: { crumb: string }) {
  const { lang, setLang, t } = useT();
  const router = useRouter();
  const [q, setQ] = useState("");

  // Global asset search (MA3-114): routes to the inventory pre-filtered by the
  // query (name / sha256 / source), which reads ?q.
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const term = q.trim();
    router.push(term ? `/inventory?q=${encodeURIComponent(term)}` : "/inventory");
  };

  return (
    <header className="flex h-[54px] flex-none items-center gap-3.5 border-b border-border bg-sidebar px-[22px]">
      <span className="text-[13px] text-muted">
        <b className="font-semibold text-text">{crumb}</b>
      </span>
      <form onSubmit={submit} className="ml-auto">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="w-[230px] rounded-[7px] border border-border bg-bg px-[11px] py-1.5 font-mono text-[12px] text-text outline-none placeholder:text-dim focus:border-primary"
          placeholder={t("shell.search")}
          aria-label={t("shell.search")}
        />
      </form>
      {/* Language toggle (MA3-101) */}
      <div className="flex items-center overflow-hidden rounded-[7px] border border-border text-[11px] font-semibold">
        {(["en", "ko"] as const).map((l) => (
          <button
            key={l}
            type="button"
            onClick={() => setLang(l)}
            aria-pressed={lang === l}
            aria-label={l === "en" ? "English" : "한국어"}
            className={`px-2 py-1 ${
              lang === l ? "bg-primary text-[#04101F]" : "text-muted hover:text-text"
            }`}
          >
            {l === "en" ? "EN" : "한국어"}
          </button>
        ))}
      </div>
    </header>
  );
}
