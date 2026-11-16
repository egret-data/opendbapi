import { useListGroups } from "@/api/generated/groups/groups";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { METHODS, type DraftForm, type Method } from "./endpointForm";

const NONE = "__none__";
const int = (s: string, fallback: number) => (Number.isFinite(Number.parseInt(s, 10)) ? Number.parseInt(s, 10) : fallback);

/** Everything about the endpoint as an HTTP object (spec §17.4), one settings table. */
export function SettingsTab({ ws, form, onChange, live, disabled }: {
  ws: string; form: DraftForm; onChange: (f: DraftForm) => void; live: boolean; disabled: boolean;
}) {
  const groups = useListGroups(ws);
  const def = form.definition;
  const setDef = (patch: Partial<DraftForm["definition"]>) => onChange({ ...form, definition: { ...def, ...patch } });
  const routeLocked = live ? "Fixed while live: callers depend on it. Disable the endpoint to change it." : undefined;

  return (
    <fieldset disabled={disabled} className="min-w-0">
      <ConfigTable valueClassName="w-96" detailsClassName="max-w-prose">
        <ConfigTableGroup label="Endpoint" />
        <ConfigTableRow label="Name" htmlFor="ep-name" details="How it is listed">
          <Input id="ep-name" value={form.name} maxLength={120} onChange={(e) => onChange({ ...form, name: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label="Description" htmlFor="ep-desc" details="Shown in the generated API documentation">
          <Textarea id="ep-desc" rows={2} value={form.description} onChange={(e) => onChange({ ...form, description: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label="Method" htmlFor="ep-method" details={routeLocked ?? "GET must not change data"}>
          <Select value={form.method} disabled={disabled || live} onValueChange={(v) => onChange({ ...form, method: v as Method })}>
            <SelectTrigger id="ep-method" className="w-32"><SelectValue /></SelectTrigger>
            <SelectContent>{METHODS.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}</SelectContent>
          </Select>
        </ConfigTableRow>
        <ConfigTableRow label="Path" htmlFor="ep-path" details={routeLocked ?? "{name} segments become path parameters"}>
          <Input id="ep-path" className="font-mono text-xs" value={form.path} disabled={disabled || live}
            onChange={(e) => onChange({ ...form, path: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label="Access" details="Takes effect when published">
          <RadioGroup value={form.access} onValueChange={(v) => onChange({ ...form, access: v as "private" | "public" })}
            className="flex gap-4" aria-label="Access" disabled={disabled}>
            <div className="flex items-center gap-2"><RadioGroupItem id="set-private" value="private" /><Label htmlFor="set-private">Private</Label></div>
            <div className="flex items-center gap-2"><RadioGroupItem id="set-public" value="public" /><Label htmlFor="set-public">Public</Label></div>
          </RadioGroup>
        </ConfigTableRow>
        <ConfigTableRow label="Group" htmlFor="ep-group" details="A folder for the list; not part of the URL">
          <Select value={form.groupId ?? NONE} onValueChange={(v) => onChange({ ...form, groupId: v === NONE ? null : v })}>
            <SelectTrigger id="ep-group" className="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>No group</SelectItem>
              {(groups.data ?? []).map((g) => <SelectItem key={g.id} value={g.id}>{g.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </ConfigTableRow>

        <ConfigTableGroup label="Pagination" />
        <ConfigTableRow label="Paginate" htmlFor="ep-page" details="Callers send page and size; the engine adds LIMIT/OFFSET for the database">
          <Switch id="ep-page" checked={def.pagination.enabled} onCheckedChange={(v) => setDef({ pagination: { ...def.pagination, enabled: v } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Default page size" htmlFor="ep-size">
          <Input id="ep-size" type="number" min={1} className="w-32 tabular-nums" value={def.pagination.defaultSize} disabled={disabled || !def.pagination.enabled}
            onChange={(e) => setDef({ pagination: { ...def.pagination, defaultSize: int(e.target.value, 20) } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Largest page size" htmlFor="ep-max">
          <Input id="ep-max" type="number" min={1} className="w-32 tabular-nums" value={def.pagination.maxSize} disabled={disabled || !def.pagination.enabled}
            onChange={(e) => setDef({ pagination: { ...def.pagination, maxSize: int(e.target.value, 500) } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Count total" htmlFor="ep-count" details="Adds a COUNT query per call; off returns only hasNext">
          <Switch id="ep-count" checked={def.pagination.count} disabled={disabled || !def.pagination.enabled}
            onCheckedChange={(v) => setDef({ pagination: { ...def.pagination, count: v } })} />
        </ConfigTableRow>

        <ConfigTableGroup label="Streaming" />
        <ConfigTableRow label="Allow streaming" htmlFor="ep-stream"
          details="Callers ask with ?format=ndjson|csv or an Accept header. Streams skip pagination and are written row by row.">
          <Switch id="ep-stream" checked={def.streaming.allowed}
            onCheckedChange={(v) => setDef({ streaming: { ...def.streaming, allowed: v } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Formats" details="At least one">
          <div className="flex gap-4">
            {(["ndjson", "csv"] as const).map((f) => (
              <div key={f} className="flex items-center gap-2">
                <Checkbox id={`ep-fmt-${f}`} checked={def.streaming.formats.includes(f)} disabled={disabled || !def.streaming.allowed}
                  onCheckedChange={(v) => {
                    const next = v === true ? [...def.streaming.formats, f] : def.streaming.formats.filter((x) => x !== f);
                    if (next.length > 0) setDef({ streaming: { ...def.streaming, formats: next } });
                  }} />
                <Label htmlFor={`ep-fmt-${f}`} className="font-mono text-xs">{f}</Label>
              </div>
            ))}
          </div>
        </ConfigTableRow>
        <ConfigTableRow label="Most rows streamed" htmlFor="ep-stream-rows" details="Empty = same as 'Most rows returned'. Up to 10,000,000.">
          <Input id="ep-stream-rows" type="number" min={1} max={10000000} className="w-36 tabular-nums" disabled={disabled || !def.streaming.allowed}
            value={def.limits.streamMaxRows ?? ""}
            onChange={(e) => setDef({ limits: { ...def.limits, streamMaxRows: e.target.value === "" ? null : int(e.target.value, 100000) } })} />
        </ConfigTableRow>

        <ConfigTableGroup label="Limits" />
        <ConfigTableRow label="Timeout" htmlFor="ep-timeout" unit="seconds" details="The query is cancelled after this">
          <Input id="ep-timeout" type="number" min={1} max={300} className="w-32 tabular-nums" value={def.limits.timeoutSeconds}
            onChange={(e) => setDef({ limits: { ...def.limits, timeoutSeconds: int(e.target.value, 30) } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Most rows returned" htmlFor="ep-rows" details="More rows are cut off and meta.truncated is set">
          <Input id="ep-rows" type="number" min={1} max={100000} className="w-32 tabular-nums" value={def.limits.maxRows}
            onChange={(e) => setDef({ limits: { ...def.limits, maxRows: int(e.target.value, 10000) } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Rate limit" htmlFor="ep-rate" unit="calls / minute"
          details="Across all callers of this endpoint, per server instance. Empty = no limit. Takes effect when published.">
          <Input id="ep-rate" type="number" min={1} className="w-32 tabular-nums" value={def.limits.ratePerMinute ?? ""}
            onChange={(e) => setDef({ limits: { ...def.limits, ratePerMinute: e.target.value === "" ? null : int(e.target.value, 60) } })} />
        </ConfigTableRow>
        <ConfigTableRow label="Transaction" htmlFor="ep-tx" details="Run all statements in one transaction; any failure rolls them all back">
          <Switch id="ep-tx" checked={def.task.transaction} onCheckedChange={(v) => setDef({ task: { ...def.task, transaction: v } })} />
        </ConfigTableRow>
      </ConfigTable>
    </fieldset>
  );
}
