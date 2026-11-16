import { useId, useState, type FormEvent } from "react";
import { Plus, Users } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { useMe } from "@/api/auth";
import {
  getListUsersQueryKey,
  useCreateUser,
  useListUsers,
  useResetUserPassword,
  useUpdateUser,
} from "@/api/generated/users/users";
import type { UserView } from "@/api/generated/model";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { RowActionsMenu } from "@/components/list/RowActionsMenu";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenuItem } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";

const MIN_PASSWORD = 12;

function userStatus(u: UserView) {
  if (u.disabled) return <StatusBadge tone="inactive">Disabled</StatusBadge>;
  if (u.locked) return <StatusBadge tone="highlight">Locked</StatusBadge>;
  return <StatusBadge tone="success">Active</StatusBadge>;
}

export default function UsersPage() {
  const query = useListUsers();
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<UserView | null>(null);
  const [disabling, setDisabling] = useState<UserView | null>(null);
  const update = useUpdateUser({
    mutation: { onSuccess: () => qc.invalidateQueries({ queryKey: getListUsersQueryKey() }) },
  });

  const change = (u: UserView, data: { systemAdmin?: boolean; disabled?: boolean }, done: string) =>
    update.mutate(
      { id: u.id, data },
      {
        onSuccess: () => {
          toast.success(done);
          setDisabling(null);
        },
        onError: (e) => toast.error(errorMessage(e)),
      },
    );

  return (
    <ListPageShell
      title="Users"
      description="Local accounts are created here. LDAP and SSO users appear after their first sign-in."
      toolbar={
        <Input
          type="search"
          aria-label="Search users"
          placeholder="Search by name, username or email"
          className="w-72"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      }
      action={
        <Button onClick={() => setCreating(true)}>
          <Plus aria-hidden="true" />
          New local user
        </Button>
      }
    >
      <QueryBoundary query={query} what="users">
        {(users) => {
          const q = search.trim().toLowerCase();
          const shown = users.filter((u) =>
            [u.username, u.displayName ?? "", u.email ?? ""].some((v) => v.toLowerCase().includes(q)),
          );
          if (shown.length === 0) {
            return q ? (
              <EmptyState title="No users match your search" description="Try another name, username or email." />
            ) : (
              <EmptyState variant="dashed" icon={Users} title="No users" />
            );
          }
          return (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Sign-in</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Last sign-in</TableHead>
                    <TableHead className="w-10"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {shown.map((u) => {
                    const self = u.id === me?.id;
                    return (
                      <TableRow key={u.id}>
                        <TableCell>
                          <div className="font-medium">
                            {u.displayName ?? u.username}
                            {u.systemAdmin && (
                              <StatusBadge tone="primary" className="ml-2">
                                System admin
                              </StatusBadge>
                            )}
                          </div>
                          <div className="text-xs text-muted-foreground">
                            {u.username}
                            {u.email ? ` · ${u.email}` : ""}
                          </div>
                        </TableCell>
                        <TableCell>{u.source.toUpperCase()}</TableCell>
                        <TableCell>{userStatus(u)}</TableCell>
                        <TableCell className="tabular-nums">{formatDateTime(u.lastLoginAt)}</TableCell>
                        <TableCell>
                          <RowActionsMenu label={`Actions for ${u.username}`}>
                            {u.systemAdmin ? (
                              <DropdownMenuItem
                                disabled={self}
                                title={self ? "You cannot remove your own system admin role" : undefined}
                                onSelect={() => change(u, { systemAdmin: false }, `${u.username} is no longer a system admin.`)}
                              >
                                Remove system admin
                              </DropdownMenuItem>
                            ) : (
                              <DropdownMenuItem
                                onSelect={() => change(u, { systemAdmin: true }, `${u.username} is now a system admin.`)}
                              >
                                Make system admin
                              </DropdownMenuItem>
                            )}
                            {u.source === "local" && (
                              <DropdownMenuItem onSelect={() => setResetting(u)}>Reset password…</DropdownMenuItem>
                            )}
                            {u.disabled ? (
                              <DropdownMenuItem onSelect={() => change(u, { disabled: false }, `${u.username} can sign in again.`)}>
                                Enable
                              </DropdownMenuItem>
                            ) : (
                              <DropdownMenuItem
                                disabled={self}
                                title={self ? "You cannot disable yourself" : undefined}
                                onSelect={() => setDisabling(u)}
                              >
                                Disable…
                              </DropdownMenuItem>
                            )}
                          </RowActionsMenu>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          );
        }}
      </QueryBoundary>

      <CreateUserDialog open={creating} onOpenChange={setCreating} />
      <ResetPasswordDialog user={resetting} onClose={() => setResetting(null)} />
      <ConfirmDialog
        open={disabling !== null}
        onOpenChange={(open) => !open && setDisabling(null)}
        title={`Disable ${disabling?.username ?? "user"}?`}
        description={<p>They are signed out at once and cannot sign in until enabled again.</p>}
        confirmLabel="Disable"
        destructive
        pending={update.isPending}
        onConfirm={() => disabling && change(disabling, { disabled: true }, `${disabling.username} disabled.`)}
      />
    </ListPageShell>
  );
}

function CreateUserDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const qc = useQueryClient();
  const create = useCreateUser();
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [systemAdmin, setSystemAdmin] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ids = { username: useId(), displayName: useId(), email: useId(), password: useId(), admin: useId() };

  const close = () => {
    setUsername("");
    setDisplayName("");
    setEmail("");
    setPassword("");
    setSystemAdmin(false);
    setError(null);
    onOpenChange(false);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate(
      { data: { username, displayName: displayName || null, email: email || null, password, systemAdmin } },
      {
        onSuccess: (u) => {
          toast.success(`User ${u.username} created. Share the password with them securely.`);
          qc.invalidateQueries({ queryKey: getListUsersQueryKey() });
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
            <DialogTitle>New local user</DialogTitle>
            <DialogDescription>For people who do not sign in through LDAP or SSO.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor={ids.username}>Username</Label>
            <Input id={ids.username} value={username} required maxLength={64} autoComplete="off"
              onChange={(e) => setUsername(e.target.value)} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1">
              <Label htmlFor={ids.displayName}>Display name</Label>
              <Input id={ids.displayName} value={displayName} maxLength={120}
                onChange={(e) => setDisplayName(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor={ids.email}>Email</Label>
              <Input id={ids.email} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
          </div>
          <div className="space-y-1">
            <Label htmlFor={ids.password}>Initial password</Label>
            <Input id={ids.password} type="password" value={password} required minLength={MIN_PASSWORD}
              autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
            <p className="text-xs text-muted-foreground">At least {MIN_PASSWORD} characters.</p>
          </div>
          <div className="flex items-center gap-2">
            <Checkbox id={ids.admin} checked={systemAdmin} onCheckedChange={(v) => setSystemAdmin(v === true)} />
            <Label htmlFor={ids.admin}>System admin</Label>
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
            <Button type="submit" disabled={create.isPending}>
              Create user
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function ResetPasswordDialog({ user, onClose }: { user: UserView | null; onClose: () => void }) {
  const reset = useResetUserPassword();
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const id = useId();

  const close = () => {
    setPassword("");
    setError(null);
    onClose();
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!user) return;
    reset.mutate(
      { id: user.id, data: { password } },
      {
        onSuccess: () => {
          toast.success(`Password for ${user.username} reset. Their lockout is cleared too.`);
          close();
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <Dialog open={user !== null} onOpenChange={(open) => !open && close()}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>Reset password for {user?.username}</DialogTitle>
            <DialogDescription>Share the new password with them securely.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor={id}>New password</Label>
            <Input id={id} type="password" value={password} required minLength={MIN_PASSWORD}
              autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
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
            <Button type="submit" disabled={reset.isPending}>
              Reset password
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
