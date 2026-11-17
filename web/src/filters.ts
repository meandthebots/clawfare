import type { FilterSpec, Itinerary, Leg, ResultSet } from "./domain.ts";

export interface FilterUiState {
  maxStops: string;
  maxLayoverHours: string;
  excludeReturn: string;
  excludeOut: string;
  allow: string;
  block: string;
  maxTravelHours: string;
  departAfter: string;
  departBefore: string;
  returnAfter: string;
  nonstop: boolean;
  eu: boolean;
  hideMock: boolean;
  cashRefund: boolean;
  cabin: string;
  nights: string;
  origin: string;
  hide: boolean;
  lhr: boolean;
}
export function splitList(raw: string): string[] {
  return raw
    .split(/[,/\s]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}
/** Reproduce the Kotlin FilterSpec JSON envelope while keeping unset values null. */
export function toFilterSpec(state: FilterUiState): FilterSpec {
  return {
    maxStops: state.maxStops === "" ? null : Number(state.maxStops),
    maxLayoverMinutes:
      state.maxLayoverHours === "" ? null : Math.round(Number(state.maxLayoverHours) * 60),
    excludeOutboundDates: splitList(state.excludeOut),
    excludeReturnDates: splitList(state.excludeReturn),
    europeanPremiumOnly: state.eu || state.cabin === "EU",
    airlineAllow: splitList(state.allow),
    airlineBlock: splitList(state.block),
    maxTravelMinutes:
      state.maxTravelHours === "" ? null : Math.round(Number(state.maxTravelHours) * 60),
    outboundDepartAfter: state.departAfter || null,
    outboundDepartBefore: state.departBefore || null,
    returnDepartAfter: state.returnAfter || null,
    nonstopOnly: state.nonstop,
    hideMock: state.hideMock,
    verifiedCashRefundOnly: state.cashRefund,
    cabin: state.cabin === "all" || state.cabin === "EU" ? null : state.cabin,
    nights: state.nights === "all" ? null : Number(state.nights),
    origin: state.lhr ? "LHR" : state.origin === "all" ? null : state.origin,
  };
}
function normalizeTime(raw: string): string {
  if (!raw) return "";
  const parts = raw.trim().slice(0, 5).split(":");
  if (parts.length < 2) return "00:00";
  return parts[0]!.padStart(2, "0") + ":" + parts[1]!.padStart(2, "0");
}
function cabinKey(raw: string): string {
  const text = raw.toLowerCase();
  if (!text) return "economy";
  if (text.includes("prem") || text === "w") return "premium_economy";
  if (text.includes("bus") || text === "j") return "business";
  if (text.includes("first") || text === "f") return "first";
  return "economy";
}
/** Preserve existing browser semantics, including named carrier substrings. */
export function carrierMatch(leg: Leg, token: string): boolean {
  const requested = token.toLowerCase();
  if (requested === leg.airlineCode.toLowerCase()) return true;
  if (requested === leg.airline.toLowerCase()) return true;
  if (requested === leg.operatingCarrier.toLowerCase()) return true;
  if (
    token.length > 2 &&
    (leg.airline.toLowerCase().includes(requested) ||
      leg.operatingCarrier.toLowerCase().includes(requested))
  )
    return true;
  return false;
}
function allows(it: Itinerary, tokens: string[]): boolean {
  const legs = it.outbound.legs.concat(it.returning?.legs ?? []);
  if (!legs.length)
    return tokens.some((token) => it.airlineSummary.toLowerCase().includes(token.toLowerCase()));
  return legs.every((leg) => tokens.some((token) => carrierMatch(leg, token)));
}
function blocked(it: Itinerary, tokens: string[]): boolean {
  const legs = it.outbound.legs.concat(it.returning?.legs ?? []);
  if (!legs.length)
    return tokens.some((token) => it.airlineSummary.toLowerCase().includes(token.toLowerCase()));
  return legs.some((leg) => tokens.some((token) => carrierMatch(leg, token)));
}
export function matchesFilter(
  it: Itinerary,
  filter: FilterSpec,
  isVerifiedCashRefund: (it: Itinerary) => boolean,
): boolean {
  if (filter.hideMock && it.sourceLabel === "mock") return false;
  if (filter.verifiedCashRefundOnly && !isVerifiedCashRefund(it)) return false;
  const stops = it.stopsRet == null ? it.stopsOut : Math.max(it.stopsOut, it.stopsRet);
  if (filter.nonstopOnly && (it.stopsOut !== 0 || it.returnMissing || it.stopsRet !== 0))
    return false;
  if (filter.maxStops != null && stops > filter.maxStops) return false;
  if (
    filter.maxLayoverMinutes != null &&
    it.layoverMinutes.some((minutes) => minutes > filter.maxLayoverMinutes!)
  )
    return false;
  if (filter.excludeOutboundDates?.includes(it.outboundDate)) return false;
  if (it.returnDate && filter.excludeReturnDates?.includes(it.returnDate)) return false;
  if (filter.europeanPremiumOnly && !it.euPremium) return false;
  if (
    filter.maxTravelMinutes != null &&
    (it.travelMinutes == null || it.travelMinutes > filter.maxTravelMinutes)
  )
    return false;
  if (
    filter.outboundDepartAfter &&
    it.outboundDepart &&
    it.outboundDepart < normalizeTime(filter.outboundDepartAfter)
  )
    return false;
  if (
    filter.outboundDepartBefore &&
    it.outboundDepart &&
    it.outboundDepart > normalizeTime(filter.outboundDepartBefore)
  )
    return false;
  if (
    filter.returnDepartAfter &&
    it.returnDepart &&
    it.returnDepart < normalizeTime(filter.returnDepartAfter)
  )
    return false;
  if (filter.cabin && filter.cabin !== "all" && it.cabinKey !== cabinKey(filter.cabin))
    return false;
  if (filter.nights != null && it.nights !== filter.nights) return false;
  if (filter.origin && it.origin !== filter.origin.toUpperCase()) return false;
  if (filter.airlineAllow?.length && !allows(it, filter.airlineAllow)) return false;
  if (filter.airlineBlock?.length && blocked(it, filter.airlineBlock)) return false;
  return true;
}

export function visibleItineraries(
  result: ResultSet,
  filter: FilterSpec,
  hideInfeasible: boolean,
  isVerifiedCashRefund: (it: Itinerary) => boolean,
): Itinerary[] {
  const rows = result.itineraries.filter((it) => matchesFilter(it, filter, isVerifiedCashRefund));
  if (!hideInfeasible || !result.addons.length) return rows;
  const cells = new Map(
    result.cells.map((cell) => [cell.primaryId + "\0" + (cell.addonId ?? ""), cell]),
  );
  return rows.filter((it) =>
    result.addons.some((addon) => {
      const cell = cells.get(it.id + "\0" + addon.id);
      return cell != null && cell.status !== "no";
    }),
  );
}
