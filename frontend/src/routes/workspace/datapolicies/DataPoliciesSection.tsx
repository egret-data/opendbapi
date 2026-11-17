import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import {
  getListDataPoliciesQueryKey,
  useCreateDataPolicy,
  useDeleteDataPolicy,
  useListDataPolicies,
  useUpdateDataPolicy,
} from "@/api/generated/data-policies/data-policies";
import type { DataPolicy, MaskType } from "@/api/generated/model";
import { useListServiceAccounts } from "@/api/generated/service-accounts/service-accounts";
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

const MASK_LABEL: Record<MaskType, string> = {
  phone: "Phone (090****123)",
  email: "Email (n***@example.com)",
  partial: "Keep the ends",
  hash: "Hash (salted SHA-256)",
  nullify: "Always null",
};

export function describeRule(p: DataPolicy, t: TFunction): string {
  if (p.kind === "row_filter") return t("Only rows where it equals the caller's \"{{attribute}}\" attribute", { attribute: p.attribute ?? "" });
  if (!p.mask) return "";
  if (p.mask.type === "partial") {
    return t("Keep {{start}} at the start and {{end}} at the end", { start: p.mask.keepStart ?? 0, end: p.mask.keepEnd ?? 0 });
  }
  return t(MASK_LABEL[p.mask.type]);
}

type Scope = { kind: "endpoint"; endpointId: string; datasourceId?: string | null } | { kind: "datasource"; datasourceId: string };

/**
 * Masks and row filters of an endpoint or a datasource (spec §12, §17.4 Access tab). On an
 * endpoint, the masks its datasource adds are listed too, read-only.
 */
export function DataPoliciesSection({ ws, scope, isAdmin }: { ws: string; scope: Scope; isAdmin: boolean }) {
  const { t } = useTranslation();
  const own = useListDataPolicies(ws, scope.kind === "endpoint" ? { endpointId: scope.endpointId } : { datasourceId: scope.datasourceId });
  const inheritedId = scope.kind === "endpoint" ? scope.datasourceId : null;
  const inherited = useListDataPolicies(ws, { datasourceId: inheritedId ?? "" }, { query: { enabled: !!inheritedId } });
  const accounts = useListServiceAccounts(ws);
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: getListDataPoliciesQueryKey(ws) });
  const remove = useDeleteDataPolicy({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const [editing, setEditing] = useState<{ kind: "mask" | "row_filter"; policy?: DataPolicy } | null>(null);
  const accountName = (id: string) => accounts.data?.find((a) => a.id === id)?.name ?? t("deleted account");

  return (
    <section aria-labelledby="policies-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="policies-heading" className="text-lg font-semibold">{t("Data policies")}</h2>
          <p className="text-sm text-muted-foreground">
            {scope.kind === "endpoint"
              ? t("Mask result columns, or return only the rows that belong to the calling service account. Preview ignores them; Try-it and real calls apply them.")
              : t("Masks here apply to every endpoint on this datasource, wherever the column appears in a result, also under another name.")}
          </p>
        </div>
        {isAdmin && (
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => setEditing({ kind: "mask" })}>
              <Plus aria-hidden="true" />
              {t("Add mask")}
            </Button>
            {scope.kind === "endpoint" && !(own.data ?? []).some((p) => p.kind === "row_filter") && (
              <Button variant="outline" size="sm" onClick={() => setEditing({ kind: "row_filter" })}>
                <Plus aria-hidden="true" />
                {t("Add row filter")}
              </Button>
            )}
          </div>
        )}
      </div>
      <QueryBoundary query={own} what={t("data policies")}>
        {(rows) => {
          const all = [...rows.map((p) => ({ p, inherited: false })), ...(inherited.data ?? []).map((p) => ({ p, inherited: true }))];
          return all.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("No data policies: callers see every row and value the query returns.")}</p>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("Column")}</TableHead>
                    <TableHead>{t("Policy")}</TableHead>
                    <TableHead>{t("Not applied to")}</TableHead>
                    <TableHead className="w-36"><span className="sr-only">{t("Actions")}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {all.map(({ p, inherited: fromDatasource }) => (
                    <TableRow key={p.id}>
                      <TableCell className="font-mono text-xs">
                        {p.column}
                        {fromDatasource && <StatusBadge tone="neutral" className="ml-2 font-sans">{t("From datasource")}</StatusBadge>}
                      </TableCell>
                      <TableCell>
                        <span className="font-medium">{p.kind === "row_filter" ? t("Row filter") : t("Mask")}</span>
                        <span className="text-muted-foreground"> · {describeRule(p, t)}</span>
                      </TableCell>
                      <TableCell className="text-sm">
                        {p.exemptServiceAccounts.length === 0 ? <span className="text-muted-foreground">{t("Everyone")}</span>
                          : p.exemptServiceAccounts.map(accountName).join(", ")}
                      </TableCell>
                      <TableCell className="text-right">
                        {isAdmin && !fromDatasource && (
                          <>
                            <Button variant="ghost" size="sm" onClick={() => setEditing({ kind: p.kind, policy: p })}>{t("Edit")}</Button>
                            <Button variant="ghost" size="sm" aria-label={t("Remove policy on {{column}}", { column: p.column })}
                              onClick={() => remove.mutate({ ws, id: p.id })}>
                              {t("Remove")}
                            </Button>
                          </>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          );
        }}
      </QueryBoundary>
      {editing && (
        <PolicyDialog ws={ws} scope={scope} kind={editing.kind} policy={editing.policy}
          accounts={(accounts.data ?? []).map((a) => ({ id: a.id, name: a.name }))}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />
      )}
    </section>
  );
}

function PolicyDialog({ ws, scope, kind, policy, accounts, onClose, onSaved }: {
  ws: string;
  scope: Scope;
  kind: "mask" | "row_filter";
  policy?: DataPolicy;
  accounts: { id: string; name: string }[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [column, setColumn] = useState(policy?.column ?? "");
  const [type, setType] = useState<MaskType>(policy?.mask?.type ?? "partial");
  const [keepStart, setKeepStart] = useState(String(policy?.mask?.keepStart ?? 2));
  const [keepEnd, setKeepEnd] = useState(String(policy?.mask?.keepEnd ?? 2));
  const [attribute, setAttribute] = useState(policy?.attribute ?? "");
  const [exempt, setExempt] = useState<string[]>(policy?.exemptServiceAccounts ?? []);
  const [error, setError] = useState<string | null>(null);
  const create = useCreateDataPolicy();
  const update = useUpdateDataPolicy();
  const pending = create.isPending || update.isPending;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    const data = {
      kind,
      endpointId: scope.kind === "endpoint" ? scope.endpointId : null,
      datasourceId: scope.kind === "datasource" ? scope.datasourceId : null,
      column: column.trim(),
      mask: kind === "mask" ? { type, keepStart: type === "partial" ? Number(keepStart) : null, keepEnd: type === "partial" ? Number(keepEnd) : null } : null,
      attribute: kind === "row_filter" ? attribute.trim() : null,
      exemptServiceAccounts: exempt,
    };
    const handlers = { onSuccess: onSaved, onError: (err: unknown) => setError(errorMessage(err)) };
    if (policy) update.mutate({ ws, id: policy.id, data }, handlers);
    else create.mutate({ ws, data }, handlers);
  };

  const title = kind === "mask"
    ? policy ? t("Edit mask") : t("New mask")
    : policy ? t("Edit row filter") : t("New row filter");
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>
              {kind === "row_filter"
                ? t("Callers get only rows whose column equals their service account's attribute. Without the attribute, a call is refused.")
                : scope.kind === "datasource"
                  ? t("Name a table column. Results computed from it are masked, under any alias.")
                  : t("Name a column as this endpoint returns it.")}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="policy-column">{scope.kind === "datasource" ? t("Table column") : t("Result column")}</Label>
            <Input id="policy-column" required className="font-mono text-xs" value={column}
              placeholder={scope.kind === "datasource" ? "customers.email" : kind === "row_filter" ? "tenant_id" : "phone"}
              onChange={(e) => setColumn(e.target.value)} />
          </div>
          {kind === "mask" ? (
            <div className="flex flex-wrap items-end gap-3">
              <div className="space-y-1">
                <Label htmlFor="policy-type">{t("Mask")}</Label>
                <Select value={type} onValueChange={(v) => setType(v as MaskType)}>
                  <SelectTrigger id="policy-type" className="w-60"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {(Object.keys(MASK_LABEL) as MaskType[]).map((m) => <SelectItem key={m} value={m}>{t(MASK_LABEL[m])}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              {type === "partial" && (
                <>
                  <div className="space-y-1">
                    <Label htmlFor="policy-start">{t("Keep at start")}</Label>
                    <Input id="policy-start" type="number" min={0} max={64} className="w-24" value={keepStart} onChange={(e) => setKeepStart(e.target.value)} />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="policy-end">{t("Keep at end")}</Label>
                    <Input id="policy-end" type="number" min={0} max={64} className="w-24" value={keepEnd} onChange={(e) => setKeepEnd(e.target.value)} />
                  </div>
                </>
              )}
            </div>
          ) : (
            <div className="space-y-1">
              <Label htmlFor="policy-attribute">{t("Service account attribute")}</Label>
              <Input id="policy-attribute" required className="font-mono text-xs" value={attribute} placeholder="tenant"
                onChange={(e) => setAttribute(e.target.value)} />
              <p className="text-xs text-muted-foreground">{t("Set it on each service account's page. A list allows several values.")}</p>
            </div>
          )}
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">{t("Not applied to")}</legend>
            {accounts.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("No service accounts yet.")}</p>
            ) : (
              <div className="max-h-40 space-y-1 overflow-y-auto">
                {accounts.map((a) => (
                  <div key={a.id} className="flex items-center gap-2">
                    <Checkbox id={`exempt-${a.id}`} checked={exempt.includes(a.id)}
                      onCheckedChange={(c) => setExempt(c === true ? [...exempt, a.id] : exempt.filter((x) => x !== a.id))} />
                    <Label htmlFor={`exempt-${a.id}`} className="font-normal">{a.name}</Label>
                  </div>
                ))}
              </div>
            )}
          </fieldset>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("Cancel")}</Button>
            <Button type="submit" disabled={pending}>{t("Save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
