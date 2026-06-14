"use client";

import { useEffect, useRef, useState } from "react";

/**
 * A dark-themed custom dropdown (MA3-136). Native <select> can't style its option
 * popup — it renders the OS default, which clashes with the Control Room theme —
 * so this is a button trigger + a styled listbox. Accessible: aria-haspopup /
 * aria-expanded, role=listbox/option, Escape + click-outside to close, arrow-key
 * navigation, and the selected option highlighted.
 */
export function Select<T extends string>({
  label,
  value,
  options,
  onChange,
  format,
  ariaLabel,
}: {
  label?: string;
  value: T;
  options: readonly T[];
  onChange: (v: T) => void;
  format?: (v: T) => string;
  ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const display = format ? format(value) : value;

  useEffect(() => {
    if (!open) return;
    setActive(Math.max(0, options.indexOf(value)));
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open, options, value]);

  const choose = (v: T) => {
    onChange(v);
    setOpen(false);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") return setOpen(false);
    if (!open && (e.key === "ArrowDown" || e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      return setOpen(true);
    }
    if (!open) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(options.length - 1, a + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      choose(options[active]);
    }
  };

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        onKeyDown={onKeyDown}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel ?? label}
        className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-surface-2 px-2.5 py-[7px] text-[12px] text-muted transition-colors hover:border-border-strong focus-visible:border-primary"
      >
        {label && <span>{label}</span>}
        <span className="font-semibold text-text">{display}</span>
        <span
          aria-hidden
          className={`text-[10px] text-dim transition-transform ${open ? "rotate-180" : ""}`}
        >
          ▾
        </span>
      </button>

      {open && (
        <ul
          role="listbox"
          aria-label={ariaLabel ?? label}
          className="absolute left-0 z-50 mt-1 max-h-64 min-w-[150px] overflow-auto rounded-lg border border-border-strong bg-surface py-1 shadow-2xl"
        >
          {options.map((o, i) => {
            const selected = o === value;
            return (
              <li
                key={o}
                role="option"
                aria-selected={selected}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(o)}
                className={`cursor-pointer px-3 py-1.5 text-[12.5px] ${
                  selected
                    ? "bg-primary/[0.14] font-semibold text-[#9DC3FF]"
                    : i === active
                      ? "bg-surface-2 text-text"
                      : "text-text"
                }`}
              >
                {format ? format(o) : o}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
