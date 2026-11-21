/** Browser DTOs are generated from api/openapi.json, not declared twice here. */
import type {
  Estimate,
  QuotaView,
  ProviderCapability,
  ResultSet,
  SearchCoverage,
  SavedInvestigation,
} from "./generated/openapi-types.ts";

export type * from "./generated/openapi-types.ts";

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
    Array.isArray(value.vendors) &&
    (value.coverage === undefined || isCoverage(value.coverage))
  );
}
function isInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value);
}
function isCount(value: unknown): value is number {
  return isInteger(value) && value >= 0;
}

function isCoverage(value: unknown): value is SearchCoverage {
  return (
    isRecord(value) &&
    value.version === 1 &&
    (value.state === "complete" || value.state === "partial" || value.state === "failed") &&
    typeof value.observedAt === "string" &&
    Number.isFinite(Date.parse(value.observedAt)) &&
    isCount(value.eligibleItineraries) &&
    isRecord(value.excludedByReason) &&
    Object.values(value.excludedByReason).every(isCount) &&
    Array.isArray(value.routes) &&
    value.routes.every(
      (route: unknown) =>
        isRecord(route) &&
        [route.provider, route.market, route.origin, route.destination, route.outboundDate].every(
          (field) => typeof field === "string",
        ) &&
        (route.returnDate === null || typeof route.returnDate === "string") &&
        typeof route.state === "string" &&
        ["not_attempted", "complete", "empty", "partial", "failed"].includes(route.state) &&
        (route.sourceMode === null ||
          (typeof route.sourceMode === "string" &&
            ["live", "cached", "mock", "manual"].includes(route.sourceMode))) &&
        (route.providerRows === null || isCount(route.providerRows)) &&
        (route.parsedOffers === null || isCount(route.parsedOffers)) &&
        isCount(route.eligibleOffers) &&
        isCount(route.returnLookupsLoaded) &&
        isCount(route.unresolvedReturns) &&
        typeof route.returnSelectionLimited === "boolean" &&
        (route.errorKind === null || typeof route.errorKind === "string"),
    )
  );
}
export function isEstimate(value: unknown): value is Estimate {
  return (
    isRecord(value) &&
    isInteger(value.liveCalls) &&
    isInteger(value.cachedCalls) &&
    isInteger(value.skippedCalls) &&
    isInteger(value.returnLookupsUpperBound) &&
    isQuotaList(value.quota) &&
    Array.isArray(value.warnings) &&
    Array.isArray(value.lines)
  );
}
function isProviderCapability(value: unknown): value is ProviderCapability {
  return (
    isRecord(value) &&
    typeof value.state === "string" &&
    [
      "ready",
      "disabled",
      "unsupported",
      "unconfigured",
      "no_cap",
      "zero_cap",
      "exhausted",
    ].includes(value.state) &&
    typeof value.priceMode === "string" &&
    ["live", "cached", "mock"].includes(value.priceMode) &&
    typeof value.checkedAt === "string" &&
    Number.isFinite(Date.parse(value.checkedAt)) &&
    value.externalQuota === "unknown" &&
    (value.dailyRemaining === null || isCount(value.dailyRemaining)) &&
    (value.monthlyRemaining === null || isCount(value.monthlyRemaining)) &&
    typeof value.oneWay === "boolean" &&
    typeof value.roundTrip === "boolean" &&
    typeof value.sellerOptions === "boolean"
  );
}
export function isQuotaList(value: unknown): value is QuotaView[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry: unknown) =>
        isRecord(entry) &&
        typeof entry.provider === "string" &&
        typeof entry.configured === "boolean" &&
        (entry.hosted === undefined || isProviderCapability(entry.hosted)),
    )
  );
}
export function isSavedList(value: unknown): value is SavedInvestigation[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry: unknown) =>
        isRecord(entry) && typeof entry.id === "string" && typeof entry.title === "string",
    )
  );
}
