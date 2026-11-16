import { listAudit } from "@/api/generated/audit/audit";
import { AuditTable } from "@/components/audit/AuditTable";
import { ListPageShell } from "@/components/list/ListPageShell";

export default function AuditPage() {
  return (
    <ListPageShell title="Audit log" description="Sign-ins, and every change to workspaces, users, members and identity providers.">
      <AuditTable queryKey={["audit", "system"]} fetchPage={(p) => listAudit(p)} />
    </ListPageShell>
  );
}
