import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Copy, Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import type { ApiToken } from "@/api/generated/model";
import { getListApiTokensQueryKey, useCreateApiToken, useListApiTokens, useRevokeApiToken } from "@/api/generated/tokens/tokens";
import { useAuth } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { PageContainer } from "@/components/layout/PageContainer";
import { PageTitle } from "@/components/layout/PageTitle";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDate, formatDateTime } from "@/lib/format";

const ALL = "__all__";
const NEVER = "never";

/** Your API tokens (spec §8.6): for CI and scripts, e.g. the opendbapi command line. */
export default function ApiTokensPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const query = useListApiTokens();
  const refresh = () => qc.invalidateQueries({ queryKey: getListApiTokensQueryKey() });
  const revoke = useRevokeApiToken({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const [creating, setCreating] = useState(false);

  return (
    <PageContainer width="narrow">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <PageTitle>{t("API tokens")}</PageTitle>
        <Button onClick={() => setCreating(true)}>
          <Plus aria-hidden="true" />
          {t("New token")}
        </Button>
      </div>
      <p className="mt-2 max-w-prose text-sm text-muted-foreground">
        {t("A token acts as you, with your roles, for automation such as CI: send it as Authorization: Bearer, or set OPENDBAPI_TOKEN for the opendbapi command line. Limit it to one workspace and give it an expiry where you can.")}
      </p>
      <div className="mt-6">
        <QueryBoundary query={query} what={t("tokens")}>
          {(tokens) => tokens.length === 0 ? <p className="text-sm text-muted-foreground">{t("No tokens yet.")}</p> : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("Name")}</TableHead>
                    <TableHead>{t("Workspace")}</TableHead>
                    <TableHead>{t("Expires")}</TableHead>
                    <TableHead>{t("Last used")}</TableHead>
                    <TableHead className="w-24"><span className="sr-only">{t("Actions")}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {tokens.map((tk: ApiToken) => (
                    <TableRow key={tk.id}>
                      <TableCell>
                        <div className="font-medium">{tk.name}</div>
                        <div className="font-mono text-xs text-muted-foreground">{tk.prefix}…</div>
                      </TableCell>
                      <TableCell className="font-mono text-xs">{tk.workspace ?? t("All my workspaces")}</TableCell>
                      <TableCell className="tabular-nums">{tk.expiresAt ? formatDate(tk.expiresAt) : t("Never")}</TableCell>
                      <TableCell className="tabular-nums">{tk.lastUsedAt ? formatDateTime(tk.lastUsedAt) : t("Never")}</TableCell>
                      <TableCell className="text-right">
                        <Button variant="ghost" size="sm" aria-label={t("Revoke {{name}}", { name: tk.name })}
                          onClick={() => revoke.mutate({ id: tk.id })}>
                          {t("Revoke")}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </QueryBoundary>
      </div>
      {creating && <NewTokenDialog onClose={() => { setCreating(false); refresh(); }} />}
    </PageContainer>
  );
}

function NewTokenDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { me } = useAuth();
  const [name, setName] = useState("");
  const [workspace, setWorkspace] = useState(ALL);
  const [expiry, setExpiry] = useState("90");
  const [secret, setSecret] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const create = useCreateApiToken();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate({ data: { name: name.trim(), workspace: workspace === ALL ? null : workspace,
      expiresInDays: expiry === NEVER ? null : Number(expiry) } }, {
      onSuccess: (r) => setSecret(r.secret),
      onError: (err) => setError(errorMessage(err)),
    });
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        {secret ? (
          <div className="space-y-4">
            <DialogHeader>
              <DialogTitle>{t("Copy your token now")}</DialogTitle>
              <DialogDescription>{t("It is shown only this once. Store it in your CI's secrets.")}</DialogDescription>
            </DialogHeader>
            <div className="flex items-center gap-2">
              <Input readOnly aria-label={t("Token")} className="font-mono text-xs" value={secret} onFocus={(e) => e.target.select()} />
              <Button type="button" variant="outline" size="icon" aria-label={t("Copy token")}
                onClick={() => navigator.clipboard?.writeText(secret).then(() => toast.success(t("Token copied.")))}>
                <Copy aria-hidden="true" />
              </Button>
            </div>
            <DialogFooter>
              <Button type="button" onClick={onClose}>{t("Done")}</Button>
            </DialogFooter>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{t("New API token")}</DialogTitle>
              <DialogDescription>{t("The token can do what you can, limited by the choices below.")}</DialogDescription>
            </DialogHeader>
            <div className="space-y-1">
              <Label htmlFor="token-name">{t("Name")}</Label>
              <Input id="token-name" required maxLength={100} value={name} placeholder="GitHub Actions – prod" onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="token-ws">{t("Workspace")}</Label>
              <Select value={workspace} onValueChange={setWorkspace}>
                <SelectTrigger id="token-ws"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>{t("All my workspaces")}</SelectItem>
                  {me.workspaces.map((w) => <SelectItem key={w.slug} value={w.slug}>{w.name} ({w.slug})</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="token-expiry">{t("Expires")}</Label>
              <Select value={expiry} onValueChange={setExpiry}>
                <SelectTrigger id="token-expiry"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="30">{t("In {{n}} days", { n: 30 })}</SelectItem>
                  <SelectItem value="90">{t("In {{n}} days", { n: 90 })}</SelectItem>
                  <SelectItem value="365">{t("In {{n}} days", { n: 365 })}</SelectItem>
                  <SelectItem value={NEVER}>{t("Never")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>{t("Cancel")}</Button>
              <Button type="submit" disabled={create.isPending}>{t("Create token")}</Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
