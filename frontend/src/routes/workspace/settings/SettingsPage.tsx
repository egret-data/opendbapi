import { NavLink, Navigate, Route, Routes, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { useAuth, effectiveRole, hasRole } from "@/auth/useAuth";
import { PageContainer } from "@/components/layout/PageContainer";
import { PageTitle } from "@/components/layout/PageTitle";
import { tabsListClass, tabsTriggerClass } from "@/components/ui/tabs";
import { Forbidden } from "@/routes/Forbidden";
import { AlertsSection } from "./AlertsSection";
import { AuditSection } from "./AuditSection";
import { GeneralSection } from "./GeneralSection";
import { MembersSection } from "./MembersSection";
import { TransferSection } from "./TransferSection";
import { SecretsSection } from "./SecretsSection";
import { ServingPolicySection } from "./ServingPolicySection";

/** `/w/:ws/settings/*`: each section is a route, so the open tab survives a reload (FE-PAGE-2). */
export default function SettingsPage() {
  const { ws = "" } = useParams();
  const { me } = useAuth();
  const role = effectiveRole(me, ws) ?? "viewer";
  const isAdmin = hasRole(role, "admin");
  const { t } = useTranslation();

  // NavLink marks the open section aria-current="page", which the shared tab style keys on.
  const tab = (to: string, label: string) => (
    <NavLink to={to} end className={tabsTriggerClass("pill")}>
      {label}
    </NavLink>
  );

  return (
    <PageContainer>
      <PageTitle>{t("Workspace settings")}</PageTitle>
      <nav aria-label={t("Settings sections")} className={`${tabsListClass("pill")} mt-4`}>
        {tab(`/w/${ws}/settings`, t("General"))}
        {tab(`/w/${ws}/settings/members`, t("Members"))}
        {tab(`/w/${ws}/settings/security`, t("Security"))}
        {tab(`/w/${ws}/settings/alerts`, t("Alerts"))}
        {isAdmin && tab(`/w/${ws}/settings/secrets`, t("Secrets"))}
        {isAdmin && tab(`/w/${ws}/settings/transfer`, t("Import & export"))}
        {isAdmin && tab(`/w/${ws}/settings/audit`, t("Audit log"))}
      </nav>
      <div className="pt-4">
        <Routes>
          <Route index element={<GeneralSection ws={ws} role={role} />} />
          <Route path="members" element={<MembersSection ws={ws} role={role} />} />
          <Route path="security" element={<ServingPolicySection ws={ws} role={role} />} />
          <Route path="alerts" element={<AlertsSection ws={ws} role={role} />} />
          <Route path="secrets" element={<SecretsSection ws={ws} role={role} />} />
          <Route path="transfer" element={<TransferSection ws={ws} role={role} />} />
          <Route
            path="audit"
            element={isAdmin ? <AuditSection ws={ws} /> : <Forbidden reason={t("The audit log is for workspace admins.")} />}
          />
          <Route path="*" element={<Navigate to={`/w/${ws}/settings`} replace />} />
        </Routes>
      </div>
    </PageContainer>
  );
}
