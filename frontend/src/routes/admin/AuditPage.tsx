import { useTranslation } from "react-i18next";

import { listAudit } from "@/api/generated/audit/audit";
import { AuditTable } from "@/components/audit/AuditTable";
import { ListPageShell } from "@/components/list/ListPageShell";

export default function AuditPage() {
  const { t } = useTranslation();
  return (
    <ListPageShell
      title={t("Audit log")}
      description={t("Sign-ins, and every change to workspaces, users, members and identity providers.")}
    >
      <AuditTable queryKey={["audit", "system"]} fetchPage={(p) => listAudit(p)} />
    </ListPageShell>
  );
}
