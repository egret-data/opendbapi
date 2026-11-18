import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { HttpCall, HttpCallMethod } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const METHODS: NonNullable<HttpCallMethod>[] = ["GET", "POST", "PUT", "PATCH", "DELETE"];

type Row = { name: string; value: string };

const rowsOf = (list: NonNullable<HttpCall>["query"]): Row[] =>
  (list ?? []).flatMap((f) => (f ? [{ name: f.name ?? "", value: f.value ?? "" }] : []));

/** The request an HTTP task sends (spec §6): method, path, query and body fields, and where the rows are. */
export function HttpRequestEditor({ request, baseUrl, disabled, onChange }: {
  request: NonNullable<HttpCall>;
  baseUrl?: string | null;
  disabled: boolean;
  onChange: (request: NonNullable<HttpCall>) => void;
}) {
  const { t } = useTranslation();
  const set = (patch: Partial<NonNullable<HttpCall>>) => onChange({ ...request, ...patch });
  const query = rowsOf(request.query);
  const body = rowsOf(request.body);
  return (
    <div className="space-y-5 rounded-md border p-4">
      <div className="space-y-2">
        <Label htmlFor="http-path">{t("Request")}</Label>
        <div className="flex flex-wrap items-center gap-2">
          <Select value={request.method ?? "GET"} disabled={disabled}
            onValueChange={(v) => set({ method: v as HttpCallMethod, body: v === "GET" ? [] : request.body })}>
            <SelectTrigger aria-label={t("Request method")} className="w-28"><SelectValue /></SelectTrigger>
            <SelectContent>{METHODS.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}</SelectContent>
          </Select>
          {baseUrl && <span className="font-mono text-xs text-muted-foreground">{baseUrl}</span>}
          <Input id="http-path" className="min-w-48 flex-1 font-mono text-xs" value={request.path} disabled={disabled}
            placeholder="/orders/{id}" onChange={(e) => set({ path: e.target.value })} />
        </div>
        <p className="text-xs text-muted-foreground">
          {t("Put a parameter in the path with {name}. Values here and below are expressions, as in <if test>: a parameter name, customer.id, or 'text'.")}
        </p>
      </div>
      <Fields label={t("Query string")} rows={query} disabled={disabled} onChange={(rows) => set({ query: rows })}
        empty={t("No query string. Add a row to pass a parameter on, e.g. status → status.")} />
      {request.method !== "GET" && (
        <Fields label={t("JSON body")} rows={body} disabled={disabled} onChange={(rows) => set({ body: rows })}
          empty={t("No body. Add a row to send a JSON field.")} />
      )}
      <div className="space-y-2">
        <Label htmlFor="http-result">{t("Rows at")}</Label>
        <Input id="http-result" className="w-72 font-mono text-xs" value={request.resultPath ?? ""} disabled={disabled}
          placeholder="/data/items" onChange={(e) => set({ resultPath: e.target.value })} />
        <p className="text-xs text-muted-foreground">
          {t("A JSON Pointer to the rows in the response; empty for the whole response. An array gives one row per item, an object one row.")}
        </p>
      </div>
    </div>
  );
}

function Fields({ label, rows, disabled, onChange, empty }: {
  label: string;
  rows: Row[];
  disabled: boolean;
  onChange: (rows: Row[]) => void;
  empty: string;
}) {
  const { t } = useTranslation();
  const setRow = (i: number, patch: Partial<Row>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <div className="space-y-2">
      <Label>{label}</Label>
      {rows.length === 0 && <p className="text-xs text-muted-foreground">{empty}</p>}
      {rows.map((r, i) => (
        <div key={i} className="flex items-center gap-2">
          <Input aria-label={t("{{section}} field {{number}} name", { section: label, number: i + 1 })} className="w-44 font-mono text-xs"
            value={r.name} disabled={disabled} placeholder="status" onChange={(e) => setRow(i, { name: e.target.value })} />
          <span aria-hidden="true" className="text-muted-foreground">←</span>
          <Input aria-label={t("{{section}} field {{number}} value", { section: label, number: i + 1 })} className="font-mono text-xs"
            value={r.value} disabled={disabled} placeholder="status" onChange={(e) => setRow(i, { value: e.target.value })} />
          {!disabled && (
            <Button type="button" variant="ghost" size="icon" aria-label={t("Remove {{section}} field {{number}}", { section: label, number: i + 1 })}
              onClick={() => onChange(rows.filter((_, j) => j !== i))}>
              <Trash2 className="h-4 w-4" />
            </Button>
          )}
        </div>
      ))}
      {!disabled && (
        <Button type="button" variant="outline" size="sm" onClick={() => onChange([...rows, { name: "", value: "" }])}>
          <Plus aria-hidden="true" />
          {t("Add field")}
        </Button>
      )}
    </div>
  );
}
