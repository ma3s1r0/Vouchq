/**
 * Inline nav icons, traced from design-system/components/app-shell.html.
 * 16px, currentColor stroke, no fill.
 */
type IconProps = { className?: string };

const base = "h-4 w-4 flex-none";
const svg = (children: React.ReactNode, className?: string) => (
  <svg
    viewBox="0 0 24 24"
    className={`${base} ${className ?? ""}`}
    fill="none"
    stroke="currentColor"
    strokeWidth={1.7}
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    {children}
  </svg>
);

export const DashboardIcon = ({ className }: IconProps) =>
  svg(
    <>
      <rect x="3" y="3" width="7" height="7" rx="1" />
      <rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" />
      <rect x="14" y="14" width="7" height="7" rx="1" />
    </>,
    className,
  );

export const InventoryIcon = ({ className }: IconProps) =>
  svg(<path d="M4 6h16M4 12h16M4 18h16" />, className);

export const ApprovalsIcon = ({ className }: IconProps) =>
  svg(
    <>
      <path d="M9 12l2 2 4-4" />
      <circle cx="12" cy="12" r="9" />
    </>,
    className,
  );

export const DriftIcon = ({ className }: IconProps) =>
  svg(
    <>
      <path d="M12 9v4M12 17h.01" />
      <path d="M10.3 3.6L2 18a2 2 0 001.7 3h16.6a2 2 0 001.7-3L13.7 3.6a2 2 0 00-3.4 0z" />
    </>,
    className,
  );

export const AuditIcon = ({ className }: IconProps) =>
  svg(
    <>
      <path d="M4 4h16v16H4z" />
      <path d="M8 9h8M8 13h8M8 17h5" />
    </>,
    className,
  );

export const SettingsIcon = ({ className }: IconProps) =>
  svg(
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19 12a7 7 0 00-.1-1l2-1.5-2-3.5-2.4 1a7 7 0 00-1.7-1L14.5 3h-5l-.3 2.5a7 7 0 00-1.7 1l-2.4-1-2 3.5L3.1 11a7 7 0 000 2l-2 1.5 2 3.5 2.4-1a7 7 0 001.7 1l.3 2.5h5l.3-2.5a7 7 0 001.7-1l2.4 1 2-3.5-2-1.5a7 7 0 00.1-1z" />
    </>,
    className,
  );
