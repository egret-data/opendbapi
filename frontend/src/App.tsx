import { lazy } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Outlet, Route, Routes } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { queryClient } from "@/api/queryClient";
import { LoginPage } from "@/auth/LoginPage";
import { RequireAuth, RequireSystemAdmin, RequireWorkspaceMember } from "@/auth/guards";
import { AppShell } from "@/components/layout/AppShell";
import { LazyRouteArea } from "@/components/layout/LazyRouteArea";
import { ThemeProvider } from "@/components/theme-provider";
import { Skeleton } from "@/components/ui/skeleton";
import { Toaster } from "@/components/ui/sonner";
import { HomeRedirect } from "@/routes/HomeRedirect";
import { NotFound } from "@/routes/NotFound";

// Feature areas are lazy (FE-ROUTE-2): the login screen never downloads them.
const WorkspaceRoutes = lazy(() => import("@/routes/workspace/WorkspaceRoutes"));
const AdminRoutes = lazy(() => import("@/routes/admin/AdminRoutes"));
const ApiTokensPage = lazy(() => import("@/routes/account/ApiTokensPage"));

function AreaFallback() {
  const { t } = useTranslation();
  return (
    <div className="space-y-3" aria-busy="true" aria-label={t("Loading")}>
      <Skeleton className="h-6 w-48" />
      <Skeleton className="h-40 w-full" />
    </div>
  );
}

const areaFallback = <AreaFallback />;

function ShellLayout() {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  );
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <HomeRedirect />
          </RequireAuth>
        }
      />
      <Route element={<ShellLayout />}>
        <Route path="/w/:ws" element={<RequireWorkspaceMember />}>
          <Route
            path="*"
            element={
              <LazyRouteArea area="workspace" fallback={areaFallback}>
                <WorkspaceRoutes />
              </LazyRouteArea>
            }
          />
        </Route>
        <Route
          path="/account/tokens"
          element={
            <LazyRouteArea area="account" fallback={areaFallback}>
              <ApiTokensPage />
            </LazyRouteArea>
          }
        />
        <Route path="/admin" element={<RequireSystemAdmin />}>
          <Route
            path="*"
            element={
              <LazyRouteArea area="administration" fallback={areaFallback}>
                <AdminRoutes />
              </LazyRouteArea>
            }
          />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
        <Toaster />
      </QueryClientProvider>
    </ThemeProvider>
  );
}
