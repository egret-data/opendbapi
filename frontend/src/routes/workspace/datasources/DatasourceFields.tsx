import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useListDialects, useTestDatasourceSettings } from "@/api/generated/datasources/datasources";
import type { DatasourceTestResult } from "@/api/generated/model";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/errors";
import { DATABASE_LABEL, DIALECT_LABEL, toRequest, type DatasourceForm, type Dialect } from "./datasourceForm";

/**
 * The datasource settings as one `Setting | Details | Value` table (FE-PAGE-5). `disabled`
 * renders it read-only for members who may look but not change.
 */
export function DatasourceFields({
  ws,
  form,
  onChange,
  disabled = false,
  hasPassword = false,
  passwordEnv,
  passwordSecret,
}: {
  ws: string;
  form: DatasourceForm;
  onChange: (next: DatasourceForm) => void;
  disabled?: boolean;
  hasPassword?: boolean;
  passwordEnv?: string | null;
  passwordSecret?: string | null;
}) {
  const { t } = useTranslation();
  const dialects = useListDialects(ws);
  const info = dialects.data?.find((d) => d.dialect === form.dialect);
  const allowed = info?.allowedProperties ?? [];
  const set = (patch: Partial<DatasourceForm>) => onChange({ ...form, ...patch });
  const setProperty = (i: number, patch: Partial<DatasourceForm["properties"][number]>) =>
    set({ properties: form.properties.map((p, j) => (j === i ? { ...p, ...patch } : p)) });
  const passwordHelp = hasPassword
    ? passwordSecret
      ? t("Read from the managed secret {{name}}. Leave empty to keep it.", { name: passwordSecret })
      : passwordEnv
        ? t("Stored as {{env}}. Leave empty to keep it.", { env: passwordEnv })
        : t("Stored encrypted. Leave empty to keep it.")
    : t("Saved encrypted. Or type secret:NAME for a managed secret, or env:NAME to read it from the server's environment.");

  return (
    <fieldset disabled={disabled} className="min-w-0">
      <ConfigTable valueClassName="w-96" detailsClassName="max-w-prose">
        <ConfigTableGroup label={t("Datasource")} />
        <ConfigTableRow label={t("Name")} htmlFor="ds-name" details={t("How endpoints and people refer to it; unique in the workspace")}>
          <Input id="ds-name" value={form.name} required maxLength={120} onChange={(e) => set({ name: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Description")} htmlFor="ds-description" details={t("Optional")}>
          <Textarea id="ds-description" rows={2} value={form.description} maxLength={1000}
            onChange={(e) => set({ description: e.target.value })} />
        </ConfigTableRow>

        <ConfigTableGroup label={t("Connection")} />
        <ConfigTableRow label={t("Database type")} htmlFor="ds-dialect" details={t("Drivers for these are built in")}>
          <Select value={form.dialect} onValueChange={(v) => set({ dialect: v as Dialect, properties: [] })} disabled={disabled}>
            <SelectTrigger id="ds-dialect" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(Object.keys(DIALECT_LABEL) as Dialect[]).map((d) => (
                <SelectItem key={d} value={d}>
                  {DIALECT_LABEL[d]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </ConfigTableRow>
        <ConfigTableRow label={t("Host")} htmlFor="ds-host" details={t("Host name or IP address, without port")}>
          <Input id="ds-host" className="font-mono text-xs" value={form.host} required placeholder="db.corp.local"
            onChange={(e) => set({ host: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Port")} htmlFor="ds-port"
          details={info ? t("Leave empty for the default ({{port}})", { port: info.defaultPort }) : t("Leave empty for the default")}>
          <Input id="ds-port" type="number" min={1} max={65535} className="w-32 tabular-nums" value={form.port}
            placeholder={info ? String(info.defaultPort) : undefined} onChange={(e) => set({ port: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t(DATABASE_LABEL[form.dialect])} htmlFor="ds-database"
          details={form.dialect === "ORACLE" ? t("The service name, e.g. ORCLPDB1") : t("The database to connect to")}>
          <Input id="ds-database" className="font-mono text-xs" value={form.database} required
            onChange={(e) => set({ database: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Driver properties")}
          details={allowed.length ? t("Only properties that are safe to set are offered") : t("None can be set for this database")}>
          <div className="space-y-2">
            {form.properties.map((p, i) => (
              <div key={i} className="flex items-center gap-2">
                <Select value={p.key} onValueChange={(v) => setProperty(i, { key: v })} disabled={disabled}>
                  <SelectTrigger className="w-44" aria-label={t("Property {{number}} name", { number: i + 1 })}>
                    <SelectValue placeholder={t("Property")} />
                  </SelectTrigger>
                  <SelectContent>
                    {allowed.map((k) => (
                      <SelectItem key={k} value={k}>
                        {k}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Input aria-label={t("Property {{number}} value", { number: i + 1 })} className="font-mono text-xs" value={p.value}
                  onChange={(e) => setProperty(i, { value: e.target.value })} />
                <Button type="button" variant="ghost" size="icon" aria-label={t("Remove property {{number}}", { number: i + 1 })}
                  onClick={() => set({ properties: form.properties.filter((_, j) => j !== i) })}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            {allowed.length > 0 && !disabled && (
              <Button type="button" variant="outline" size="sm"
                onClick={() => set({ properties: [...form.properties, { key: "", value: "" }] })}>
                <Plus aria-hidden="true" />
                {t("Add property")}
              </Button>
            )}
          </div>
        </ConfigTableRow>

        <ConfigTableGroup label={t("Credentials")} />
        <ConfigTableRow label={t("Username")} htmlFor="ds-username" details={t("Prefer an account that can only read what endpoints need")}>
          <Input id="ds-username" value={form.username} required autoComplete="off" onChange={(e) => set({ username: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Password")} htmlFor="ds-password" details={passwordHelp}>
          <Input id="ds-password" type="password" autoComplete="new-password" value={form.password}
            placeholder={hasPassword ? "••••••••" : undefined} required={!hasPassword}
            onChange={(e) => set({ password: e.target.value })} />
        </ConfigTableRow>

        <ConfigTableGroup label={t("Pool")} />
        <ConfigTableRow label={t("Maximum connections")} htmlFor="ds-max" details={t("Per server instance, 1 to 50")}>
          <Input id="ds-max" type="number" min={1} max={50} className="w-32 tabular-nums" value={form.maximumPoolSize}
            onChange={(e) => set({ maximumPoolSize: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Idle connections kept")} htmlFor="ds-min" details={t("0 opens connections only when needed")}>
          <Input id="ds-min" type="number" min={0} max={50} className="w-32 tabular-nums" value={form.minimumIdle}
            onChange={(e) => set({ minimumIdle: e.target.value })} />
        </ConfigTableRow>
        <ConfigTableRow label={t("Connection timeout")} htmlFor="ds-timeout" unit={t("milliseconds")}
          details={t("How long a request waits for a free connection")}>
          <Input id="ds-timeout" type="number" min={250} max={60000} className="w-32 tabular-nums" value={form.connectionTimeoutMs}
            onChange={(e) => set({ connectionTimeoutMs: e.target.value })} />
        </ConfigTableRow>

        <ConfigTableGroup label={t("Safety")} />
        <ConfigTableRow label={t("Read only")} htmlFor="ds-readonly"
          details={t("Endpoints cannot publish SQL that writes, and PostgreSQL, MySQL and MariaDB sessions are opened read-only. A read-only database account is still the stronger guarantee, and the only one for SQL Server and Oracle.")}>
          <Switch id="ds-readonly" checked={form.readOnly} onCheckedChange={(v) => set({ readOnly: v })} />
        </ConfigTableRow>
      </ConfigTable>
    </fieldset>
  );
}

/** Tries the settings on screen (saved password reused when the field is empty). */
export function TestConnection({ ws, form, existingId }: { ws: string; form: DatasourceForm; existingId?: string }) {
  const { t } = useTranslation();
  const test = useTestDatasourceSettings();
  const [result, setResult] = useState<DatasourceTestResult | null>(null);
  return (
    <div className="space-y-2">
      <Button
        type="button"
        variant="outline"
        disabled={test.isPending}
        onClick={() => {
          setResult(null);
          test.mutate(
            { ws, data: { settings: toRequest(form), existingId: existingId ?? null } },
            {
              onSuccess: setResult,
              onError: (err) => setResult({ ok: false, message: errorMessage(err), product: null, version: null, latencyMs: null }),
            },
          );
        }}
      >
        {test.isPending ? t("Testing…") : t("Test connection")}
      </Button>
      {result && (
        <Alert variant={result.ok ? "default" : "destructive"}>
          <AlertDescription className="break-words">
            <span className="font-medium">{result.ok ? t("Connected.") : t("Could not connect.")} </span>
            {result.ok ? result.message : result.message.replace(/^Could not connect: /, "")}
          </AlertDescription>
        </Alert>
      )}
    </div>
  );
}
