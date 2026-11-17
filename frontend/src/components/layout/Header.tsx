import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Check, KeyRound, Languages, LogOut, Moon, Sun } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useLogout } from "@/api/auth";
import { useAuth } from "@/auth/useAuth";
import { useTheme } from "@/components/theme-provider";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useIsDark } from "@/hooks/useIsDark";
import { LANGUAGES, setLanguage, type Language } from "@/i18n";
import { useIsNarrowViewport } from "@/lib/useIsNarrowViewport";
import { ChangePasswordDialog } from "./ChangePasswordDialog";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

function initials(name: string): string {
  const parts = name.split(/[\s._@-]+/).filter(Boolean);
  const chars = parts.length >= 2 ? parts[0][0] + parts[1][0] : name.slice(0, 2);
  return chars.toUpperCase();
}

export function Header() {
  const { me } = useAuth();
  const logout = useLogout();
  const { setTheme } = useTheme();
  const isDark = useIsDark();
  const narrow = useIsNarrowViewport();
  const { ws } = useParams();
  const display = me.displayName ?? me.username;
  const [changingPassword, setChangingPassword] = useState(false);
  const { t, i18n } = useTranslation();

  return (
    <header
      data-testid="app-header"
      className={`flex h-14 shrink-0 items-center border-b border-border bg-card ${narrow ? "gap-2 px-2" : "gap-3 px-4"}`}
    >
      <Link to="/" className="flex shrink-0 items-center gap-2">
        <img src="/favicon.svg" alt={narrow ? "OpenDBAPI" : ""} className="h-8 w-8 rounded-lg shadow-sm" />
        {!narrow && <span className="text-base font-semibold tracking-tight">OpenDBAPI</span>}
      </Link>

      {ws && <WorkspaceSwitcher className={narrow ? "w-auto min-w-0 flex-1" : undefined} />}
      {!(ws && narrow) && <div className="flex-1" />}

      <Button variant="ghost" size="icon" aria-label={t("Toggle theme")} onClick={() => setTheme(isDark ? "light" : "dark")}>
        {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
      </Button>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" className="rounded-full" aria-label={t("User menu")}>
            <Avatar className="h-8 w-8">
              <AvatarFallback className="bg-primary/10 text-xs font-medium text-primary">{initials(display)}</AvatarFallback>
            </Avatar>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuLabel className="flex flex-col gap-0.5">
            <span className="text-xs font-normal text-muted-foreground">{t("Signed in as")}</span>
            <span className="truncate text-sm font-medium">{display}</span>
            <span className="text-xs font-normal text-muted-foreground">{t("via {{source}}", { source: me.source.toUpperCase() })}</span>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          {me.source === "local" && (
            <DropdownMenuItem onSelect={() => setChangingPassword(true)}>
              <KeyRound className="mr-2 h-4 w-4" />
              {t("Change password")}
            </DropdownMenuItem>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuLabel className="flex items-center gap-2 text-xs font-normal text-muted-foreground">
            <Languages className="h-3.5 w-3.5" aria-hidden="true" />
            {t("Language")}
          </DropdownMenuLabel>
          {(Object.keys(LANGUAGES) as Language[]).map((code) => (
            <DropdownMenuItem key={code} onSelect={() => setLanguage(code)} lang={code}>
              <Check className={`mr-2 h-4 w-4 ${i18n.language === code ? "" : "invisible"}`} aria-hidden="true" />
              {LANGUAGES[code]}
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => logout.mutate()}>
            <LogOut className="mr-2 h-4 w-4" />
            {t("Log out")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ChangePasswordDialog open={changingPassword} onOpenChange={setChangingPassword} />
    </header>
  );
}
