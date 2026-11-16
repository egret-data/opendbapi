import { useId, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { Building2, Plus } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { useMe } from "@/api/auth";
import {
  getListWorkspacesQueryKey,
  useArchiveWorkspace,
  useCreateWorkspace,
  useListWorkspaces,
  useRestoreWorkspace,
} from "@/api/generated/workspaces/workspaces";
import { useListUsers } from "@/api/generated/users/users";
import type { WorkspaceView } from "@/api/generated/model";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { RowActionsMenu } from "@/components/list/RowActionsMenu";
import { SummaryStrip } from "@/components/list/SummaryStrip";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenuItem } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDate } from "@/lib/format";

type Filter = "all" | "active" | "archived";

/** `Sales Analytics` → `sales-analytics`; a suggestion the author can edit. */
export function slugify(name: string): string {
  return name
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/đ/gi, "d")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 63);
}

export default function WorkspacesPage() {
  const query = useListWorkspaces();
  const [filter, setFilter] = useState<Filter>("all");
  const [creating, setCreating] = useState(false);
  const [archiving, setArchiving] = useState<WorkspaceView | null>(null);
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: getListWorkspacesQueryKey() });
  const archive = useArchiveWorkspace({ mutation: { onSuccess: invalidate } });
  const restore = useRestoreWorkspace({ mutation: { onSuccess: invalidate } });

  return (
    <ListPageShell
      title="Workspaces"
      description="Each workspace has its own endpoints, datasources, clients and members."
      action={
        <Button onClick={() => setCreating(true)}>
          <Plus aria-hidden="true" />
          New workspace
        </Button>
      }
    >
      <QueryBoundary query={query} what="workspaces">
        {(workspaces) => {
          const active = workspaces.filter((w) => w.status === "active").length;
          const shown = workspaces.filter((w) => filter === "all" || w.status === filter);
          if (workspaces.length === 0) {
            return (
              <EmptyState
                variant="dashed"
                icon={Building2}
                title="No workspaces yet"
                description="Create the first workspace and choose its owner."
                action={<Button onClick={() => setCreating(true)}>New workspace</Button>}
              />
            );
          }
          return (
            <div className="space-y-3">
              <SummaryStrip
                items={[
                  { label: "workspaces", singular: "workspace", value: workspaces.length, active: filter === "all", onClick: () => setFilter("all") },
                  { label: "active", value: active, active: filter === "active", onClick: () => setFilter("active") },
                  { label: "archived", value: workspaces.length - active, active: filter === "archived", onClick: () => setFilter("archived") },
                ]}
              />
              {shown.length === 0 ? (
                <EmptyState title={`No ${filter} workspaces`} description="Choose another filter above." />
              ) : (
                <div className="overflow-x-auto rounded-md border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Name</TableHead>
                        <TableHead>Slug</TableHead>
                        <TableHead>Status</TableHead>
                        <TableHead>Created</TableHead>
                        <TableHead className="w-10"><span className="sr-only">Actions</span></TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {shown.map((w) => (
                        <TableRow key={w.id}>
                          <TableCell className="font-medium">
                            {w.status === "active" ? (
                              <Link className="hover:underline" to={`/w/${w.slug}/settings`}>
                                {w.name}
                              </Link>
                            ) : (
                              w.name
                            )}
                          </TableCell>
                          <TableCell className="font-mono text-xs">{w.slug}</TableCell>
                          <TableCell>
                            <StatusBadge tone={w.status === "active" ? "success" : "inactive"}>
                              {w.status === "active" ? "Active" : "Archived"}
                            </StatusBadge>
                          </TableCell>
                          <TableCell className="tabular-nums">{formatDate(w.createdAt)}</TableCell>
                          <TableCell>
                            <RowActionsMenu label={`Actions for ${w.name}`}>
                              {w.status === "active" ? (
                                <>
                                  <DropdownMenuItem asChild>
                                    <Link to={`/w/${w.slug}/settings`}>Open settings</Link>
                                  </DropdownMenuItem>
                                  <DropdownMenuItem onSelect={() => setArchiving(w)}>Archive…</DropdownMenuItem>
                                </>
                              ) : (
                                <DropdownMenuItem
                                  onSelect={() =>
                                    restore.mutate(
                                      { ws: w.slug },
                                      {
                                        onSuccess: () => toast.success(`${w.name} is active again.`),
                                        onError: (e) => toast.error(errorMessage(e)),
                                      },
                                    )
                                  }
                                >
                                  Restore
                                </DropdownMenuItem>
                              )}
                            </RowActionsMenu>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
          );
        }}
      </QueryBoundary>

      <CreateWorkspaceDialog open={creating} onOpenChange={setCreating} onCreated={invalidate} />
      <ConfirmDialog
        open={archiving !== null}
        onOpenChange={(open) => !open && setArchiving(null)}
        title={`Archive ${archiving?.name ?? "workspace"}?`}
        description={
          <>
            <p>Its endpoints stop answering immediately and members no longer see it.</p>
            <p>You can restore it later from this page.</p>
          </>
        }
        confirmLabel="Archive"
        destructive
        pending={archive.isPending}
        onConfirm={() =>
          archiving &&
          archive.mutate(
            { ws: archiving.slug },
            {
              onSuccess: () => {
                toast.success(`${archiving.name} archived.`);
                setArchiving(null);
              },
              onError: (e) => toast.error(errorMessage(e)),
            },
          )
        }
      />
    </ListPageShell>
  );
}

const ME = "__me__";

function CreateWorkspaceDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: () => void;
}) {
  const { data: me } = useMe();
  const users = useListUsers({ query: { enabled: open } });
  const create = useCreateWorkspace();
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [owner, setOwner] = useState(ME);
  const [error, setError] = useState<string | null>(null);
  const nameId = useId();
  const slugId = useId();
  const ownerId = useId();

  const reset = () => {
    setName("");
    setSlug("");
    setSlugEdited(false);
    setOwner(ME);
    setError(null);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate(
      { data: { name, slug, ownerUserId: owner === ME ? null : owner } },
      {
        onSuccess: (created) => {
          toast.success(`Workspace ${created.name} created.`);
          onCreated();
          reset();
          onOpenChange(false);
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>New workspace</DialogTitle>
            <DialogDescription>The slug is part of every endpoint URL and cannot change later.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor={nameId}>Name</Label>
            <Input
              id={nameId}
              value={name}
              required
              maxLength={120}
              onChange={(e) => {
                setName(e.target.value);
                if (!slugEdited) setSlug(slugify(e.target.value));
              }}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor={slugId}>Slug</Label>
            <Input
              id={slugId}
              value={slug}
              required
              pattern="[a-z0-9][a-z0-9-]{1,62}"
              title="2-63 lowercase letters, digits or hyphens"
              className="font-mono"
              onChange={(e) => {
                setSlugEdited(true);
                setSlug(e.target.value);
              }}
            />
            <p className="text-xs text-muted-foreground">
              Endpoints will live under <span className="font-mono">/api/{slug || "slug"}/…</span>
            </p>
          </div>
          <div className="space-y-1">
            <Label htmlFor={ownerId}>First owner</Label>
            <Select value={owner} onValueChange={setOwner}>
              <SelectTrigger id={ownerId} className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ME}>Me ({me?.displayName ?? me?.username})</SelectItem>
                {(users.data ?? [])
                  .filter((u) => u.id !== me?.id && !u.disabled)
                  .map((u) => (
                    <SelectItem key={u.id} value={u.id}>
                      {u.displayName ?? u.username} · {u.source.toUpperCase()}
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
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              Create workspace
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
