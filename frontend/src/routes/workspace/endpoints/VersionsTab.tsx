import { useState } from "react";
import { toast } from "sonner";

import { useListEndpointVersions, useRollbackEndpoint } from "@/api/generated/endpoints/endpoints";
import type { VersionView } from "@/api/generated/model";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { MonacoDiffEditor } from "@/components/monaco/MonacoEditor";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";
import { mainSql } from "./endpointForm";

export function VersionsTab({ ws, id, draftSql, canAuthor, onChanged }: {
  ws: string; id: string; draftSql: string; canAuthor: boolean; onChanged: () => void;
}) {
  const query = useListEndpointVersions(ws, id);
  const rollback = useRollbackEndpoint();
  const [comparing, setComparing] = useState<VersionView | null>(null);
  const [target, setTarget] = useState<VersionView | null>(null);

  return (
    <QueryBoundary query={query} what="versions">
      {(versions) =>
        versions.length === 0 ? (
          <EmptyState title="Not published yet" description="Publish the draft to create version 1." />
        ) : (
          <div className="space-y-4">
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Version</TableHead>
                    <TableHead>Note</TableHead>
                    <TableHead>Published</TableHead>
                    <TableHead>By</TableHead>
                    <TableHead className="w-56"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {versions.map((v) => (
                    <TableRow key={v.version}>
                      <TableCell className="tabular-nums">
                        v{v.version} {v.live && <StatusBadge tone="success" className="ml-2">Serving</StatusBadge>}
                      </TableCell>
                      <TableCell className="max-w-sm whitespace-normal break-words">{v.note ?? <span className="text-muted-foreground">—</span>}</TableCell>
                      <TableCell className="tabular-nums">{formatDateTime(v.publishedAt)}</TableCell>
                      <TableCell>{v.publishedBy ?? "—"}</TableCell>
                      <TableCell className="space-x-1 text-right">
                        <Button variant="ghost" size="sm" onClick={() => setComparing(comparing?.version === v.version ? null : v)}>
                          {comparing?.version === v.version ? "Hide diff" : "Diff with draft"}
                        </Button>
                        {canAuthor && !v.live && (
                          <Button variant="outline" size="sm" onClick={() => setTarget(v)}>Roll back</Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {comparing && (
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">Left: v{comparing.version} · Right: saved draft</p>
                <div className="h-72 overflow-hidden rounded-md border">
                  <MonacoDiffEditor language="sql" original={mainSql(comparing.definition)} modified={draftSql}
                    options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false }, fontSize: 12 }} />
                </div>
              </div>
            )}
            <ConfirmDialog
              open={target !== null}
              onOpenChange={(o) => !o && setTarget(null)}
              title={`Serve version ${target?.version ?? ""} again?`}
              description={<p>Callers get v{target?.version} at once. Your draft is not changed.</p>}
              confirmLabel="Roll back"
              pending={rollback.isPending}
              onConfirm={() => target && rollback.mutate({ ws, id, version: target.version }, {
                onSuccess: () => { toast.success(`Now serving v${target.version}.`); setTarget(null); onChanged(); },
                onError: (e) => { setTarget(null); toast.error(errorMessage(e)); },
              })}
            />
          </div>
        )
      }
    </QueryBoundary>
  );
}
