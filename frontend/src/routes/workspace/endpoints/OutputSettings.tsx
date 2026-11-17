import { Plus, Trash2 } from "lucide-react";

import type { EndpointDefinition, KeysetKey, NestCollection, PaginationMode } from "@/api/generated/model";
import { ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

type Def = EndpointDefinition;
type SetDef = (patch: Partial<Def>) => void;

const VALUE_TYPES = { decimal_string: "Decimal as text", iso8601: "ISO 8601 date", epoch_ms: "Epoch milliseconds", boolean: "Boolean (0/1)" };
type ValueType = keyof typeof VALUE_TYPES;

/** Offset or keyset paging (spec §9.3); keyset needs key columns. */
export function PaginationModeRows({ def, setDef, disabled }: { def: Def; setDef: SetDef; disabled: boolean }) {
  const p = def.pagination;
  type Key = NonNullable<KeysetKey>;
  const keys: Key[] = (p.keys ?? []).filter((k): k is Key => k != null);
  const setKeys = (k: Key[]) => setDef({ pagination: { ...p, keys: k } });
  const keyset = p.mode === "keyset";
  return (
    <>
      <ConfigTableRow label="Mode" htmlFor="ep-page-mode"
        details="Offset uses page and size. Keyset returns a cursor for the next page: steady under inserts and fast on deep pages, but no jumping to page N.">
        <Select value={p.mode ?? "offset"} disabled={disabled || !p.enabled} onValueChange={(v) => setDef({ pagination: { ...p, mode: v as PaginationMode } })}>
          <SelectTrigger id="ep-page-mode" className="w-40"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="offset">Offset</SelectItem>
            <SelectItem value="keyset">Keyset</SelectItem>
          </SelectContent>
        </Select>
      </ConfigTableRow>
      {p.enabled && keyset && (
        <ConfigTableRow label="Key columns" details="Result columns the pages are ordered by. Never null; together unique, so end with an id.">
          <div className="space-y-2">
            {keys.map((k, i) => (
              <div key={i} className="flex items-center gap-2">
                <Input aria-label={`Key ${i + 1} column`} className="w-44 font-mono text-xs" value={k.column} placeholder="created_at"
                  onChange={(e) => setKeys(keys.map((x, j) => (j === i ? { ...x, column: e.target.value } : x)))} />
                <Checkbox id={`key-desc-${i}`} checked={k.descending}
                  onCheckedChange={(c) => setKeys(keys.map((x, j) => (j === i ? { ...x, descending: c === true } : x)))} />
                <Label htmlFor={`key-desc-${i}`} className="font-normal">Newest / largest first</Label>
                <Button type="button" variant="ghost" size="icon" aria-label={`Remove key ${i + 1}`} onClick={() => setKeys(keys.filter((_, j) => j !== i))}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" disabled={keys.length >= 4}
              onClick={() => setKeys([...keys, { column: "", descending: false }])}>
              <Plus aria-hidden="true" />
              Add key
            </Button>
          </div>
        </ConfigTableRow>
      )}
    </>
  );
}

/** Naming, shape, value types and nesting of the response (spec §9.2). */
export function OutputRows({ def, setDef }: { def: Def; setDef: SetDef }) {
  const out = def.output;
  const set = (patch: Partial<Def["output"]>) => setDef({ output: { ...out, ...patch } });
  const types: [string, string][] = Object.entries(out.types ?? {}).map(([c, t]) => [c, t ?? "decimal_string"]);
  const setTypes = (entries: [string, string][]) => set({ types: Object.fromEntries(entries) });
  const nest = (out.nest ?? [])[0];
  const collections: NestCollection[] = nest?.collections ?? [];
  const setNest = (key: string | null, cols: NestCollection[]) => set({ nest: key === null ? [] : [{ key, collections: cols }] });

  return (
    <>
      <ConfigTableGroup label="Output" />
      <ConfigTableRow label="Key names" htmlFor="ep-naming" details="camelCase turns created_at into createdAt">
        <Select value={out.naming} onValueChange={(v) => set({ naming: v as Def["output"]["naming"] })}>
          <SelectTrigger id="ep-naming" className="w-40"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="as_is">As in SQL</SelectItem>
            <SelectItem value="camelCase">camelCase</SelectItem>
          </SelectContent>
        </Select>
      </ConfigTableRow>
      <ConfigTableRow label="Shape" htmlFor="ep-shape"
        details="List returns every row. Single returns the first row as an object, 404 when there is none. Scalar returns the first column of the first row.">
        <Select value={out.shape} onValueChange={(v) => set({ shape: v as Def["output"]["shape"] })}>
          <SelectTrigger id="ep-shape" className="w-40"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="list">List</SelectItem>
            <SelectItem value="single">Single object</SelectItem>
            <SelectItem value="scalar">Scalar value</SelectItem>
          </SelectContent>
        </Select>
      </ConfigTableRow>
      <ConfigTableRow label="Value types" details="Convert a column's values, e.g. money as decimal text so JavaScript keeps every digit">
        <div className="space-y-2">
          {types.map(([column, type], i) => (
            <div key={i} className="flex items-center gap-2">
              <Input aria-label={`Typed column ${i + 1}`} className="w-40 font-mono text-xs" value={column} placeholder="amount"
                onChange={(e) => setTypes(types.map(([c, t], j) => (j === i ? [e.target.value, t] : [c, t])))} />
              <Select value={type} onValueChange={(v) => setTypes(types.map(([c, t], j) => (j === i ? [c, v] : [c, t])))}>
                <SelectTrigger className="w-44" aria-label={`Typed column ${i + 1} type`}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {(Object.keys(VALUE_TYPES) as ValueType[]).map((t) => <SelectItem key={t} value={t}>{VALUE_TYPES[t]}</SelectItem>)}
                </SelectContent>
              </Select>
              <Button type="button" variant="ghost" size="icon" aria-label={`Remove typed column ${i + 1}`}
                onClick={() => setTypes(types.filter((_, j) => j !== i))}>
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
          <Button type="button" variant="outline" size="sm" onClick={() => setTypes([...types, ["", "decimal_string"]])}>
            <Plus aria-hidden="true" />
            Add type
          </Button>
        </div>
      </ConfigTableRow>
      <ConfigTableRow label="Nesting"
        details="Group flat join rows by a key column; columns with a prefix become an array of child objects. Not with pagination or streaming.">
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Label htmlFor="ep-nest-key" className="w-24 text-sm">Group by</Label>
            <Input id="ep-nest-key" className="w-40 font-mono text-xs" value={nest?.key ?? ""} placeholder="id (empty = off)"
              onChange={(e) => setNest(e.target.value === "" ? null : e.target.value, collections)} />
          </div>
          {nest && collections.map((c, i) => (
            <div key={i} className="flex items-center gap-2">
              <Input aria-label={`Collection ${i + 1} name`} className="w-24 text-xs" value={c.as} placeholder="orders"
                onChange={(e) => setNest(nest.key, collections.map((x, j) => (j === i ? { ...x, as: e.target.value } : x)))} />
              <Input aria-label={`Collection ${i + 1} prefix`} className="w-24 font-mono text-xs" value={c.prefix} placeholder="order_"
                onChange={(e) => setNest(nest.key, collections.map((x, j) => (j === i ? { ...x, prefix: e.target.value } : x)))} />
              <Input aria-label={`Collection ${i + 1} key`} className="w-28 font-mono text-xs" value={c.key} placeholder="order_id"
                onChange={(e) => setNest(nest.key, collections.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
              <Button type="button" variant="ghost" size="icon" aria-label={`Remove collection ${i + 1}`}
                onClick={() => setNest(nest.key, collections.filter((_, j) => j !== i))}>
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
          {nest && (
            <Button type="button" variant="outline" size="sm" onClick={() => setNest(nest.key, [...collections, { as: "", prefix: "", key: "" }])}>
              <Plus aria-hidden="true" />
              Add collection
            </Button>
          )}
        </div>
      </ConfigTableRow>
    </>
  );
}
