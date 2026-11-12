import type { SelectionMetadata, TokenHandle } from "./ExpoScreenTime.types";
import ExpoScreenTimeModule from "./ExpoScreenTimeModule";

/**
 * List all persisted selection IDs.
 */
export function listSelections(): Promise<string[]> {
  return ExpoScreenTimeModule.listSelections();
}

/**
 * Check if a selection exists in the native store.
 */
export function hasSelection(selectionId: string): Promise<boolean> {
  return ExpoScreenTimeModule.hasSelection(selectionId);
}

/**
 * Get metadata (counts) for a selection without loading token data.
 */
export function getSelectionMetadata(
  selectionId: string,
): Promise<SelectionMetadata | null> {
  return ExpoScreenTimeModule.getSelectionMetadata(selectionId);
}

/**
 * Delete a selection from the native store.
 */
export function deleteSelection(selectionId: string): Promise<void> {
  return ExpoScreenTimeModule.deleteSelection(selectionId);
}

/**
 * Get lightweight token handles for a selection.
 * Handles can be passed to TokenLabel for rendering.
 * On app restart, call this to restore handles from the native store.
 */
export function getTokens(selectionId: string): Promise<TokenHandle[] | null> {
  return ExpoScreenTimeModule.getTokens(selectionId);
}

/**
 * Remove a specific token from a selection by its handle.
 */
export function removeToken(
  selectionId: string,
  handle: string,
): Promise<void> {
  return ExpoScreenTimeModule.removeToken(selectionId, handle);
}

/**
 * Merge multiple selections into a target selection (union).
 */
export function mergeSelections(
  targetId: string,
  sourceIds: string[],
): Promise<void> {
  return ExpoScreenTimeModule.mergeSelections(targetId, sourceIds);
}

/**
 * Keep only tokens common to all source selections (intersection).
 */
export function keepCommonSelections(
  targetId: string,
  sourceIds: string[],
): Promise<void> {
  return ExpoScreenTimeModule.keepCommonSelections(targetId, sourceIds);
}

/**
 * Subtract one selection from another.
 */
export function subtractSelection(
  targetId: string,
  fromId: string,
  subtractId: string,
): Promise<void> {
  return ExpoScreenTimeModule.subtractSelection(targetId, fromId, subtractId);
}

/**
 * Export a selection as an opaque string for backup/sync.
 * WARNING: The blob is bound to this device (on iOS, also to the user's iCloud account).
 */
export function exportSelection(selectionId: string): Promise<string | null> {
  return ExpoScreenTimeModule.exportSelection(selectionId);
}

/**
 * Import a previously exported selection.
 * WARNING: Only works on the same device/iCloud account; another platform's blob resolves `false`.
 */
export function importSelection(
  selectionId: string,
  data: string,
): Promise<boolean> {
  return ExpoScreenTimeModule.importSelection(selectionId, data);
}
