import { Navigate } from "react-router-dom";

import { useAuth } from "@/auth/useAuth";
import { NoWorkspaceYet } from "@/auth/NoWorkspaceYet";

/** `/` sends the user to their first workspace, or to administration, or explains why neither. */
export function HomeRedirect() {
  const { me } = useAuth();
  const first = me.workspaces[0];
  if (first) return <Navigate to={`/w/${first.slug}/endpoints`} replace />;
  if (me.systemAdmin) return <Navigate to="/admin/workspaces" replace />;
  return <NoWorkspaceYet username={me.displayName ?? me.username} />;
}
