import { ApiError } from "@/api/client";

/** The server's own words when it sent a problem (FE-ACTION-3), otherwise a plain fallback. */
export function errorMessage(err: unknown, fallback = "Something went wrong. Try again."): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}
