import { useEffect, useId, useState, type FormEvent } from "react";
import { Info, UserPlus, Users } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { authKeys, useMe, type WorkspaceRole } from "@/api/auth";
import {
  getListMembersQueryKey,
  useAddMember,
  useChangeMemberRole,
  useListMembers,
  useRemoveMember,
  useSearchUsers,
} from "@/api/generated/members/members";
import type { MemberView, UserMatch } from "@/api/generated/model";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { hasRole } from "@/auth/useAuth";
import { errorMessage } from "@/lib/errors";
import { ROLE_HINT, ROLE_LABEL, ROLE_ORDER } from "@/lib/roles";

/** Why the caller cannot change this member, or null when they can (FE-ACTION-4). */
function lockReason(member: MemberView, myRole: WorkspaceRole, ownerCount: number): string | null {
  if (member.fromMapping) return "This role comes from a directory group mapping. Change the group or the mapping.";
  if (!hasRole(myRole, "admin")) return "Only workspace admins can change members.";
  if (member.role === "owner" && myRole !== "owner") return "Only an owner can change another owner.";
  if (member.role === "owner" && ownerCount <= 1) return "A workspace needs at least one owner. Make someone else owner first.";
  return null;
}

export function MembersSection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const query = useListMembers(ws);
  const qc = useQueryClient();
  const { data: me } = useMe();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<MemberView | null>(null);
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: getListMembersQueryKey(ws) });
    qc.invalidateQueries({ queryKey: authKeys.me });
  };
  const change = useChangeMemberRole({ mutation: { onSuccess: invalidate } });
  const remove = useRemoveMember({ mutation: { onSuccess: invalidate } });
  const canManage = hasRole(role, "admin");
  const grantable = ROLE_ORDER.filter((r) => r !== "owner" || role === "owner").reverse();

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          People can also get roles from directory groups; those rows are marked and can only be changed through the mapping.
        </p>
        {canManage && (
          <Button onClick={() => setAdding(true)}>
            <UserPlus aria-hidden="true" />
            Add member
          </Button>
        )}
      </div>
      <QueryBoundary query={query} what="members">
        {(members) =>
          members.length === 0 ? (
            <EmptyState variant="dashed" icon={Users} title="No members" />
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Member</TableHead>
                    <TableHead>Sign-in</TableHead>
                    <TableHead>Role</TableHead>
                    <TableHead className="w-24"><span className="sr-only">Remove</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {members.map((m) => {
                    const reason = lockReason(m, role, members.filter((x) => x.role === "owner").length);
                    const roleSelect = (
                      <Select
                        value={m.role}
                        disabled={change.isPending}
                        onValueChange={(next) =>
                          change.mutate(
                            { ws, userId: m.userId, data: { role: next as WorkspaceRole } },
                            {
                              onSuccess: () => toast.success(`${m.displayName ?? m.username} is now ${ROLE_LABEL[next as WorkspaceRole]}.`),
                              onError: (e) => toast.error(errorMessage(e)),
                            },
                          )
                        }
                      >
                        <SelectTrigger className="w-40" aria-label={`Role of ${m.username}`}>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {(m.role === "owner" && role !== "owner" ? ROLE_ORDER.slice().reverse() : grantable).map((r) => (
                            <SelectItem key={r} value={r}>
                              {ROLE_LABEL[r]}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    );
                    return (
                      <TableRow key={m.userId}>
                        <TableCell>
                          <div className="font-medium">
                            {m.displayName ?? m.username}
                            {m.userId === me?.id && <span className="ml-1 text-xs text-muted-foreground">(you)</span>}
                          </div>
                          <div className="text-xs text-muted-foreground">
                            {m.username}
                            {m.email ? ` · ${m.email}` : ""}
                          </div>
                        </TableCell>
                        <TableCell>
                          {m.source.toUpperCase()}
                          {m.fromMapping && (
                            <StatusBadge tone="neutral" className="ml-2">
                              From group
                            </StatusBadge>
                          )}
                        </TableCell>
                        <TableCell>
                          {reason ? (
                            <span className="inline-flex items-center gap-1">
                              {ROLE_LABEL[m.role]}
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <Button variant="ghost" size="iconSm" aria-label={`Why the role of ${m.username} is locked`}>
                                    <Info className="h-3.5 w-3.5" />
                                  </Button>
                                </TooltipTrigger>
                                <TooltipContent className="max-w-xs">{reason}</TooltipContent>
                              </Tooltip>
                            </span>
                          ) : (
                            roleSelect
                          )}
                        </TableCell>
                        <TableCell>
                          {reason === null && (
                            <Button variant="ghost" size="sm" onClick={() => setRemoving(m)}>
                              Remove
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          )
        }
      </QueryBoundary>

      {canManage && (
        <AddMemberDialog ws={ws} open={adding} onOpenChange={setAdding} grantable={grantable} onAdded={invalidate} />
      )}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={`Remove ${removing?.displayName ?? removing?.username ?? "member"}?`}
        description={<p>They lose access to this workspace at once.</p>}
        confirmLabel="Remove"
        destructive
        pending={remove.isPending}
        onConfirm={() =>
          removing &&
          remove.mutate(
            { ws, userId: removing.userId },
            {
              onSuccess: () => {
                toast.success(`${removing.username} removed.`);
                setRemoving(null);
              },
              onError: (e) => {
                setRemoving(null);
                toast.error(errorMessage(e));
              },
            },
          )
        }
      />
    </div>
  );
}

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

function AddMemberDialog({
  ws,
  open,
  onOpenChange,
  grantable,
  onAdded,
}: {
  ws: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  grantable: WorkspaceRole[];
  onAdded: () => void;
}) {
  const [q, setQ] = useState("");
  const [picked, setPicked] = useState<UserMatch | null>(null);
  const [role, setRole] = useState<WorkspaceRole>("author");
  const [error, setError] = useState<string | null>(null);
  const term = useDebounced(q.trim(), 250);
  const results = useSearchUsers(ws, { q: term }, { query: { enabled: open && term.length > 0 && !picked } });
  const add = useAddMember();
  const searchId = useId();
  const roleId = useId();

  const close = () => {
    setQ("");
    setPicked(null);
    setRole("author");
    setError(null);
    onOpenChange(false);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!picked) return;
    setError(null);
    add.mutate(
      { ws, data: { userId: picked.id, role } },
      {
        onSuccess: () => {
          toast.success(`${picked.displayName ?? picked.username} added as ${ROLE_LABEL[role]}.`);
          onAdded();
          close();
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>Add member</DialogTitle>
            <DialogDescription>
              People from LDAP or SSO can be added after their first sign-in. To add whole groups, use a group mapping.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor={searchId}>User</Label>
            {picked ? (
              <div className="flex items-center justify-between rounded-md border px-3 py-2 text-sm">
                <span>
                  {picked.displayName ?? picked.username}{" "}
                  <span className="text-muted-foreground">
                    · {picked.username} · {picked.source.toUpperCase()}
                  </span>
                </span>
                <Button type="button" variant="ghost" size="sm" onClick={() => setPicked(null)}>
                  Change
                </Button>
              </div>
            ) : (
              <>
                <Input
                  id={searchId}
                  type="search"
                  placeholder="Name, username or email"
                  autoComplete="off"
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                />
                {term && (
                  <ul className="max-h-56 overflow-y-auto rounded-md border" aria-label="Matching users">
                    {results.isError && <li className="p-2 text-sm text-destructive">{errorMessage(results.error)}</li>}
                    {results.isPending && <li className="p-2 text-sm text-muted-foreground">Searching…</li>}
                    {results.data?.length === 0 && (
                      <li className="p-2 text-sm text-muted-foreground">Nobody matches “{term}”.</li>
                    )}
                    {results.data?.map((u) => (
                      <li key={u.id}>
                        <button
                          type="button"
                          className="w-full px-3 py-2 text-left text-sm hover:bg-accent focus-visible:bg-accent focus-visible:outline-none"
                          onClick={() => setPicked(u)}
                        >
                          {u.displayName ?? u.username}{" "}
                          <span className="text-muted-foreground">
                            · {u.username} · {u.source.toUpperCase()}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </div>
          <div className="space-y-1">
            <Label htmlFor={roleId}>Role</Label>
            <Select value={role} onValueChange={(v) => setRole(v as WorkspaceRole)}>
              <SelectTrigger id={roleId} className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {grantable.map((r) => (
                  <SelectItem key={r} value={r}>
                    {ROLE_LABEL[r]} · <span className="text-muted-foreground">{ROLE_HINT[r]}</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" disabled={!picked || add.isPending} title={!picked ? "Choose a user first" : undefined}>
              Add member
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
