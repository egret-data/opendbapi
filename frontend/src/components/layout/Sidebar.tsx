import { NavLink, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Activity,
  Building2,
  Database,
  Fingerprint,
  KeyRound,
  type LucideIcon,
  PanelLeftClose,
  PanelLeftOpen,
  Plug,
  ScrollText,
  Settings,
  Users,
} from "lucide-react";

import { useAuth } from "@/auth/useAuth";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

type NavItem = { label: string; icon: LucideIcon; to: string };

/** The workspace areas (spec §17.2). */
const WORKSPACE_ITEMS: NavItem[] = [
  { label: "Endpoints", icon: Plug, to: "endpoints" },
  { label: "Datasources", icon: Database, to: "datasources" },
  { label: "Clients", icon: KeyRound, to: "clients" },
  { label: "Activity", icon: Activity, to: "activity" },
];
const WORKSPACE_SETTINGS: NavItem = { label: "Settings", icon: Settings, to: "settings" };

/** System administration, shown only to system admins. */
const ADMIN_ITEMS: NavItem[] = [
  { label: "Workspaces", icon: Building2, to: "/admin/workspaces" },
  { label: "Users", icon: Users, to: "/admin/users" },
  { label: "Identity providers", icon: Fingerprint, to: "/admin/identity-providers" },
  { label: "Audit log", icon: ScrollText, to: "/admin/audit" },
];

export function Sidebar({ collapsed, onToggle }: { collapsed: boolean; onToggle?: () => void }) {
  const { me } = useAuth();
  const { ws } = useParams();
  const { t } = useTranslation();

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
      collapsed ? "justify-center" : ""
    } ${isActive ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:bg-accent/50 hover:text-foreground"}`;

  const renderItem = ({ label: english, icon: Icon, to }: NavItem) => {
    const label = t(english);
    const path = to.startsWith("/") ? to : `/w/${ws}/${to}`;
    const link = (
      <NavLink key={english} to={path} className={linkClass} aria-label={collapsed ? label : undefined}>
        <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
        {!collapsed && label}
      </NavLink>
    );
    if (!collapsed) return link;
    return (
      <Tooltip key={english}>
        <TooltipTrigger asChild>{link}</TooltipTrigger>
        <TooltipContent side="right">{label}</TooltipContent>
      </Tooltip>
    );
  };

  return (
    <nav
      data-testid="app-sidebar"
      aria-label={t("Main")}
      data-collapsed={collapsed ? "true" : "false"}
      className={`flex shrink-0 flex-col gap-1 overflow-y-auto border-r border-border bg-card p-3 transition-[width] duration-200 motion-reduce:transition-none ${
        collapsed ? "w-16" : "w-56"
      }`}
    >
      {ws && (
        <>
          {WORKSPACE_ITEMS.map(renderItem)}
          <hr className="my-2 border-border" />
          {renderItem(WORKSPACE_SETTINGS)}
        </>
      )}

      {me.systemAdmin && (
        <>
          {collapsed ? (
            <hr className="my-2 border-border" />
          ) : (
            <p className="px-3 pb-1 pt-3 text-xs font-medium uppercase tracking-wider text-muted-foreground">
              {t("Administration")}
            </p>
          )}
          {ADMIN_ITEMS.map(renderItem)}
        </>
      )}

      {onToggle &&
        (collapsed ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                onClick={onToggle}
                aria-label={t("Expand sidebar")}
                className="mt-auto flex items-center justify-center rounded-md px-3 py-2 text-muted-foreground transition-colors hover:bg-accent/50 hover:text-foreground"
              >
                <PanelLeftOpen className="h-4 w-4 shrink-0" />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">{t("Expand")}</TooltipContent>
          </Tooltip>
        ) : (
          <button
            type="button"
            onClick={onToggle}
            aria-label={t("Collapse sidebar")}
            className="mt-auto flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent/50 hover:text-foreground"
          >
            <PanelLeftClose className="h-4 w-4 shrink-0" />
            <span>{t("Collapse")}</span>
          </button>
        ))}
    </nav>
  );
}
