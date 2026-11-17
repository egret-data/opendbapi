import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import type { WorkspaceRole } from "@/api/auth";
import type { ManagedSecret } from "@/api/generated/model";
import {
  getListSecretsQueryKey,
  useCreateSecret,
  useDeleteSecret,
  useListSecrets,
  useUpdateSecret,
} from "@/api/generated/secrets/secrets";
import { hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";

/** Secrets OpenDBAPI keeps for the workspace (spec §4.5): names visible, values write-only. */
export function SecretsSection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const { t } = useTranslation();
  const isAdmin = hasRole(role, "admin");
  const query = useListSecrets(ws, { query: { enabled: isAdmin } });
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: getListSecretsQueryKey(ws) });
  const remove = useDeleteSecret({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const [editing, setEditing] = useState<{ secret?: ManagedSecret } | null>(null);

  if (!isAdmin) {
    return <p className="text-sm text-muted-foreground">{t("Secrets are for workspace admins.")}</p>;
  }

  return (
    <section aria-labelledby="secrets-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="secrets-heading" className="text-lg font-semibold">{t("Secrets")}</h2>
          <p className="max-w-prose text-sm text-muted-foreground">
            {t("Store a password or webhook URL once and use it as secret:NAME in datasources and alert channels. Values are encrypted with the server's master key and never shown again; changing one updates everything that uses it.")}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => setEditing({})}>
          <Plus aria-hidden="true" />
          {t("Add secret")}
        </Button>
      </div>
      <QueryBoundary query={query} what={t("secrets")}>
        {(secrets) =>
          secrets.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("No secrets yet.")}</p>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("Name")}</TableHead>
                    <TableHead>{t("Used by")}</TableHead>
                    <TableHead>{t("Changed")}</TableHead>
                    <TableHead className="w-48"><span className="sr-only">{t("Actions")}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {secrets.map((s) => (
                    <TableRow key={s.name}>
                      <TableCell>
                        <span className="font-mono text-xs">secret:{s.name}</span>
                        {s.description && <div className="text-xs text-muted-foreground">{s.description}</div>}
                      </TableCell>
                      <TableCell className="whitespace-normal text-sm">
                        {s.usedBy.length === 0 ? <span className="text-muted-foreground">{t("Not used")}</span>
                          : s.usedBy.map((u) => `${u.kind === "datasource" ? t("Datasource") : t("Alert channel")} ${u.name}`).join(", ")}
                      </TableCell>
                      <TableCell className="tabular-nums">{formatDateTime(s.updatedAt)}</TableCell>
                      <TableCell className="text-right">
                        <Button variant="ghost" size="sm" onClick={() => setEditing({ secret: s })}>{t("Change")}</Button>
                        <Button variant="ghost" size="sm" aria-label={t("Delete secret {{name}}", { name: s.name })}
                          disabled={s.usedBy.length > 0} title={s.usedBy.length > 0 ? t("In use") : undefined}
                          onClick={() => remove.mutate({ ws, name: s.name })}>
                          {t("Delete")}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )
        }
      </QueryBoundary>
      {editing && <SecretDialog ws={ws} secret={editing.secret} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />}
    </section>
  );
}

function SecretDialog({ ws, secret, onClose, onSaved }: { ws: string; secret?: ManagedSecret; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState(secret?.name ?? "");
  const [description, setDescription] = useState(secret?.description ?? "");
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = useCreateSecret();
  const update = useUpdateSecret();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const handlers = {
      onSuccess: () => {
        toast.success(secret ? t("Secret saved.") : t("Secret added."));
        onSaved();
      },
      onError: (err: unknown) => setError(errorMessage(err)),
    };
    if (secret) update.mutate({ ws, name: secret.name, data: { description: description.trim() || null, value: value || null } }, handlers);
    else create.mutate({ ws, data: { name: name.trim(), description: description.trim() || null, value } }, handlers);
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4" autoComplete="off">
          <DialogHeader>
            <DialogTitle>{secret ? t("Change secret") : t("New secret")}</DialogTitle>
            <DialogDescription>{t("The value is write-only: nobody can read it back, only replace it.")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="secret-name">{t("Name")}</Label>
            <Input id="secret-name" required className="font-mono text-xs" value={name} disabled={!!secret} placeholder="crm-db-password"
              onChange={(e) => setName(e.target.value)} />
            {!secret && <p className="text-xs text-muted-foreground">{t("Lower-case letters, digits, '.', '_' and '-'. It cannot be renamed later.")}</p>}
          </div>
          <div className="space-y-1">
            <Label htmlFor="secret-desc">{t("Description (optional)")}</Label>
            <Input id="secret-desc" value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="secret-value">{secret ? t("New value (optional)") : t("Value")}</Label>
            <Input id="secret-value" type="password" required={!secret} value={value} autoComplete="new-password"
              placeholder={secret ? t("Unchanged") : undefined} onChange={(e) => setValue(e.target.value)} />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t("Cancel")}</Button>
            <Button type="submit" disabled={create.isPending || update.isPending}>{t("Save")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
