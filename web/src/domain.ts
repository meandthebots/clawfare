/** Browser DTOs are generated from api/openapi.json, not redefined here. */
export type * from "./generated/openapi-types.ts";
import type { Estimate, QuotaView, ResultSet, SavedInvestigation } from "./generated/openapi-types.ts";

/** Deliberately structural at the network boundary, not a false full-schema proof. */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
export function isResultSet(value: unknown): value is ResultSet {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.title === "string" &&
    Array.isArray(value.itineraries) &&
    value.itineraries.every((it: unknown) => isRecord(it) && typeof it.id === "string") &&
    Array.isArray(value.addons) &&
    Array.isArray(value.cells) &&
    Array.isArray(value.vendors)
  );
}
function isInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value);
}
export function isEstimate(value: unknown): value is Estimate {
  return (
    isRecord(value) &&
    isInteger(value.liveCalls) &&
    isInteger(value.cachedCalls) &&
    isInteger(value.skippedCalls) &&
    isInteger(value.returnLookupsUpperBound) &&
    Array.isArray(value.warnings) &&
    Array.isArray(value.lines)
  );
}
export function isQuotaList(value: unknown): value is QuotaView[] {
  return Array.isArray(value) && value.every(
    (entry: unknown) => isRecord(entry) && typeof entry.provider === "string" &&
      typeof entry.configured === "boolean",
  );
}
export function isSavedList(value: unknown): value is SavedInvestigation[] {
  return Array.isArray(value) && value.every(
    (entry: unknown) => isRecord(entry) && typeof entry.id === "string" &&
      typeof entry.title === "string",
  );
}
