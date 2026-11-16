import { Navigate, Route, Routes } from "react-router-dom";

import { NotBuiltYet } from "@/routes/NotBuiltYet";
import { NotFound } from "@/routes/NotFound";

/** Everything under `/w/:ws` (spec §17.3). Loaded lazily from `App.tsx`. */
export default function WorkspaceRoutes() {
  return (
    <Routes>
      <Route index element={<Navigate to="endpoints" replace />} />
      <Route
        path="endpoints/*"
        element={
          <NotBuiltYet
            title="Endpoints"
            step={5}
            summary="Write SQL, preview it, and publish versioned endpoints. Arrives with the endpoint lifecycle."
          />
        }
      />
      <Route
        path="datasources/*"
        element={
          <NotBuiltYet title="Datasources" step={4} summary="JDBC connections this workspace's endpoints query." />
        }
      />
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
      <Route
        path="settings/*"
        element={<NotBuiltYet title="Workspace settings" step={2} summary="General settings and members." />}
      />
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
