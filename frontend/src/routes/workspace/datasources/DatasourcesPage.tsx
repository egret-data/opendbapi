import { Link, useParams } from "react-router-dom";
import { Database, Plus } from "lucide-react";

import { useListDatasources } from "@/api/generated/datasources/datasources";
import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDate } from "@/lib/format";
import { DIALECT_LABEL } from "./datasourceForm";

export default function DatasourcesPage() {
  const { ws = "" } = useParams();
  const { me } = useAuth();
  const canManage = hasRole(effectiveRole(me, ws) ?? "viewer", "admin");
  const query = useListDatasources(ws);
  const newButton = canManage ? (
    <Button asChild>
      <Link to="new">
        <Plus aria-hidden="true" />
        New datasource
      </Link>
    </Button>
  ) : undefined;

  return (
    <ListPageShell
      title="Datasources"
      description="Databases this workspace's endpoints query. Passwords are stored encrypted and never shown again."
      action={newButton}
    >
      <QueryBoundary query={query} what="datasources">
        {(datasources) =>
          datasources.length === 0 ? (
            <EmptyState
              variant="dashed"
              icon={Database}
              title="No datasources yet"
              description={
                canManage
                  ? "Connect a database to start writing endpoints."
                  : "Ask a workspace admin to connect a database."
              }
              action={newButton}
            />
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Database</TableHead>
                    <TableHead>Address</TableHead>
                    <TableHead>Access</TableHead>
                    <TableHead>Updated</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {datasources.map((d) => (
                    <TableRow key={d.id}>
                      <TableCell className="font-medium">
                        <Link className="hover:underline" to={d.id}>
                          {d.name}
                        </Link>
                        {d.description && <div className="text-xs font-normal text-muted-foreground">{d.description}</div>}
                      </TableCell>
                      <TableCell>{DIALECT_LABEL[d.dialect]}</TableCell>
                      <TableCell className="font-mono text-xs">
                        {d.host}:{d.port}/{d.database}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={d.readOnly ? "neutral" : "highlight"}>{d.readOnly ? "Read only" : "Read & write"}</StatusBadge>
                      </TableCell>
                      <TableCell className="tabular-nums">{formatDate(d.updatedAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )
        }
      </QueryBoundary>
    </ListPageShell>
  );
}
