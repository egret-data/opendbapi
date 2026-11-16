import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { KeyRound, Plus } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";

import {
  getListServiceAccountsQueryKey,
  useCreateServiceAccount,
  useListServiceAccounts,
} from "@/api/generated/service-accounts/service-accounts";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";

/** Service accounts: who calls private endpoints (spec §8.1, §17.4). */
export default function ClientsPage() {
  const { ws = "" } = useParams();
  const { me } = useAuth();
  const isAdmin = hasRole(effectiveRole(me, ws) ?? "viewer", "admin");
  const query = useListServiceAccounts(ws);
  const [creating, setCreating] = useState(false);

  const newButton = isAdmin ? (
    <Button onClick={() => setCreating(true)}>
      <Plus aria-hidden="true" />
      New service account
    </Button>
  ) : undefined;

  return (
    <ListPageShell
      title="Clients"
      description="A service account is a caller. Give it clients (credentials) and grants (endpoints it may call)."
      action={newButton}
    >
      <QueryBoundary query={query} what="service accounts">
        {(accounts) =>
          accounts.length === 0 ? (
            <EmptyState variant="dashed" icon={KeyRound} title="No service accounts yet"
              description={isAdmin ? "Create one for each system that calls your private endpoints." : "Workspace admins create service accounts."}
              action={newButton} />
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Active clients</TableHead>
                    <TableHead>Grants</TableHead>
                    <TableHead>Last token</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {accounts.map((a) => (
                    <TableRow key={a.id}>
                      <TableCell>
                        <Link className="font-medium hover:underline" to={a.id}>{a.name}</Link>
                        {a.description && <p className="max-w-md truncate text-xs text-muted-foreground">{a.description}</p>}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={a.disabled ? "inactive" : "success"}>{a.disabled ? "Disabled" : "Active"}</StatusBadge>
                      </TableCell>
                      <TableCell className="tabular-nums">{a.clientCount}</TableCell>
                      <TableCell className="tabular-nums">{a.grantCount}</TableCell>
                      <TableCell className="tabular-nums">{a.lastUsedAt ? formatDateTime(a.lastUsedAt) : "Never"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )
        }
      </QueryBoundary>
      <CreateDialog ws={ws} open={creating} onOpenChange={setCreating} />
    </ListPageShell>
  );
}

function CreateDialog({ ws, open, onOpenChange }: { ws: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const create = useCreateServiceAccount();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate(
      { ws, data: { name: name.trim(), description: description.trim() || null, disabled: false } },
      {
        onSuccess: (sa) => {
          qc.invalidateQueries({ queryKey: getListServiceAccountsQueryKey(ws) });
          navigate(sa.summary.id);
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>New service account</DialogTitle>
            <DialogDescription>Name it after the system that will call the API.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="sa-name">Name</Label>
            <Input id="sa-name" required maxLength={120} value={name} placeholder="billing-service" onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="sa-desc">Description (optional)</Label>
            <Textarea id="sa-desc" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={create.isPending}>Create</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
