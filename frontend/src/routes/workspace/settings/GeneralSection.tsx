import { useId, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { authKeys, type WorkspaceRole } from "@/api/auth";
import { useSystemInfo } from "@/api/generated/system/system";
import { getGetWorkspaceQueryKey, useGetWorkspace, useUpdateWorkspace } from "@/api/generated/workspaces/workspaces";
import type { WorkspaceView } from "@/api/generated/model";
import { QueryBoundary } from "@/components/QueryBoundary";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/errors";
import { ROLE_LABEL } from "@/lib/roles";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";

export function GeneralSection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const query = useGetWorkspace(ws);
  return (
    <QueryBoundary query={query} what="the workspace">
      {(workspace) => <GeneralForm key={workspace.name} workspace={workspace} role={role} />}
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
  const canEdit = role === "owner";
  const dirty = name.trim() !== workspace.name;
  useUnsavedChangesWarning(dirty);
  const quotas = Object.entries(workspace.quotas);

  const save = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    update.mutate(
      { ws: workspace.slug, data: { name: name.trim(), quotas: null } },
      {
        onSuccess: () => {
          toast.success("Workspace renamed.");
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
        <ConfigTableGroup label="Workspace" />
        <ConfigTableRow label="Name" htmlFor={nameId} details={canEdit ? "Shown in the workspace switcher" : "Only owners can rename the workspace"}>
          <Input id={nameId} value={name} required maxLength={120} disabled={!canEdit} onChange={(e) => setName(e.target.value)} />
        </ConfigTableRow>
        <ConfigTableRow label="Slug" details="Part of every endpoint URL; it never changes">
          <span className="font-mono text-sm">{workspace.slug}</span>
        </ConfigTableRow>
        <ConfigTableRow label="Endpoint base URL" details="Published endpoints answer under this address">
          <span className="font-mono text-sm break-all">
            {info.data ? `${info.data.servingBaseUrl}/${workspace.slug}/` : "…"}
          </span>
        </ConfigTableRow>
        <ConfigTableRow label="Your role" details="Change it on the Members tab, if you are allowed to">
          {ROLE_LABEL[role]}
        </ConfigTableRow>
        <ConfigTableGroup label="Quotas" />
        {quotas.length === 0 ? (
          <ConfigTableRow label="Limits" details="Set by a system administrator">
            <span className="text-sm text-muted-foreground">Defaults</span>
          </ConfigTableRow>
        ) : (
          quotas.map(([key, value]) => (
            <ConfigTableRow key={key} label={key} details="Set by a system administrator">
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
          <Button type="submit" disabled={!dirty || update.isPending} title={!dirty ? "No changes to save" : undefined}>
            Save changes
          </Button>
          <Button type="button" variant="outline" disabled={!dirty} onClick={() => setName(workspace.name)}>
            Discard
          </Button>
        </div>
      )}
    </form>
  );
}
