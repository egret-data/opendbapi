import { Lock } from "lucide-react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { findWorkspace, useAuth } from "@/auth/useAuth";
import { Alert, AlertDescription } from "@/components/ui/alert";

/** Whether the workspace is managed by configuration files only (spec §15.2). */
export function useConfigLocked(ws: string | undefined): boolean {
  const { me } = useAuth();
  return findWorkspace(me, ws)?.configLocked ?? false;
}

/** Tells members that endpoints and the like change through configuration files here. */
export function ConfigLockedNotice() {
  const { t } = useTranslation();
  const { ws } = useParams();
  if (!useConfigLocked(ws)) return null;
  return (
    <div className="px-4 pt-4">
      <Alert>
        <Lock className="h-4 w-4" aria-hidden="true" />
        <AlertDescription>
          {t("This workspace is managed by configuration files: endpoints, groups, service accounts and data policies change through opendbapi apply. You can still test, preview and manage datasources, secrets and clients.")}
        </AlertDescription>
      </Alert>
    </div>
  );
}
