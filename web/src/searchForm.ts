import type { Cabin, SearchQuery, TripType } from "./domain.ts";
import { splitList } from "./filters.ts";

export interface SearchForm {
  origins: string;
  destinations: string;
  outFrom: string;
  outTo: string;
  nights: string;
  retFrom: string;
  retTo: string;
  tripType: TripType;
  adults: string;
  bags: string;
  cabin: Cabin;
  cabinShort: Cabin;
  returnChoices: string;
  serpapi: boolean;
  searchapi: boolean;
  travelpayouts: boolean;
  duffel: boolean;
  nonstop: boolean;
  cacheOnly: boolean;
}
export const INITIAL_SEARCH: Readonly<SearchForm> = Object.freeze({
  origins: "LHR, LGW", destinations: "TPA, SRQ",
  outFrom: "2026-12-16", outTo: "2026-12-23",
  nights: "8", retFrom: "", retTo: "", tripType: "round_trip",
  adults: "2", bags: "1", cabin: "premium_economy", cabinShort: "economy",
  returnChoices: "3", serpapi: true, searchapi: true,
  travelpayouts: true, duffel: true, nonstop: false, cacheOnly: false,
});
export function buildSearchQuery(form: SearchForm): SearchQuery {
  const providers = ([
    ["serpapi", form.serpapi],
    ["searchapi", form.searchapi],
    ["travelpayouts", form.travelpayouts],
    ["duffel", form.duffel],
  ] as const).filter(([, enabled]) => enabled).map(([name]) => name);
  return {
    origins: splitList(form.origins),
    destinations: splitList(form.destinations),
    outboundFrom: form.outFrom,
    outboundTo: form.outTo || form.outFrom,
    returnFrom: form.retFrom || null,
    returnTo: form.retTo || null,
    nights: form.nights.trim() ? Number(form.nights) : null,
    tripType: form.tripType,
    adults: Number(form.adults || 1),
    cabin: form.cabin,
    cabinShortHaul: form.cabinShort,
    checkedBags: Number(form.bags || 0),
    currency: "GBP",
    providers,
    nonstopOnly: form.nonstop,
    returnChoices: Number(form.returnChoices || 3),
    fetchReturns: true,
    cacheOnly: form.cacheOnly,
    confirmed: false,
  };
}
export function estimateIsRunnable(
  warnings: readonly string[], liveCalls: number, cachedCalls: number,
  returnLookups: number, cacheOnly: boolean,
): boolean {
  return !warnings.some((warning) =>
    /Enter at least|Check the dates|Set a return|No outbound|Narrow/.test(warning)) &&
    (cacheOnly || liveCalls > 0 || cachedCalls > 0 || returnLookups > 0);
}
