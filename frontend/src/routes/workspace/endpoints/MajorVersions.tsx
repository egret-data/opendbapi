import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { useEndpointLineage, useDeprecateEndpoint, useNewMajorEndpoint } from "@/api/generated/endpoints/endpoints";
import type { EndpointSummary } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDate } from "@/lib/format";

/** Spec §16: copy the draft to a new route as the next major version. */
export function NewMajorDialog({ ws, endpoint, open, onOpenChange, onCreated }: {
  ws: string; endpoint: EndpointSummary; open: boolean; onOpenChange: (open: boolean) => void; onCreated: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [path, setPath] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = useNewMajorEndpoint();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate({ ws, id: endpoint.id, data: { path: path.trim() || null } }, {
      onSuccess: (c) => onCreated(c.summary.id),
      onError: (err) => setError(errorMessage(err)),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{t("New major version")}</DialogTitle>
            <DialogDescription>
              {t("For changes that break callers. The draft is copied to a new route; both versions are served side by side, so callers move when they are ready.")}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="major-path">{t("Path of the new version")}</Label>
            <Input id="major-path" className="font-mono text-xs" value={path} onChange={(e) => setPath(e.target.value)}
              placeholder={t("Leave empty for v{{n}}/…", { n: endpoint.major + 1 })} />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t("Cancel")}</Button>
            <Button type="submit" disabled={create.isPending}>{t("Create draft")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** Spec §16: responses announce the deprecation, and the sunset date when given. */
export function DeprecateDialog({ ws, endpoint, open, onOpenChange, onDone }: {
  ws: string; endpoint: EndpointSummary; open: boolean; onOpenChange: (open: boolean) => void; onDone: () => void;
}) {
  const { t } = useTranslation();
  const [sunset, setSunset] = useState("");
  const [error, setError] = useState<string | null>(null);
  const deprecate = useDeprecateEndpoint();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    deprecate.mutate({ ws, id: endpoint.id, data: { sunset: sunset ? new Date(`${sunset}T00:00:00Z`).toISOString() : null } }, {
      onSuccess: onDone,
      onError: (err) => setError(errorMessage(err)),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{t("Deprecate endpoint")}</DialogTitle>
            <DialogDescription>
              {t("It keeps answering, but every response carries a Deprecation header, a Sunset header when you set a date, and a link to the newest live major version.")}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="sunset">{t("Sunset date (optional)")}</Label>
            <Input id="sunset" type="date" value={sunset} onChange={(e) => setSunset(e.target.value)} />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t("Cancel")}</Button>
            <Button type="submit" disabled={deprecate.isPending}>{t("Deprecate")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** The major versions sharing this endpoint's lineage; hidden while there is only one. */
export function LineagePanel({ ws, id }: { ws: string; id: string }) {
  const { t } = useTranslation();
  const lineage = useEndpointLineage(ws, id);
  const members = lineage.data ?? [];
  if (members.length < 2) return null;
  return (
    <section aria-labelledby="lineage-heading" className="space-y-2">
      <h2 id="lineage-heading" className="text-sm font-semibold">{t("Major versions")}</h2>
      <div className="overflow-x-auto rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("Major")}</TableHead>
              <TableHead>{t("Route")}</TableHead>
              <TableHead>{t("State")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {members.map((m) => (
              <TableRow key={m.id} aria-current={m.id === id ? "true" : undefined}>
                <TableCell className="tabular-nums">v{m.major}</TableCell>
                <TableCell className="font-mono text-xs">
                  {m.id === id ? `${m.method} /${m.path}` : <Link className="underline" to={`/w/${ws}/endpoints/${m.id}/versions`}>{m.method} /{m.path}</Link>}
                </TableCell>
                <TableCell className="space-x-2">
                  <StatusBadge tone={m.state === "live" ? "success" : "neutral"}>{m.state === "live" ? t("Live") : m.state === "disabled" ? t("Disabled") : t("Draft")}</StatusBadge>
                  {m.deprecatedAt && (
                    <StatusBadge tone="inactive">
                      {m.sunsetAt ? t("Deprecated · sunset {{date}}", { date: formatDate(m.sunsetAt) }) : t("Deprecated")}
                    </StatusBadge>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </section>
  );
}
