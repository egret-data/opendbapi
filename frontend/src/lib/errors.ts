import { ApiError } from "@/api/client";
import i18n from "@/i18n";

/** The server's own words when it sent a problem (FE-ACTION-3), otherwise a plain fallback. */
export function errorMessage(err: unknown, fallback?: string): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error && err.message) return err.message;
  return fallback ?? i18n.t("Something went wrong. Try again.");
}
