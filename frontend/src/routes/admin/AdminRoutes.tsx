import { Navigate, Route, Routes } from "react-router-dom";

import { NotFound } from "@/routes/NotFound";
import AuditPage from "./AuditPage";
import IdentityProviderEditPage from "./IdentityProviderEditPage";
import IdentityProvidersPage from "./IdentityProvidersPage";
import UsersPage from "./UsersPage";
import WorkspacesPage from "./WorkspacesPage";

/** Everything under `/admin` for system administrators (spec §17.3). Loaded lazily. */
export default function AdminRoutes() {
  return (
    <Routes>
      <Route index element={<Navigate to="workspaces" replace />} />
      <Route path="workspaces" element={<WorkspacesPage />} />
      <Route path="users" element={<UsersPage />} />
      <Route path="identity-providers" element={<IdentityProvidersPage />} />
      <Route path="identity-providers/:id" element={<IdentityProviderEditPage />} />
      <Route path="audit" element={<AuditPage />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
