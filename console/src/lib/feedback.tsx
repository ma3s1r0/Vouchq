"use client";

/**
 * In-app feedback (MA3-114 UX): themed toasts + a promise-based confirm dialog,
 * replacing native alert()/confirm() (which break the Control Room dark theme)
 * and the scattered silent catches. `useToast()` pushes success/error/info
 * toasts; `useConfirm()` returns an async confirm() that resolves true/false.
 *
 * Lives above AuthProvider but below LanguageProvider so the dialog can localize.
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { useT } from "./i18n";

type ToastKind = "success" | "error" | "info";
interface Toast {
  id: number;
  kind: ToastKind;
  text: string;
}
interface ConfirmReq {
  text: string;
  resolve: (ok: boolean) => void;
}

interface FeedbackApi {
  toast: (kind: ToastKind, text: string) => void;
  confirm: (text: string) => Promise<boolean>;
}

const FeedbackContext = createContext<FeedbackApi>({
  toast: () => {},
  confirm: async () => true,
});

let nextId = 1;

export function FeedbackProvider({ children }: { children: React.ReactNode }) {
  const { t } = useT();
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [req, setReq] = useState<ConfirmReq | null>(null);

  const toast = useCallback((kind: ToastKind, text: string) => {
    const id = nextId++;
    setToasts((prev) => [...prev, { id, kind, text }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((x) => x.id !== id));
    }, 4000);
  }, []);

  const confirm = useCallback(
    (text: string) =>
      new Promise<boolean>((resolve) => setReq({ text, resolve })),
    [],
  );

  const settle = (ok: boolean) => {
    req?.resolve(ok);
    setReq(null);
  };

  // Modal a11y (MA3-116): Escape cancels, Tab is trapped within the dialog, and
  // focus returns to the trigger on close. Cancel is autofocused (not the
  // destructive Confirm).
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!req) return;
    const trigger = document.activeElement as HTMLElement | null;
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        settle(false);
      } else if (e.key === "Tab") {
        const nodes = dialogRef.current?.querySelectorAll<HTMLElement>(
          'button, [href], input, [tabindex]:not([tabindex="-1"])',
        );
        if (!nodes || nodes.length === 0) return;
        const first = nodes[0];
        const last = nodes[nodes.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      trigger?.focus?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [req]);

  return (
    <FeedbackContext.Provider value={{ toast, confirm }}>
      {children}

      {/* Toast stack */}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[100] flex flex-col gap-2">
        {toasts.map((x) => (
          <div
            key={x.id}
            role={x.kind === "error" ? "alert" : "status"}
            aria-live={x.kind === "error" ? "assertive" : "polite"}
            className={`pointer-events-auto flex max-w-[360px] items-start gap-2 rounded-[10px] border px-3.5 py-2.5 text-[13px] shadow-xl ${
              x.kind === "success"
                ? "border-approved/40 bg-surface text-approved"
                : x.kind === "error"
                  ? "border-crit/40 bg-surface text-crit"
                  : "border-border bg-surface text-text"
            }`}
          >
            <span className="mt-[1px] font-mono text-[11px]" aria-hidden>
              {x.kind === "success" ? "✓" : x.kind === "error" ? "✕" : "•"}
            </span>
            <span className="text-text">{x.text}</span>
          </div>
        ))}
      </div>

      {/* Confirm dialog */}
      {req && (
        <div
          className="fixed inset-0 z-[110] flex items-center justify-center bg-black/60 px-6"
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-dialog-text"
          onClick={() => settle(false)}
        >
          <div
            ref={dialogRef}
            className="w-full max-w-sm rounded-xl border border-border bg-surface p-5 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <p id="confirm-dialog-text" className="text-[13px] leading-relaxed text-text">{req.text}</p>
            <div className="mt-4 flex justify-end gap-2.5">
              <button
                ref={cancelRef}
                type="button"
                onClick={() => settle(false)}
                className="rounded-lg border border-border px-3.5 py-2 text-[13px] font-semibold text-muted hover:text-text"
              >
                {t("common.cancel")}
              </button>
              <button
                type="button"
                onClick={() => settle(true)}
                className="rounded-lg bg-blocked px-3.5 py-2 text-[13px] font-semibold text-[#1a0606]"
              >
                {t("common.confirm")}
              </button>
            </div>
          </div>
        </div>
      )}
    </FeedbackContext.Provider>
  );
}

export function useToast() {
  return useContext(FeedbackContext).toast;
}

export function useConfirm() {
  return useContext(FeedbackContext).confirm;
}
