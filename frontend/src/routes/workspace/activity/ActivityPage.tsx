import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useActivityLog, useActivitySummary } from "@/api/generated/activity/activity";
import { useListEndpoints } from "@/api/generated/endpoints/endpoints";
import type { Day } from "@/api/generated/model";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { PageContainer } from "@/components/layout/PageContainer";
import { PageTitle } from "@/components/layout/PageTitle";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime } from "@/lib/format";

const RANGES = { "24h": 1, "7d": 7, "30d": 30 } as const;
type Range = keyof typeof RANGES;
const ANY = "__any__";

/** Calls, errors and latency from the access log (spec §17.4). Try-it calls are hidden unless asked for. */
export default function ActivityPage() {
  const { ws = "" } = useParams();
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const range: Range = (params.get("range") as Range) in RANGES ? (params.get("range") as Range) : "7d";
  const includeTryIt = params.get("tryit") === "1";
  // Fixed per range choice and refresh, so the query key does not change on every render.
  const [window, setWindow] = useState(() => windowFor(range));
  const set = (patch: Record<string, string>) => {
    if (patch.range) setWindow(windowFor(patch.range as Range));
    setParams(Object.fromEntries(Object.entries({ ...Object.fromEntries(params), ...patch }).filter(([, v]) => v)), { replace: true });
  };
  const summary = useActivitySummary(ws, { ...window, includeTryIt });

  return (
    <PageContainer>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <PageTitle>{t("Activity")}</PageTitle>
          <p className="text-sm text-muted-foreground">{t("Calls to this workspace's published endpoints.")}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Select value={range} onValueChange={(v) => set({ range: v })}>
            <SelectTrigger className="w-36" aria-label={t("Time range")}><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="24h">{t("Last 24 hours")}</SelectItem>
              <SelectItem value="7d">{t("Last 7 days")}</SelectItem>
              <SelectItem value="30d">{t("Last 30 days")}</SelectItem>
            </SelectContent>
          </Select>
          <div className="flex items-center gap-2">
            <Switch id="act-tryit" checked={includeTryIt} onCheckedChange={(v) => set({ tryit: v ? "1" : "" })} />
            <Label htmlFor="act-tryit">{t("Include Try-it")}</Label>
          </div>
          <Button variant="ghost" size="icon" aria-label={t("Refresh")} onClick={() => setWindow(windowFor(range))}>
            <RefreshCw className="h-4 w-4" />
          </Button>
        </div>
      </div>
      <div className="mt-6 space-y-8">
        <QueryBoundary query={summary} what={t("activity")}>
          {(s) =>
            s.requests === 0 ? (
              <EmptyState title={t("No calls in this period")} description={t("Calls appear here a second or two after they are made.")} />
            ) : (
              <>
                <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <Tile label={t("Requests")} value={s.requests.toLocaleString()} />
                  <Tile label={t("Error rate")} value={`${((s.errors / s.requests) * 100).toFixed(1)}%`}
                    hint={t("{{errors}} with status 4xx or 5xx", { errors: s.errors.toLocaleString() })} />
                  <Tile label={t("p95 latency")} value={s.p95Ms == null ? "—" : `${Math.round(s.p95Ms)} ms`} />
                  <Tile label={t("Active clients")} value={s.activeClients.toLocaleString()} />
                </dl>
                <DayChart days={s.byDay} />
                <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
                  <section aria-labelledby="top-endpoints" className="space-y-2">
                    <h2 id="top-endpoints" className="text-sm font-semibold">{t("Top endpoints")}</h2>
                    <div className="overflow-x-auto rounded-md border">
                      <Table>
                        <TableHeader>
                          <TableRow>
                            <TableHead>{t("Endpoint")}</TableHead>
                            <TableHead className="text-right">{t("Requests")}</TableHead>
                            <TableHead className="text-right">{t("Errors")}</TableHead>
                            <TableHead className="text-right">p95</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {s.topEndpoints.map((e, i) => (
                            <TableRow key={e.endpointId ?? `none-${i}`}>
                              <TableCell>
                                {e.endpointId ? (
                                  <Link className="font-medium hover:underline" to={`/w/${ws}/endpoints/${e.endpointId}`}>{e.name ?? t("(deleted)")}</Link>
                                ) : (
                                  <span className="text-muted-foreground">{t("No matching endpoint")}</span>
                                )}
                                <span className="ml-2 font-mono text-xs text-muted-foreground">{e.method} {e.path}</span>
                              </TableCell>
                              <TableCell className="text-right tabular-nums">{e.requests.toLocaleString()}</TableCell>
                              <TableCell className="text-right tabular-nums">{e.errors.toLocaleString()}</TableCell>
                              <TableCell className="text-right tabular-nums">{e.p95Ms == null ? "—" : `${Math.round(e.p95Ms)} ms`}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </div>
                  </section>
                  <section aria-labelledby="top-errors" className="space-y-2">
                    <h2 id="top-errors" className="text-sm font-semibold">{t("Top errors")}</h2>
                    {s.topErrors.length === 0 ? (
                      <p className="text-sm text-muted-foreground">{t("No errors.")}</p>
                    ) : (
                      <ul className="divide-y rounded-md border text-sm">
                        {s.topErrors.map((e) => (
                          <li key={e.code} className="flex justify-between px-3 py-2">
                            <span className="font-mono text-xs">{e.code}</span>
                            <span className="tabular-nums">{e.count.toLocaleString()}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </section>
                </div>
                <div className="grid gap-6 lg:grid-cols-3">
                  <RankList id="slowest" title={t("Slowest endpoints (p95, 5+ calls)")} empty={t("Not enough calls yet.")}
                    rows={s.slowestEndpoints.map((e) => ({
                      key: e.endpointId ?? e.path,
                      label: e.endpointId
                        ? <Link className="hover:underline" to={`/w/${ws}/endpoints/${e.endpointId}`}>{e.name ?? `${e.method} ${e.path}`}</Link>
                        : `${e.method} ${e.path}`,
                      value: e.p95Ms == null ? "—" : `${Math.round(e.p95Ms)} ms`,
                    }))} />
                  <RankList id="top-clients" title={t("Top clients")} empty={t("No calls with a client token.")}
                    rows={s.topClients.map((c) => ({
                      key: c.clientId,
                      label: <span><span className="font-mono text-xs">{c.clientId}</span>
                        {c.serviceAccount && <span className="ml-2 text-muted-foreground">{c.serviceAccount}</span>}</span>,
                      value: `${c.requests.toLocaleString()}${c.errors ? ` · ${t("{{errors}} err", { errors: c.errors.toLocaleString() })}` : ""}`,
                    }))} />
                  <RankList id="top-ips" title={t("Top addresses")} empty={t("No calls.")}
                    rows={s.topIps.map((ip) => ({
                      key: ip.ip,
                      label: <span className="font-mono text-xs">{ip.ip}</span>,
                      value: `${ip.requests.toLocaleString()}${ip.errors ? ` · ${t("{{errors}} err", { errors: ip.errors.toLocaleString() })}` : ""}`,
                    }))} />
                </div>
              </>
            )
          }
        </QueryBoundary>
        <CallLog ws={ws} from={window.from} to={window.to} includeTryIt={includeTryIt} />
      </div>
    </PageContainer>
  );
}

function windowFor(range: Range) {
  const to = new Date();
  return { from: new Date(to.getTime() - RANGES[range] * 86_400_000).toISOString(), to: to.toISOString() };
}

function RankList({ id, title, empty, rows }: {
  id: string; title: string; empty: string; rows: { key: string; label: React.ReactNode; value: string }[];
}) {
  return (
    <section aria-labelledby={id} className="space-y-2">
      <h2 id={id} className="text-sm font-semibold">{title}</h2>
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="divide-y rounded-md border text-sm">
          {rows.map((r) => (
            <li key={r.key} className="flex items-center justify-between gap-3 px-3 py-2">
              <span className="min-w-0 truncate">{r.label}</span>
              <span className="shrink-0 tabular-nums">{r.value}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-md border p-4">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 text-2xl font-semibold tabular-nums">{value}</dd>
      {hint && <dd className="text-xs text-muted-foreground">{hint}</dd>}
    </div>
  );
}

/** Requests per day: one series, so no legend; a hidden table carries the same numbers. */
function DayChart({ days }: { days: Day[] }) {
  const { t } = useTranslation();
  const max = Math.max(1, ...days.map((d) => d.requests));
  return (
    <section aria-labelledby="per-day" className="space-y-2">
      <h2 id="per-day" className="text-sm font-semibold">{t("Requests per day (UTC)")}</h2>
      <div className="flex h-40 items-end gap-[2px] border-b border-border" aria-hidden="true">
        {days.map((d) => (
          <div key={d.day} className="group relative flex h-full flex-1 items-end justify-center" title={t("{{day}}: {{requests}} requests, {{errors}} errors", { day: d.day, requests: d.requests, errors: d.errors })}>
            <div className="w-full max-w-10 rounded-t-[4px] bg-primary/80 group-hover:bg-primary" style={{ height: `${(d.requests / max) * 100}%` }} />
            <div className="pointer-events-none absolute bottom-full mb-1 hidden whitespace-nowrap rounded-md border bg-popover px-2 py-1 text-xs text-popover-foreground shadow group-hover:block">
              {t("{{day}} · {{requests}} requests · {{errors}} errors", { day: d.day, requests: d.requests.toLocaleString(), errors: d.errors.toLocaleString() })}
            </div>
          </div>
        ))}
      </div>
      <div className="flex gap-[2px] text-xs text-muted-foreground" aria-hidden="true">
        {days.map((d) => <span key={d.day} className="flex-1 truncate text-center">{d.day.slice(5)}</span>)}
      </div>
      <table className="sr-only">
        <caption>{t("Requests per day")}</caption>
        <thead><tr><th>{t("Day")}</th><th>{t("Requests")}</th><th>{t("Errors")}</th></tr></thead>
        <tbody>{days.map((d) => <tr key={d.day}><td>{d.day}</td><td>{d.requests}</td><td>{d.errors}</td></tr>)}</tbody>
      </table>
    </section>
  );
}

function CallLog({ ws, from, to, includeTryIt }: { ws: string; from: string; to: string; includeTryIt: boolean }) {
  const { t } = useTranslation();
  const endpoints = useListEndpoints(ws, { trashed: false });
  const [endpointId, setEndpointId] = useState(ANY);
  const [status, setStatus] = useState(ANY);
  const [requestId, setRequestId] = useState("");
  const [limit, setLimit] = useState(100);
  const query = useActivityLog(ws, {
    from, to, includeTryIt, limit,
    endpointId: endpointId === ANY ? undefined : endpointId,
    status: status === ANY ? undefined : status,
    requestId: requestId.trim() || undefined,
  });

  return (
    <section aria-labelledby="call-log" className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="call-log" className="mr-2 text-sm font-semibold">{t("Calls")}</h2>
        <Select value={endpointId} onValueChange={setEndpointId}>
          <SelectTrigger className="w-56" aria-label={t("Endpoint filter")}><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ANY}>{t("All endpoints")}</SelectItem>
            {(endpoints.data ?? []).map((e) => <SelectItem key={e.id} value={e.id}>{e.name}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger className="w-32" aria-label={t("Status filter")}><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ANY}>{t("Any status")}</SelectItem>
            <SelectItem value="2xx">2xx</SelectItem>
            <SelectItem value="4xx">4xx</SelectItem>
            <SelectItem value="5xx">5xx</SelectItem>
          </SelectContent>
        </Select>
        <Input type="search" className="w-72 font-mono text-xs" aria-label={t("Request id")} placeholder={t("Request id from an error")}
          value={requestId} onChange={(e) => setRequestId(e.target.value)} />
      </div>
      <QueryBoundary query={query} what={t("calls")}>
        {(rows) =>
          rows.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("No calls match.")}</p>
          ) : (
            <>
              <div className="overflow-x-auto rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("Time")}</TableHead>
                      <TableHead>{t("Status")}</TableHead>
                      <TableHead>{t("Call")}</TableHead>
                      <TableHead>{t("Client")}</TableHead>
                      <TableHead className="text-right">{t("Duration")}</TableHead>
                      <TableHead className="text-right">{t("Rows")}</TableHead>
                      <TableHead>{t("Error")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((r) => (
                      <TableRow key={r.id}>
                        <TableCell className="whitespace-nowrap tabular-nums">{formatDateTime(r.ts)}</TableCell>
                        <TableCell>
                          <StatusBadge tone={r.status < 300 ? "success" : r.status < 500 ? "highlight" : "destructive"}>{r.status}</StatusBadge>
                        </TableCell>
                        <TableCell className="font-mono text-xs">
                          {r.method} {r.path}
                          {r.version != null && <span className="ml-1 text-muted-foreground">v{r.version}</span>}
                        </TableCell>
                        <TableCell className="font-mono text-xs">
                          {r.tryIt ? <StatusBadge tone="neutral">{t("Try-it")}</StatusBadge> : (r.clientId ?? r.ip ?? "—")}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{r.durationMs} ms</TableCell>
                        <TableCell className="text-right tabular-nums">{r.rows ?? "—"}</TableCell>
                        <TableCell className="max-w-md">
                          {r.errorCode && (
                            <details>
                              <summary className="cursor-pointer font-mono text-xs">{r.errorCode}</summary>
                              {r.error && <p className="mt-1 break-words text-xs">{r.error}</p>}
                              <p className="mt-1 font-mono text-xs text-muted-foreground">{t("request {{id}}", { id: r.requestId })}{r.traceId ? ` · ${t("trace {{id}}", { id: r.traceId })}` : ""}</p>
                            </details>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              {rows.length >= limit && limit < 500 && (
                <Button variant="outline" size="sm" onClick={() => setLimit((l) => Math.min(500, l + 100))}>{t("Show more")}</Button>
              )}
            </>
          )
        }
      </QueryBoundary>
    </section>
  );
}
