import { useQueryClient } from "@tanstack/react-query";
import { Trans, useTranslation } from "react-i18next";

import { authKeys, useLogout } from "@/api/auth";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

/** Landing for a signed-in user who belongs to no workspace (spec §17.2). */
export function NoWorkspaceYet({ username }: { username: string }) {
  const qc = useQueryClient();
  const logout = useLogout();
  const { t } = useTranslation();
  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md space-y-4 p-6">
        <h1 className="text-xl font-semibold">{t("No workspaces yet")}</h1>
        <p className="text-sm text-muted-foreground">
          <Trans
            i18nKey="You're signed in as <name>{{username}}</name>, but you're not a member of any workspace. Ask a workspace admin to add you, or ask your directory admin to put you in a group that maps to a workspace."
            values={{ username }}
            components={{ name: <span className="font-medium text-foreground" /> }}
          />
        </p>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => qc.invalidateQueries({ queryKey: authKeys.me })}>{t("Check again")}</Button>
          <Button variant="outline" onClick={() => logout.mutate()} disabled={logout.isPending}>
            {t("Sign out")}
          </Button>
        </div>
      </Card>
    </div>
  );
}
