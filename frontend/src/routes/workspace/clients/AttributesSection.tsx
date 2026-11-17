import { useState, type FormEvent } from "react";
import { Plus, Trash2 } from "lucide-react";
import i18n from "i18next";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";

import { useUpdateServiceAccount } from "@/api/generated/service-accounts/service-accounts";
import type { ServiceAccount } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";

type Kind = "text" | "number" | "texts" | "numbers";
type Row = { name: string; kind: Kind; value: string };

const KIND_LABEL: Record<Kind, string> = { text: "Text", number: "Number", texts: "List of text", numbers: "List of numbers" };

export function toRows(attributes: Record<string, unknown>): Row[] {
  return Object.entries(attributes).map(([name, v]) => {
    if (Array.isArray(v)) {
      const numbers = v.length > 0 && v.every((x) => typeof x === "number");
      return { name, kind: numbers ? "numbers" : "texts", value: v.join(", ") };
    }
    return { name, kind: typeof v === "number" ? "number" : "text", value: String(v) };
  });
}

/** @returns the attributes, or a message naming the first bad row */
export function fromRows(rows: Row[], t: TFunction = i18n.t.bind(i18n)): Record<string, unknown> | string {
  const out: Record<string, unknown> = {};
  for (const r of rows) {
    const name = r.name.trim();
    if (!name) continue;
    if (name in out) return t('Attribute "{{name}}" is listed twice.', { name });
    const parts = r.value.split(",").map((p) => p.trim()).filter((p) => p !== "");
    const asNumber = (s: string) => (s !== "" && Number.isFinite(Number(s)) ? Number(s) : null);
    switch (r.kind) {
      case "text":
        out[name] = r.value.trim();
        break;
      case "number": {
        const n = asNumber(r.value.trim());
        if (n === null) return t('Attribute "{{name}}" must be a number.', { name });
        out[name] = n;
        break;
      }
      case "texts":
        out[name] = parts;
        break;
      case "numbers": {
        const ns = parts.map(asNumber);
        if (ns.some((n) => n === null)) return t('Every value of "{{name}}" must be a number.', { name });
        out[name] = ns;
        break;
      }
    }
  }
  return out;
}

function show(v: unknown): string {
  return Array.isArray(v) ? v.join(", ") : String(v);
}

/** Facts row filters read, such as the tenant a caller belongs to (spec §12.2). */
export function AttributesSection({ ws, sa, isAdmin, onChanged }: { ws: string; sa: ServiceAccount; isAdmin: boolean; onChanged: () => void }) {
  const [editing, setEditing] = useState(false);
  const entries = Object.entries(sa.attributes ?? {});
  const { t } = useTranslation();
  return (
    <section aria-labelledby="attributes-heading" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 id="attributes-heading" className="text-lg font-semibold">{t("Attributes")}</h2>
        {isAdmin && <Button variant="outline" size="sm" onClick={() => setEditing(true)}>{t("Edit attributes")}</Button>}
      </div>
      {entries.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("No attributes. Row filters read them to decide which rows this account may see.")}</p>
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow><TableHead>{t("Name")}</TableHead><TableHead>{t("Value")}</TableHead></TableRow>
            </TableHeader>
            <TableBody>
              {entries.map(([name, v]) => (
                <TableRow key={name}>
                  <TableCell className="font-mono text-xs">{name}</TableCell>
                  <TableCell className="font-mono text-xs">{show(v)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && <AttributesDialog ws={ws} sa={sa} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); onChanged(); }} />}
    </section>
  );
}

function AttributesDialog({ ws, sa, onClose, onSaved }: { ws: string; sa: ServiceAccount; onClose: () => void; onSaved: () => void }) {
  const [rows, setRows] = useState<Row[]>(() => toRows(sa.attributes ?? {}));
  const [error, setError] = useState<string | null>(null);
  const update = useUpdateServiceAccount();
  const { t } = useTranslation();
  const set = (i: number, patch: Partial<Row>) => setRows(rows.map((r, k) => (k === i ? { ...r, ...patch } : r)));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const attributes = fromRows(rows, t);
    if (typeof attributes === "string") {
      setError(attributes);
      return;
    }
    update.mutate({ ws, id: sa.id, data: { name: sa.name, description: sa.description ?? null, disabled: sa.disabled, attributes } }, {
      onSuccess: onSaved,
      onError: (err) => setError(errorMessage(err)),
    });
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-2xl">
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{t("Edit attributes")}</DialogTitle>
            <DialogDescription>
              {t("Use the type the filtered column has: a number for a numeric column. Separate list values with commas.")}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            {rows.map((r, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <Input aria-label={t("Attribute {{n}} name", { n: i + 1 })} className="w-40 font-mono text-xs" value={r.name} placeholder="tenant"
                  onChange={(e) => set(i, { name: e.target.value })} />
                <Select value={r.kind} onValueChange={(v) => set(i, { kind: v as Kind })}>
                  <SelectTrigger className="w-40" aria-label={t("Attribute {{n}} type", { n: i + 1 })}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {(Object.keys(KIND_LABEL) as Kind[]).map((k) => <SelectItem key={k} value={k}>{t(KIND_LABEL[k])}</SelectItem>)}
                  </SelectContent>
                </Select>
                <Input aria-label={t("Attribute {{n}} value", { n: i + 1 })} className="min-w-0 flex-1 font-mono text-xs" value={r.value}
                  onChange={(e) => set(i, { value: e.target.value })} />
                <Button type="button" variant="ghost" size="icon" aria-label={t("Remove attribute {{n}}", { n: i + 1 })}
                  onClick={() => setRows(rows.filter((_, k) => k !== i))}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" onClick={() => setRows([...rows, { name: "", kind: "text", value: "" }])}>
              <Plus aria-hidden="true" />
              {t("Add attribute")}
            </Button>
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("Cancel")}</Button>
            <Button type="submit" disabled={update.isPending}>{t("Save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
