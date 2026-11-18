import { type DragEvent, Fragment, type ReactNode, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ChevronDown, ChevronRight, FolderPlus, ListChecks, Plug, Plus } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

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
import type { ApiGroup, EndpointSummary } from "@/api/generated/model";
import { useConfigLocked } from "@/components/ConfigLockedNotice";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { RowActionsMenu } from "@/components/list/RowActionsMenu";
import { SummaryStrip } from "@/components/list/SummaryStrip";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DropdownMenuItem, DropdownMenuSeparator } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDate } from "@/lib/format";
import { DeleteGroupDialog, GroupNameDialog, MoveToGroupDialog, useEndpointMoves } from "./EndpointGroups";
import { STATE_LABEL, STATE_TONE } from "./endpointForm";

const ALL = "__all__";
const DRAG_TYPE = "application/x-opendbapi-endpoints";

/** Which group headings are collapsed; remembered per workspace in this browser. */
function useCollapsed(ws: string) {
  const key = `opendbapi.endpoints.collapsed.${ws}`;
  const [collapsed, setCollapsed] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem(key) ?? "[]") as string[]);
    } catch {
      return new Set();
    }
  });
  const toggle = (id: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      try {
        localStorage.setItem(key, JSON.stringify([...next]));
      } catch {
        // private mode: remembered for this page only
      }
      return next;
    });
  return { collapsed, toggle };
}

type Section = { key: string; group: ApiGroup | null; rows: EndpointSummary[] };

export default function EndpointsPage() {
  const { ws = "" } = useParams();
  const { me } = useAuth();
  const { t } = useTranslation();
  const locked = useConfigLocked(ws);
  const canAuthor = hasRole(effectiveRole(me, ws) ?? "viewer", "author") && !locked;
  const [params, setParams] = useSearchParams();
  const trashed = params.get("view") === "trash";
  const state = params.get("state") ?? "";
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
  const groupList = groups.data ?? [];
  const moves = useEndpointMoves(ws, query.data ?? [], groupList);
  const { collapsed, toggle } = useCollapsed(ws);
  const [naming, setNaming] = useState<{ group?: ApiGroup; moveIds?: string[] } | null>(null);
  const [deletingGroup, setDeletingGroup] = useState<ApiGroup | null>(null);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [picking, setPicking] = useState(false);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const canOrganize = canAuthor && !trashed;
  const stopSelecting = () => {
    setSelecting(false);
    setSelected(new Set());
  };
  const moveSelected = (to: string | null) => {
    moves.request({ ids: [...selected], to });
    stopSelecting();
  };
  const dragStart = (e: DragEvent, id: string) => {
    const ids = selecting && selected.has(id) ? [...selected] : [id];
    e.dataTransfer.setData(DRAG_TYPE, JSON.stringify(ids));
    e.dataTransfer.effectAllowed = "move";
  };
  const dropProps = (key: string, to: string | null) =>
    canOrganize
      ? {
          onDragOver: (e: DragEvent) => {
            if (!e.dataTransfer.types.includes(DRAG_TYPE)) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = "move";
            setDropTarget(key);
          },
          onDragLeave: () => setDropTarget((k) => (k === key ? null : k)),
          onDrop: (e: DragEvent) => {
            e.preventDefault();
            setDropTarget(null);
            const raw = e.dataTransfer.getData(DRAG_TYPE);
            if (raw) moves.request({ ids: JSON.parse(raw) as string[], to });
            if (selecting) stopSelecting();
          },
        }
      : {};
  const set = (patch: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ ...Object.fromEntries(params), ...patch }).filter(([, v]) => v)), { replace: true });
  const dsName = (id?: string | null) => datasources.data?.find((d) => d.id === id)?.name ?? "—";
  const url = (e: EndpointSummary) => `${info.data?.servingBaseUrl ?? ""}/${ws}/${e.path}`;

  const newButton = canAuthor ? (
    <Button asChild>
      <Link to="new">
        <Plus aria-hidden="true" />
        {t("New endpoint")}
      </Link>
    </Button>
  ) : undefined;

  return (
    <ListPageShell
      title={trashed ? t("Trash") : t("Endpoints")}
      description={trashed ? t("Endpoints here are not served. Restore them, or delete them for good.") : t("SQL published as REST APIs.")}
      back={trashed ? <Button variant="link" className="px-0" onClick={() => set({ view: "" })}>{t("← All endpoints")}</Button> : undefined}
      toolbar={
        <div className="flex flex-wrap items-center gap-2">
          <Input type="search" aria-label={t("Search endpoints")} placeholder={t("Search name or path")} className="w-60" value={q}
            onChange={(e) => setQ(e.target.value)} />
          <Select value={datasource || ALL} onValueChange={(v) => set({ ds: v === ALL ? "" : v })}>
            <SelectTrigger className="w-44" aria-label={t("Datasource")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t("All datasources")}</SelectItem>
              {(datasources.data ?? []).map((d) => (
                <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          {canOrganize && (
            <>
              <Button variant="outline" size="sm" onClick={() => setNaming({})}>
                <FolderPlus aria-hidden="true" />
                {t("New group")}
              </Button>
              {selecting ? (
                <>
                  <Button size="sm" disabled={selected.size === 0} onClick={() => setPicking(true)}>
                    {t("Move {{n}} to group…", { n: selected.size })}
                  </Button>
                  <Button variant="ghost" size="sm" onClick={stopSelecting}>{t("Cancel selection")}</Button>
                </>
              ) : (
                <Button variant="outline" size="sm" onClick={() => setSelecting(true)}>
                  <ListChecks aria-hidden="true" />
                  {t("Select")}
                </Button>
              )}
            </>
          )}
          {!trashed && (
            <>
              <Button variant="ghost" size="sm" onClick={() => set({ view: "trash" })}>
                {t("Trash")}{trashCount > 0 ? ` (${trashCount})` : ""}
              </Button>
              <Button asChild variant="ghost" size="sm">
                <a href={`${info.data?.servingBaseUrl ?? "/api"}/${ws}/docs.html`} target="_blank" rel="noreferrer">
                  {t("API docs")}
                </a>
              </Button>
              <Button asChild variant="ghost" size="sm">
                <a href={`/admin/api/ws/${ws}/openapi.json`} download={`${ws}-openapi.json`}>{t("Download OpenAPI")}</a>
              </Button>
            </>
          )}
        </div>
      }
      action={trashed ? undefined : newButton}
    >
      <QueryBoundary query={query} what={t("endpoints")}>
        {(all) => {
          const counts = { live: 0, draft: 0, disabled: 0 } as Record<string, number>;
          all.forEach((e) => (counts[e.state] = (counts[e.state] ?? 0) + 1));
          const needle = q.trim().toLowerCase();
          const shown = all.filter(
            (e) =>
              (!state || e.state === state) &&
              (!datasource || e.datasourceId === datasource) &&
              (!needle || e.name.toLowerCase().includes(needle) || e.path.toLowerCase().includes(needle)),
          );
          const filtering = !!(state || datasource || needle);
          const known = new Set(groupList.map((g) => g.id));
          const grouped = !trashed && groupList.length > 0;
          const sections: Section[] = grouped
            ? [
                ...groupList.map((g) => ({ key: g.id, group: g, rows: shown.filter((e) => e.groupId === g.id) })),
                { key: "__none__", group: null, rows: shown.filter((e) => !e.groupId || !known.has(e.groupId)) },
              ].filter((sec) => sec.rows.length > 0 || !filtering)
            : [{ key: "__all__", group: null, rows: shown }];
          const columns = selecting ? 8 : 7;
          if (all.length === 0) {
            return trashed ? (
              <EmptyState title={t("The trash is empty")} />
            ) : (
              <EmptyState variant="dashed" icon={Plug} title={t("No endpoints yet")}
                description={canAuthor ? t("Write a query and publish it as an API.") : t("Authors of this workspace create endpoints.")}
                action={newButton} />
            );
          }
          return (
            <div className="space-y-3">
              {!trashed && (
                <SummaryStrip
                  items={[
                    { label: t("endpoints"), singular: t("endpoint"), value: all.length, active: !state, onClick: () => set({ state: "" }) },
                    { label: t("live"), value: counts.live, active: state === "live", onClick: () => set({ state: "live" }) },
                    { label: t("drafts"), singular: t("draft"), value: counts.draft, active: state === "draft", onClick: () => set({ state: "draft" }) },
                    { label: t("disabled"), value: counts.disabled, active: state === "disabled", onClick: () => set({ state: "disabled" }) },
                    ...(grouped ? [{ label: t("groups"), singular: t("group"), value: groupList.length }] : []),
                  ]}
                />
              )}
              {shown.length === 0 && (filtering || !grouped) ? (
                <EmptyState title={t("No endpoint matches")} description={t("Change the search or the filters.")} />
              ) : (
                <div className="overflow-x-auto rounded-md border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        {selecting && <TableHead className="w-10"><span className="sr-only">{t("Selected")}</span></TableHead>}
                        <TableHead>{t("Name")}</TableHead>
                        <TableHead>{t("Route")}</TableHead>
                        <TableHead>{t("State")}</TableHead>
                        <TableHead>{t("Version")}</TableHead>
                        <TableHead>{t("Datasource")}</TableHead>
                        <TableHead>{t("Updated")}</TableHead>
                        <TableHead className="w-10"><span className="sr-only">{t("Actions")}</span></TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {sections.map((sec) => {
                        const open = !collapsed.has(sec.key);
                        return (
                          <Fragment key={sec.key}>
                            {grouped && (
                              <TableRow
                                className={dropTarget === sec.key ? "bg-accent" : "bg-muted/40 hover:bg-muted/40"}
                                {...dropProps(sec.key, sec.group?.id ?? null)}
                              >
                                <TableCell colSpan={columns} className="py-1.5">
                                  <div className="flex items-center gap-1">
                                    <Button variant="ghost" size="sm" className="h-7 gap-1 px-1 font-medium" aria-expanded={open}
                                      onClick={() => toggle(sec.key)}>
                                      {open ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
                                      {sec.group ? sec.group.name : t("No group")}{" "}
                                      <span className="font-normal text-muted-foreground">({sec.rows.length})</span>
                                    </Button>
                                    {sec.group && canOrganize && (
                                      <GroupMenu group={sec.group} onRename={() => setNaming({ group: sec.group! })}
                                        onDelete={() => setDeletingGroup(sec.group)} />
                                    )}
                                  </div>
                                </TableCell>
                              </TableRow>
                            )}
                            {open && sec.rows.map((e) => (
                              <TableRow key={e.id} draggable={canOrganize} onDragStart={canOrganize ? (ev) => dragStart(ev, e.id) : undefined}
                                {...(grouped ? dropProps(sec.key, sec.group?.id ?? null) : {})}>
                                {selecting && (
                                  <TableCell>
                                    <Checkbox aria-label={t("Select {{name}}", { name: e.name })} checked={selected.has(e.id)}
                                      onCheckedChange={(v) => setSelected((prev) => {
                                        const next = new Set(prev);
                                        if (v === true) next.add(e.id);
                                        else next.delete(e.id);
                                        return next;
                                      })} />
                                  </TableCell>
                                )}
                                <TableCell className="font-medium">
                                  {trashed ? e.name : <Link className="hover:underline" to={e.id}>{e.name}</Link>}
                                </TableCell>
                                <TableCell className="font-mono text-xs">
                                  <span className="mr-2 font-semibold">{e.method}</span>/{e.path}
                                </TableCell>
                                <TableCell>
                                  <span className="inline-flex items-center gap-2">
                                    <StatusBadge tone={STATE_TONE[e.state]}>{t(STATE_LABEL[e.state])}</StatusBadge>
                                    {e.dirty && e.liveVersion != null && (
                                      <span className="text-xs text-muted-foreground" title={t("The draft differs from the live version")}>{t("• unpublished changes")}</span>
                                    )}
                                    {e.access === "public" && <StatusBadge tone="highlight">{t("Public")}</StatusBadge>}
                                  </span>
                                </TableCell>
                                <TableCell className="tabular-nums">{e.liveVersion != null ? `v${e.liveVersion}` : "—"}</TableCell>
                                <TableCell>{dsName(e.datasourceId)}</TableCell>
                                <TableCell className="tabular-nums">{formatDate(e.updatedAt)}</TableCell>
                                <TableCell>
                                  {canAuthor && (
                                    <RowActionsMenu label={t("Actions for {{name}}", { name: e.name })}>
                                      {trashed ? (
                                        <>
                                          <DropdownMenuItem onSelect={() => restore.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(t("{{name}} restored.", { name: e.name })) })}>
                                            {t("Restore")}
                                          </DropdownMenuItem>
                                          <DropdownMenuItem onSelect={() => setDeleting(e)}>{t("Delete for good…")}</DropdownMenuItem>
                                        </>
                                      ) : (
                                        <>
                                          <DropdownMenuItem asChild><Link to={e.id}>{t("Open")}</Link></DropdownMenuItem>
                                          <DropdownMenuItem onSelect={() => navigator.clipboard?.writeText(url(e)).then(() => toast.success(t("URL copied.")))}>
                                            {t("Copy URL")}
                                          </DropdownMenuItem>
                                          {e.liveVersion != null && (
                                            <DropdownMenuItem asChild>
                                              <a href={`/admin/api/ws/${ws}/endpoints/${e.id}/openapi.json`} download>{t("Download OpenAPI")}</a>
                                            </DropdownMenuItem>
                                          )}
                                          <DropdownMenuItem onSelect={() => duplicate.mutate({ ws, id: e.id }, { onSuccess: (c) => toast.success(t("Copied as /{{path}}.", { path: c.summary.path })) })}>
                                            {t("Duplicate")}
                                          </DropdownMenuItem>
                                          <DropdownMenuSeparator />
                                          {groupList.filter((g) => g.id !== e.groupId).map((g) => (
                                            <DropdownMenuItem key={g.id} onSelect={() => moves.request({ ids: [e.id], to: g.id })}>
                                              {t("Move to {{group}}", { group: g.name })}
                                            </DropdownMenuItem>
                                          ))}
                                          {e.groupId && (
                                            <DropdownMenuItem onSelect={() => moves.request({ ids: [e.id], to: null })}>{t("Remove from group")}</DropdownMenuItem>
                                          )}
                                          <DropdownMenuItem onSelect={() => setNaming({ moveIds: [e.id] })}>{t("Move to new group…")}</DropdownMenuItem>
                                          <DropdownMenuSeparator />
                                          {e.state === "live" && (
                                            <DropdownMenuItem onSelect={() => disable.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(t("{{name}} disabled.", { name: e.name })) })}>
                                              {t("Disable")}
                                            </DropdownMenuItem>
                                          )}
                                          {e.state === "disabled" && (
                                            <DropdownMenuItem onSelect={() => enable.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(t("{{name}} is live again.", { name: e.name })) })}>
                                              {t("Enable")}
                                            </DropdownMenuItem>
                                          )}
                                          <DropdownMenuItem
                                            disabled={e.state === "live"}
                                            title={e.state === "live" ? t("Disable it first: a live endpoint cannot go to the trash") : undefined}
                                            onSelect={() => trash.mutate({ ws, id: e.id }, { onSuccess: () => toast.success(t("{{name}} moved to the trash.", { name: e.name })) })}
                                          >
                                            {t("Move to trash")}
                                          </DropdownMenuItem>
                                        </>
                                      )}
                                    </RowActionsMenu>
                                  )}
                                </TableCell>
                              </TableRow>
                            ))}
                            {open && grouped && sec.rows.length === 0 && (
                              <TableRow {...dropProps(sec.key, sec.group?.id ?? null)}>
                                <TableCell colSpan={columns} className="py-3 pl-10 text-sm text-muted-foreground">
                                  {canOrganize ? t("Empty. Drag endpoints here, or use Move to in a row's menu.") : t("Empty.")}
                                </TableCell>
                              </TableRow>
                            )}
                          </Fragment>
                        );
                      })}
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
        title={t("Delete {{name}} for good?", { name: deleting?.name ?? t("endpoint") })}
        description={<p>{t("Its versions are deleted too. This cannot be undone.")}</p>}
        confirmLabel={t("Delete")}
        destructive
        pending={remove.isPending}
        onConfirm={() => deleting && remove.mutate({ ws, id: deleting.id }, { onSuccess: () => setDeleting(null) })}
      />
      {moves.confirm}
      <GroupNameDialog ws={ws} open={naming !== null} onOpenChange={(o) => !o && setNaming(null)} group={naming?.group}
        onCreated={naming?.moveIds ? (g) => moves.request({ ids: naming.moveIds!, to: g.id }, g) : undefined} />
      <DeleteGroupDialog ws={ws} group={deletingGroup} onOpenChange={(o) => !o && setDeletingGroup(null)} />
      <MoveToGroupDialog open={picking} onOpenChange={setPicking} count={selected.size} groups={groupList}
        onMove={moveSelected}
        onNewGroup={() => {
          setNaming({ moveIds: [...selected] });
          stopSelecting();
        }} />
    </ListPageShell>
  );
}

function GroupMenu({ group, onRename, onDelete }: { group: ApiGroup; onRename: () => void; onDelete: () => void }): ReactNode {
  const { t } = useTranslation();
  return (
    <RowActionsMenu label={t("Actions for group {{name}}", { name: group.name })}>
      <DropdownMenuItem onSelect={onRename}>{t("Rename")}</DropdownMenuItem>
      <DropdownMenuItem onSelect={onDelete}>{t("Delete group…")}</DropdownMenuItem>
    </RowActionsMenu>
  );
}
