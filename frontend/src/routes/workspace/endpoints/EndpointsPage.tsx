import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { Plug, Plus } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { useListDatasources } from "@/api/generated/datasources/datasources";
import {
  getListEndpointsQueryKey,
  useDeleteEndpoint,
  useDisableEndpoint,
  useDuplicateEndpoint,
  useEnableEndpoint,
  useListEndpoints,
  useRestoreEndpoint,
  useTrashEndpoint,
} from "@/api/generated/endpoints/endpoints";
import { useListGroups } from "@/api/generated/groups/groups";
import { useSystemInfo } from "@/api/generated/system/system";
import type { EndpointSummary } from "@/api/generated/model";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { RowActionsMenu } from "@/components/list/RowActionsMenu";
import { SummaryStrip } from "@/components/list/SummaryStrip";
import { Button } from "@/components/ui/button";
import { DropdownMenuItem, DropdownMenuSeparator } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDate } from "@/lib/format";
import { STATE_LABEL, STATE_TONE } from "./endpointForm";

const ALL = "__all__";

export default function EndpointsPage() {
  const { ws = "" } = useParams();
  const { me } = useAuth();
  const canAuthor = hasRole(effectiveRole(me, ws) ?? "viewer", "author");
  const [params, setParams] = useSearchParams();
  const trashed = params.get("view") === "trash";
  const state = params.get("state") ?? "";
  const group = params.get("group") ?? "";
  const datasource = params.get("ds") ?? "";
  const [q, setQ] = useState("");
  const query = useListEndpoints(ws, { trashed });
  const trashCount = useListEndpoints(ws, { trashed: true }, { query: { enabled: !trashed } }).data?.length ?? 0;
  const groups = useListGroups(ws);
  const datasources = useListDatasources(ws);
  const info = useSystemInfo();
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) });
  const opts = { mutation: { onSuccess: invalidate, onError: (e: unknown) => toast.error(errorMessage(e)) } };
  const disable = useDisableEndpoint(opts);
  const enable = useEnableEndpoint(opts);
  const trash = useTrashEndpoint(opts);
  const restore = useRestoreEndpoint(opts);
  const duplicate = useDuplicateEndpoint(opts);
  const remove = useDeleteEndpoint(opts);
  const [deleting, setDeleting] = useState<EndpointSummary | null>(null);
  const set = (patch: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ ...Object.fromEntries(params), ...patch }).filter(([, v]) => v)), { replace: true });
  const dsName = (id?: string | null) => datasources.data?.find((d) => d.id === id)?.name ?? "—";
  const url = (e: EndpointSummary) => `${info.data?.servingBaseUrl ?? ""}/${ws}/${e.path}`;

  const newButton = canAuthor ? (
    <Button asChild>
      <Link to="new">
        <Plus aria-hidden="true" />
        New endpoint
      </Link>
    </Button>
  ) : undefined;

  return (
    <ListPageShell
      title={trashed ? "Trash" : "Endpoints"}
      description={trashed ? "Endpoints here are not served. Restore them, or delete them for good." : "SQL published as REST APIs."}
      back={trashed ? <Button variant="link" className="px-0" onClick={() => set({ view: "" })}>← All endpoints</Button> : undefined}
      toolbar={
        <div className="flex flex-wrap items-center gap-2">
          <Input type="search" aria-label="Search endpoints" placeholder="Search name or path" className="w-60" value={q}
            onChange={(e) => setQ(e.target.value)} />
          <Select value={group || ALL} onValueChange={(v) => set({ group: v === ALL ? "" : v })}>
            <SelectTrigger className="w-44" aria-label="Group">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>All groups</SelectItem>
              {(groups.data ?? []).map((g) => (
                <SelectItem key={g.id} value={g.id}>{g.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={datasource || ALL} onValueChange={(v) => set({ ds: v === ALL ? "" : v })}>
            <SelectTrigger className="w-44" aria-label="Datasource">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>All datasources</SelectItem>
              {(datasources.data ?? []).map((d) => (
                <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          {!trashed && (
            <>
              <Button variant="ghost" size="sm" onClick={() => set({ view: "trash" })}>
                Trash{trashCount > 0 ? ` (${trashCount})` : ""}
              </Button>
              <Button asChild variant="ghost" size="sm">
                <a href={`${info.data?.servingBaseUrl ?? "/api"}/${ws}/docs.html`} target="_blank" rel="noreferrer">
                  API docs
                </a>
              </Button>
              <Button asChild variant="ghost" size="sm">
                <a href={`/admin/api/ws/${ws}/openapi.json`} download={`${ws}-openapi.json`}>Download OpenAPI</a>
              </Button>
            </>
          )}
        </div>
      }
      action={trashed ? undefined : newButton}
    >
      <QueryBoundary query={query} what="endpoints">
        {(all) => {
          const counts = { live: 0, draft: 0, disabled: 0 } as Record<string, number>;
          all.forEach((e) => (counts[e.state] = (counts[e.state] ?? 0) + 1));
          const needle = q.trim().toLowerCase();
          const shown = all.filter(
            (e) =>
              (!state || e.state === state) &&
              (!group || e.groupId === group) &&
              (!datasource || e.datasourceId === datasource) &&
              (!needle || e.name.toLowerCase().includes(needle) || e.path.toLowerCase().includes(needle)),
          );
          if (all.length === 0) {
            return trashed ? (
              <EmptyState title="The trash is empty" />
            ) : (
              <EmptyState variant="dashed" icon={Plug} title="No endpoints yet"
                description={canAuthor ? "Write a query and publish it as an API." : "Authors of this workspace create endpoints."}
                action={newButton} />
            );
          }
          return (
            <div className="space-y-3">
              {!trashed && (
                <SummaryStrip
                  items={[
                    { label: "endpoints", singular: "endpoint", value: all.length, active: !state, onClick: () => set({ state: "" }) },
                    { label: "live", value: counts.live, active: state === "live", onClick: () => set({ state: "live" }) },
                    { label: "drafts", singular: "draft", value: counts.draft, active: state === "draft", onClick: () => set({ state: "draft" }) },
                    { label: "disabled", value: counts.disabled, active: state === "disabled", onClick: () => set({ state: "disabled" }) },
                  ]}
                />
              )}
              {shown.length === 0 ? (
                <EmptyState title="No endpoint matches" description="Change the search or the filters." />
              ) : (
                <div className="overflow-x-auto rounded-md border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Name</TableHead>
                        <TableHead>Route</TableHead>
                        <TableHead>State</TableHead>
                        <TableHead>Version</TableHead>
                        <TableHead>Datasource</TableHead>
                        <TableHead>Updated</TableHead>
                        <TableHead className="w-10"><span className="sr-only">Actions</span></TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {shown.map((e) => (
                        <TableRow key={e.id}>
                          <TableCell className="font-medium">
                            {trashed ? e.name : <Link className="hover:underline" to={e.id}>{e.name}</Link>}
                          </TableCell>
                          <TableCell className="font-mono text-xs">
                            <span className="mr-2 font-semibold">{e.method}</span>/{e.path}
                          </TableCell>
                          <TableCell>
                            <span className="inline-flex items-center gap-2">
                              <StatusBadge tone={STATE_TONE[e.state]}>{STATE_LABEL[e.state]}</StatusBadge>
                              {e.dirty && e.liveVersion != null && (
                                <span className="text-xs text-muted-foreground" title="The draft differs from the live version">• unpublished changes</span>
                              )}
                              {e.access === "public" && <StatusBadge tone="highlight">Public</StatusBadge>}
                            </span>
                          </TableCell>
                          <TableCell className="tabular-nums">{e.liveVersion != null ? `v${e.liveVersion}` : "—"}</TableCell>
                          <TableCell>{dsName(e.datasourceId)}</TableCell>
                          <TableCell className="tabular-nums">{formatDate(e.updatedAt)}</TableCell>
                          <TableCell>
                            {canAuthor && (
                              <RowActionsMenu label={`Actions for ${e.name}`}>
                                {trashed ? (
                                  <>
                                    <DropdownMenuItem onSelect={() => restore.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(`${e.name} restored.`) })}>
                                      Restore
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onSelect={() => setDeleting(e)}>Delete for good…</DropdownMenuItem>
                                  </>
                                ) : (
                                  <>
                                    <DropdownMenuItem asChild><Link to={e.id}>Open</Link></DropdownMenuItem>
                                    <DropdownMenuItem onSelect={() => navigator.clipboard?.writeText(url(e)).then(() => toast.success("URL copied."))}>
                                      Copy URL
                                    </DropdownMenuItem>
                                    {e.liveVersion != null && (
                                      <DropdownMenuItem asChild>
                                        <a href={`/admin/api/ws/${ws}/endpoints/${e.id}/openapi.json`} download>Download OpenAPI</a>
                                      </DropdownMenuItem>
                                    )}
                                    <DropdownMenuItem onSelect={() => duplicate.mutate({ ws, id: e.id }, { onSuccess: (c) => toast.success(`Copied as /${c.summary.path}.`) })}>
                                      Duplicate
                                    </DropdownMenuItem>
                                    <DropdownMenuSeparator />
                                    {e.state === "live" && (
                                      <DropdownMenuItem onSelect={() => disable.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(`${e.name} disabled.`) })}>
                                        Disable
                                      </DropdownMenuItem>
                                    )}
                                    {e.state === "disabled" && (
                                      <DropdownMenuItem onSelect={() => enable.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(`${e.name} is live again.`) })}>
                                        Enable
                                      </DropdownMenuItem>
                                    )}
                                    <DropdownMenuItem
                                      disabled={e.state === "live"}
                                      title={e.state === "live" ? "Disable it first: a live endpoint cannot go to the trash" : undefined}
                                      onSelect={() => trash.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(`${e.name} moved to the trash.`) })}
                                    >
                                      Move to trash
                                    </DropdownMenuItem>
                                  </>
                                )}
                              </RowActionsMenu>
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
          );
        }}
      </QueryBoundary>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={`Delete ${deleting?.name ?? "endpoint"} for good?`}
        description={<p>Its versions are deleted too. This cannot be undone.</p>}
        confirmLabel="Delete"
        destructive
        pending={remove.isPending}
        onConfirm={() => deleting && remove.mutate({ ws, id: deleting.id }, { onSuccess: () => setDeleting(null) })}
      />
    </ListPageShell>
  );
}
