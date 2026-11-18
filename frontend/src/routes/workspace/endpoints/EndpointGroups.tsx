import { type FormEvent, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { getListEndpointsQueryKey, moveEndpoint } from "@/api/generated/endpoints/endpoints";
import {
  getListGroupsQueryKey,
  useCreateGroup,
  useDeleteGroup,
  useRenameGroup,
} from "@/api/generated/groups/groups";
import type { ApiGroup, EndpointSummary } from "@/api/generated/model";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { errorMessage } from "@/lib/errors";

/** Endpoints to move and where; `null` takes them out of every group. */
export type MoveRequest = { ids: string[]; to: string | null };

const NONE = "__none__";
const NEW = "__new__";

/**
 * Moving endpoints between groups, the way folders work in Egret: one level, shared by the workspace.
 * A group can carry grants, so a move that changes who can call an endpoint asks first.
 */
export function useEndpointMoves(ws: string, endpoints: EndpointSummary[], groups: ApiGroup[]) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [pending, setPending] = useState<MoveRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const byId = new Map(groups.map((g) => [g.id, g]));
  const name = (id: string | null) => (id ? byId.get(id)?.name ?? "" : "");

  const run = async ({ ids, to }: MoveRequest, created?: ApiGroup) => {
    setBusy(true);
    try {
      await Promise.all(ids.map((id) => moveEndpoint(ws, id, { groupId: to })));
      const target = created?.name ?? name(to);
      toast.success(
        to === null
          ? t("Moved out of its group.")
          : ids.length === 1
            ? t("Moved to {{group}}.", { group: target })
            : t("Moved {{n}} endpoints to {{group}}.", { n: ids.length, group: target }),
      );
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setBusy(false);
      setPending(null);
      await Promise.all([
        qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) }),
        qc.invalidateQueries({ queryKey: getListGroupsQueryKey(ws) }),
      ]);
    }
  };

  /** What the move changes for service accounts granted a whole group. */
  const effects = ({ ids, to }: MoveRequest) => {
    const moving = endpoints.filter((e) => ids.includes(e.id) && (e.groupId ?? null) !== to);
    const losing = [...new Set(moving.map((e) => e.groupId).filter((g): g is string => !!g))]
      .map((g) => byId.get(g))
      .filter((g): g is ApiGroup => !!g && g.grantCount > 0);
    const gaining = to && moving.length > 0 ? byId.get(to) : undefined;
    return { moving, losing, gaining: gaining && gaining.grantCount > 0 ? gaining : undefined };
  };

  const request = (move: MoveRequest, created?: ApiGroup) => {
    const { moving, losing, gaining } = effects(move);
    if (moving.length === 0) return;
    const real = { ids: moving.map((e) => e.id), to: move.to };
    if (losing.length > 0 || gaining) setPending(real);
    else void run(real, created);
  };

  const effect = pending ? effects(pending) : null;
  const confirm = (
    <ConfirmDialog
      open={pending !== null}
      onOpenChange={(o) => !o && setPending(null)}
      title={t("Change who can call these endpoints?")}
      description={
        <>
          <p>{t("Service accounts granted a group can call every endpoint in it. The move takes effect at once:")}</p>
          {effect?.losing.map((g) => (
            <p key={g.id}>
              {t("Service accounts granted {{group}} lose access ({{n}}).", { n: g.grantCount, group: g.name })}
            </p>
          ))}
          {effect?.gaining && (
            <p>
              {t("Service accounts granted {{group}} gain access ({{n}}).", { n: effect.gaining.grantCount, group: effect.gaining.name })}
            </p>
          )}
        </>
      }
      confirmLabel={t("Move")}
      pending={busy}
      onConfirm={() => pending && void run(pending)}
    />
  );

  return { request, confirm };
}

/** Name a new group, or rename one. With `onCreated`, the caller moves endpoints into it. */
export function GroupNameDialog({
  ws,
  open,
  onOpenChange,
  group,
  onCreated,
}: {
  ws: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  group?: ApiGroup;
  onCreated?: (group: ApiGroup) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <GroupNameForm ws={ws} group={group} onCreated={onCreated} close={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

/** Mounted only while the dialog is open, so it starts from the group's current name. */
function GroupNameForm({ ws, group, onCreated, close }: { ws: string; group?: ApiGroup; onCreated?: (group: ApiGroup) => void; close: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [name, setName] = useState(group?.name ?? "");
  const done = () => qc.invalidateQueries({ queryKey: getListGroupsQueryKey(ws) });
  const create = useCreateGroup({ mutation: { onError: (e) => toast.error(errorMessage(e)) } });
  const rename = useRenameGroup({ mutation: { onError: (e) => toast.error(errorMessage(e)) } });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const value = name.trim();
    if (!value) return;
    if (group) {
      rename.mutate({ ws, id: group.id, data: { name: value } }, {
        onSuccess: () => {
          void done();
          toast.success(t("Group renamed."));
          close();
        },
      });
    } else {
      create.mutate({ ws, data: { name: value } }, {
        onSuccess: (created) => {
          void done();
          close();
          if (onCreated) onCreated(created);
          else toast.success(t("Group created."));
        },
      });
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <DialogHeader>
        <DialogTitle>{group ? t("Rename group") : t("New group")}</DialogTitle>
        <DialogDescription>
          {t("Groups are folders for the list; they are not part of any URL. A grant on a group covers every endpoint in it.")}
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-2">
        <Label htmlFor="group-name">{t("Name")}</Label>
        <Input id="group-name" required maxLength={120} value={name} placeholder="Reports" onChange={(e) => setName(e.target.value)} />
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={close}>{t("Cancel")}</Button>
        <Button type="submit" disabled={!name.trim() || create.isPending || rename.isPending}>
          {group ? t("Rename") : t("Create")}
        </Button>
      </DialogFooter>
    </form>
  );
}

/** Delete a group: its endpoints stay, without a group; grants on it are revoked. */
export function DeleteGroupDialog({ ws, group, onOpenChange }: { ws: string; group: ApiGroup | null; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const remove = useDeleteGroup({
    mutation: {
      onSuccess: async () => {
        toast.success(t("Group deleted."));
        onOpenChange(false);
        await Promise.all([
          qc.invalidateQueries({ queryKey: getListGroupsQueryKey(ws) }),
          qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) }),
        ]);
      },
      onError: (e) => toast.error(errorMessage(e)),
    },
  });
  return (
    <ConfirmDialog
      open={group !== null}
      onOpenChange={onOpenChange}
      title={t("Delete group {{name}}?", { name: group?.name ?? "" })}
      description={
        <>
          <p>{t("Its endpoints are not deleted; they move out of the group.")}</p>
          {group && group.grantCount > 0 && (
            <p className="font-medium">
              {t("Service accounts granted this group lose access to its endpoints ({{n}}).", { n: group.grantCount })}
            </p>
          )}
        </>
      }
      confirmLabel={t("Delete group")}
      destructive
      pending={remove.isPending}
      onConfirm={() => group && remove.mutate({ ws, id: group.id })}
    />
  );
}

/** Pick where selected endpoints go: a group, no group, or a new group. */
export function MoveToGroupDialog({
  open,
  onOpenChange,
  count,
  groups,
  onMove,
  onNewGroup,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  count: number;
  groups: ApiGroup[];
  onMove: (to: string | null) => void;
  onNewGroup: () => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <MoveToGroupForm count={count} groups={groups} onMove={onMove} onNewGroup={onNewGroup} close={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

function MoveToGroupForm({ count, groups, onMove, onNewGroup, close }: {
  count: number;
  groups: ApiGroup[];
  onMove: (to: string | null) => void;
  onNewGroup: () => void;
  close: () => void;
}) {
  const { t } = useTranslation();
  const [choice, setChoice] = useState(groups[0]?.id ?? NEW);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    close();
    if (choice === NEW) onNewGroup();
    else onMove(choice === NONE ? null : choice);
  };
  return (
    <form onSubmit={submit} className="space-y-4">
      <DialogHeader>
        <DialogTitle>{t("Move to group")}</DialogTitle>
        <DialogDescription>{t("Selected endpoints: {{n}}. Choose a group.", { n: count })}</DialogDescription>
      </DialogHeader>
      <RadioGroup value={choice} onValueChange={setChoice} aria-label={t("Group")} className="max-h-72 overflow-y-auto">
        {groups.map((g) => (
          <div key={g.id} className="flex items-center gap-2">
            <RadioGroupItem id={`move-${g.id}`} value={g.id} />
            <Label htmlFor={`move-${g.id}`}>{g.name}</Label>
          </div>
        ))}
        <div className="flex items-center gap-2">
          <RadioGroupItem id="move-none" value={NONE} />
          <Label htmlFor="move-none">{t("No group")}</Label>
        </div>
        <div className="flex items-center gap-2">
          <RadioGroupItem id="move-new" value={NEW} />
          <Label htmlFor="move-new">{t("New group…")}</Label>
        </div>
      </RadioGroup>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={close}>{t("Cancel")}</Button>
        <Button type="submit">{t("Move")}</Button>
      </DialogFooter>
    </form>
  );
}
