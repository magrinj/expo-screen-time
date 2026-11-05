import ExpoScreenTimeModule from "../ExpoScreenTimeModule";

// The native template slot (the App Group on iOS, the module's own on Android) is a single shared channel. Ownership is tracked
// per OWNER (component instance / imperative caller), not by template contents:
// two reports with identical JSON must not clear each other, and unmounting the
// active owner restores the most recent surviving one ("last mounted wins",
// with restore). Writes happen synchronously at acquire time so the template is
// in the slot BEFORE the native view (whose identity carries the hash)
// triggers the native read — a post-commit effect would race it.

const ownerTemplates = new Map<object, string>(); // insertion order = acquire order
let activeOwner: object | null = null;

function write(json: string): void {
  // Optional-chained: no-op until the native module function exists.
  ExpoScreenTimeModule.setReportTemplate?.(json);
}

/** Claim the channel for `owner` and write its template. Idempotent per
 * (owner, json) so it's safe to call during render. */
export function acquireTemplate(owner: object, json: string): void {
  if (activeOwner === owner && ownerTemplates.get(owner) === json) {
    return;
  }
  // Re-acquiring moves the owner to the most-recent position.
  ownerTemplates.delete(owner);
  ownerTemplates.set(owner, json);
  activeOwner = owner;
  write(json);
}

/** Release `owner`'s claim. If it was active, restore the most recent
 * surviving owner's template; clear the channel when none remain. */
export function releaseTemplate(owner: object): void {
  ownerTemplates.delete(owner);
  if (activeOwner !== owner) {
    return;
  }
  const survivors = Array.from(ownerTemplates.entries());
  const last = survivors[survivors.length - 1];
  if (last !== undefined) {
    activeOwner = last[0];
    write(last[1]);
  } else {
    activeOwner = null;
    ExpoScreenTimeModule.clearReportTemplate?.();
  }
}

/** Imperative API owner (module-level singleton). */
export const IMPERATIVE_OWNER: object = {};

/** Unconditionally clear the channel and forget every owner. */
export function clearTemplateChannel(): void {
  ownerTemplates.clear();
  activeOwner = null;
  ExpoScreenTimeModule.clearReportTemplate?.();
}

/** Stable short hash of the template JSON — drives the native view identity
 * (`.id(templateHash)`) so the extension re-reads the App Group on change.
 * Length-suffixed to shrink the (already unlikely) djb2 collision window. */
export function hashTemplate(json: string): string {
  let hash = 5381;
  for (let i = 0; i < json.length; i++) {
    hash = ((hash << 5) + hash + json.charCodeAt(i)) | 0;
  }
  return `${(hash >>> 0).toString(36)}-${json.length.toString(36)}`;
}
