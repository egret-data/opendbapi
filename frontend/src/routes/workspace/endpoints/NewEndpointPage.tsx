import { useId, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { useListDatasources } from "@/api/generated/datasources/datasources";
import { getListEndpointsQueryKey, useCreateEndpoint } from "@/api/generated/endpoints/endpoints";
import { useListGroups } from "@/api/generated/groups/groups";
import { useSystemInfo } from "@/api/generated/system/system";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { DetailHeader } from "@/components/layout/DetailHeader";
import { PageContainer } from "@/components/layout/PageContainer";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { errorMessage } from "@/lib/errors";
import { METHODS, type Method } from "./endpointForm";

const NONE = "__none__";

/** Collects what an endpoint cannot exist without, creates it, and opens its editor (spec §17.3). */
export default function NewEndpointPage() {
  const { ws = "" } = useParams();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const qc = useQueryClient();
  const create = useCreateEndpoint();
  const datasources = useListDatasources(ws);
  const groups = useListGroups(ws);
  const info = useSystemInfo();
  const [name, setName] = useState("");
  const [method, setMethod] = useState<Method>("GET");
  const [path, setPath] = useState("");
  const [access, setAccess] = useState<"private" | "public">("private");
  const [datasourceId, setDatasourceId] = useState("");
  const [groupId, setGroupId] = useState(NONE);
  const [error, setError] = useState<string | null>(null);
  const ids = { name: useId(), method: useId(), path: useId(), ds: useId(), group: useId() };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate(
      { ws, data: { name: name.trim(), method, path: path.trim().replace(/^\/+/, ""), access, datasourceId,
        groupId: groupId === NONE ? null : groupId } },
      {
        onSuccess: (created) => {
          qc.invalidateQueries({ queryKey: getListEndpointsQueryKey(ws) });
          navigate(`/w/${ws}/endpoints/${created.summary.id}`, { replace: true });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  const noDatasource = datasources.data?.length === 0;

  return (
    <PageContainer>
      <DetailHeader back={{ to: `/w/${ws}/endpoints`, label: t("Endpoints") }} title={t("New endpoint")} />
      <form onSubmit={submit} className="space-y-4">
        <ConfigTable valueClassName="w-96" detailsClassName="max-w-prose">
          <ConfigTableGroup label={t("Endpoint")} />
          <ConfigTableRow label={t("Name")} htmlFor={ids.name} details={t("How it is listed")}>
            <Input id={ids.name} value={name} required maxLength={120} onChange={(e) => setName(e.target.value)} />
          </ConfigTableRow>
          <ConfigTableRow label={t("Method")} htmlFor={ids.method} details={t("GET reads; POST, PUT and DELETE may change data")}>
            <Select value={method} onValueChange={(v) => setMethod(v as Method)}>
              <SelectTrigger id={ids.method} className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                {METHODS.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}
              </SelectContent>
            </Select>
          </ConfigTableRow>
          <ConfigTableRow label={t("Path")} htmlFor={ids.path} details={t("Segments of letters, digits, _ and -; {name} for a path parameter. Fixed once live.")}>
            <div className="flex items-center gap-1">
              <span className="shrink-0 font-mono text-xs text-muted-foreground">
                {info.data ? new URL(info.data.servingBaseUrl).pathname : "/api"}/{ws}/
              </span>
              <Input id={ids.path} className="font-mono text-xs" value={path} required placeholder="v1/customers/{id}"
                pattern="[A-Za-z0-9_\-{}]+(/[A-Za-z0-9_\-{}]+)*" title={t("Segments of letters, digits, _ - or {param}, separated by /")}
                onChange={(e) => setPath(e.target.value)} />
            </div>
          </ConfigTableRow>
          <ConfigTableRow label={t("Access")} details={t("Private needs a client token; public answers anyone who can reach the server")}>
            <RadioGroup value={access} onValueChange={(v) => setAccess(v as "private" | "public")} className="flex gap-4" aria-label={t("Access")}>
              <div className="flex items-center gap-2"><RadioGroupItem id="acc-private" value="private" /><Label htmlFor="acc-private">{t("Private")}</Label></div>
              <div className="flex items-center gap-2"><RadioGroupItem id="acc-public" value="public" /><Label htmlFor="acc-public">{t("Public")}</Label></div>
            </RadioGroup>
          </ConfigTableRow>
          <ConfigTableRow label={t("Datasource")} htmlFor={ids.ds} details={noDatasource ? t("Connect a datasource first") : t("The database the query runs on")}>
            <Select value={datasourceId} onValueChange={setDatasourceId} disabled={noDatasource}>
              <SelectTrigger id={ids.ds} className="w-full"><SelectValue placeholder={t("Choose a datasource")} /></SelectTrigger>
              <SelectContent>
                {(datasources.data ?? []).map((d) => <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </ConfigTableRow>
          <ConfigTableRow label={t("Group")} htmlFor={ids.group} details={t("Optional folder; not part of the URL")}>
            <Select value={groupId} onValueChange={setGroupId}>
              <SelectTrigger id={ids.group} className="w-full"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NONE}>{t("No group")}</SelectItem>
                {(groups.data ?? []).map((g) => <SelectItem key={g.id} value={g.id}>{g.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </ConfigTableRow>
        </ConfigTable>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        <div className="flex gap-2">
          <Button type="submit" disabled={create.isPending || !datasourceId}
            title={!datasourceId ? t("Choose a datasource first") : undefined}>
            {t("Create and edit query")}
          </Button>
          <Button asChild variant="outline"><Link to={`/w/${ws}/endpoints`}>{t("Cancel")}</Link></Button>
        </div>
        {noDatasource && (
          <p className="text-sm text-muted-foreground">
            {t("This workspace has no datasource yet.")} <Link className="underline" to={`/w/${ws}/datasources`}>{t("Datasources")}</Link>
          </p>
        )}
      </form>
    </PageContainer>
  );
}
