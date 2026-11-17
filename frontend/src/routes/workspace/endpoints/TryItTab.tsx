import { useState } from "react";
import { Copy, Send } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { useListEndpointVersions, useTryEndpoint } from "@/api/generated/endpoints/endpoints";
import { useListServiceAccounts } from "@/api/generated/service-accounts/service-accounts";
import type { EndpointDefinition, EndpointSummary, TryResponse } from "@/api/generated/model";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { errorMessage } from "@/lib/errors";

const ANONYMOUS = "__none__";

/**
 * Calls the live version through the real serving pipeline (spec §11.2). The server mints a
 * 60-second token; it never reaches the browser, so the request shows `Bearer ••••`.
 */
export function TryItTab({ ws, summary, onOpenAccess }: { ws: string; summary: EndpointSummary; onOpenAccess: () => void }) {
  const versions = useListEndpointVersions(ws, summary.id);
  const { t } = useTranslation();
  if (summary.liveVersion == null) {
    return <EmptyState title={t("Not published yet")} description={t("Try-it calls the live version. Publish the endpoint first, or use Preview on the Query tab.")} />;
  }
  return (
    <QueryBoundary query={versions} what={t("the live version")}>
      {(all) => {
        const live = all.find((v) => v.version === summary.liveVersion);
        return live ? <TryForm ws={ws} summary={summary} live={live.definition} onOpenAccess={onOpenAccess} /> : null;
      }}
    </QueryBoundary>
  );
}

function TryForm({ ws, summary, live, onOpenAccess }: {
  ws: string; summary: EndpointSummary; live: EndpointDefinition; onOpenAccess: () => void;
}) {
  const accounts = useListServiceAccounts(ws);
  const run = useTryEndpoint();
  const { t } = useTranslation();
  const isPrivate = summary.access !== "public";
  const [account, setAccount] = useState(ANONYMOUS);
  const [values, setValues] = useState<Record<string, string>>({});
  const [page, setPage] = useState("");
  const [size, setSize] = useState("");
  const [result, setResult] = useState<TryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const missingAccount = isPrivate && account === ANONYMOUS;

  const send = () => {
    setError(null);
    const params: Record<string, unknown> = {};
    for (const p of live.params) {
      const v = values[p.name];
      if (v === undefined || v === "") continue;
      params[p.name] = p.type.startsWith("array<") ? v.split(",").map((s) => s.trim()).filter(Boolean) : v;
    }
    run.mutate(
      { ws, id: summary.id, data: {
        serviceAccountId: account === ANONYMOUS ? null : account,
        params,
        page: page ? Number(page) : null,
        size: size ? Number(size) : null,
      } },
      { onSuccess: setResult, onError: (e) => { setResult(null); setError(errorMessage(e)); } },
    );
  };

  const notGranted = result?.status === 403 && (result.body as { code?: string } | null)?.code === "not_granted";

  return (
    <div className="space-y-4">
      <p className="max-w-prose text-sm text-muted-foreground">
        {t("Calls live version v{{version}} exactly as a client would: firewall, token, grants and rate limits all apply. The call is logged as made by you.", { version: summary.liveVersion })}
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <Label htmlFor="try-account">{t("Call as")}</Label>
          <Select value={account} onValueChange={setAccount}>
            <SelectTrigger id="try-account" className="w-60"><SelectValue /></SelectTrigger>
            <SelectContent>
              {!isPrivate && <SelectItem value={ANONYMOUS}>{t("Anonymous (no token)")}</SelectItem>}
              {isPrivate && <SelectItem value={ANONYMOUS} disabled>{t("Choose a service account")}</SelectItem>}
              {(accounts.data ?? []).map((a) => <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        {live.params.map((p) => (
          <div key={p.name} className="space-y-1">
            <Label htmlFor={`try-${p.name}`} className="font-mono text-xs">
              {p.name}{p.required ? " *" : ""} <span className="text-muted-foreground">({p.in})</span>
            </Label>
            <Input id={`try-${p.name}`} className="h-9 w-40 text-xs" placeholder={p.type.startsWith("array<") ? "a, b, c" : p.type}
              value={values[p.name] ?? ""} onChange={(e) => setValues({ ...values, [p.name]: e.target.value })} />
          </div>
        ))}
        {live.pagination.enabled && (
          <>
            <div className="space-y-1">
              <Label htmlFor="try-page" className="font-mono text-xs">page</Label>
              <Input id="try-page" type="number" min={1} className="h-9 w-20 text-xs" value={page} onChange={(e) => setPage(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="try-size" className="font-mono text-xs">size</Label>
              <Input id="try-size" type="number" min={1} className="h-9 w-20 text-xs" value={size} onChange={(e) => setSize(e.target.value)} />
            </div>
          </>
        )}
        <Button onClick={send} disabled={run.isPending || missingAccount} title={missingAccount ? t("A private endpoint needs a service account") : undefined}>
          <Send aria-hidden="true" />
          {run.isPending ? t("Sending…") : t("Send")}
        </Button>
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {result && (
        <section aria-label={t("Response")} className="space-y-3">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <StatusBadge tone={result.status < 300 ? "success" : result.status < 500 ? "highlight" : "destructive"}>{result.status}</StatusBadge>
            <span className="tabular-nums text-muted-foreground">{result.durationMs} ms</span>
            <span className="font-mono text-xs">{result.method} {result.url}</span>
          </div>
          {notGranted && (
            <p className="text-sm">
              {t("{{name}} has no grant on this endpoint.", { name: result.serviceAccountName })}{" "}
              <Button variant="link" className="h-auto p-0" onClick={onOpenAccess}>{t("Grant access")}</Button>
            </p>
          )}
          <div className="grid gap-3 lg:grid-cols-2">
            <div className="space-y-1">
              <h3 className="text-sm font-semibold">{t("Request")}</h3>
              <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs whitespace-pre-wrap">{requestText(result)}</pre>
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => navigator.clipboard?.writeText(result.curl).then(() => toast.success(t("curl copied.")))}>
                  <Copy aria-hidden="true" />
                  {t("Copy as curl")}
                </Button>
                <span className="text-xs text-muted-foreground">{t("Uses $TOKEN: get one from the token endpoint with a client of your own.")}</span>
              </div>
            </div>
            <div className="space-y-1">
              <h3 className="text-sm font-semibold">{t("Response")}</h3>
              {Object.keys(result.headers).length > 0 && (
                <pre className="overflow-x-auto rounded-md border p-2 font-mono text-xs">
                  {Object.entries(result.headers).map(([k, v]) => `${k}: ${v}`).join("\n")}
                </pre>
              )}
              <pre className="max-h-[28rem] overflow-auto rounded-md bg-muted p-3 font-mono text-xs">
                {result.body != null ? JSON.stringify(result.body, null, 2) : (result.text ?? "")}
              </pre>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}

function requestText(r: TryResponse): string {
  const lines = [`${r.method} ${r.url}`];
  if (r.curl.includes("$TOKEN")) lines.push("Authorization: Bearer ••••");
  return lines.join("\n");
}
