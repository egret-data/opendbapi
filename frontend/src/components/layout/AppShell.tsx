import { useEffect, useState, type ReactNode } from "react";

import { TooltipProvider } from "@/components/ui/tooltip";
import { SIDEBAR_RAIL_BELOW_PX, useIsNarrowViewport } from "@/lib/useIsNarrowViewport";
import { Header } from "./Header";
import { Sidebar } from "./Sidebar";

const STORAGE_KEY = "opendbapi:sidebar-collapsed";

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

/** Header + sidebar + scrolling main area (spec §17.2). */
export function AppShell({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(readCollapsed);
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, collapsed ? "1" : "0");
    } catch {
      /* storage unavailable: the preference just does not persist */
    }
  }, [collapsed]);

  // Below `lg` there is no room for the expanded rail; the stored desktop preference is
  // left untouched so a phone visit does not rewrite it.
  const narrow = useIsNarrowViewport(SIDEBAR_RAIL_BELOW_PX);

  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex h-full flex-col bg-background">
        <Header />
        <div className="flex min-h-0 flex-1">
          <Sidebar collapsed={narrow || collapsed} onToggle={narrow ? undefined : () => setCollapsed((c) => !c)} />
          <main className="min-w-0 flex-1 overflow-auto p-6">{children}</main>
        </div>
      </div>
    </TooltipProvider>
  );
}
