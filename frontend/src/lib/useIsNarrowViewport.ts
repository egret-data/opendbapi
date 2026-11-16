import { useCallback, useSyncExternalStore } from "react";

/** Viewport width below which the header compacts (icon-only buttons, flexible switcher). */
export const NARROW_VIEWPORT_PX = 768;

/** Window width below which the app sidebar is an icon rail, whatever the stored
 *  preference: an expanded 224px sidebar leaves too little room for tables. Tailwind's `lg`. */
export const SIDEBAR_RAIL_BELOW_PX = 1024;

/** Whether the window is narrower than `px` (default `NARROW_VIEWPORT_PX`). */
export function useIsNarrowViewport(px: number = NARROW_VIEWPORT_PX): boolean {
  const query = `(max-width: ${px - 1}px)`;
  const hasMatchMedia = typeof window !== "undefined" && typeof window.matchMedia === "function";
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (!hasMatchMedia) return () => {};
      const mq = window.matchMedia(query);
      mq.addEventListener("change", onChange);
      return () => mq.removeEventListener("change", onChange);
    },
    [query, hasMatchMedia],
  );
  return useSyncExternalStore(
    subscribe,
    () => (hasMatchMedia ? window.matchMedia(query).matches : false),
    () => false,
  );
}
