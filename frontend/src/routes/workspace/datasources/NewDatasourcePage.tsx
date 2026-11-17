import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { getListDatasourcesQueryKey, useCreateDatasource } from "@/api/generated/datasources/datasources";
import { DetailHeader } from "@/components/layout/DetailHeader";
import { PageContainer } from "@/components/layout/PageContainer";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/errors";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";
import { DatasourceFields, TestConnection } from "./DatasourceFields";
import { emptyForm, toRequest } from "./datasourceForm";

export default function NewDatasourcePage() {
  const { ws = "" } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { t } = useTranslation();
  const create = useCreateDatasource();
  const [form, setForm] = useState(emptyForm);
  const [error, setError] = useState<string | null>(null);
  useUnsavedChangesWarning(JSON.stringify(form) !== JSON.stringify(emptyForm()) && !create.isSuccess);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate(
      { ws, data: toRequest(form) },
      {
        onSuccess: (created) => {
          qc.invalidateQueries({ queryKey: getListDatasourcesQueryKey(ws) });
          navigate(`/w/${ws}/datasources/${created.id}`, { replace: true });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <PageContainer>
      <DetailHeader back={{ to: `/w/${ws}/datasources`, label: t("Datasources") }} title={t("New datasource")} />
      <form onSubmit={submit} className="space-y-4">
        <DatasourceFields ws={ws} form={form} onChange={setForm} />
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <div className="flex flex-wrap items-start gap-2">
          <Button type="submit" disabled={create.isPending}>
            {t("Create datasource")}
          </Button>
          <Button asChild variant="outline">
            <Link to={`/w/${ws}/datasources`}>{t("Cancel")}</Link>
          </Button>
          <div className="ml-auto">
            <TestConnection ws={ws} form={form} />
          </div>
        </div>
      </form>
    </PageContainer>
  );
}
