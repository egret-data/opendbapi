import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import type { WorkspaceRole } from "@/api/auth";
import type { DbapiExport, ImportReport, JsonNode } from "@/api/generated/model";
import { useImportDbapi } from "@/api/generated/transfer/transfer";
import { hasRole } from "@/auth/useAuth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";

type FileKey = "apis" | "groups" | "datasources";
const FILES: { key: FileKey; label: string; hint: string }[] = [
  { key: "apis", label: "APIs", hint: "api_config.json (API list → Export API)" },
  { key: "groups", label: "Groups", hint: "group.json (Export API groups)" },
  { key: "datasources", label: "Datasources", hint: "datasource.json (Export datasources)" },
];

const RESULT_TONE = { created: "success", existing: "neutral", skipped: "inactive" } as const;

/** Export as YAML (spec §15.1) and import from DBAPI (spec §15.3). */
export function TransferSection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const isAdmin = hasRole(role, "admin");
  const [files, setFiles] = useState<Partial<Record<FileKey, unknown>>>({});
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = useImportDbapi();
  const qc = useQueryClient();
  const { t } = useTranslation();

  if (!isAdmin) {
    return <p className="text-sm text-muted-foreground">{t("Export and import are for workspace admins.")}</p>;
  }

  const read = async (key: FileKey, file: File | undefined) => {
    setReport(null);
    setError(null);
    if (!file) {
      setFiles((f) => ({ ...f, [key]: undefined }));
      return;
    }
    try {
      const parsed: unknown = JSON.parse(await file.text());
      setFiles((f) => ({ ...f, [key]: parsed }));
    } catch {
      setError(t("{{file}} is not valid JSON.", { file: file.name }));
    }
  };

  const submit = (dryRun: boolean) => {
    setError(null);
    const data: DbapiExport = {
      apis: (files.apis ?? null) as JsonNode,
      groups: (files.groups ?? null) as JsonNode,
      datasources: (files.datasources ?? null) as JsonNode,
    };
    run.mutate({ ws, data, params: { dryRun } }, {
      onSuccess: (r) => {
        setReport(r);
        if (!dryRun) {
          toast.success(t("Imported: {{created}} created, {{skipped}} skipped.", { created: r.created, skipped: r.skipped }));
          qc.invalidateQueries();
        }
      },
      onError: (e) => setError(errorMessage(e)),
    });
  };

  const chosen = Object.values(files).some((v) => v !== undefined);
  return (
    <div className="space-y-8">
      <section aria-labelledby="export-heading" className="space-y-2">
        <h2 id="export-heading" className="text-lg font-semibold">{t("Export")}</h2>
        <p className="max-w-prose text-sm text-muted-foreground">
          {t("Datasources, groups, endpoint drafts with their tests, service accounts, grants and data policies as YAML, referring to each other by name. Passwords, client secrets and webhook URLs are never included.")}
        </p>
        <Button asChild variant="outline">
          <a href={`/admin/api/ws/${ws}/export?format=yaml`} download={`${ws}.yaml`}>
            <Download aria-hidden="true" />
            {t("Download YAML")}
          </a>
        </Button>
      </section>

      <section aria-labelledby="import-heading" className="space-y-3">
        <h2 id="import-heading" className="text-lg font-semibold">{t("Import from DBAPI")}</h2>
        <p className="max-w-prose text-sm text-muted-foreground">
          {t("Choose the files DBAPI exports. APIs arrive as drafts, groups are matched by name, and datasources are created read-only without passwords. Check first: nothing is written until you import.")}
        </p>
        <div className="grid gap-3 sm:grid-cols-3">
          {FILES.map((f) => (
            <div key={f.key} className="space-y-1">
              <Label htmlFor={`dbapi-${f.key}`}>{t(f.label)}</Label>
              <Input id={`dbapi-${f.key}`} type="file" accept="application/json,.json"
                onChange={(e) => void read(f.key, e.target.files?.[0])} />
              <p className="text-xs text-muted-foreground">{t(f.hint)}</p>
            </div>
          ))}
        </div>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        <div className="flex gap-2">
          <Button variant="outline" disabled={!chosen || run.isPending} onClick={() => submit(true)}>{t("Check")}</Button>
          <Button disabled={!report?.dryRun || run.isPending} title={!report?.dryRun ? t("Check the files first") : undefined}
            onClick={() => submit(false)}>
            {t("Import")}
          </Button>
        </div>
        {report && (
          <div className="space-y-2">
            <p className="text-sm">
              {report.dryRun
                ? t("Would create {{created}}, skipped {{skipped}}.", { created: report.created, skipped: report.skipped })
                : t("Created {{created}}, skipped {{skipped}}.", { created: report.created, skipped: report.skipped })}
            </p>
            <div className="overflow-x-auto rounded-md border">
              <Table aria-label={t("Import report")}>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("Kind")}</TableHead>
                    <TableHead>{t("Name")}</TableHead>
                    <TableHead>{t("Result")}</TableHead>
                    <TableHead>{t("Notes")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {report.items.map((item, i) => (
                    <TableRow key={i}>
                      <TableCell className="capitalize">{item.kind}</TableCell>
                      <TableCell className="font-medium">{item.name}</TableCell>
                      <TableCell><StatusBadge tone={RESULT_TONE[item.result]}>{item.result}</StatusBadge></TableCell>
                      <TableCell className="whitespace-normal text-sm">
                        {item.notes.length === 0 ? <span className="text-muted-foreground">—</span>
                          : <ul className="list-disc pl-4">{item.notes.map((n, k) => <li key={k}>{n}</li>)}</ul>}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
