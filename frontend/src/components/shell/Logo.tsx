import { cn } from "@/lib/utils";

/** Text wordmark: a green tile with the initial, plus the product name. */
export function Logo({ collapsed = false, className }: { collapsed?: boolean; className?: string }) {
  return (
    <span className={cn("flex items-center gap-2", className)}>
      <span
        className="flex h-[22px] w-[22px] items-center justify-center rounded-[6px] bg-green font-mono text-[12px] font-bold text-ink before:content-['A']"
        aria-hidden="true"
      />
      {!collapsed && <span className="font-mono text-[13px] font-bold tracking-[0.04em] uppercase">atomity-cms</span>}
    </span>
  );
}
