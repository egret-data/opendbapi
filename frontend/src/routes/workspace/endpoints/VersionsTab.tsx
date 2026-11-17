import { useState } from "react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { useListEndpointVersions, useRollbackEndpoint } from "@/api/generated/endpoints/endpoints";
import type { VersionView } from "@/api/generated/model";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { MonacoDiffEditor } from "@/components/monaco/MonacoEditor";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";
import { mainSql } from "./endpointForm";

const DRAFT = "draft";

function sqlOf(choice: string, versions: VersionView[], draftSql: string): string {
  if (choice === DRAFT) return draftSql;
  const v = versions.find((x) => String(x.version) === choice);
  return v ? mainSql(v.definition) : "";
}

function VersionPicker({ label, value, onChange, versions, draft }: {
  label: string; value: string; onChange: (v: string) => void; versions: VersionView[]; draft?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className="h-8 w-36" aria-label={label}><SelectValue /></SelectTrigger>
      <SelectContent>
        {draft && <SelectItem value={DRAFT}>{t("Saved draft")}</SelectItem>}
        {versions.map((v) => <SelectItem key={v.version} value={String(v.version)}>v{v.version}{v.live ? ` ${t("(live)")}` : ""}</SelectItem>)}
      </SelectContent>
    </Select>
  );
}

export function VersionsTab({ ws, id, draftSql, canAuthor, onChanged }: {
  ws: string; id: string; draftSql: string; canAuthor: boolean; onChanged: () => void;
}) {
  const query = useListEndpointVersions(ws, id);
  const { t } = useTranslation();
  const rollback = useRollbackEndpoint();
  const [left, setLeft] = useState<string | null>(null);
  const [right, setRight] = useState<string>(DRAFT);
  const [target, setTarget] = useState<VersionView | null>(null);

  return (
    <QueryBoundary query={query} what={t("versions")}>
      {(versions) =>
        versions.length === 0 ? (
          <EmptyState title={t("Not published yet")} description={t("Publish the draft to create version 1.")} />
        ) : (
          <div className="space-y-4">
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("Version")}</TableHead>
                    <TableHead>{t("Note")}</TableHead>
                    <TableHead>{t("Published")}</TableHead>
                    <TableHead>{t("By")}</TableHead>
                    <TableHead className="w-56"><span className="sr-only">{t("Actions")}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {versions.map((v) => (
                    <TableRow key={v.version}>
                      <TableCell className="tabular-nums">
                        v{v.version} {v.live && <StatusBadge tone="success" className="ml-2">{t("Serving")}</StatusBadge>}
                      </TableCell>
                      <TableCell className="max-w-sm whitespace-normal break-words">{v.note ?? <span className="text-muted-foreground">—</span>}</TableCell>
                      <TableCell className="tabular-nums">{formatDateTime(v.publishedAt)}</TableCell>
                      <TableCell>{v.publishedBy ?? "—"}</TableCell>
                      <TableCell className="space-x-1 text-right">
                        <Button variant="ghost" size="sm" onClick={() => { setLeft(String(v.version)); setRight(DRAFT); }}>
                          {t("Compare")}
                        </Button>
                        {canAuthor && !v.live && (
                          <Button variant="outline" size="sm" onClick={() => setTarget(v)}>{t("Roll back")}</Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {left && (
              <div className="space-y-2">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <span>{t("Compare")}</span>
                  <VersionPicker label={t("Left side")} value={left} onChange={setLeft} versions={versions} />
                  <span>{t("with")}</span>
                  <VersionPicker label={t("Right side")} value={right} onChange={setRight} versions={versions} draft />
                  <Button variant="ghost" size="sm" onClick={() => setLeft(null)}>{t("Close")}</Button>
                </div>
                <div className="h-72 overflow-hidden rounded-md border">
                  <MonacoDiffEditor language="sql" original={sqlOf(left, versions, draftSql)} modified={sqlOf(right, versions, draftSql)}
                    options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false }, fontSize: 12 }} />
                </div>
              </div>
            )}
            <ConfirmDialog
              open={target !== null}
              onOpenChange={(o) => !o && setTarget(null)}
              title={t("Serve version {{version}} again?", { version: target?.version ?? "" })}
              description={<p>{t("Callers get v{{version}} at once. Your draft is not changed.", { version: target?.version })}</p>}
              confirmLabel={t("Roll back")}
              pending={rollback.isPending}
              onConfirm={() => target && rollback.mutate({ ws, id, version: target.version }, {
                onSuccess: () => { toast.success(t("Now serving v{{version}}.", { version: target.version })); setTarget(null); onChanged(); },
                onError: (e) => { setTarget(null); toast.error(errorMessage(e)); },
              })}
            />
          </div>
        )
      }
    </QueryBoundary>
  );
}
