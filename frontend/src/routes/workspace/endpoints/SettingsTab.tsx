import { Link } from "react-router-dom";
import { Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { useClearEndpointCache } from "@/api/generated/endpoints/endpoints";
import { useListGroups } from "@/api/generated/groups/groups";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/errors";
import { METHODS, type DraftForm, type Method } from "./endpointForm";
import { OutputRows, PaginationModeRows } from "./OutputSettings";

const NONE = "__none__";
const int = (s: string, fallback: number) => (Number.isFinite(Number.parseInt(s, 10)) ? Number.parseInt(s, 10) : fallback);

/** Everything about the endpoint as an HTTP object (spec §17.4), one settings table. */
export function SettingsTab({ ws, endpointId, form, onChange, live, disabled }: {
  ws: string; endpointId?: string; form: DraftForm; onChange: (f: DraftForm) => void; live: boolean; disabled: boolean;
}) {
  const groups = useListGroups(ws);
  const { t } = useTranslation();
  const clear = useClearEndpointCache({ mutation: { onError: (e) => toast.error(errorMessage(e)) } });
  const def = form.definition;
  const setDef = (patch: Partial<DraftForm["definition"]>) => onChange({ ...form, definition: { ...def, ...patch } });
  const routeLocked = live ? t("Fixed while live: callers depend on it. Disable the endpoint to change it.") : undefined;

  return (
    <fieldset disabled={disabled} className="min-w-0">
      <ConfigTable valueClassName="w-96" detailsClassName="max-w-prose">
        <ConfigTableGroup label={t("Endpoint")} />
        <ConfigTableRow label={t("Name")} htmlFor="ep-name" details={t("How it is listed")}>
          <Input id="ep-name" value={form.name} maxLength={120} onChange={(e) => onChange({ ...form, name: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Description")} htmlFor="ep-desc" details={t("Shown in the generated API documentation")}>
          <Textarea id="ep-desc" rows={2} value={form.description} onChange={(e) => onChange({ ...form, description: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Method")} htmlFor="ep-method" details={routeLocked ?? t("GET must not change data")}>
          <Select value={form.method} disabled={disabled || live} onValueChange={(v) => onChange({ ...form, method: v as Method })}>
            <SelectTrigger id="ep-method" className="w-32"><SelectValue /></SelectTrigger>
            <SelectContent>{METHODS.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}</SelectContent>
          </Select>
        </ConfigTableRow>
        <ConfigTableRow label={t("Path")} htmlFor="ep-path" details={routeLocked ?? t("{name} segments become path parameters")}>
          <Input id="ep-path" className="font-mono text-xs" value={form.path} disabled={disabled || live}
            onChange={(e) => onChange({ ...form, path: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Access")} details={t("Takes effect when published")}>
          <RadioGroup value={form.access} onValueChange={(v) => onChange({ ...form, access: v as "private" | "public" })}
            className="flex gap-4" aria-label={t("Access")} disabled={disabled}>
            <div className="flex items-center gap-2"><RadioGroupItem id="set-private" value="private" /><Label htmlFor="set-private">{t("Private")}</Label></div>
            <div className="flex items-center gap-2"><RadioGroupItem id="set-public" value="public" /><Label htmlFor="set-public">{t("Public")}</Label></div>
          </RadioGroup>
        </ConfigTableRow>
        <ConfigTableRow label={t("Group")} htmlFor="ep-group" details={t("A folder for the list; not part of the URL")}>
          <Select value={form.groupId ?? NONE} onValueChange={(v) => onChange({ ...form, groupId: v === NONE ? null : v })}>
            <SelectTrigger id="ep-group" className="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>{t("No group")}</SelectItem>
              {(groups.data ?? []).map((g) => <SelectItem key={g.id} value={g.id}>{g.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </ConfigTableRow>

        <ConfigTableGroup label={t("Pagination")} />
        <ConfigTableRow label={t("Paginate")} htmlFor="ep-page" details={t("Callers ask for one page at a time; the engine adds the paging clause for the database")}>
          <Switch id="ep-page" checked={def.pagination.enabled} onCheckedChange={(v) => setDef({ pagination: { ...def.pagination, enabled: v } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Default page size")} htmlFor="ep-size">
          <Input id="ep-size" type="number" min={1} className="w-32 tabular-nums" value={def.pagination.defaultSize} disabled={disabled || !def.pagination.enabled}
            onChange={(e) => setDef({ pagination: { ...def.pagination, defaultSize: int(e.target.value, 20) } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Largest page size")} htmlFor="ep-max">
          <Input id="ep-max" type="number" min={1} className="w-32 tabular-nums" value={def.pagination.maxSize} disabled={disabled || !def.pagination.enabled}
            onChange={(e) => setDef({ pagination: { ...def.pagination, maxSize: int(e.target.value, 500) } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Count total")} htmlFor="ep-count" details={t("Offset mode: adds a COUNT query per call; off returns only hasNext")}>
          <Switch id="ep-count" checked={def.pagination.count} disabled={disabled || !def.pagination.enabled || def.pagination.mode === "keyset"}
            onCheckedChange={(v) => setDef({ pagination: { ...def.pagination, count: v } })} />
        </ConfigTableRow>
        <PaginationModeRows def={def} setDef={setDef} disabled={disabled} />

        <OutputRows def={def} setDef={setDef} />

        <ConfigTableGroup label={t("Cache")} />
        <ConfigTableRow label={t("Cache answers")} htmlFor="ep-cache"
          details={t("Keep GET answers in memory on each server for a while. Takes effect when published; Try-it is never cached.")}>
          <div className="flex items-center gap-3">
            <Switch id="ep-cache" checked={def.cache.enabled} onCheckedChange={(v) => setDef({ cache: { ...def.cache, enabled: v } })} />
            {endpointId && (
              <Button type="button" variant="outline" size="sm" disabled={clear.isPending}
                onClick={() => clear.mutate({ ws, endpointId }, { onSuccess: () => toast.success(t("Cached answers cleared on this server.")) })}>
                {t("Clear cache")}
              </Button>
            )}
          </div>
        </ConfigTableRow>
        <ConfigTableRow label={t("Keep for")} htmlFor="ep-cache-ttl" unit={t("seconds")} details={t("Up to 86,400 (one day)")}>
          <Input id="ep-cache-ttl" type="number" min={1} max={86400} className="w-32 tabular-nums" value={def.cache.ttlSeconds}
            disabled={disabled || !def.cache.enabled}
            onChange={(e) => setDef({ cache: { ...def.cache, ttlSeconds: int(e.target.value, 60) } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Shared between callers")} htmlFor="ep-cache-scope"
          details={t("Off keeps one entry per service account, which data policies require. On serves one entry to every caller.")}>
          <Switch id="ep-cache-scope" checked={def.cache.scope === "shared"} disabled={disabled || !def.cache.enabled}
            onCheckedChange={(v) => setDef({ cache: { ...def.cache, scope: v ? "shared" : "client" } })} />
        </ConfigTableRow>

        <ConfigTableGroup label={t("Streaming")} />
        <ConfigTableRow label={t("Allow streaming")} htmlFor="ep-stream"
          details={t("Callers ask with ?format=ndjson|csv or an Accept header. Streams skip pagination and are written row by row.")}>
          <Switch id="ep-stream" checked={def.streaming.allowed}
            onCheckedChange={(v) => setDef({ streaming: { ...def.streaming, allowed: v } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Formats")} details={t("At least one")}>
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
        <ConfigTableRow label={t("Most rows streamed")} htmlFor="ep-stream-rows" details={t("Empty = same as 'Most rows returned'. Up to 10,000,000.")}>
          <Input id="ep-stream-rows" type="number" min={1} max={10000000} className="w-36 tabular-nums" disabled={disabled || !def.streaming.allowed}
            value={def.limits.streamMaxRows ?? ""}
            onChange={(e) => setDef({ limits: { ...def.limits, streamMaxRows: e.target.value === "" ? null : int(e.target.value, 100000) } })} />
        </ConfigTableRow>

        <ConfigTableGroup label={t("Limits")} />
        <ConfigTableRow label={t("Timeout")} htmlFor="ep-timeout" unit={t("seconds")} details={t("The query is cancelled after this")}>
          <Input id="ep-timeout" type="number" min={1} max={300} className="w-32 tabular-nums" value={def.limits.timeoutSeconds}
            onChange={(e) => setDef({ limits: { ...def.limits, timeoutSeconds: int(e.target.value, 30) } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Most rows returned")} htmlFor="ep-rows" details={t("More rows are cut off and meta.truncated is set")}>
          <Input id="ep-rows" type="number" min={1} max={100000} className="w-32 tabular-nums" value={def.limits.maxRows}
            onChange={(e) => setDef({ limits: { ...def.limits, maxRows: int(e.target.value, 10000) } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Rate limit")} htmlFor="ep-rate" unit={t("calls / minute")}
          details={t("Across all callers of this endpoint, per server instance. Empty = no limit. Takes effect when published.")}>
          <Input id="ep-rate" type="number" min={1} className="w-32 tabular-nums" value={def.limits.ratePerMinute ?? ""}
            onChange={(e) => setDef({ limits: { ...def.limits, ratePerMinute: e.target.value === "" ? null : int(e.target.value, 60) } })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Transaction")} htmlFor="ep-tx" details={t("Run all statements in one transaction; any failure rolls them all back")}>
          <Switch id="ep-tx" checked={def.task.transaction} onCheckedChange={(v) => setDef({ task: { ...def.task, transaction: v } })} />
        </ConfigTableRow>

        <ConfigTableGroup label={t("Parameter rules")} />
        <ConfigTableRow label={t("Rules")}
          details={t("Checked before the query, in the same language as <if test>, for example: to == null or to >= from. A false rule answers 400 with its message.")}>
          <div className="space-y-2">
            {def.rules.map((r, i) => (
              <div key={i} className="space-y-1 rounded-md border p-2">
                <Input aria-label={t("Rule {{n}} condition", { n: i + 1 })} className="font-mono text-xs" value={r.test} placeholder="to >= from"
                  onChange={(e) => setDef({ rules: def.rules.map((x, k) => (k === i ? { ...x, test: e.target.value } : x)) })} />
                <div className="flex gap-2">
                  <Input aria-label={t("Rule {{n}} message", { n: i + 1 })} value={r.message} placeholder={t("Message for callers")}
                    onChange={(e) => setDef({ rules: def.rules.map((x, k) => (k === i ? { ...x, message: e.target.value } : x)) })} />
                  <Button type="button" variant="ghost" size="icon" aria-label={t("Remove rule {{n}}", { n: i + 1 })}
                    onClick={() => setDef({ rules: def.rules.filter((_, k) => k !== i) })}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" disabled={def.rules.length >= 20}
              onClick={() => setDef({ rules: [...def.rules, { test: "", message: "" }] })}>
              <Plus aria-hidden="true" />
              {t("Add rule")}
            </Button>
          </div>
        </ConfigTableRow>

        <ConfigTableGroup label={t("Alerts")} />
        <ConfigTableRow label={t("Alert on failures")} htmlFor="ep-alerts"
          details={t("Send server errors (5xx) to the workspace's alert channels, at most once per error every few minutes. Takes effect when published.")}>
          <div className="flex items-center gap-3">
            <Switch id="ep-alerts" checked={def.alerts.enabled} onCheckedChange={(v) => setDef({ alerts: { enabled: v } })} />
            <Link className="text-sm underline" to={`/w/${ws}/settings/alerts`}>{t("Alert channels")}</Link>
          </div>
        </ConfigTableRow>
      </ConfigTable>
    </fieldset>
  );
}
