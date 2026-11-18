import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { SECRET_LABEL, type DatasourceForm, type HttpAuth } from "./datasourceForm";

const AUTH_LABEL: Record<HttpAuth, string> = {
  none: "None",
  bearer: "Bearer token",
  basic: "Basic (username and password)",
  header: "API key in a header",
};

/** The connection rows of an HTTP API datasource (spec §4.6). */
export function HttpSourceFields({ form, set, disabled, hasPassword, passwordHelp }: {
  form: DatasourceForm;
  set: (patch: Partial<DatasourceForm>) => void;
  disabled: boolean;
  hasPassword: boolean;
  passwordHelp: string;
}) {
  const { t } = useTranslation();
  const setHeader = (i: number, patch: Partial<DatasourceForm["headers"][number]>) =>
    set({ headers: form.headers.map((h, j) => (j === i ? { ...h, ...patch } : h)) });
  return (
    <>
      <ConfigTableGroup label={t("Connection")} />
      <ConfigTableRow label={t("Base URL")} htmlFor="ds-base-url"
        details={t("Every request goes to this URL plus the endpoint's path. Redirects are not followed.")}>
        <Input id="ds-base-url" className="font-mono text-xs" value={form.baseUrl} required placeholder="https://api.example.com/v1"
          onChange={(e) => set({ baseUrl: e.target.value })} />
      </ConfigTableRow>
      <ConfigTableRow label={t("Headers")}
        details={t("Sent with every request. Not secret: put credentials under Authentication.")}>
        <div className="space-y-2">
          {form.headers.map((h, i) => (
            <div key={i} className="flex items-center gap-2">
              <Input aria-label={t("Header {{number}} name", { number: i + 1 })} className="w-44 font-mono text-xs" value={h.key}
                placeholder="X-Tenant" onChange={(e) => setHeader(i, { key: e.target.value })} />
              <Input aria-label={t("Header {{number}} value", { number: i + 1 })} className="font-mono text-xs" value={h.value}
                onChange={(e) => setHeader(i, { value: e.target.value })} />
              <Button type="button" variant="ghost" size="icon" aria-label={t("Remove header {{number}}", { number: i + 1 })}
                onClick={() => set({ headers: form.headers.filter((_, j) => j !== i) })}>
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
          {!disabled && (
            <Button type="button" variant="outline" size="sm" onClick={() => set({ headers: [...form.headers, { key: "", value: "" }] })}>
              <Plus aria-hidden="true" />
              {t("Add header")}
            </Button>
          )}
        </div>
      </ConfigTableRow>
      <ConfigTableRow label={t("Connection timeout")} htmlFor="ds-timeout" unit={t("milliseconds")}
        details={t("How long to wait for a connection; the endpoint's timeout limits the whole request")}>
        <Input id="ds-timeout" type="number" min={250} max={60000} className="w-32 tabular-nums" value={form.connectionTimeoutMs}
          onChange={(e) => set({ connectionTimeoutMs: e.target.value })} />
      </ConfigTableRow>

      <ConfigTableGroup label={t("Authentication")} />
      <ConfigTableRow label={t("Authentication")} htmlFor="ds-auth" details={t("How OpenDBAPI proves itself to the API")}>
        <Select value={form.auth} onValueChange={(v) => set({ auth: v as HttpAuth })} disabled={disabled}>
          <SelectTrigger id="ds-auth" className="w-full"><SelectValue /></SelectTrigger>
          <SelectContent>
            {(Object.keys(AUTH_LABEL) as HttpAuth[]).map((a) => <SelectItem key={a} value={a}>{t(AUTH_LABEL[a])}</SelectItem>)}
          </SelectContent>
        </Select>
      </ConfigTableRow>
      {form.auth === "basic" && (
        <ConfigTableRow label={t("Username")} htmlFor="ds-http-username" details={t("Sent with the password as Basic authentication")}>
          <Input id="ds-http-username" value={form.httpUsername} required autoComplete="off"
            onChange={(e) => set({ httpUsername: e.target.value })} />
        </ConfigTableRow>
      )}
      {form.auth === "header" && (
        <ConfigTableRow label={t("Header name")} htmlFor="ds-header-name" details={t("The header that carries the API key")}>
          <Input id="ds-header-name" className="font-mono text-xs" value={form.headerName} required
            onChange={(e) => set({ headerName: e.target.value })} />
        </ConfigTableRow>
      )}
      {form.auth !== "none" && (
        <ConfigTableRow label={t(SECRET_LABEL[form.auth])} htmlFor="ds-password" details={passwordHelp}>
          <Input id="ds-password" type="password" autoComplete="new-password" value={form.password}
            placeholder={hasPassword ? "••••••••" : undefined} required={!hasPassword}
            onChange={(e) => set({ password: e.target.value })} />
        </ConfigTableRow>
      )}
    </>
  );
}
