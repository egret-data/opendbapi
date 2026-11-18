import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Copy, MoreHorizontal } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { useListDatasources } from "@/api/generated/datasources/datasources";
import {
  getGetEndpointQueryKey,
  getListEndpointVersionsQueryKey,
  getListEndpointsQueryKey,
  useDisableEndpoint,
  useDuplicateEndpoint,
  useUndeprecateEndpoint,
  useEnableEndpoint,
  useGetEndpoint,
  usePublishEndpoint,
  useTrashEndpoint,
  useUpdateEndpoint,
  useValidateEndpoint,
} from "@/api/generated/endpoints/endpoints";
import { useSystemInfo } from "@/api/generated/system/system";
import type { EndpointDetail, ValidationView } from "@/api/generated/model";
import { useConfigLocked } from "@/components/ConfigLockedNotice";
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
import { formatDate } from "@/lib/format";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";
import { AccessTab } from "./AccessTab";
import { DeprecateDialog, LineagePanel, NewMajorDialog } from "./MajorVersions";
import { TestsTab } from "./TestsTab";
import { ParamsEditor } from "./ParamsEditor";
import { FindingsList, PreviewPanel } from "./PreviewPanel";
import { SettingsTab } from "./SettingsTab";
import { TryItTab } from "./TryItTab";
import { VersionsTab } from "./VersionsTab";
import { STATE_LABEL, STATE_TONE, formFrom, isScript, mainSql, needsScriptRole, nodeIds, paramSource, taskKind, toUpdate, withLanguage, withMainSql, type DraftForm, type TaskKind } from "./endpointForm";
import { HttpRequestEditor } from "./HttpRequestEditor";
import { WorkflowEditor } from "./WorkflowEditor";

const TABS = ["query", "try", "tests", "settings", "access", "versions"] as const;

export default function EndpointDetailPage() {
  const { ws = "", id = "" } = useParams();
  const query = useGetEndpoint(ws, id);
  const { t } = useTranslation();
  return (
    <PageContainer>
      <QueryBoundary query={query} what={t("the endpoint")}>
        {(detail) => <Editor key={detail.summary.id} ws={ws} detail={detail} />}
      </QueryBoundary>
    </PageContainer>
  );
}

function Editor({ ws, detail }: { ws: string; detail: EndpointDetail }) {
  const { me } = useAuth();
  const { t } = useTranslation();
  const locked = useConfigLocked(ws);
  // Authors may still preview and try a locked workspace's endpoints; they cannot change them.
  const isAuthor = hasRole(effectiveRole(me, ws) ?? "viewer", "author");
  const canAuthor = isAuthor && !locked;
  const canScript = hasRole(effectiveRole(me, ws) ?? "viewer", "script_author");
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
  const script = isScript(form.definition);
  const scripted = needsScriptRole(form.definition);
  const workflow = taskKind(form.definition) === "workflow";
  const http = taskKind(form.definition) === "http";
  useUnsavedChangesWarning(dirty);

  // Each tab is a child route (spec §17.3): /w/:ws/endpoints/:id/settings survives a reload.
  const { tab: tabParam } = useParams();
  const tab = TABS.includes(tabParam as (typeof TABS)[number]) ? tabParam! : "query";
  const goTab = (next: string) => navigate(`/w/${ws}/endpoints/${detail.summary.id}${next === "query" ? "" : `/${next}`}`, { replace: true });
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
  const undeprecate = useUndeprecateEndpoint({ mutation: { onSuccess: refresh, onError: fail } });
  const [majorOpen, setMajorOpen] = useState(false);
  const [deprecateOpen, setDeprecateOpen] = useState(false);
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
          if (after) after(); else toast.success(t("Draft saved. Callers still get the live version until you publish."));
        },
        onError: fail,
      },
    );

  const publishBlockedReason = dirty ? t("Save the draft first: publish freezes what is saved") : !canAuthor ? t("Authors publish") : undefined;

  return (
    <>
      <DetailHeader
        back={{ to: `/w/${ws}/endpoints`, label: t("Endpoints") }}
        title={s.name}
        status={
          <span className="inline-flex flex-wrap items-center gap-2">
            <StatusBadge tone={STATE_TONE[s.state]}>{t(STATE_LABEL[s.state])}{s.liveVersion != null ? ` · v${s.liveVersion}` : ""}</StatusBadge>
            {s.dirty && s.liveVersion != null && <StatusBadge tone="highlight">{t("Unpublished changes")}</StatusBadge>}
            {s.access === "public" && <StatusBadge tone="highlight">{t("Public")}</StatusBadge>}
            {s.major > 1 && <StatusBadge tone="neutral">{t("Major {{n}}", { n: s.major })}</StatusBadge>}
            {s.deprecatedAt && (
              <StatusBadge tone="inactive">
                {s.sunsetAt ? t("Deprecated · sunset {{date}}", { date: formatDate(s.sunsetAt) }) : t("Deprecated")}
              </StatusBadge>
            )}
          </span>
        }
        description={
          <span className="inline-flex items-center gap-2 font-mono text-xs">
            <span className="font-semibold">{s.method}</span> {url}
            <Button variant="ghost" size="iconSm" aria-label={t("Copy URL")}
              onClick={() => navigator.clipboard?.writeText(url).then(() => toast.success(t("URL copied.")))}>
              <Copy className="h-3.5 w-3.5" />
            </Button>
          </span>
        }
        actions={
          canAuthor && (
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" disabled={!dirty || update.isPending} title={!dirty ? t("No changes to save") : undefined}
                onClick={() => save()}>
                {t("Save")}
              </Button>
              <Button disabled={publishBlockedReason !== undefined} title={publishBlockedReason} onClick={openPublish}>
                {t("Publish")}
              </Button>
              {s.state === "live" && <Button variant="outline" onClick={() => disable.mutate({ ws, id: s.id })}>{t("Disable")}</Button>}
              {s.state === "disabled" && <Button variant="outline" onClick={() => enable.mutate({ ws, id: s.id })}>{t("Enable")}</Button>}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="icon" aria-label={t("More actions")}><MoreHorizontal className="h-4 w-4" /></Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {s.liveVersion != null && (
                    <DropdownMenuItem asChild>
                      <a href={`/admin/api/ws/${ws}/endpoints/${s.id}/openapi.json`} download>{t("Download OpenAPI")}</a>
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem onSelect={() => duplicate.mutate({ ws, id: s.id }, {
                    onSuccess: (c) => navigate(`/w/${ws}/endpoints/${c.summary.id}`) })}>
                    {t("Duplicate")}
                  </DropdownMenuItem>
                  <DropdownMenuItem onSelect={() => setMajorOpen(true)}>{t("New major version…")}</DropdownMenuItem>
                  {s.deprecatedAt
                    ? <DropdownMenuItem onSelect={() => undeprecate.mutate({ ws, id: s.id })}>{t("Stop deprecation")}</DropdownMenuItem>
                    : <DropdownMenuItem onSelect={() => setDeprecateOpen(true)}>{t("Deprecate…")}</DropdownMenuItem>}
                  <DropdownMenuItem disabled={s.state === "live"} title={s.state === "live" ? t("Disable it first") : undefined}
                    onSelect={() => trash.mutate({ ws, id: s.id }, { onSuccess: () => {
                      qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) });
                      navigate(`/w/${ws}/endpoints`);
                    } })}>
                    {t("Move to trash")}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )
        }
      />
      <Tabs value={tab} onValueChange={goTab}>
        <TabsList>
          <TabsTrigger value="query">{t("Query")}</TabsTrigger>
          {isAuthor && <TabsTrigger value="try">{t("Try it")}</TabsTrigger>}
          <TabsTrigger value="tests">{t("Tests")}</TabsTrigger>
          <TabsTrigger value="settings">{t("Settings")}</TabsTrigger>
          <TabsTrigger value="access">{t("Access")}</TabsTrigger>
          <TabsTrigger value="versions">{t("Versions")}</TabsTrigger>
        </TabsList>
        <TabsContent value="query" className="space-y-4 pt-4">
          <div className="flex flex-wrap items-center gap-3">
            <Label htmlFor="ep-datasource">{t("Datasource")}</Label>
            <Select value={form.definition.task.datasourceId ?? ""} disabled={!canAuthor}
              onValueChange={(v) => {
                // An HTTP API takes an HTTP request; a database takes SQL.
                const toHttp = (datasources.data ?? []).find((d) => d.id === v)?.type === "http";
                const def = { ...form.definition, task: { ...form.definition.task, datasourceId: v } };
                const kind = taskKind(def);
                setForm({ ...form, definition: toHttp && kind !== "http" ? withLanguage(def, "http")
                  : !toHttp && kind === "http" ? withLanguage(def, "sql") : def });
              }}>
              <SelectTrigger id="ep-datasource" className="w-64"><SelectValue placeholder={t("Choose a datasource")} /></SelectTrigger>
              <SelectContent>
                {(datasources.data ?? []).map((d) => <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>)}
              </SelectContent>
            </Select>
            <Label htmlFor="ep-language">{t("Language")}</Label>
            <Select value={taskKind(form.definition)} disabled={!canAuthor || (scripted && !canScript) || http}
              onValueChange={(v) => setForm({ ...form, definition: withLanguage(form.definition, v as TaskKind) })}>
              <SelectTrigger id="ep-language" className="w-56"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="sql">{t("SQL template")}</SelectItem>
                <SelectItem value="fragment" disabled={!canScript}>{t("Groovy script")}</SelectItem>
                <SelectItem value="full" disabled={!canScript}>{t("Groovy script (full: db, any answer)")}</SelectItem>
                <SelectItem value="workflow">{t("Workflow (several steps)")}</SelectItem>
                {http && <SelectItem value="http">{t("HTTP request")}</SelectItem>}
              </SelectContent>
            </Select>
            {scripted && !canScript && (
              <span className="text-sm text-muted-foreground">{t("Only script authors can change or preview the script.")}</span>
            )}
          </div>
          <div className={workflow ? "space-y-6" : "grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,34rem)]"}>
            {http && form.definition.task.request ? (
              <HttpRequestEditor request={form.definition.task.request} disabled={!canAuthor}
                baseUrl={(datasources.data ?? []).find((d) => d.id === form.definition.task.datasourceId)?.http?.baseUrl}
                onChange={(request) => setForm({ ...form, definition: { ...form.definition, task: { ...form.definition.task, request } } })} />
            ) : workflow ? (
              <WorkflowEditor ws={ws} selfId={s.id} task={form.definition.task} disabled={!canAuthor} canScript={canScript}
                onChange={(task) => setForm({ ...form, definition: { ...form.definition, task } })} />
            ) : (
            <div className="h-[22rem] overflow-hidden rounded-md border" aria-label={script ? t("Groovy script") : t("SQL template")}>
              <MonacoEditor
                language={script ? "java" : "sql"}
                value={mainSql(form.definition)}
                onChange={(v) => setForm({ ...form, definition: withMainSql(form.definition, v ?? "") })}
                options={{ minimap: { enabled: false }, fontSize: 13, readOnly: !canAuthor || (script && !canScript), wordWrap: "on", scrollBeyondLastLine: false }}
              />
            </div>
            )}
            <ParamsEditor params={form.definition.params} disabled={!canAuthor} sql={paramSource(form.definition)} path={form.path} http={http}
              ignore={nodeIds(form.definition)}
              onChange={(p) => setForm({ ...form, definition: { ...form.definition, params: p } })} />
          </div>
          {isAuthor && (!scripted || canScript) && <PreviewPanel ws={ws} id={s.id} form={form} />}
        </TabsContent>
        <TabsContent value="settings" className="pt-4">
          <SettingsTab ws={ws} endpointId={s.id} form={form} onChange={setForm} live={s.state === "live"} disabled={!canAuthor} />
        </TabsContent>
        {isAuthor && (
          <TabsContent value="try" className="pt-4">
            <TryItTab ws={ws} summary={s} onOpenAccess={() => goTab("access")} />
          </TabsContent>
        )}
        <TabsContent value="tests" className="pt-4">
          <TestsTab ws={ws} endpointId={s.id} paramNames={saved.definition.params.map((p) => p.name)}
            paginated={saved.definition.pagination.enabled} canAuthor={canAuthor} dirty={dirty} />
        </TabsContent>
        <TabsContent value="access" className="pt-4">
          <AccessTab ws={ws} endpointId={s.id} datasourceId={saved.definition.task.datasourceId} isPublic={s.access === "public"} isAdmin={isAdmin} />
        </TabsContent>
        <TabsContent value="versions" className="space-y-6 pt-4">
          <LineagePanel ws={ws} id={s.id} />
          <VersionsTab ws={ws} id={s.id} draftSql={mainSql(saved.definition)} canAuthor={canAuthor} onChanged={refresh} />
        </TabsContent>
      </Tabs>
      {majorOpen && <NewMajorDialog ws={ws} endpoint={s} open onOpenChange={setMajorOpen}
        onCreated={(id) => { setMajorOpen(false); qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) }); navigate(`/w/${ws}/endpoints/${id}`); }} />}
      {deprecateOpen && <DeprecateDialog ws={ws} endpoint={s} open onOpenChange={setDeprecateOpen}
        onDone={() => { setDeprecateOpen(false); refresh(); }} />}
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
  const { t } = useTranslation();
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) setError(null); onOpenChange(o); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("Publish a new version")}</DialogTitle>
          <DialogDescription>{t("The saved draft is frozen as a new version and served at once.")}</DialogDescription>
        </DialogHeader>
        {validating && <p className="text-sm text-muted-foreground">{t("Checking the draft…")}</p>}
        {validation && <FindingsList findings={validation.findings} />}
        {validation?.ok && (
          <div className="space-y-1">
            <Label htmlFor="publish-note">{t("Note (optional)")}</Label>
            <Input id="publish-note" value={note} maxLength={500} placeholder={t("What changed")} onChange={(e) => setNote(e.target.value)} />
          </div>
        )}
        {(error ?? validationError) && <p role="alert" className="text-sm text-destructive">{error ?? validationError}</p>}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("Cancel")}</Button>
          <Button disabled={!validation?.ok || publish.isPending} title={validation && !validation.ok ? t("Fix the errors above first") : undefined}
            onClick={() => publish.mutate({ ws, id, data: { note: note.trim() || null } }, {
              onSuccess: (v) => { toast.success(t("Version {{version}} is live.", { version: v.version })); setNote(""); onOpenChange(false); onPublished(); },
              onError: (e) => setError(errorMessage(e)),
            })}>
            {t("Publish")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
