import { useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Copy, MoreHorizontal } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { useListDatasources } from "@/api/generated/datasources/datasources";
import {
  getGetEndpointQueryKey,
  getListEndpointVersionsQueryKey,
  getListEndpointsQueryKey,
  useDisableEndpoint,
  useDuplicateEndpoint,
  useEnableEndpoint,
  useGetEndpoint,
  usePublishEndpoint,
  useTrashEndpoint,
  useUpdateEndpoint,
  useValidateEndpoint,
} from "@/api/generated/endpoints/endpoints";
import { useSystemInfo } from "@/api/generated/system/system";
import type { EndpointDetail, ValidationView } from "@/api/generated/model";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { DetailHeader } from "@/components/layout/DetailHeader";
import { PageContainer } from "@/components/layout/PageContainer";
import MonacoEditor from "@/components/monaco/MonacoEditor";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { errorMessage } from "@/lib/errors";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";
import { AccessTab } from "./AccessTab";
import { ParamsEditor } from "./ParamsEditor";
import { FindingsList, PreviewPanel } from "./PreviewPanel";
import { SettingsTab } from "./SettingsTab";
import { TryItTab } from "./TryItTab";
import { VersionsTab } from "./VersionsTab";
import { STATE_LABEL, STATE_TONE, formFrom, mainSql, toUpdate, withMainSql, type DraftForm } from "./endpointForm";

const TABS = ["query", "try", "settings", "access", "versions"] as const;

export default function EndpointDetailPage() {
  const { ws = "", id = "" } = useParams();
  const query = useGetEndpoint(ws, id);
  return (
    <PageContainer>
      <QueryBoundary query={query} what="the endpoint">
        {(detail) => <Editor key={detail.summary.id} ws={ws} detail={detail} />}
      </QueryBoundary>
    </PageContainer>
  );
}

function Editor({ ws, detail }: { ws: string; detail: EndpointDetail }) {
  const { me } = useAuth();
  const canAuthor = hasRole(effectiveRole(me, ws) ?? "viewer", "author");
  const isAdmin = hasRole(effectiveRole(me, ws) ?? "viewer", "admin");
  const s = detail.summary;
  const saved = useMemo(() => formFrom(detail), [detail]);
  const [form, setForm] = useState<DraftForm>(saved);
  // A refetch after saving gives a new baseline: adopt it (React's "adjust state on prop change").
  const [baseline, setBaseline] = useState(saved);
  if (baseline !== saved) {
    setBaseline(saved);
    setForm(saved);
  }
  const dirty = JSON.stringify(form) !== JSON.stringify(saved);
  useUnsavedChangesWarning(dirty);

  const [params, setParams] = useSearchParams();
  const tab = TABS.includes(params.get("tab") as (typeof TABS)[number]) ? params.get("tab")! : "query";
  const qc = useQueryClient();
  const navigate = useNavigate();
  const info = useSystemInfo();
  const datasources = useListDatasources(ws);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: getGetEndpointQueryKey(ws, s.id) });
    qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) });
    qc.invalidateQueries({ queryKey: getListEndpointVersionsQueryKey(ws, s.id) });
  };
  const fail = (e: unknown) => toast.error(errorMessage(e));
  const update = useUpdateEndpoint();
  const disable = useDisableEndpoint({ mutation: { onSuccess: refresh, onError: fail } });
  const enable = useEnableEndpoint({ mutation: { onSuccess: refresh, onError: fail } });
  const trash = useTrashEndpoint({ mutation: { onError: fail } });
  const duplicate = useDuplicateEndpoint({ mutation: { onError: fail } });
  const [publishing, setPublishing] = useState(false);
  const validate = useValidateEndpoint();
  const openPublish = () => {
    validate.reset();
    validate.mutate({ ws, id: s.id });
    setPublishing(true);
  };
  const url = `${info.data?.servingBaseUrl ?? ""}/${ws}/${s.path}`;

  const save = (after?: () => void) =>
    update.mutate(
      { ws, id: s.id, data: toUpdate(form) },
      {
        onSuccess: () => {
          refresh();
          if (after) after(); else toast.success("Draft saved. Callers still get the live version until you publish.");
        },
        onError: fail,
      },
    );

  const publishBlockedReason = dirty ? "Save the draft first: publish freezes what is saved" : !canAuthor ? "Authors publish" : undefined;

  return (
    <>
      <DetailHeader
        back={{ to: `/w/${ws}/endpoints`, label: "Endpoints" }}
        title={s.name}
        status={
          <span className="inline-flex flex-wrap items-center gap-2">
            <StatusBadge tone={STATE_TONE[s.state]}>{STATE_LABEL[s.state]}{s.liveVersion != null ? ` · v${s.liveVersion}` : ""}</StatusBadge>
            {s.dirty && s.liveVersion != null && <StatusBadge tone="highlight">Unpublished changes</StatusBadge>}
            {s.access === "public" && <StatusBadge tone="highlight">Public</StatusBadge>}
          </span>
        }
        description={
          <span className="inline-flex items-center gap-2 font-mono text-xs">
            <span className="font-semibold">{s.method}</span> {url}
            <Button variant="ghost" size="iconSm" aria-label="Copy URL"
              onClick={() => navigator.clipboard?.writeText(url).then(() => toast.success("URL copied."))}>
              <Copy className="h-3.5 w-3.5" />
            </Button>
          </span>
        }
        actions={
          canAuthor && (
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" disabled={!dirty || update.isPending} title={!dirty ? "No changes to save" : undefined}
                onClick={() => save()}>
                Save
              </Button>
              <Button disabled={publishBlockedReason !== undefined} title={publishBlockedReason} onClick={openPublish}>
                Publish
              </Button>
              {s.state === "live" && <Button variant="outline" onClick={() => disable.mutate({ ws, id: s.id })}>Disable</Button>}
              {s.state === "disabled" && <Button variant="outline" onClick={() => enable.mutate({ ws, id: s.id })}>Enable</Button>}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="icon" aria-label="More actions"><MoreHorizontal className="h-4 w-4" /></Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {s.liveVersion != null && (
                    <DropdownMenuItem asChild>
                      <a href={`/admin/api/ws/${ws}/endpoints/${s.id}/openapi.json`} download>Download OpenAPI</a>
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem onSelect={() => duplicate.mutate({ ws, id: s.id }, {
                    onSuccess: (c) => navigate(`/w/${ws}/endpoints/${c.summary.id}`) })}>
                    Duplicate
                  </DropdownMenuItem>
                  <DropdownMenuItem disabled={s.state === "live"} title={s.state === "live" ? "Disable it first" : undefined}
                    onSelect={() => trash.mutate({ ws, id: s.id }, { onSuccess: () => {
                      qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) });
                      navigate(`/w/${ws}/endpoints`);
                    } })}>
                    Move to trash
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )
        }
      />
      <Tabs value={tab} onValueChange={(t) => setParams({ tab: t }, { replace: true })}>
        <TabsList>
          <TabsTrigger value="query">Query</TabsTrigger>
          {canAuthor && <TabsTrigger value="try">Try it</TabsTrigger>}
          <TabsTrigger value="settings">Settings</TabsTrigger>
          <TabsTrigger value="access">Access</TabsTrigger>
          <TabsTrigger value="versions">Versions</TabsTrigger>
        </TabsList>
        <TabsContent value="query" className="space-y-4 pt-4">
          <div className="flex flex-wrap items-center gap-3">
            <Label htmlFor="ep-datasource">Datasource</Label>
            <Select value={form.definition.task.datasourceId ?? ""} disabled={!canAuthor}
              onValueChange={(v) => setForm({ ...form, definition: { ...form.definition, task: { ...form.definition.task, datasourceId: v } } })}>
              <SelectTrigger id="ep-datasource" className="w-64"><SelectValue placeholder="Choose a datasource" /></SelectTrigger>
              <SelectContent>
                {(datasources.data ?? []).map((d) => <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,34rem)]">
            <div className="h-[22rem] overflow-hidden rounded-md border" aria-label="SQL template">
              <MonacoEditor
                language="sql"
                value={mainSql(form.definition)}
                onChange={(v) => setForm({ ...form, definition: withMainSql(form.definition, v ?? "") })}
                options={{ minimap: { enabled: false }, fontSize: 13, readOnly: !canAuthor, wordWrap: "on", scrollBeyondLastLine: false }}
              />
            </div>
            <ParamsEditor params={form.definition.params} disabled={!canAuthor} sql={mainSql(form.definition)} path={form.path}
              onChange={(p) => setForm({ ...form, definition: { ...form.definition, params: p } })} />
          </div>
          {canAuthor && <PreviewPanel ws={ws} id={s.id} form={form} />}
        </TabsContent>
        <TabsContent value="settings" className="pt-4">
          <SettingsTab ws={ws} form={form} onChange={setForm} live={s.state === "live"} disabled={!canAuthor} />
        </TabsContent>
        {canAuthor && (
          <TabsContent value="try" className="pt-4">
            <TryItTab ws={ws} summary={s} onOpenAccess={() => setParams({ tab: "access" }, { replace: true })} />
          </TabsContent>
        )}
        <TabsContent value="access" className="pt-4">
          <AccessTab ws={ws} endpointId={s.id} isPublic={s.access === "public"} isAdmin={isAdmin} />
        </TabsContent>
        <TabsContent value="versions" className="pt-4">
          <VersionsTab ws={ws} id={s.id} draftSql={mainSql(saved.definition)} canAuthor={canAuthor} onChanged={refresh} />
        </TabsContent>
      </Tabs>
      <PublishDialog ws={ws} id={s.id} open={publishing} onOpenChange={setPublishing} onPublished={refresh}
        validating={validate.isPending} validation={validate.data ?? null}
        validationError={validate.error ? errorMessage(validate.error) : null} />
    </>
  );
}

function PublishDialog({ ws, id, open, onOpenChange, onPublished, validating, validation, validationError }: {
  ws: string; id: string; open: boolean; onOpenChange: (o: boolean) => void; onPublished: () => void;
  validating: boolean; validation: ValidationView | null; validationError: string | null;
}) {
  const publish = usePublishEndpoint();
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) setError(null); onOpenChange(o); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Publish a new version</DialogTitle>
          <DialogDescription>The saved draft is frozen as a new version and served at once.</DialogDescription>
        </DialogHeader>
        {validating && <p className="text-sm text-muted-foreground">Checking the draft…</p>}
        {validation && <FindingsList findings={validation.findings} />}
        {validation?.ok && (
          <div className="space-y-1">
            <Label htmlFor="publish-note">Note (optional)</Label>
            <Input id="publish-note" value={note} maxLength={500} placeholder="What changed" onChange={(e) => setNote(e.target.value)} />
          </div>
        )}
        {(error ?? validationError) && <p role="alert" className="text-sm text-destructive">{error ?? validationError}</p>}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={!validation?.ok || publish.isPending} title={validation && !validation.ok ? "Fix the errors above first" : undefined}
            onClick={() => publish.mutate({ ws, id, data: { note: note.trim() || null } }, {
              onSuccess: (v) => { toast.success(`Version ${v.version} is live.`); setNote(""); onOpenChange(false); onPublished(); },
              onError: (e) => setError(errorMessage(e)),
            })}>
            Publish
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
