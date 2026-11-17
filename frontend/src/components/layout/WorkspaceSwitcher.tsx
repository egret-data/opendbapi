import { useLocation, useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { useAuth } from "@/auth/useAuth";
import { cn } from "@/lib/utils";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/**
 * Lists only the workspaces the user belongs to. Switching keeps the current area:
 * from `/w/sales/datasources` to `/w/finance/datasources` (spec §17.2). Detail pages
 * fall back to their list, since an id never exists in another workspace.
 */
export function WorkspaceSwitcher({ className }: { className?: string }) {
  const { me } = useAuth();
  const { ws } = useParams();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const area = pathname.split("/")[3] || "endpoints";

  return (
    <Select value={ws ?? ""} onValueChange={(slug) => navigate(`/w/${slug}/${area}`)}>
      <SelectTrigger className={cn("w-56", className)} aria-label={t("Workspace")}>
        <SelectValue placeholder={t("Select a workspace")} />
      </SelectTrigger>
      <SelectContent>
        {me.workspaces.map((w) => (
          <SelectItem key={w.id} value={w.slug}>
            {w.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
