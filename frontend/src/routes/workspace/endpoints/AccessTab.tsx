import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import {
  getListEndpointAccessQueryKey,
  useGrantAccess,
  useListEndpointAccess,
  useListServiceAccounts,
  useRevokeAccess,
} from "@/api/generated/service-accounts/service-accounts";
import { QueryBoundary } from "@/components/QueryBoundary";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/errors";

import { DataPoliciesSection } from "../datapolicies/DataPoliciesSection";

/** Who may call this endpoint (spec §17.4 Access tab). */
export function AccessTab({ ws, endpointId, datasourceId, isPublic, isAdmin }: {
  ws: string;
  endpointId: string;
  datasourceId?: string | null;
  isPublic: boolean;
  isAdmin: boolean;
}) {
  const query = useListEndpointAccess(ws, endpointId);
  const { t } = useTranslation();
  const accounts = useListServiceAccounts(ws);
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: getListEndpointAccessQueryKey(ws, endpointId) });
  const grant = useGrantAccess({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const revoke = useRevokeAccess({ mutation: { onSuccess: refresh, onError: (e) => toast.error(errorMessage(e)) } });
  const [picked, setPicked] = useState("");

  return (
    <div className="space-y-4">
      {isPublic && (
        <p className="rounded-md border bg-muted/40 p-3 text-sm">
          {t("This endpoint is")} <strong>{t("public")}</strong>
          {t(": anyone who can reach the server may call it, without a token. Grants apply only once it is private.")}
        </p>
      )}
      <QueryBoundary query={query} what={t("access")}>
        {(rows) => {
          const present = new Set(rows.map((r) => r.serviceAccountId));
          return (
            <div className="space-y-3">
              {isAdmin && (
                <div className="flex flex-wrap items-center gap-2">
                  <Select value={picked} onValueChange={setPicked}>
                    <SelectTrigger className="w-64" aria-label={t("Service account to grant")}><SelectValue placeholder={t("Choose a service account")} /></SelectTrigger>
                    <SelectContent>
                      {(accounts.data ?? []).filter((a) => !present.has(a.id)).map((a) => (
                        <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button size="sm" disabled={!picked || grant.isPending}
                    onClick={() => grant.mutate({ ws, id: picked, data: { resourceType: "endpoint", resourceId: endpointId } },
                      { onSuccess: () => setPicked("") })}>
                    {t("Grant")}
                  </Button>
                  <Link className="text-sm underline" to={`/w/${ws}/clients`}>{t("Manage service accounts")}</Link>
                </div>
              )}
              {rows.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("No service account may call this endpoint yet.")}</p>
              ) : (
                <div className="overflow-x-auto rounded-md border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t("Service account")}</TableHead>
                        <TableHead>{t("Through")}</TableHead>
                        <TableHead className="w-24"><span className="sr-only">{t("Actions")}</span></TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {rows.map((r) => (
                        <TableRow key={r.serviceAccountId}>
                          <TableCell>
                            <Link className="font-medium hover:underline" to={`/w/${ws}/clients/${r.serviceAccountId}`}>{r.name}</Link>
                            {r.disabled && <StatusBadge tone="inactive" className="ml-2">{t("Disabled")}</StatusBadge>}
                          </TableCell>
                          <TableCell>{r.via === "group" ? t("Its group") : t("This endpoint")}</TableCell>
                          <TableCell className="text-right">
                            {isAdmin && r.via === "endpoint" && (
                              <Button variant="ghost" size="sm"
                                onClick={() => revoke.mutate({ ws, id: r.serviceAccountId, resourceType: "endpoint", resourceId: endpointId })}>
                                {t("Remove")}
                              </Button>
                            )}
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
      <DataPoliciesSection ws={ws} scope={{ kind: "endpoint", endpointId, datasourceId }} isAdmin={isAdmin} />
    </div>
  );
}
