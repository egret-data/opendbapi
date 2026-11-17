import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Play, Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import {
  getListEndpointTestsQueryKey,
  useCreateEndpointTest,
  useDeleteEndpointTest,
  useListEndpointTests,
  useRunEndpointTests,
  useUpdateEndpointTest,
} from "@/api/generated/endpoint-tests/endpoint-tests";
import type { EndpointTestCase, JsonNode, JsonPathCheck, SaveEndpointTest } from "@/api/generated/model";
import { useListServiceAccounts } from "@/api/generated/service-accounts/service-accounts";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { errorMessage } from "@/lib/errors";

const NO_CLIENT = "__draft__";

type PathRow = { path: string; equals: string; exists: "any" | "yes" | "no" };

/** Parses an expected value as JSON when it is JSON (3, true, "x", null), else takes it as text. */
export function parseExpected(text: string): unknown {
  const t = text.trim();
  if (t === "") return null;
  try {
    return JSON.parse(t);
  } catch {
    return t;
  }
}

export function toPathChecks(rows: PathRow[]): NonNullable<JsonPathCheck>[] {
  return rows.filter((r) => r.path.trim() !== "").map((r) => ({
    path: r.path.trim(),
    // The generated JsonNode type says "object", but any JSON value is accepted.
    equals: r.equals.trim() === "" ? null : (parseExpected(r.equals) as JsonNode),
    exists: r.exists === "any" ? null : r.exists === "yes",
  }));
}

/** Saved checks of this endpoint, run against the saved draft (spec §11.3). */
export function TestsTab({ ws, endpointId, paramNames, paginated, canAuthor, dirty }: {
  ws: string; endpointId: string; paramNames: string[]; paginated: boolean; canAuthor: boolean; dirty: boolean;
}) {
  const query = useListEndpointTests(ws, endpointId);
  const { t } = useTranslation();
  const accounts = useListServiceAccounts(ws);
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: getListEndpointTestsQueryKey(ws, endpointId) });
  const run = useRunEndpointTests({
    mutation: {
      onSuccess: (r) => {
        refresh();
        if (r.failed === 0) toast.success(t("{{passed}} of {{total}} passed.", { passed: r.passed, total: r.passed }));
        else toast.error(t("{{failed}} of {{total}} failed.", { failed: r.failed, total: r.passed + r.failed }));
      },
      onError: (e) => toast.error(errorMessage(e)),
    },
  });
  const remove = useDeleteEndpointTest({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const [editing, setEditing] = useState<{ test?: EndpointTestCase } | null>(null);
  const accountName = (id?: string | null) => (id ? accounts.data?.find((a) => a.id === id)?.name ?? t("deleted account") : t("No client (like preview)"));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-prose text-sm text-muted-foreground">
          {t("Tests run the")} <strong>{t("saved")}</strong>{" "}
          {t("draft and always roll back. A test that calls as a service account also checks its grant and applies rules and data policies.")}
          {dirty && ` ${t("You have unsaved changes; save to test them.")}`}
        </p>
        {canAuthor && (
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => setEditing({})}>
              <Plus aria-hidden="true" />
              {t("Add test")}
            </Button>
            <Button size="sm" disabled={run.isPending || (query.data ?? []).length === 0}
              onClick={() => run.mutate({ ws, endpointId, params: {} })}>
              <Play aria-hidden="true" />
              {run.isPending ? t("Running…") : t("Run all")}
            </Button>
          </div>
        )}
      </div>
      <QueryBoundary query={query} what={t("tests")}>
        {(tests) =>
          tests.length === 0 ? (
            <EmptyState variant="dashed" title={t("No tests yet")}
              description={t("Save parameter values and what the answer must look like; run them before each publish.")} />
          ) : (
            <ul className="divide-y rounded-md border" aria-label={t("Tests")}>
              {tests.map((tc) => {
                const r = tc.lastResult;
                return (
                  <li key={tc.id} className="space-y-1 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{tc.name}</span>
                      {!r ? <StatusBadge tone="neutral">{t("Not run")}</StatusBadge>
                        : r.passed ? <StatusBadge tone="success">{t("Passed")}</StatusBadge>
                          : <StatusBadge tone="inactive">{t("Failed")}</StatusBadge>}
                      {r && <span className="text-xs text-muted-foreground tabular-nums">{r.status} · {r.durationMs} ms{r.rowCount != null ? ` · ${t("{{n}} rows", { n: r.rowCount })}` : ""}</span>}
                      <span className="text-xs text-muted-foreground">· {accountName(tc.serviceAccountId)}</span>
                      {canAuthor && (
                        <span className="ml-auto flex gap-1">
                          <Button variant="ghost" size="sm" disabled={run.isPending}
                            onClick={() => run.mutate({ ws, endpointId, params: { testId: tc.id } })}>{t("Run")}</Button>
                          <Button variant="ghost" size="sm" onClick={() => setEditing({ test: tc })}>{t("Edit")}</Button>
                          <Button variant="ghost" size="sm" aria-label={t("Delete test {{name}}", { name: tc.name })}
                            onClick={() => remove.mutate({ ws, endpointId, testId: tc.id })}>{t("Delete")}</Button>
                        </span>
                      )}
                    </div>
                    {r && r.failures.length > 0 && (
                      <ul className="list-disc pl-5 text-sm text-destructive">
                        {r.failures.map((f, i) => <li key={i}>{f}</li>)}
                      </ul>
                    )}
                  </li>
                );
              })}
            </ul>
          )
        }
      </QueryBoundary>
      {editing && (
        <TestDialog ws={ws} endpointId={endpointId} test={editing.test} paramNames={paramNames} paginated={paginated}
          accounts={(accounts.data ?? []).map((a) => ({ id: a.id, name: a.name }))}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />
      )}
    </div>
  );
}

function TestDialog({ ws, endpointId, test, paramNames, paginated, accounts, onClose, onSaved }: {
  ws: string; endpointId: string; test?: EndpointTestCase; paramNames: string[]; paginated: boolean;
  accounts: { id: string; name: string }[]; onClose: () => void; onSaved: () => void;
}) {
  const { t } = useTranslation();
  const x = test?.expect;
  const names = [...paramNames, ...(paginated ? ["page", "size"] : [])];
  const [name, setName] = useState(test?.name ?? "");
  const [account, setAccount] = useState(test?.serviceAccountId ?? NO_CLIENT);
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(names.map((n) => [n, test?.params?.[n] == null ? "" : String(test.params[n])])));
  const [status, setStatus] = useState(String(x?.status ?? 200));
  const [minRows, setMinRows] = useState(x?.rowCount?.min == null ? "" : String(x.rowCount.min));
  const [maxRows, setMaxRows] = useState(x?.rowCount?.max == null ? "" : String(x.rowCount.max));
  const [maxMs, setMaxMs] = useState(x?.maxDurationMs == null ? "" : String(x.maxDurationMs));
  const [schema, setSchema] = useState(x?.schemaMatchesLive === true);
  const [paths, setPaths] = useState<PathRow[]>(() => (x?.jsonPath ?? []).filter((c): c is NonNullable<JsonPathCheck> => c != null).map((c) => ({
    path: c.path, equals: c.equals == null ? "" : JSON.stringify(c.equals), exists: c.exists == null ? "any" : c.exists ? "yes" : "no",
  })));
  const [error, setError] = useState<string | null>(null);
  const create = useCreateEndpointTest();
  const update = useUpdateEndpointTest();
  const num = (s: string) => (s.trim() === "" ? null : Number(s));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const params = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ""));
    const data: SaveEndpointTest = {
      name: name.trim(),
      serviceAccountId: account === NO_CLIENT ? null : account,
      params,
      expect: {
        status: num(status) ?? 200,
        rowCount: minRows.trim() === "" && maxRows.trim() === "" ? null : { min: num(minRows), max: num(maxRows) },
        jsonPath: toPathChecks(paths),
        schemaMatchesLive: schema || null,
        maxDurationMs: num(maxMs),
      },
    };
    const handlers = { onSuccess: onSaved, onError: (err: unknown) => setError(errorMessage(err)) };
    if (test) update.mutate({ ws, endpointId, testId: test.id, data }, handlers);
    else create.mutate({ ws, endpointId, data }, handlers);
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{test ? t("Edit test") : t("New test")}</DialogTitle>
            <DialogDescription>{t("Leave an expectation empty to skip it.")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <Label htmlFor="test-name">{t("Name")}</Label>
              <Input id="test-name" required maxLength={120} value={name} placeholder={t("active customers only")} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="test-account">{t("Call as")}</Label>
              <Select value={account} onValueChange={setAccount}>
                <SelectTrigger id="test-account"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_CLIENT}>{t("No client (like preview)")}</SelectItem>
                  {accounts.map((a) => <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
          </div>
          {names.length > 0 && (
            <fieldset className="space-y-2">
              <legend className="text-sm font-medium">{t("Parameters")}</legend>
              <div className="flex flex-wrap gap-3">
                {names.map((n) => (
                  <div key={n} className="space-y-1">
                    <Label htmlFor={`test-p-${n}`} className="font-mono text-xs">{n}</Label>
                    <Input id={`test-p-${n}`} className="h-8 w-36 text-xs" value={values[n] ?? ""}
                      onChange={(e) => setValues({ ...values, [n]: e.target.value })} />
                  </div>
                ))}
              </div>
            </fieldset>
          )}
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">{t("Expect")}</legend>
            <div className="flex flex-wrap gap-3">
              <div className="space-y-1">
                <Label htmlFor="test-status">{t("Status")}</Label>
                <Input id="test-status" type="number" min={100} max={599} className="w-24" value={status} onChange={(e) => setStatus(e.target.value)} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="test-min">{t("Rows at least")}</Label>
                <Input id="test-min" type="number" min={0} className="w-28" value={minRows} onChange={(e) => setMinRows(e.target.value)} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="test-max">{t("Rows at most")}</Label>
                <Input id="test-max" type="number" min={0} className="w-28" value={maxRows} onChange={(e) => setMaxRows(e.target.value)} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="test-ms">{t("Within (ms)")}</Label>
                <Input id="test-ms" type="number" min={1} className="w-28" value={maxMs} onChange={(e) => setMaxMs(e.target.value)} />
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Checkbox id="test-schema" checked={schema} onCheckedChange={(c) => setSchema(c === true)} />
              <Label htmlFor="test-schema" className="font-normal">{t("Same result columns as the live version")}</Label>
            </div>
          </fieldset>
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">{t("JSONPath checks")}</legend>
            <p className="text-xs text-muted-foreground">{t("Paths into the answer, such as $.data[0].status or $.meta.total. Values are JSON: 3, true, \"active\".")}</p>
            {paths.map((p, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <Input aria-label={t("Check {{n}} path", { n: i + 1 })} className="w-56 font-mono text-xs" value={p.path} placeholder="$.data[0].status"
                  onChange={(e) => setPaths(paths.map((x2, k) => (k === i ? { ...x2, path: e.target.value } : x2)))} />
                <Input aria-label={t("Check {{n}} value", { n: i + 1 })} className="w-40 font-mono text-xs" value={p.equals} placeholder={'"active"'}
                  onChange={(e) => setPaths(paths.map((x2, k) => (k === i ? { ...x2, equals: e.target.value } : x2)))} />
                <Select value={p.exists} onValueChange={(v) => setPaths(paths.map((x2, k) => (k === i ? { ...x2, exists: v as PathRow["exists"] } : x2)))}>
                  <SelectTrigger className="w-36" aria-label={t("Check {{n}} presence", { n: i + 1 })}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="any">{t("Value only")}</SelectItem>
                    <SelectItem value="yes">{t("Must exist")}</SelectItem>
                    <SelectItem value="no">{t("Must be absent")}</SelectItem>
                  </SelectContent>
                </Select>
                <Button type="button" variant="ghost" size="icon" aria-label={t("Remove check {{n}}", { n: i + 1 })}
                  onClick={() => setPaths(paths.filter((_, k) => k !== i))}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" onClick={() => setPaths([...paths, { path: "", equals: "", exists: "any" }])}>
              <Plus aria-hidden="true" />
              {t("Add check")}
            </Button>
          </fieldset>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("Cancel")}</Button>
            <Button type="submit" disabled={create.isPending || update.isPending}>{t("Save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
