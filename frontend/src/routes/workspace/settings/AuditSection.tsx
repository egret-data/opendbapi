import { listWorkspaceAudit } from "@/api/generated/audit/audit";
import { AuditTable } from "@/components/audit/AuditTable";

export function AuditSection({ ws }: { ws: string }) {
  return <AuditTable queryKey={["audit", "workspace", ws]} fetchPage={(p) => listWorkspaceAudit(ws, p)} />;
}
