import { Navigate, Route, Routes } from "react-router-dom";

import { NotBuiltYet } from "@/routes/NotBuiltYet";
import { NotFound } from "@/routes/NotFound";
import SettingsPage from "./settings/SettingsPage";
import EndpointDetailPage from "./endpoints/EndpointDetailPage";
import EndpointsPage from "./endpoints/EndpointsPage";
import NewEndpointPage from "./endpoints/NewEndpointPage";
import DatasourceDetailPage from "./datasources/DatasourceDetailPage";
import DatasourcesPage from "./datasources/DatasourcesPage";
import NewDatasourcePage from "./datasources/NewDatasourcePage";
import { RequireRole } from "@/auth/guards";

/** Everything under `/w/:ws` (spec §17.3). Loaded lazily from `App.tsx`. */
export default function WorkspaceRoutes() {
  return (
    <Routes>
      <Route index element={<Navigate to="endpoints" replace />} />
      <Route path="endpoints" element={<EndpointsPage />} />
      <Route path="endpoints/new" element={<RequireRole minimum="author"><NewEndpointPage /></RequireRole>} />
      <Route path="endpoints/:id" element={<EndpointDetailPage />} />
      <Route path="datasources" element={<DatasourcesPage />} />
      <Route path="datasources/new" element={<RequireRole minimum="admin"><NewDatasourcePage /></RequireRole>} />
      <Route path="datasources/:id" element={<DatasourceDetailPage />} />
      <Route
        path="clients/*"
        element={
          <NotBuiltYet
            title="Clients"
            step={6}
            summary="Service accounts and their OAuth2 clients, and which endpoints each may call."
          />
        }
      />
      <Route
        path="activity/*"
        element={<NotBuiltYet title="Activity" step={10} summary="Calls, errors and latency from the access log." />}
      />
      <Route path="settings/*" element={<SettingsPage />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
