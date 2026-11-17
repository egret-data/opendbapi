import { useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Copy, Plus, Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { useListEndpoints } from "@/api/generated/endpoints/endpoints";
import { useListGroups } from "@/api/generated/groups/groups";
import {
  getGetServiceAccountQueryKey,
  getListServiceAccountsQueryKey,
  useCreateClient,
  useDeleteServiceAccount,
  useGetServiceAccount,
  useGrantAccess,
  useRevokeAccess,
  useRevokeClient,
  useRotateClientSecret,
  useUpdateServiceAccount,
} from "@/api/generated/service-accounts/service-accounts";
import { useSystemInfo } from "@/api/generated/system/system";
import type { Client, Grant, NewClient, ServiceAccountDetail } from "@/api/generated/model";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { DetailHeader } from "@/components/layout/DetailHeader";
import { PageContainer } from "@/components/layout/PageContainer";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";

import { AttributesSection } from "./AttributesSection";
import { ClientSecurityDialog } from "./ClientSecurityDialog";

export default function ServiceAccountPage() {
  const { ws = "", id = "" } = useParams();
  const query = useGetServiceAccount(ws, id);
  return (
    <PageContainer>
      <QueryBoundary query={query} what="the service account">
        {(detail) => <Account ws={ws} detail={detail} />}
      </QueryBoundary>
    </PageContainer>
  );
}

function Account({ ws, detail }: { ws: string; detail: ServiceAccountDetail }) {
  const { me } = useAuth();
  const isAdmin = hasRole(effectiveRole(me, ws) ?? "viewer", "admin");
  const sa = detail.summary;
  const qc = useQueryClient();
  const navigate = useNavigate();
  const refresh = () => {
    qc.invalidateQueries({ queryKey: getGetServiceAccountQueryKey(ws, sa.id) });
    qc.invalidateQueries({ queryKey: getListServiceAccountsQueryKey(ws) });
  };
  const fail = (e: unknown) => toast.error(errorMessage(e));
  const update = useUpdateServiceAccount({ mutation: { onSuccess: refresh, onError: fail } });
  const remove = useDeleteServiceAccount();
  const [deleting, setDeleting] = useState(false);
  const [editing, setEditing] = useState(false);

  const setDisabled = (disabled: boolean) =>
    update.mutate({ ws, id: sa.id, data: { name: sa.name, description: sa.description ?? null, disabled } },
      { onSuccess: () => toast.success(disabled ? "Disabled: its tokens stop working within seconds." : "Enabled again.") });

  return (
    <>
      <DetailHeader
        back={{ to: `/w/${ws}/clients`, label: "Clients" }}
        title={sa.name}
        status={<StatusBadge tone={sa.disabled ? "inactive" : "success"}>{sa.disabled ? "Disabled" : "Active"}</StatusBadge>}
        description={sa.description ?? undefined}
        actions={
          isAdmin && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setEditing(true)}>Edit</Button>
              <Button variant="outline" onClick={() => setDisabled(!sa.disabled)}>{sa.disabled ? "Enable" : "Disable"}</Button>
              <Button variant="outline" onClick={() => setDeleting(true)}>Delete…</Button>
            </div>
          )
        }
      />
      <div className="space-y-8">
        <ClientsSection ws={ws} saId={sa.id} clients={detail.clients} isAdmin={isAdmin} onChanged={refresh} />
        <GrantsSection ws={ws} saId={sa.id} grants={detail.grants} isAdmin={isAdmin} onChanged={refresh} />
        <AttributesSection ws={ws} sa={sa} isAdmin={isAdmin} onChanged={refresh} />
      </div>
      <EditDialog ws={ws} detail={detail} open={editing} onOpenChange={setEditing} onSaved={refresh} />
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`Delete ${sa.name}?`}
        description={<p>Its clients and grants are deleted too, and its tokens stop working. This cannot be undone.</p>}
        confirmLabel="Delete"
        destructive
        pending={remove.isPending}
        onConfirm={() => remove.mutate({ ws, id: sa.id }, {
          onSuccess: () => { qc.invalidateQueries({ queryKey: getListServiceAccountsQueryKey(ws) }); navigate(`/w/${ws}/clients`); },
          onError: fail,
        })}
      />
    </>
  );
}

function EditDialog({ ws, detail, open, onOpenChange, onSaved }: {
  ws: string; detail: ServiceAccountDetail; open: boolean; onOpenChange: (o: boolean) => void; onSaved: () => void;
}) {
  const sa = detail.summary;
  const update = useUpdateServiceAccount();
  const [name, setName] = useState(sa.name);
  const [description, setDescription] = useState(sa.description ?? "");
  const [error, setError] = useState<string | null>(null);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    update.mutate({ ws, id: sa.id, data: { name: name.trim(), description: description.trim() || null, disabled: sa.disabled } }, {
      onSuccess: () => { onSaved(); onOpenChange(false); },
      onError: (err) => setError(errorMessage(err)),
    });
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader><DialogTitle>Edit service account</DialogTitle></DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="edit-name">Name</Label>
            <Input id="edit-name" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="edit-desc">Description</Label>
            <Textarea id="edit-desc" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={update.isPending}>Save</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function clientState(c: Client): { label: string; tone: "success" | "inactive" | "neutral" } {
  if (c.revokedAt) return { label: "Revoked", tone: "inactive" };
  if (c.expiresAt && new Date(c.expiresAt) <= new Date()) return { label: "Expired", tone: "inactive" };
  return { label: "Active", tone: "success" };
}

function ClientsSection({ ws, saId, clients, isAdmin, onChanged }: {
  ws: string; saId: string; clients: Client[]; isAdmin: boolean; onChanged: () => void;
}) {
  const create = useCreateClient();
  const revoke = useRevokeClient();
  const [expiresInDays, setExpiresInDays] = useState("");
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<ShownSecret | null>(null);
  const [revoking, setRevoking] = useState<Client | null>(null);
  const [rotating, setRotating] = useState<Client | null>(null);
  const [securing, setSecuring] = useState<Client | null>(null);
  const rotate = useRotateClientSecret();

  return (
    <section aria-labelledby="clients-heading" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 id="clients-heading" className="text-lg font-semibold">Clients</h2>
        {isAdmin && (
          <Button variant="outline" size="sm" onClick={() => setCreating(true)}>
            <Plus aria-hidden="true" />
            New client
          </Button>
        )}
      </div>
      {clients.length === 0 ? (
        <p className="text-sm text-muted-foreground">No clients. A client holds the id and secret a system exchanges for tokens.</p>
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Client id</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Expires</TableHead>
                <TableHead>Last token</TableHead>
                <TableHead className="w-72"><span className="sr-only">Actions</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {clients.map((c) => {
                const state = clientState(c);
                return (
                  <TableRow key={c.id}>
                    <TableCell className="font-mono text-xs">{c.clientId}</TableCell>
                    <TableCell>
                      <span className="inline-flex flex-wrap gap-1">
                        <StatusBadge tone={state.tone}>{state.label}</StatusBadge>
                        {c.hmacRequired && <StatusBadge tone="neutral">Signed</StatusBadge>}
                        {c.responseEncrypt && <StatusBadge tone="neutral">Encrypted</StatusBadge>}
                      </span>
                    </TableCell>
                    <TableCell className="tabular-nums">{formatDateTime(c.createdAt)}</TableCell>
                    <TableCell className="tabular-nums">{c.expiresAt ? formatDateTime(c.expiresAt) : "Never"}</TableCell>
                    <TableCell className="tabular-nums">{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : "Never"}</TableCell>
                    <TableCell className="text-right">
                      {isAdmin && !c.revokedAt && (
                        <span className="inline-flex gap-1">
                          <Button variant="ghost" size="sm" onClick={() => setSecuring(c)}>Security</Button>
                          <Button variant="ghost" size="sm" onClick={() => setRotating(c)}>Rotate secret</Button>
                          <Button variant="ghost" size="sm" onClick={() => setRevoking(c)}>Revoke</Button>
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}

      {securing && (
        <ClientSecurityDialog ws={ws} saId={saId} client={securing} onClose={() => setSecuring(null)} onSaved={onChanged} />
      )}
      <Dialog open={creating} onOpenChange={(o) => { setCreating(o); if (!o) setExpiresInDays(""); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>New client</DialogTitle>
            <DialogDescription>The secret is shown once, right after this step.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="client-expiry">Expires after (days, optional)</Label>
            <Input id="client-expiry" type="number" min={1} max={3650} className="w-32 tabular-nums" value={expiresInDays}
              placeholder="Never" onChange={(e) => setExpiresInDays(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreating(false)}>Cancel</Button>
            <Button disabled={create.isPending} onClick={() => create.mutate(
              { ws, id: saId, data: { expiresInDays: expiresInDays ? Number(expiresInDays) : null } },
              {
                onSuccess: (c) => { setCreating(false); setExpiresInDays(""); setCreated(c); onChanged(); },
                onError: (e) => toast.error(errorMessage(e)),
              },
            )}>
              Create client
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <SecretDialog created={created} onClose={() => setCreated(null)} ws={ws} />

      <ConfirmDialog
        open={rotating !== null}
        onOpenChange={(o) => !o && setRotating(null)}
        title="Rotate this client's secret?"
        description={<p>The current secret of <span className="font-mono">{rotating?.clientId}</span> stops working at once. Update the systems that use it.</p>}
        confirmLabel="Rotate"
        pending={rotate.isPending}
        onConfirm={() => rotating && rotate.mutate({ ws, id: saId, clientRowId: rotating.id }, {
          onSuccess: (c) => { setRotating(null); setCreated({ ...c, rotated: true }); onChanged(); },
          onError: (e) => toast.error(errorMessage(e)),
        })}
      />

      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(o) => !o && setRevoking(null)}
        title="Revoke this client?"
        description={<p>Systems using <span className="font-mono">{revoking?.clientId}</span> lose access within seconds. This cannot be undone.</p>}
        confirmLabel="Revoke"
        destructive
        pending={revoke.isPending}
        onConfirm={() => revoking && revoke.mutate({ ws, id: saId, clientRowId: revoking.id }, {
          onSuccess: () => { setRevoking(null); onChanged(); toast.success("Client revoked."); },
          onError: (e) => toast.error(errorMessage(e)),
        })}
      />
    </section>
  );
}

type ShownSecret = NewClient & { rotated?: boolean };

function SecretDialog({ created, onClose, ws }: { created: ShownSecret | null; onClose: () => void; ws: string }) {
  const info = useSystemInfo();
  const tokenUrl = info.data?.tokenUrl ?? "/oauth2/token";
  const copy = (text: string, what: string) => navigator.clipboard?.writeText(text).then(() => toast.success(`${what} copied.`));
  if (!created) return null;
  const example = `curl -s -u '${created.client.clientId}:${created.clientSecret}' \\
  -d grant_type=client_credentials ${tokenUrl}
# then
curl -H "Authorization: Bearer <access_token>" ${info.data?.servingBaseUrl ?? ""}/${ws}/<path>`;
  return (
    // Not closable by Escape, outside click or an X: the secret is gone once this closes (spec §17.4).
    <Dialog open>
      <DialogContent className="sm:max-w-2xl" showCloseButton={false}
        onEscapeKeyDown={(e) => e.preventDefault()} onInteractOutside={(e) => e.preventDefault()}>
        <DialogHeader>
          <DialogTitle>{created.rotated ? "Secret rotated" : "Client created"}</DialogTitle>
          <DialogDescription>Copy the secret now. It is not stored in a readable form and cannot be shown again.</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <Field label="Client id" value={created.client.clientId} onCopy={() => copy(created.client.clientId, "Client id")} />
          <Field label="Client secret" value={created.clientSecret} onCopy={() => copy(created.clientSecret, "Secret")} />
          <div className="space-y-1">
            <p className="text-sm font-medium">Get a token and call an endpoint</p>
            <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs">{example}</pre>
          </div>
          <Alert>
            <AlertDescription>
              {created.rotated
                ? "The old secret no longer works. Tokens already issued stay valid until they expire (15 minutes)."
                : "The service account also needs a grant on each endpoint or group it calls."}
            </AlertDescription>
          </Alert>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>I have stored the secret</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Field({ label, value, onCopy }: { label: string; value: string; onCopy: () => void }) {
  return (
    <div className="space-y-1">
      <p className="text-sm font-medium">{label}</p>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 break-all rounded-md border bg-muted px-2 py-1.5 text-xs">{value}</code>
        <Button variant="outline" size="icon" aria-label={`Copy ${label.toLowerCase()}`} onClick={onCopy}>
          <Copy className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

function GrantsSection({ ws, saId, grants, isAdmin, onChanged }: {
  ws: string; saId: string; grants: Grant[]; isAdmin: boolean; onChanged: () => void;
}) {
  const grant = useGrantAccess();
  const revoke = useRevokeAccess();
  const endpoints = useListEndpoints(ws, { trashed: false });
  const groups = useListGroups(ws);
  const [adding, setAdding] = useState(false);
  const [type, setType] = useState<"endpoint" | "group">("endpoint");
  const [resourceId, setResourceId] = useState("");
  const options = type === "endpoint"
    ? (endpoints.data ?? []).map((e) => ({ id: e.id, label: `${e.name} · ${e.method} /${e.path}` }))
    : (groups.data ?? []).map((g) => ({ id: g.id, label: `${g.name} (${g.endpointCount} endpoints)` }));
  const granted = new Set(grants.map((g) => `${g.resourceType}:${g.resourceId}`));

  return (
    <section aria-labelledby="grants-heading" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 id="grants-heading" className="text-lg font-semibold">Grants</h2>
        {isAdmin && (
          <Button variant="outline" size="sm" onClick={() => setAdding(true)}>
            <Plus aria-hidden="true" />
            Grant access
          </Button>
        )}
      </div>
      {grants.length === 0 ? (
        <p className="text-sm text-muted-foreground">No grants: this account cannot call any private endpoint.</p>
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Resource</TableHead>
                <TableHead>Kind</TableHead>
                <TableHead>Granted</TableHead>
                <TableHead className="w-12"><span className="sr-only">Remove</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {grants.map((g) => (
                <TableRow key={`${g.resourceType}:${g.resourceId}`}>
                  <TableCell>
                    <span className="font-medium">{g.resourceName}</span>
                    {g.detail && <span className="ml-2 font-mono text-xs text-muted-foreground">{g.detail}</span>}
                  </TableCell>
                  <TableCell>{g.resourceType === "group" ? "Group (all its endpoints)" : "Endpoint"}</TableCell>
                  <TableCell className="tabular-nums">{formatDateTime(g.grantedAt)}</TableCell>
                  <TableCell>
                    {isAdmin && (
                      <Button variant="ghost" size="icon" aria-label={`Remove grant on ${g.resourceName}`}
                        onClick={() => revoke.mutate({ ws, id: saId, resourceType: g.resourceType, resourceId: g.resourceId }, {
                          onSuccess: onChanged, onError: (e) => toast.error(errorMessage(e)) })}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <Dialog open={adding} onOpenChange={(o) => { setAdding(o); if (!o) setResourceId(""); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Grant access</DialogTitle>
            <DialogDescription>A group grant covers every endpoint in the group, including ones added later.</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <Label htmlFor="grant-type">Kind</Label>
              <Select value={type} onValueChange={(v) => { setType(v as "endpoint" | "group"); setResourceId(""); }}>
                <SelectTrigger id="grant-type" className="w-48"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="endpoint">Endpoint</SelectItem>
                  <SelectItem value="group">Group</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="grant-resource">{type === "endpoint" ? "Endpoint" : "Group"}</Label>
              <Select value={resourceId} onValueChange={setResourceId}>
                <SelectTrigger id="grant-resource" className="w-full"><SelectValue placeholder="Choose…" /></SelectTrigger>
                <SelectContent>
                  {options.filter((o) => !granted.has(`${type}:${o.id}`)).map((o) => (
                    <SelectItem key={o.id} value={o.id}>{o.label}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAdding(false)}>Cancel</Button>
            <Button disabled={!resourceId || grant.isPending} onClick={() => grant.mutate(
              { ws, id: saId, data: { resourceType: type, resourceId } },
              { onSuccess: () => { setAdding(false); setResourceId(""); onChanged(); }, onError: (e) => toast.error(errorMessage(e)) },
            )}>
              Grant
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
