import { useId, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { authKeys, type WorkspaceRole } from "@/api/auth";
import { useSystemInfo } from "@/api/generated/system/system";
import { getGetWorkspaceQueryKey, useGetWorkspace, useUpdateWorkspace } from "@/api/generated/workspaces/workspaces";
import type { WorkspaceView } from "@/api/generated/model";
import { QueryBoundary } from "@/components/QueryBoundary";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { errorMessage } from "@/lib/errors";
import { ROLE_LABEL } from "@/lib/roles";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";

export function GeneralSection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const query = useGetWorkspace(ws);
  return (
    <QueryBoundary query={query} what="the workspace">
      {(workspace) => <GeneralForm key={`${workspace.name}-${workspace.configLocked}`} workspace={workspace} role={role} />}
    </QueryBoundary>
  );
}

function GeneralForm({ workspace, role }: { workspace: WorkspaceView; role: WorkspaceRole }) {
  const qc = useQueryClient();
  const info = useSystemInfo();
  const update = useUpdateWorkspace();
  const [name, setName] = useState(workspace.name);
  const [error, setError] = useState<string | null>(null);
  const nameId = useId();
  const lockId = useId();
  const canEdit = role === "owner";
  const dirty = name.trim() !== workspace.name;
  useUnsavedChangesWarning(dirty);
  const quotas = Object.entries(workspace.quotas);
  const { t } = useTranslation();

  const toggleLock = (locked: boolean) =>
    update.mutate({ ws: workspace.slug, data: { name: null, quotas: null, configLocked: locked } }, {
      onSuccess: () => {
        toast.success(locked ? t("This workspace now changes through configuration files.") : t("Editing in the UI is open again."));
        qc.invalidateQueries({ queryKey: getGetWorkspaceQueryKey(workspace.slug) });
        qc.invalidateQueries({ queryKey: authKeys.me });
      },
      onError: (err) => toast.error(errorMessage(err)),
    });

  const save = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    update.mutate(
      { ws: workspace.slug, data: { name: name.trim(), quotas: null, configLocked: null } },
      {
        onSuccess: () => {
          toast.success(t("Workspace renamed."));
          qc.invalidateQueries({ queryKey: getGetWorkspaceQueryKey(workspace.slug) });
          qc.invalidateQueries({ queryKey: authKeys.me });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <form onSubmit={save} className="space-y-4">
      <ConfigTable valueClassName="w-96" detailsClassName="max-w-prose">
        <ConfigTableGroup label={t("Workspace")} />
        <ConfigTableRow label={t("Name")} htmlFor={nameId} details={canEdit ? t("Shown in the workspace switcher") : t("Only owners can rename the workspace")}>
          <Input id={nameId} value={name} required maxLength={120} disabled={!canEdit} onChange={(e) => setName(e.target.value)} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Slug")} details={t("Part of every endpoint URL; it never changes")}>
          <span className="font-mono text-sm">{workspace.slug}</span>
        </ConfigTableRow>
        <ConfigTableRow label={t("Endpoint base URL")} details={t("Published endpoints answer under this address")}>
          <span className="font-mono text-sm break-all">
            {info.data ? `${info.data.servingBaseUrl}/${workspace.slug}/` : "…"}
          </span>
        </ConfigTableRow>
        <ConfigTableRow label={t("Your role")} details={t("Change it on the Members tab, if you are allowed to")}>
          {t(ROLE_LABEL[role])}
        </ConfigTableRow>
        <ConfigTableGroup label={t("Configuration as code")} />
        <ConfigTableRow label={t("Managed by configuration files")} htmlFor={lockId}
          details={workspace.lockedByServer ? t("Locked for every workspace by the server (opendbapi.ui.read-only)")
            : t("Endpoints, groups, service accounts and data policies then change through opendbapi apply only, never by editing here")}>
          <Switch id={lockId} checked={workspace.configLocked} disabled={!canEdit || workspace.lockedByServer || update.isPending}
            onCheckedChange={toggleLock} />
        </ConfigTableRow>
        <ConfigTableGroup label={t("Quotas")} />
        {quotas.length === 0 ? (
          <ConfigTableRow label={t("Limits")} details={t("Set by a system administrator")}>
            <span className="text-sm text-muted-foreground">{t("Defaults")}</span>
          </ConfigTableRow>
        ) : (
          quotas.map(([key, value]) => (
            <ConfigTableRow key={key} label={key} details={t("Set by a system administrator")}>
              <span className="tabular-nums">{String(value)}</span>
            </ConfigTableRow>
          ))
        )}
      </ConfigTable>
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {canEdit && (
        <div className="flex gap-2">
          <Button type="submit" disabled={!dirty || update.isPending} title={!dirty ? t("No changes to save") : undefined}>
            {t("Save changes")}
          </Button>
          <Button type="button" variant="outline" disabled={!dirty} onClick={() => setName(workspace.name)}>
            {t("Discard")}
          </Button>
        </div>
      )}
    </form>
  );
}
