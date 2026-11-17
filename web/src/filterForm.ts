import type { FilterSpec, ResultSet } from "./domain.ts";
import type { FilterUiState } from "./filters.ts";

export interface FilterControlsState extends FilterUiState {
  sort: "date" | "price";
  market: string;
  vsort: "allin" | "market" | "vendor";
}
export function initialFilters(hideMock = true): FilterControlsState {
  return {
    maxStops: "", maxLayoverHours: "", excludeReturn: "", excludeOut: "",
    allow: "", block: "", maxTravelHours: "", departAfter: "",
    departBefore: "", returnAfter: "", nonstop: false, eu: false,
    hideMock, cashRefund: false, cabin: "all", nights: "all",
    origin: "all", hide: false, lhr: false, sort: "date",
    market: "all", vsort: "allin",
  };
}
export function suggestedFilters(spec: FilterSpec = {}): FilterControlsState {
  return {
    ...initialFilters(spec.hideMock !== false),
    maxStops: spec.maxStops == null ? "" : String(spec.maxStops),
    maxLayoverHours: spec.maxLayoverMinutes == null ? "" : String(spec.maxLayoverMinutes / 60),
    excludeReturn: (spec.excludeReturnDates ?? []).join(", "),
    excludeOut: (spec.excludeOutboundDates ?? []).join(", "),
    allow: (spec.airlineAllow ?? []).join(", "),
    block: (spec.airlineBlock ?? []).join(", "),
    maxTravelHours: spec.maxTravelMinutes == null ? "" : String(spec.maxTravelMinutes / 60),
    departAfter: spec.outboundDepartAfter ?? "",
    departBefore: spec.outboundDepartBefore ?? "",
    returnAfter: spec.returnDepartAfter ?? "",
    nonstop: Boolean(spec.nonstopOnly),
    eu: Boolean(spec.europeanPremiumOnly),
    cashRefund: Boolean(spec.verifiedCashRefundOnly),
    cabin: spec.europeanPremiumOnly ? "EU" : (spec.cabin ?? "all"),
    nights: spec.nights == null ? "all" : String(spec.nights),
    origin: spec.origin ?? "all",
    lhr: spec.origin === "LHR",
  };
}
export function filterChoices(result: ResultSet | null): {
  nights: string[]; origins: string[];
} {
  if (!result) return { nights: ["all"], origins: ["all"] };
  const nights = new Set<string>(), origins = new Set<string>();
  for (const it of result.itineraries) {
    if (it.nights != null) nights.add(String(it.nights));
    if (it.origin) origins.add(it.origin);
  }
  return {
    nights: ["all", ...[...nights].sort((a, b) => Number(a) - Number(b))],
    origins: ["all", ...[...origins].sort()],
  };
}
