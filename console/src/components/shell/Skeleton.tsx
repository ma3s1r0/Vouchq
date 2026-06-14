/**
 * Shimmer skeleton placeholders (MA3-114 UX) for client-side fetches, so a
 * loading list reads as "loading" rather than empty or a bare "Loading…".
 */
export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`animate-pulse rounded bg-surface-2 ${className}`} />;
}

/** A few placeholder rows for a loading table/list. */
export function SkeletonRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-2">
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          className="flex items-center gap-3 rounded-[10px] border border-border bg-surface px-4 py-3"
        >
          <Skeleton className="h-3.5 w-3.5 flex-none rounded-full" />
          <Skeleton className="h-3 w-1/3" />
          <Skeleton className="ml-auto h-3 w-16" />
        </div>
      ))}
    </div>
  );
}
