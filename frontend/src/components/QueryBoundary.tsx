import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { RotateCw } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { errorMessage } from "@/lib/errors";

/**
 * Error, then loading, then content (FE-STATE-1). An error is never shown as "empty":
 * a list that failed to load must not tell the reader there is nothing in it.
 */
export function QueryBoundary<T>({
  query,
  what,
  children,
}: {
  query: UseQueryResult<T, unknown>;
  /** For the error sentence: "Could not load {what}". */
  what: string;
  children: (data: T) => ReactNode;
}) {
  const { t } = useTranslation();
  // Callers pass English; a Vietnamese entry for `what` translates it, otherwise it stays as given.
  const subject = t(what);
  if (query.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription className="flex flex-wrap items-center gap-3">
          <span>
            {t("Could not load {{what}}: {{error}}", { what: subject, error: errorMessage(query.error) })}
          </span>
          <Button variant="outline" size="sm" onClick={() => query.refetch()}>
            <RotateCw aria-hidden="true" />
            {t("Try again")}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
  if (query.isPending) {
    return (
      <div className="space-y-2" aria-busy="true" aria-label={t("Loading {{what}}", { what: subject })}>
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-2/3" />
      </div>
    );
  }
  return <>{children(query.data as T)}</>;
}
