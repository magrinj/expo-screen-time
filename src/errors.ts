/**
 * The `code` of every error the module rejects or throws with. Branch on it
 * after narrowing with {@link isScreenTimeError}.
 */
export type ScreenTimeErrorCode =
  | "UNAUTHORIZED"
  | "UNSUPPORTED"
  | "SELECTION_NOT_FOUND"
  | "NO_ACTIVE_BLOCK"
  | "EXCESSIVE_ACTIVITIES"
  | "INTERVAL_TOO_SHORT"
  | "INTERVAL_TOO_LONG"
  | "INVALID_DATE_COMPONENTS"
  | "INVALID_CONFIG"
  | "MONITORING_ERROR"
  | "MONITORING_ROLLBACK_FAILED";

/** An error from this module: a regular `Error` with a {@link ScreenTimeErrorCode}. */
export type ScreenTimeError = Error & { code: ScreenTimeErrorCode };

/** Every {@link ScreenTimeErrorCode}, kept equal to the native list by a test. */
export const SCREEN_TIME_ERROR_CODES: readonly ScreenTimeErrorCode[] = [
  "UNAUTHORIZED",
  "UNSUPPORTED",
  "SELECTION_NOT_FOUND",
  "NO_ACTIVE_BLOCK",
  "EXCESSIVE_ACTIVITIES",
  "INTERVAL_TOO_SHORT",
  "INTERVAL_TOO_LONG",
  "INVALID_DATE_COMPONENTS",
  "INVALID_CONFIG",
  "MONITORING_ERROR",
  "MONITORING_ROLLBACK_FAILED",
];

const codes: ReadonlySet<string> = new Set(SCREEN_TIME_ERROR_CODES);

/**
 * Narrows a caught value to an error from this module.
 *
 * ```ts
 * try {
 *   await ExpoScreenTime.temporaryUnlock(60);
 * } catch (error) {
 *   if (isScreenTimeError(error) && error.code === "NO_ACTIVE_BLOCK") return;
 *   throw error;
 * }
 * ```
 */
export function isScreenTimeError(error: unknown): error is ScreenTimeError {
  if (!(error instanceof Error)) return false;
  const code: unknown = (error as Error & { code?: unknown }).code;
  return typeof code === "string" && codes.has(code);
}
