import { Link } from "react-router-dom";
import { Fingerprint, Plus } from "lucide-react";

import { useListIdentityProviders } from "@/api/generated/identity-providers/identity-providers";
import { QueryBoundary } from "@/components/QueryBoundary";
import { EmptyState } from "@/components/list/EmptyState";
import { ListPageShell } from "@/components/list/ListPageShell";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDate } from "@/lib/format";

export default function IdentityProvidersPage() {
  const query = useListIdentityProviders();
  const newButton = (
    <Button asChild>
      <Link to="new">
        <Plus aria-hidden="true" />
        New provider
      </Link>
    </Button>
  );
  return (
    <ListPageShell
      title="Identity providers"
      description="LDAP directories and SSO (OIDC) issuers people can sign in through, and how their groups map to roles."
      action={newButton}
    >
      <QueryBoundary query={query} what="identity providers">
        {(providers) =>
          providers.length === 0 ? (
            <EmptyState
              variant="dashed"
              icon={Fingerprint}
              title="Only local accounts so far"
              description="Add an LDAP directory or an OIDC issuer to let people sign in with their company account."
              action={newButton}
            />
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Type</TableHead>
                    <TableHead>Address</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Updated</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {providers.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell className="font-medium">
                        <Link className="hover:underline" to={p.id}>
                          {p.name}
                        </Link>
                      </TableCell>
                      <TableCell>{p.type === "ldap" ? "LDAP" : "OIDC"}</TableCell>
                      <TableCell className="max-w-xs truncate font-mono text-xs">
                        {String((p.type === "ldap" ? p.config.url : p.config.issuerUri) ?? "")}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={p.enabled ? "success" : "inactive"}>{p.enabled ? "Enabled" : "Disabled"}</StatusBadge>
                      </TableCell>
                      <TableCell className="tabular-nums">{formatDate(p.updatedAt)}</TableCell>
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
