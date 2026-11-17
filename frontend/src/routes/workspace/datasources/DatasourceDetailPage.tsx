import { useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import {
  getGetDatasourceQueryKey,
  getListDatasourcesQueryKey,
  useDeleteDatasource,
  useGetDatasource,
  useListColumns,
  useListSchemas,
  useListTables,
  useUpdateDatasource,
} from "@/api/generated/datasources/datasources";
import { useListEndpoints } from "@/api/generated/endpoints/endpoints";
import type { DatasourceView } from "@/api/generated/model";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { DetailHeader } from "@/components/layout/DetailHeader";
import { PageContainer } from "@/components/layout/PageContainer";
import { EmptyState } from "@/components/list/EmptyState";
import { Button } from "@/components/ui/button";
import { ConfirmDeleteDialog } from "@/components/ui/confirm-delete-dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { errorMessage } from "@/lib/errors";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";
import { DataPoliciesSection } from "../datapolicies/DataPoliciesSection";
import { STATE_LABEL } from "../endpoints/endpointForm";
import { ConnectionBadge } from "./ConnectionBadge";
import { DatasourceFields, TestConnection } from "./DatasourceFields";
import { DIALECT_LABEL, formFrom, toRequest } from "./datasourceForm";

export default function DatasourceDetailPage() {
  const { ws = "", id = "" } = useParams();
  const { me } = useAuth();
  const { t } = useTranslation();
  const role = effectiveRole(me, ws) ?? "viewer";
  const query = useGetDatasource(ws, id);
  const [params, setParams] = useSearchParams();
  const tab = ["tables", "usage", "policies"].includes(params.get("tab") ?? "") ? params.get("tab")! : "settings";

  return (
    <PageContainer>
      <QueryBoundary query={query} what={t("the datasource")}>
        {(ds) => (
          <>
            <DetailHeader
              back={{ to: `/w/${ws}/datasources`, label: t("Datasources") }}
              title={ds.name}
              status={
                <span className="inline-flex gap-2">
                  <StatusBadge tone={ds.readOnly ? "neutral" : "highlight"}>{ds.readOnly ? t("Read only") : t("Read & write")}</StatusBadge>
                  <ConnectionBadge test={ds.lastTest} />
                </span>
              }
              description={`${DIALECT_LABEL[ds.dialect]} · ${ds.host}:${ds.port}/${ds.database}`}
            />
            <Tabs value={tab} onValueChange={(value) => setParams({ tab: value }, { replace: true })}>
              <TabsList>
                <TabsTrigger value="settings">{t("Settings")}</TabsTrigger>
                <TabsTrigger value="tables">{t("Tables")}</TabsTrigger>
                <TabsTrigger value="usage">{t("Usage")}</TabsTrigger>
                <TabsTrigger value="policies">{t("Masking")}</TabsTrigger>
              </TabsList>
              <TabsContent value="policies" className="pt-4">
                <DataPoliciesSection ws={ws} scope={{ kind: "datasource", datasourceId: ds.id }} isAdmin={hasRole(role, "admin")} />
              </TabsContent>
              <TabsContent value="usage" className="pt-4">
                <UsageTab ws={ws} datasourceId={ds.id} />
              </TabsContent>
              <TabsContent value="settings" className="pt-4">
                <SettingsTab key={ds.updatedAt} ws={ws} ds={ds} canManage={hasRole(role, "admin")} />
              </TabsContent>
              <TabsContent value="tables" className="pt-4">
                {hasRole(role, "author") ? (
                  <TablesTab ws={ws} ds={ds} />
                ) : (
                  <EmptyState title={t("Tables are visible to authors")} description={t("Ask a workspace admin for the author role.")} />
                )}
              </TabsContent>
            </Tabs>
          </>
        )}
      </QueryBoundary>
    </PageContainer>
  );
}

function SettingsTab({ ws, ds, canManage }: { ws: string; ds: DatasourceView; canManage: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const update = useUpdateDatasource();
  const remove = useDeleteDatasource();
  const initial = useMemo(() => formFrom(ds), [ds]);
  const [form, setForm] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedChangesWarning(dirty);

  const save = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    update.mutate(
      { ws, id: ds.id, data: toRequest(form) },
      {
        onSuccess: () => {
          toast.success(t("Datasource saved. Its connections are reopened with the new settings."));
          qc.invalidateQueries({ queryKey: getGetDatasourceQueryKey(ws, ds.id) });
          qc.invalidateQueries({ queryKey: getListDatasourcesQueryKey(ws) });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <form onSubmit={save} className="space-y-4">
      {!canManage && (
        <p className="text-sm text-muted-foreground">{t("Only workspace admins can change datasources.")}</p>
      )}
      <DatasourceFields ws={ws} form={form} onChange={setForm} disabled={!canManage} hasPassword={ds.hasPassword}
        passwordEnv={ds.passwordEnv} />
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {canManage && (
        <div className="flex flex-wrap items-start gap-2">
          <Button type="submit" disabled={!dirty || update.isPending} title={!dirty ? t("No changes to save") : undefined}>
            {t("Save changes")}
          </Button>
          <Button type="button" variant="outline" disabled={!dirty} onClick={() => setForm(initial)}>
            {t("Discard")}
          </Button>
          <TestConnection ws={ws} form={form} existingId={ds.id} />
          <Button type="button" variant="ghost" className="ml-auto text-destructive" onClick={() => setDeleting(true)}>
            <Trash2 aria-hidden="true" />
            {t("Delete datasource")}
          </Button>
        </div>
      )}
      <ConfirmDeleteDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={t("Delete {{name}}?", { name: ds.name })}
        description={t("Endpoints that use it must be changed first. Its connections are closed at once.")}
        confirmText={ds.name}
        onConfirm={() =>
          remove.mutate(
            { ws, id: ds.id },
            {
              onSuccess: () => {
                qc.invalidateQueries({ queryKey: getListDatasourcesQueryKey(ws) });
                navigate(`/w/${ws}/datasources`, { replace: true });
              },
              onError: (err) => {
                setDeleting(false);
                toast.error(errorMessage(err));
              },
            },
          )
        }
      />
    </form>
  );
}

/** The schema endpoints will most likely query: the configured one, then the usual defaults. */
export function defaultSchema(schemas: string[], ds: DatasourceView): string {
  const preferred = [ds.properties.currentSchema, "public", "dbo", ds.database, ds.username];
  for (const candidate of preferred) {
    const match = schemas.find((s) => candidate && s.toLowerCase() === candidate.toLowerCase());
    if (match) return match;
  }
  return schemas[0] ?? "";
}

function TablesTab({ ws, ds }: { ws: string; ds: DatasourceView }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const schemas = useListSchemas(ws, ds.id);
  const schema = params.get("schema") ?? defaultSchema(schemas.data ?? [], ds);
  const tables = useListTables(ws, ds.id, { schema }, { query: { enabled: schema !== "" } });
  const table = params.get("table") ?? "";
  const columns = useListColumns(ws, ds.id, { schema, table }, { query: { enabled: table !== "" } });
  const [filter, setFilter] = useState("");
  const select = (patch: Record<string, string>) =>
    setParams({ tab: "tables", ...Object.fromEntries(params), ...patch }, { replace: true });

  return (
    <QueryBoundary query={schemas} what={t("schemas")}>
      {(schemaList) => (
        <div className="grid gap-4 lg:grid-cols-[minmax(16rem,22rem)_1fr]">
          <div className="space-y-2">
            {schemaList.length > 1 && (
              <Select value={schema} onValueChange={(s) => select({ schema: s, table: "" })}>
                <SelectTrigger aria-label={t("Schema")} className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {schemaList.map((s) => (
                    <SelectItem key={s} value={s}>
                      {s}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
            <Input type="search" aria-label={t("Filter tables")} placeholder={t("Filter tables")} value={filter}
              onChange={(e) => setFilter(e.target.value)} />
            <QueryBoundary query={tables} what={t("tables")}>
              {(result) => {
                const shown = result.items.filter((item) => item.name.toLowerCase().includes(filter.toLowerCase()));
                return (
                  <ul className="max-h-[60vh] overflow-y-auto rounded-md border" aria-label={t("Tables")}>
                    {shown.length === 0 && (
                      <li className="p-3 text-sm text-muted-foreground">{filter ? t("No table matches.") : t("No tables in this schema.")}</li>
                    )}
                    {shown.map((item) => (
                      <li key={`${item.schema}.${item.name}`}>
                        <button
                          type="button"
                          aria-current={item.name === table ? "true" : undefined}
                          onClick={() => select({ schema, table: item.name })}
                          className="flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-sm hover:bg-accent aria-[current=true]:bg-accent aria-[current=true]:font-medium"
                        >
                          <span className="truncate font-mono text-xs">{item.name}</span>
                          {item.type === "VIEW" && <StatusBadge tone="neutral">{t("View")}</StatusBadge>}
                        </button>
                      </li>
                    ))}
                    {result.truncated && <li className="p-2 text-xs text-muted-foreground">{t("Showing the first 2000 tables.")}</li>}
                  </ul>
                );
              }}
            </QueryBoundary>
          </div>
          <div>
            {table === "" ? (
              <EmptyState title={t("Choose a table")} description={t("Its columns appear here, ready to write SQL against.")} />
            ) : (
              <QueryBoundary query={columns} what={t("columns")}>
                {(cols) => (
                  <div className="overflow-x-auto rounded-md border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead className="w-12">#</TableHead>
                          <TableHead>{t("Column")}</TableHead>
                          <TableHead>{t("Type")}</TableHead>
                          <TableHead>{t("Nullable")}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {cols.map((c) => (
                          <TableRow key={c.name}>
                            <TableCell className="tabular-nums text-muted-foreground">{c.position}</TableCell>
                            <TableCell className="font-mono text-xs">{c.name}</TableCell>
                            <TableCell className="font-mono text-xs">{c.type}</TableCell>
                            <TableCell>{c.nullable ? t("Yes") : t("No")}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                )}
              </QueryBoundary>
            )}
          </div>
        </div>
      )}
    </QueryBoundary>
  );
}

/** Endpoints whose draft runs on this datasource (spec §17.4). */
function UsageTab({ ws, datasourceId }: { ws: string; datasourceId: string }) {
  const { t } = useTranslation();
  const query = useListEndpoints(ws, { datasourceId, trashed: false });
  return (
    <QueryBoundary query={query} what={t("endpoints")}>
      {(rows) =>
        rows.length === 0 ? (
          <EmptyState title={t("No endpoint uses this datasource")} />
        ) : (
          <div className="overflow-x-auto rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("Endpoint")}</TableHead>
                  <TableHead>{t("Route")}</TableHead>
                  <TableHead>{t("State")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell><Link className="font-medium hover:underline" to={`/w/${ws}/endpoints/${e.id}`}>{e.name}</Link></TableCell>
                    <TableCell className="font-mono text-xs">{e.method} /{e.path}</TableCell>
                    <TableCell>{t(STATE_LABEL[e.state] ?? e.state)}{e.liveVersion != null ? ` · v${e.liveVersion}` : ""}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )
      }
    </QueryBoundary>
  );
}
