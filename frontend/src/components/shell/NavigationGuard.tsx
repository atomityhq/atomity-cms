"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";

/*
- Guards in-app navigation while a screen has unsaved changes. The App Router has
- no route-change event to cancel, so every shell link goes through <GuardedLink>,
- which asks first when a screen has called useBlockNavigation(true). Other ways of
- leaving the screen, like signing out, go through useGuardedAction. Tab close and
- reload are covered separately by useUnsavedChangesWarning.
 */

interface NavigationGuardApi {
  setBlocked: (blocked: boolean) => void;
  /** Navigates to `href`, asking first if navigation is blocked. */
  navigate: (href: string) => void;
  /** Runs `action`, asking first if navigation is blocked. */
  run: (action: () => void) => void;
  isBlocked: () => boolean;
}

interface GuardHistoryState {
  __navGuard?: boolean;
  [key: string]: unknown;
}

const NavigationGuardContext = createContext<NavigationGuardApi | null>(null);

function isGuardState(state: unknown): state is GuardHistoryState {
  return typeof state === "object" && state !== null && Boolean((state as GuardHistoryState).__navGuard);
}

export function NavigationGuardProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const blocked = useRef(false);
  // Skips intercepting the next popstate event caused by programmatic history cleanup/traversal.
  const bypassNextPopState = useRef(false);
  // Tracks how many guard history entries were pushed in the current dirty cycle.
  const guardDepth = useRef(0);
  const [pendingAction, setPendingAction] = useState<(() => void) | null>(null);

  // Ensures a guard entry sits at the top of history so Back/Forward can be intercepted cleanly.
  const ensureGuardEntry = useCallback(() => {
    if (typeof window === "undefined") return;
    if (isGuardState(window.history.state)) return;

    const currentState =
      typeof window.history.state === "object" && window.history.state !== null
        ? (window.history.state as Record<string, unknown>)
        : {};

    window.history.pushState({ ...currentState, __navGuard: true }, "", window.location.href);
    guardDepth.current += 1;
  }, []);

  // Removes the synthetic guard entry when leaving dirty state (e.g., after Save or unmount)
  // so subsequent Back clicks do not land on stale guard or /content/new history entries.
  const cleanupGuardEntry = useCallback(() => {
    if (typeof window === "undefined") return;

    if (isGuardState(window.history.state) && guardDepth.current > 0) {
      guardDepth.current = Math.max(0, guardDepth.current - 1);
      bypassNextPopState.current = true;
      window.history.back();
      return;
    }

    guardDepth.current = 0;
  }, []);

  const setBlocked = useCallback(
    (value: boolean) => {
      const wasBlocked = blocked.current;
      blocked.current = value;

      if (value) {
        ensureGuardEntry();
      } else if (wasBlocked || guardDepth.current > 0) {
        cleanupGuardEntry();
      }
    },
    [cleanupGuardEntry, ensureGuardEntry],
  );

  useEffect(() => {
    const handlePopState = () => {
      if (bypassNextPopState.current) {
        bypassNextPopState.current = false;
        return;
      }

      if (!blocked.current) return;

      // Keep the user on the same page while he or she decides to enter the Dialog
      ensureGuardEntry();

      // If a dialog is already open from a rapid multi-step Back press, keep the existing pending action
      // so cancelling preserves unsaved changes and unmounting does not happen.
      setPendingAction((existing) => {
        if (existing) return existing;

        return () => {
          blocked.current = false;
          // Step past the injected guard entry and the current page entry back to the previous page.
          const stepsBack = isGuardState(window.history.state) ? -2 : -1;
          guardDepth.current = 0;
          // We bypass the security entrance we added and take one step back
          bypassNextPopState.current = true;
          window.history.go(stepsBack);
        };
      });
    };

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [ensureGuardEntry]);

  const api = useMemo<NavigationGuardApi>(() => {
    const run = (action: () => void) => {
      // Wrapped, because a function passed to a state setter would be called as an updater.
      if (blocked.current) setPendingAction(() => action);
      else action();
    };

    return {
      setBlocked,
      isBlocked: () => blocked.current,
      navigate: (href) => run(() => router.push(href)),
      run,
    };
  }, [router, setBlocked]);

  return (
    <NavigationGuardContext.Provider value={api}>
      {children}
      {pendingAction && (
        <ConfirmDialog
          title="Discard unsaved changes?"
          message="You have changes that haven’t been saved. Leave anyway?"
          confirmLabel="Discard changes"
          danger
          onClose={() => setPendingAction(null)}
          onConfirm={() => {
            const action = pendingAction;
            setPendingAction(null);
            action();
          }}
        />
      )}
    </NavigationGuardContext.Provider>
  );
}

function useNavigationGuard(): NavigationGuardApi {
  const context = useContext(NavigationGuardContext);
  if (!context) throw new Error("Navigation guard used outside <NavigationGuardProvider>");
  return context;
}

/** Blocks shell navigation while `dirty` is true; unblocks on unmount. */
export function useBlockNavigation(dirty: boolean): void {
  const { setBlocked } = useNavigationGuard();
  useEffect(() => {
    setBlocked(dirty);
    return () => setBlocked(false);
  }, [dirty, setBlocked]);
}

/** Programmatic navigation that respects the guard (e.g. after an action). */
export function useGuardedNavigate(): (href: string) => void {
  return useNavigationGuard().navigate;
}

/** Runs an action that leaves the screen some other way (e.g. signing out), asking first like a guarded link. */
export function useGuardedAction(): (action: () => void) => void {
  return useNavigationGuard().run;
}

/** A next/link that asks before leaving a screen with unsaved changes. */
export function GuardedLink({ href, onClick, ...props }: React.ComponentProps<typeof Link> & { href: string }) {
  const guard = useNavigationGuard();
  const handleClick = useCallback(
    (event: React.MouseEvent<HTMLAnchorElement>) => {
      onClick?.(event);
      // Let modified clicks (new tab, etc.) through untouched.
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
      if (guard.isBlocked()) {
        event.preventDefault();
        guard.navigate(href);
      }
    },
    [guard, href, onClick],
  );
  return <Link href={href} onClick={handleClick} {...props} />;
}