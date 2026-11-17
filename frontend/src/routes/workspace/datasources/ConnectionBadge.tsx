import type { LastTest } from "@/api/generated/model";
import { StatusBadge } from "@/components/ui/status-badge";
import { formatDateTime } from "@/lib/format";

/** The last connection test of a saved datasource; the server's message is the tooltip. */
export function ConnectionBadge({ test }: { test?: LastTest | null }) {
  if (!test) return <span className="text-sm text-muted-foreground">Not tested</span>;
  const when = formatDateTime(test.at);
  return (
    <span title={`${test.message ?? ""} (${when})`}>
      <StatusBadge tone={test.ok ? "success" : "inactive"}>{test.ok ? "Connected" : "Failed"}</StatusBadge>
    </span>
  );
}
