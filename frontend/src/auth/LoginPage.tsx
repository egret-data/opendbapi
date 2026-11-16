import { useId, useState, type FormEvent } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";

import { oidcStartUrl, useAuthProviders, useLogin, useMe, type LoginSource } from "@/api/auth";
import { ApiError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Skeleton } from "@/components/ui/skeleton";

/** Only same-app paths are followed after sign-in, never an absolute URL. */
function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export function LoginPage() {
  const [params] = useSearchParams();
  const next = safeNext(params.get("next"));
  // Set by the backend when SSO fails: /login?error=sso&reason=...
  const ssoError = params.get("error") === "sso" ? (params.get("reason") ?? "Single sign-on failed.") : null;
  // Break-glass: local sign-in stays reachable for system admins even when it is not offered.
  const forceLocal = params.get("local") === "1";
  const me = useMe();
  const providers = useAuthProviders();
  const login = useLogin();
  const navigate = useNavigate();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [sourceValue, setSourceValue] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const usernameId = useId();
  const passwordId = useId();

  if (me.data) return <Navigate to={next} replace />;

  const passwordSources: { value: string; label: string; source: LoginSource }[] = [
    ...(providers.data?.local || forceLocal
      ? [{ value: "local", label: "Local account", source: { kind: "local" } as const }]
      : []),
    ...(providers.data?.ldap ?? []).map((p) => ({
      value: `ldap:${p.id}`,
      label: p.name,
      source: { kind: "ldap", idpId: p.id } as const,
    })),
  ];
  const selected = passwordSources.find((s) => s.value === sourceValue) ?? passwordSources[0];
  const sso = providers.data?.oidc ?? [];

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    setError(null);
    try {
      await login.mutateAsync({ source: selected.source, username, password });
      navigate(next, { replace: true });
    } catch (err) {
      // Never reveal whether the username exists (spec §17.4 Login).
      setError(
        err instanceof ApiError && err.status === 429
          ? err.message
          : err instanceof ApiError && err.status >= 500
            ? `Sign-in is unavailable: ${err.message}`
            : "Invalid username or password.",
      );
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Card className="w-full max-w-sm space-y-4 p-6">
        <div className="flex items-center gap-2">
          <img src="/favicon.svg" alt="" className="h-8 w-8 rounded-lg" />
          <h1 className="text-xl font-semibold">Sign in to OpenDBAPI</h1>
        </div>

        {ssoError && (
          <p role="alert" className="text-sm text-destructive">
            Single sign-on did not complete: {ssoError}
          </p>
        )}
        {providers.isError ? (
          <p role="alert" className="text-sm text-destructive">
            Could not load sign-in options: {providers.error.message}. Reload the page to try again.
          </p>
        ) : providers.isPending ? (
          <div className="space-y-3" aria-busy="true" aria-label="Loading sign-in options">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
          </div>
        ) : (
          <>
            {sso.map((p) => (
              <Button key={p.id} asChild className="w-full">
                <a href={oidcStartUrl(p.id)}>Sign in with {p.name}</a>
              </Button>
            ))}

            {passwordSources.length > 0 && (
              <form onSubmit={onSubmit} className="space-y-3">
                {sso.length > 0 && <p className="text-center text-xs text-muted-foreground">or with a password</p>}
                {passwordSources.length > 1 && (
                  <RadioGroup
                    value={selected?.value}
                    onValueChange={setSourceValue}
                    aria-label="Account type"
                    className="flex flex-wrap gap-4"
                  >
                    {passwordSources.map((s) => (
                      <div key={s.value} className="flex items-center gap-2">
                        <RadioGroupItem id={`src-${s.value}`} value={s.value} />
                        <Label htmlFor={`src-${s.value}`}>{s.label}</Label>
                      </div>
                    ))}
                  </RadioGroup>
                )}
                <div className="space-y-1">
                  <Label htmlFor={usernameId}>Username</Label>
                  <Input
                    id={usernameId}
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    required
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor={passwordId}>Password</Label>
                  <Input
                    id={passwordId}
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                  />
                </div>
                {error && (
                  <p role="alert" className="text-sm text-destructive">
                    {error}
                  </p>
                )}
                <Button type="submit" variant={sso.length ? "outline" : "default"} className="w-full" disabled={login.isPending}>
                  Sign in
                </Button>
              </form>
            )}

            {passwordSources.length === 0 && sso.length === 0 && (
              <p className="text-sm text-muted-foreground">
                No sign-in method is enabled. Ask the operator to enable local accounts, LDAP or SSO.
              </p>
            )}
          </>
        )}
      </Card>
    </div>
  );
}
