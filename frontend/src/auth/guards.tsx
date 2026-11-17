import type { ReactNode } from "react";
import { Navigate, Outlet, useLocation, useParams } from "react-router-dom";

import { useTranslation } from "react-i18next";

import { useMe } from "@/api/auth";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Forbidden } from "@/routes/Forbidden";
import { effectiveRole, hasRole } from "./useAuth";
import type { WorkspaceRole } from "@/api/auth";

function FullPageSkeleton() {
  const { t } = useTranslation();
  return (
    <div className="flex h-screen flex-col" aria-busy="true" aria-label={t("Loading")}>
      <Skeleton className="h-14 w-full rounded-none" />
      <div className="flex flex-1">
        <Skeleton className="w-56 rounded-none" />
        <div className="flex-1 space-y-3 p-6">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-32 w-full" />
        </div>
      </div>
    </div>
  );
}

/** Signed-in users only; everyone else goes to `/login`, remembering where they were. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { data: me, isPending, isError, error, refetch } = useMe();
  const location = useLocation();
  const { t } = useTranslation();

  // Error first (FE-STATE-1): a failed session check is not "signed out".
  if (isError) {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-3 p-6 text-center">
        <p className="text-sm text-muted-foreground">
          {t("Could not reach the server: {{error}}.", { error: error instanceof Error ? error.message : t("unknown error") })}
        </p>
        <Button size="sm" onClick={() => refetch()}>
          {t("Try again")}
        </Button>
      </div>
    );
  }
  if (isPending) return <FullPageSkeleton />;
  if (!me) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  return <div className="h-screen">{children}</div>;
}

/** The `:ws` in the URL must be a workspace the user is a member of (system admins: any). */
export function RequireWorkspaceMember() {
  const { data: me } = useMe();
  const { ws } = useParams();
  const { t } = useTranslation();
  if (!me || !effectiveRole(me, ws)) {
    return <Forbidden reason={t("You are not a member of this workspace, or it does not exist.")} />;
  }
  return <Outlet />;
}

export function RequireSystemAdmin() {
  const { data: me } = useMe();
  const { t } = useTranslation();
  if (!me?.systemAdmin) {
    return <Forbidden reason={t("This area is for system administrators.")} />;
  }
  return <Outlet />;
}

/** Pages that need a minimum workspace role, such as create pages for admins. */
export function RequireRole({ minimum, children }: { minimum: WorkspaceRole; children: ReactNode }) {
  const { data: me } = useMe();
  const { ws } = useParams();
  const { t } = useTranslation();
  const mine = me ? effectiveRole(me, ws) : undefined;
  if (!mine || !hasRole(mine, minimum)) {
    return <Forbidden reason={t("This page needs the {{role}} role in this workspace.", { role: minimum })} />;
  }
  return <>{children}</>;
}
