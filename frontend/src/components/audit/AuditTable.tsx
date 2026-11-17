import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ScrollText } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { AuditEntry } from "@/api/generated/model";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime } from "@/lib/format";

const PAGE = 50;

/** Plain-text summary of an entry's detail object, for one table cell. */
function summarize(detail: AuditEntry["detail"], none: string): string {
  return Object.entries(detail ?? {})
    .map(([k, v]) => `${k}: ${Array.isArray(v) ? (v.length ? v.join("; ") : none) : String(v)}`)
    .join(" · ");
}

/**
 * Newest-first audit entries with an action-prefix filter and "Load more" paging.
 * `fetchPage(action, before)` is the system-wide or the workspace endpoint.
 */
export function AuditTable({
  queryKey,
  fetchPage,
}: {
  queryKey: readonly unknown[];
  fetchPage: (params: { action?: string; before?: number; limit: number }) => Promise<AuditEntry[]>;
}) {
  const [action, setAction] = useState("");
  const { t } = useTranslation();
  const query = useInfiniteQuery({
    queryKey: [...queryKey, action],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => fetchPage({ action: action || undefined, before: pageParam, limit: PAGE }),
    getNextPageParam: (last) => (last.length === PAGE ? last[last.length - 1].id : undefined),
  });

  return (
    <div className="space-y-3">
      <Input
        type="search"
        aria-label={t("Filter by action")}
        placeholder={t("Filter by action, e.g. auth. or member.")}
        className="w-72"
        value={action}
        onChange={(e) => setAction(e.target.value.trim())}
      />
      <QueryBoundary query={query} what={t("the audit log")}>
        {(data) => {
          const entries = data.pages.flat();
          if (entries.length === 0) {
            return action ? (
              <EmptyState
                title={t("No entries starting with “{{action}}”", { action })}
                description={t("Clear the filter to see everything.")}
              />
            ) : (
              <EmptyState variant="dashed" icon={ScrollText} title={t("Nothing recorded yet")} />
            );
          }
          return (
            <>
              <div className="overflow-x-auto rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("Time")}</TableHead>
                      <TableHead>{t("Who")}</TableHead>
                      <TableHead>{t("Action")}</TableHead>
                      <TableHead>{t("Target")}</TableHead>
                      <TableHead>{t("Details")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {entries.map((e) => (
                      <TableRow key={e.id}>
                        <TableCell className="tabular-nums">{formatDateTime(e.ts)}</TableCell>
                        <TableCell>{e.username ?? <span className="text-muted-foreground">{t("anonymous")}</span>}</TableCell>
                        <TableCell className="font-mono text-xs">{e.action}</TableCell>
                        <TableCell className="max-w-48 truncate text-xs" title={e.targetId ?? undefined}>
                          {e.targetType ? `${e.targetType}: ${e.targetId ?? ""}` : "—"}
                        </TableCell>
                        <TableCell className="max-w-md whitespace-normal break-words text-xs text-muted-foreground">
                          {summarize(e.detail, t("none"))}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              {query.hasNextPage && (
                <Button variant="outline" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
                  {t("Load older entries")}
                </Button>
              )}
            </>
          );
        }}
      </QueryBoundary>
    </div>
  );
}
