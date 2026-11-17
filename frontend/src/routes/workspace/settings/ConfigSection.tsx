import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import type { ConfigApplyResult, ConfigEnvironment, ConfigPlan } from "@/api/generated/model";
import {
  getListEnvironmentsQueryKey,
  useApplyConfig,
  useDeleteEnvironment,
  useListEnvironments,
  usePlanConfig,
  usePutEnvironment,
} from "@/api/generated/config/config";
import { useListDatasources } from "@/api/generated/datasources/datasources";
import { QueryBoundary } from "@/components/QueryBoundary";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";

const ACTION_TONE = { create: "success", update: "highlight", delete: "destructive", unchanged: "neutral", skip: "inactive" } as const;
const NO_ENV = "__none__";

/** Plan and apply a YAML file (spec §15.1), with environments (spec §15.2). Workspace admins only. */
export function ConfigSection({ ws }: { ws: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const environments = useListEnvironments(ws);
  const [file, setFile] = useState<{ name: string; text: string } | null>(null);
  const [environment, setEnvironment] = useState(NO_ENV);
  const [prune, setPrune] = useState(false);
  const [publish, setPublish] = useState(true);
  const [plan, setPlan] = useState<ConfigPlan | null>(null);
  const [result, setResult] = useState<ConfigApplyResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const planning = usePlanConfig();
  const applying = useApplyConfig();

  const params = () => ({ environment: environment === NO_ENV ? undefined : environment, prune, publish });
  const reset = () => {
    setPlan(null);
    setResult(null);
    setError(null);
  };

  const runPlan = () => {
    if (!file) return;
    reset();
    planning.mutate({ ws, data: file.text, params: params() }, {
      onSuccess: (p) => setPlan(p as ConfigPlan),
      onError: (e) => setError(errorMessage(e)),
    });
  };

  const runApply = () => {
    if (!file || !plan) return;
    setError(null);
    applying.mutate({ ws, data: file.text, params: { ...params(), expectedDigest: plan.digest } }, {
      onSuccess: (r) => {
        const applied = r as ConfigApplyResult;
        setResult(applied);
        setPlan(null);
        // Workspace data changed; the signed-in user did not (refetching it would remount the page).
        qc.invalidateQueries({ predicate: (q) => typeof q.queryKey[0] === "string" && q.queryKey[0].startsWith(`/admin/api/ws/${ws}/`) });
        if (applied.published.every((p) => p.result === "published" || p.result === "disabled")) toast.success(t("Configuration applied."));
        else toast.warning(t("Applied, but some endpoints were not published."));
      },
      onError: (e) => setError(errorMessage(e)),
    });
  };

  const changes = plan?.changes.filter((c) => c.action !== "unchanged") ?? [];
  const unchanged = (plan?.changes.length ?? 0) - changes.length;
  const actionable = changes.some((c) => c.action !== "skip");

  return (
    <div className="space-y-8">
      <section aria-labelledby="apply-heading" className="space-y-3">
        <h2 id="apply-heading" className="text-lg font-semibold">{t("Apply YAML")}</h2>
        <p className="max-w-prose text-sm text-muted-foreground">
          {t("Make this workspace match a YAML file in the export format, for example one kept in Git. Check shows what would change; nothing is written until you apply. The command line does the same: opendbapi plan and opendbapi apply.")}
        </p>
        <div className="grid max-w-3xl gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="config-file">{t("YAML file")}</Label>
            <Input id="config-file" type="file" accept=".yaml,.yml,application/yaml,text/yaml"
              onChange={async (e) => {
                reset();
                const f = e.target.files?.[0];
                setFile(f ? { name: f.name, text: await f.text() } : null);
              }} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="config-env">{t("Environment")}</Label>
            <Select value={environment} onValueChange={(v) => { setEnvironment(v); reset(); }}>
              <SelectTrigger id="config-env"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_ENV}>{t("None (names as written)")}</SelectItem>
                {(environments.data ?? []).map((env) => <SelectItem key={env.name} value={env.name}>{env.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
        </div>
        <div className="space-y-2">
            <div className="flex items-center gap-2">
              <Checkbox id="config-publish" checked={publish} onCheckedChange={(v) => { setPublish(v === true); reset(); }} />
              <Label htmlFor="config-publish">{t("Publish endpoints marked live (after their tests)")}</Label>
            </div>
            <div className="flex items-center gap-2">
              <Checkbox id="config-prune" checked={prune} onCheckedChange={(v) => { setPrune(v === true); reset(); }} />
              <Label htmlFor="config-prune">{t("Delete what the file does not list")}</Label>
            </div>
        </div>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        <div className="flex gap-2">
          <Button variant="outline" disabled={!file || planning.isPending} onClick={runPlan}>{t("Check")}</Button>
          <Button disabled={!plan || !actionable || applying.isPending} title={!plan ? t("Check the file first") : undefined} onClick={runApply}>
            {t("Apply")}
          </Button>
        </div>
        {plan && (
          <div className="space-y-2">
            <p className="text-sm">
              {actionable
                ? t("{{create}} to create, {{update}} to update, {{delete}} to delete, {{unchanged}} unchanged.", {
                  create: plan.counts.create ?? 0, update: plan.counts.update ?? 0, delete: plan.counts.delete ?? 0, unchanged,
                })
                : t("No changes.")}
            </p>
            {changes.length > 0 && (
              <div className="overflow-x-auto rounded-md border">
                <Table aria-label={t("Planned changes")}>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("Kind")}</TableHead>
                      <TableHead>{t("Name")}</TableHead>
                      <TableHead>{t("Change")}</TableHead>
                      <TableHead>{t("Details")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {changes.map((c) => (
                      <TableRow key={`${c.kind} ${c.name}`}>
                        <TableCell>{c.kind}</TableCell>
                        <TableCell className="font-mono text-xs">{c.name}</TableCell>
                        <TableCell><StatusBadge tone={ACTION_TONE[c.action]}>{t(c.action)}</StatusBadge></TableCell>
                        <TableCell className="whitespace-normal font-mono text-xs">{c.details.join(", ") || "—"}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </div>
        )}
        {result && result.published.length > 0 && (
          <ul aria-label={t("Publishing")} className="space-y-1 text-sm">
            {result.published.map((p) => (
              <li key={p.endpoint}>
                <span className="font-mono text-xs">{p.endpoint}</span>{" "}
                <StatusBadge tone={p.result === "published" || p.result === "disabled" ? "success" : "destructive"}>{t(p.result)}</StatusBadge>
                {p.message && <span className="text-muted-foreground"> {p.message}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
      <EnvironmentsSection ws={ws} />
    </div>
  );
}

function EnvironmentsSection({ ws }: { ws: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const query = useListEnvironments(ws);
  const refresh = () => qc.invalidateQueries({ queryKey: getListEnvironmentsQueryKey(ws) });
  const remove = useDeleteEnvironment({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const [editing, setEditing] = useState<{ env?: ConfigEnvironment } | null>(null);

  return (
    <section aria-labelledby="environments-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="environments-heading" className="text-lg font-semibold">{t("Environments")}</h2>
          <p className="max-w-prose text-sm text-muted-foreground">
            {t("An environment maps the datasource names used in a YAML file to this workspace's datasources, so the same file applies to dev, staging and prod. Mapped datasources keep their own settings and passwords.")}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => setEditing({})}>
          <Plus aria-hidden="true" />
          {t("Add environment")}
        </Button>
      </div>
      <QueryBoundary query={query} what={t("environments")}>
        {(envs) => envs.length === 0 ? <p className="text-sm text-muted-foreground">{t("No environments yet.")}</p> : (
          <div className="overflow-x-auto rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("Name")}</TableHead>
                  <TableHead>{t("Datasources")}</TableHead>
                  <TableHead className="w-40"><span className="sr-only">{t("Actions")}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {envs.map((env) => (
                  <TableRow key={env.name}>
                    <TableCell className="font-medium">{env.name}</TableCell>
                    <TableCell className="whitespace-normal font-mono text-xs">
                      {Object.entries(env.bindings).map(([k, v]) => `${k} → ${v}`).join(", ") || "—"}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button variant="ghost" size="sm" onClick={() => setEditing({ env })}>{t("Edit")}</Button>
                      <Button variant="ghost" size="sm" aria-label={t("Delete environment {{name}}", { name: env.name })}
                        onClick={() => remove.mutate({ ws, name: env.name })}>
                        {t("Delete")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </QueryBoundary>
      {editing && <EnvironmentDialog ws={ws} env={editing.env} onClose={() => setEditing(null)}
        onSaved={() => { setEditing(null); refresh(); }} />}
    </section>
  );
}

function EnvironmentDialog({ ws, env, onClose, onSaved }: { ws: string; env?: ConfigEnvironment; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation();
  const datasources = useListDatasources(ws);
  const [name, setName] = useState(env?.name ?? "");
  const [rows, setRows] = useState<{ logical: string; datasource: string }[]>(
    env ? Object.entries(env.bindings).map(([logical, datasource]) => ({ logical, datasource })) : [{ logical: "", datasource: "" }]);
  const [error, setError] = useState<string | null>(null);
  const save = usePutEnvironment();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const bindings = Object.fromEntries(rows.filter((r) => r.logical.trim() || r.datasource).map((r) => [r.logical.trim(), r.datasource]));
    save.mutate({ ws, name: name.trim(), data: { bindings } }, {
      onSuccess: () => {
        toast.success(t("Environment saved."));
        onSaved();
      },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{env ? t("Edit environment") : t("New environment")}</DialogTitle>
            <DialogDescription>{t("Map each datasource name in the file to a datasource of this workspace.")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="env-name">{t("Name")}</Label>
            <Input id="env-name" required className="font-mono text-xs" value={name} disabled={!!env} placeholder="prod"
              onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-2">
            {rows.map((row, i) => (
              <div key={i} className="flex items-end gap-2">
                <div className="flex-1 space-y-1">
                  <Label htmlFor={`env-logical-${i}`}>{t("Name in the file")}</Label>
                  <Input id={`env-logical-${i}`} className="font-mono text-xs" value={row.logical} placeholder="main-db"
                    onChange={(e) => setRows(rows.map((r, j) => (j === i ? { ...r, logical: e.target.value } : r)))} />
                </div>
                <div className="flex-1 space-y-1">
                  <Label htmlFor={`env-ds-${i}`}>{t("Datasource here")}</Label>
                  <Select value={row.datasource} onValueChange={(v) => setRows(rows.map((r, j) => (j === i ? { ...r, datasource: v } : r)))}>
                    <SelectTrigger id={`env-ds-${i}`}><SelectValue placeholder={t("Choose")} /></SelectTrigger>
                    <SelectContent>
                      {(datasources.data ?? []).map((d) => <SelectItem key={d.id} value={d.name}>{d.name}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <Button type="button" variant="ghost" size="icon" aria-label={t("Remove mapping")}
                  onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                  <Trash2 aria-hidden="true" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" onClick={() => setRows([...rows, { logical: "", datasource: "" }])}>
              <Plus aria-hidden="true" />
              {t("Add mapping")}
            </Button>
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("Cancel")}</Button>
            <Button type="submit" disabled={save.isPending}>{t("Save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
