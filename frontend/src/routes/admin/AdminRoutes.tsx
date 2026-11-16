import { Navigate, Route, Routes } from "react-router-dom";

import { NotBuiltYet } from "@/routes/NotBuiltYet";
import { NotFound } from "@/routes/NotFound";

/** Everything under `/admin` for system administrators (spec §17.3). Loaded lazily. */
export default function AdminRoutes() {
  return (
    <Routes>
      <Route index element={<Navigate to="workspaces" replace />} />
      <Route
        path="workspaces/*"
        element={<NotBuiltYet title="Workspaces" step={2} summary="Create workspaces, set quotas, archive." />}
      />
      <Route
        path="users/*"
        element={<NotBuiltYet title="Users" step={2} summary="Local, LDAP and SSO users; system admins." />}
      />
      <Route
        path="identity-providers/*"
        element={
          <NotBuiltYet title="Identity providers" step={2} summary="LDAP and OIDC sign-in, and group to role mapping." />
        }
      />
      <Route
        path="audit/*"
        element={<NotBuiltYet title="Audit log" step={2} summary="Who signed in and what they changed." />}
      />
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
