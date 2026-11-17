import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import type { WorkspaceRole } from "@/api/auth";
import {
  getListAlertChannelsQueryKey,
  useCreateAlertChannel,
  useDeleteAlertChannel,
  useListAlertChannels,
  useTestAlertChannel,
  useUpdateAlertChannel,
} from "@/api/generated/alert-channels/alert-channels";
import type { AlertChannel, SaveAlertChannelKind } from "@/api/generated/model";
import { hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";

const KIND_LABEL: Record<SaveAlertChannelKind, string> = {
  slack: "Slack",
  teams: "Microsoft Teams",
  google_chat: "Google Chat",
  webhook: "Webhook (JSON event)",
};

/** Webhooks told about failing endpoints (spec §13.2). Endpoints opt in on their Settings tab. */
export function AlertsSection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const isAdmin = hasRole(role, "admin");
  const query = useListAlertChannels(ws);
  const qc = useQueryClient();
  const { t } = useTranslation();
  const refresh = () => qc.invalidateQueries({ queryKey: getListAlertChannelsQueryKey(ws) });
  const remove = useDeleteAlertChannel({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const test = useTestAlertChannel({
    mutation: {
      onSuccess: (r) => {
        if (r.ok) toast.success(t("Test message delivered."));
        else toast.error(r.error ?? t("The test message was not delivered."));
        refresh();
      },
      onError: (e) => toast.error(errorMessage(e)),
    },
  });
  const [editing, setEditing] = useState<{ channel?: AlertChannel } | null>(null);

  return (
    <section aria-labelledby="alerts-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="alerts-heading" className="text-lg font-semibold">{t("Alert channels")}</h2>
          <p className="text-sm text-muted-foreground">
            {t("Endpoints with alerts on report server errors here. Alerts name the endpoint, error and request id; never parameter values or data.")}
          </p>
        </div>
        {isAdmin && (
          <Button variant="outline" size="sm" onClick={() => setEditing({})}>
            <Plus aria-hidden="true" />
            {t("Add channel")}
          </Button>
        )}
      </div>
      <QueryBoundary query={query} what="alert channels">
        {(channels) =>
          channels.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("No alert channels: failures only show on the Activity page.")}</p>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("Name")}</TableHead>
                    <TableHead>{t("Kind")}</TableHead>
                    <TableHead>{t("Posts to")}</TableHead>
                    <TableHead>{t("Last delivery")}</TableHead>
                    <TableHead className="w-48"><span className="sr-only">{t("Actions")}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {channels.map((c) => (
                    <TableRow key={c.id}>
                      <TableCell className="font-medium">
                        {c.name}
                        {!c.enabled && <StatusBadge tone="inactive" className="ml-2">{t("Off")}</StatusBadge>}
                      </TableCell>
                      <TableCell>{t(KIND_LABEL[c.kind])}</TableCell>
                      <TableCell className="font-mono text-xs">{c.urlHost}</TableCell>
                      <TableCell className="text-sm">
                        {!c.lastSentAt ? <span className="text-muted-foreground">{t("Never")}</span>
                          : c.lastError ? <span className="text-destructive">{c.lastError}</span>
                            : <span>{t("Delivered {{when}}", { when: formatDateTime(c.lastSentAt) })}</span>}
                      </TableCell>
                      <TableCell className="text-right">
                        {isAdmin && (
                          <>
                            <Button variant="ghost" size="sm" disabled={test.isPending} onClick={() => test.mutate({ ws, id: c.id })}>{t("Test")}</Button>
                            <Button variant="ghost" size="sm" onClick={() => setEditing({ channel: c })}>{t("Edit")}</Button>
                            <Button variant="ghost" size="sm" aria-label={t("Remove {{name}}", { name: c.name })} onClick={() => remove.mutate({ ws, id: c.id })}>
                              {t("Remove")}
                            </Button>
                          </>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )
        }
      </QueryBoundary>
      {editing && <ChannelDialog ws={ws} channel={editing.channel} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />}
    </section>
  );
}

function ChannelDialog({ ws, channel, onClose, onSaved }: { ws: string; channel?: AlertChannel; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(channel?.name ?? "");
  const [kind, setKind] = useState<SaveAlertChannelKind>(channel?.kind ?? "slack");
  const [url, setUrl] = useState("");
  const [enabled, setEnabled] = useState(channel?.enabled ?? true);
  const [error, setError] = useState<string | null>(null);
  const create = useCreateAlertChannel();
  const update = useUpdateAlertChannel();
  const { t } = useTranslation();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const data = { name: name.trim(), kind, url: url.trim() || null, enabled };
    const handlers = { onSuccess: onSaved, onError: (err: unknown) => setError(errorMessage(err)) };
    if (channel) update.mutate({ ws, id: channel.id, data }, handlers);
    else create.mutate({ ws, data }, handlers);
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{channel ? t("Edit alert channel") : t("New alert channel")}</DialogTitle>
            <DialogDescription>{t("The URL is stored encrypted and never shown again; anyone who has it can post to the channel.")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label htmlFor="ch-name">{t("Name")}</Label>
            <Input id="ch-name" required maxLength={120} value={name} placeholder="data-oncall" onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="ch-kind">{t("Kind")}</Label>
            <Select value={kind} onValueChange={(v) => setKind(v as SaveAlertChannelKind)}>
              <SelectTrigger id="ch-kind" className="w-60"><SelectValue /></SelectTrigger>
              <SelectContent>
                {(Object.keys(KIND_LABEL) as SaveAlertChannelKind[]).map((k) => <SelectItem key={k} value={k}>{t(KIND_LABEL[k])}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="ch-url">{t("Webhook URL")}</Label>
            <Input id="ch-url" type="url" required={!channel} className="font-mono text-xs" value={url}
              placeholder={channel ? t("Unchanged ({{host}})", { host: channel.urlHost }) : "https://hooks.slack.com/services/…"} onChange={(e) => setUrl(e.target.value)} />
            <p className="text-xs text-muted-foreground">{t("Or type secret:NAME to read the URL from a managed secret.")}</p>
          </div>
          <div className="flex items-center gap-2">
            <Switch id="ch-enabled" checked={enabled} onCheckedChange={setEnabled} />
            <Label htmlFor="ch-enabled">{t("Send alerts")}</Label>
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
