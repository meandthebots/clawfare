import type { Cabin, SearchQuery, TripType } from "./domain.ts";

/** Values in the editor remain strings until the user explicitly estimates a search. */
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
  strictPerLegCabin: boolean;
  strictOperatingCarrier: boolean;
  excludedAirlines: string;
  excludedTransitAirports: string;
  excludedTransitCountries: string;
  market: string;
  currency: string;
  investigation: string;
  maxLiveCalls: string;
  returnChoices: string;
  fetchReturns: boolean;
  serpapi: boolean;
  searchapi: boolean;
  travelpayouts: boolean;
  duffel: boolean;
  nonstop: boolean;
  cacheOnly: boolean;
}

/** A new search is not a Florida search. The example is loaded separately. */
export const INITIAL_SEARCH: Readonly<SearchForm> = Object.freeze({
  origins: "",
  destinations: "",
  outFrom: "",
  outTo: "",
  nights: "",
  retFrom: "",
  retTo: "",
  tripType: "round_trip",
  adults: "1",
  bags: "0",
  cabin: "economy",
  cabinShort: "economy",
  strictPerLegCabin: false,
  strictOperatingCarrier: false,
  excludedAirlines: "",
  excludedTransitAirports: "",
  excludedTransitCountries: "",
  market: "uk",
  currency: "GBP",
  investigation: "",
  maxLiveCalls: "16",
  returnChoices: "3",
  fetchReturns: true,
  serpapi: false,
  searchapi: false,
  travelpayouts: false,
  duffel: false,
  nonstop: false,
  cacheOnly: false,
});

export class SearchFormError extends Error {
  readonly field: keyof SearchForm;
  constructor(field: keyof SearchForm, message: string) {
    super(message);
    this.field = field;
    this.name = "SearchFormError";
  }
}

function fail(field: keyof SearchForm, message: string): never {
  throw new SearchFormError(field, message);
}

function codes(raw: string, field: keyof SearchForm, pattern: RegExp, max: number): string[] {
  const parts = raw.trim() ? raw.trim().split(/[,\s]+/) : [];
  if (parts.length > max || parts.some((part) => !pattern.test(part))) {
    fail(field, `Enter at most ${max} valid ${field} codes, separated by commas.`);
  }
  return [...new Set(parts.map((part) => part.toUpperCase()))];
}

function integer(
  raw: string,
  field: keyof SearchForm,
  min: number,
  max: number,
  optional = false,
): number | null {
  if (!raw.trim() && optional) return null;
  if (!/^(0|[1-9][0-9]*)$/.test(raw.trim())) {
    fail(field, `${field}: enter a whole number from ${min} to ${max}.`);
  }
  const value = Number(raw.trim());
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    fail(field, `${field}: enter a whole number from ${min} to ${max}.`);
  }
  return value;
}

function date(raw: string, field: keyof SearchForm, optional = false): string | null {
  if (!raw && optional) return null;
  if (!/^20[2-9][0-9]-[0-9]{2}-[0-9]{2}$/.test(raw)) {
    fail(field, `${field}: enter a valid date (YYYY-MM-DD).`);
  }
  const time = Date.parse(raw + "T00:00:00Z");
  if (!Number.isFinite(time) || new Date(time).toISOString().slice(0, 10) !== raw) {
    fail(field, `${field}: enter a real calendar date.`);
  }
  return raw;
}

function span(from: string, to: string, field: keyof SearchForm): number {
  const days = (Date.parse(to + "T00:00:00Z") - Date.parse(from + "T00:00:00Z")) / 86400000;
  if (days < 0 || days > 30) {
    fail(field, `${field}: end date must be on or after start, within a 31-day range.`);
  }
  return days + 1;
}

/**
 * Parse the entire search policy before planning or seeking billable consent.
 * Invalid primitives must not be silently coerced by the Kotlin normalizer.
 */
export function buildSearchQuery(form: SearchForm): SearchQuery {
  const origins = codes(form.origins, "origins", /^[A-Za-z]{3}$/, 8);
  const destinations = codes(form.destinations, "destinations", /^[A-Za-z]{3}$/, 8);
  if (!origins.length) fail("origins", "Select at least one origin airport.");
  if (!destinations.length) fail("destinations", "Select at least one destination airport.");
  if (origins.some((code) => destinations.includes(code))) {
    fail("destinations", "Origin and destination airport sets must not overlap.");
  }
  const outboundFrom = date(form.outFrom, "outFrom")!;
  const outboundTo = date(form.outTo || form.outFrom, "outTo")!;
  const outboundDays = span(outboundFrom, outboundTo, "outTo");
  const tripType = form.tripType;
  if (tripType !== "one_way" && tripType !== "round_trip") {
    fail("tripType", "Select one-way or round-trip.");
  }
  const nights = integer(form.nights, "nights", 1, 60, true);
  const returnFrom = date(form.retFrom, "retFrom", true);
  const returnTo = date(form.retTo, "retTo", true);
  if (tripType === "one_way" && (nights != null || returnFrom || returnTo)) {
    fail("tripType", "One-way searches cannot specify nights or return dates.");
  }
  if (tripType === "round_trip") {
    if (nights != null && (returnFrom || returnTo)) {
      fail("nights", "Choose either a fixed number of nights or a return date range, not both.");
    }
    if (nights == null && !returnFrom) {
      fail("retFrom", "Round-trip searches require nights or a return date range.");
    }
    if (returnTo && !returnFrom) fail("retFrom", "Enter the return start date.");
    if (returnFrom) {
      span(returnFrom, returnTo || returnFrom, "retTo");
      if (returnFrom < outboundFrom) {
        fail("retFrom", "Return dates cannot begin before outbound travel.");
      }
    }
  }
  const providers = (
    [
      ["serpapi", form.serpapi],
      ["searchapi", form.searchapi],
      ["travelpayouts", form.travelpayouts],
      ["duffel", form.duffel],
    ] as const
  )
    .filter(([, enabled]) => enabled)
    .map(([id]) => id);
  if (!providers.length) fail("serpapi", "Select at least one provider to plan a search.");
  const searchWidth = outboundDays * origins.length * destinations.length * providers.length;
  if (searchWidth > 2000) {
    fail(
      "outTo",
      "Search matrix exceeds 2,000 base provider calls. Narrow airports, dates or providers.",
    );
  }
  const market = form.market.trim().toLowerCase();
  if (!/^[a-z]{2}$/.test(market))
    fail("market", "Market must be a two-letter country code (e.g. uk).");
  const currency = form.currency.trim().toUpperCase();
  if (!/^[A-Z]{3}$/.test(currency)) fail("currency", "Currency must be a three-letter ISO code.");
  const investigation = form.investigation.trim();
  if (investigation && !/^[a-z0-9][a-z0-9_-]{0,99}$/.test(investigation)) {
    fail(
      "investigation",
      "Investigation ID must be lowercase letters, numbers, hyphens or underscores.",
    );
  }
  const adults = integer(form.adults, "adults", 1, 9)!;
  const checkedBags = integer(form.bags, "bags", 0, 4)!;
  const returnChoices = integer(form.returnChoices, "returnChoices", 1, 8)!;
  const maxLiveCalls = integer(form.maxLiveCalls, "maxLiveCalls", 0, 2000, true);
  if (!form.cacheOnly && maxLiveCalls != null && searchWidth > maxLiveCalls) {
    fail(
      "maxLiveCalls",
      "Call cap is below the base-search upper bound. Raise the cap or narrow the query.",
    );
  }
  return {
    origins,
    destinations,
    outboundFrom,
    outboundTo,
    returnFrom: tripType === "one_way" ? null : returnFrom,
    returnTo: tripType === "one_way" ? null : returnTo,
    nights: tripType === "one_way" ? null : nights,
    tripType,
    adults,
    cabin: form.cabin,
    cabinShortHaul: form.cabinShort,
    strictPerLegCabin: form.strictPerLegCabin,
    strictOperatingCarrier: form.strictOperatingCarrier,
    checkedBags,
    excludedAirlines: codes(form.excludedAirlines, "excludedAirlines", /^[A-Za-z0-9]{2,3}$/, 32),
    excludedTransitAirports: codes(
      form.excludedTransitAirports,
      "excludedTransitAirports",
      /^[A-Za-z]{3}$/,
      32,
    ),
    excludedTransitCountries: codes(
      form.excludedTransitCountries,
      "excludedTransitCountries",
      /^[A-Za-z]{2}$/,
      32,
    ),
    currency,
    market,
    investigation: investigation || null,
    maxLiveCalls,
    providers,
    nonstopOnly: form.nonstop,
    returnChoices,
    fetchReturns: tripType === "round_trip" && form.fetchReturns,
    cacheOnly: form.cacheOnly,
    confirmed: false,
  };
}

export function estimateIsRunnable(
  warnings: readonly string[],
  liveCalls: number,
  cachedCalls: number,
  returnLookups: number,
  cacheOnly: boolean,
): boolean {
  return (
    !warnings.some((warning) =>
      /Enter at least|Check the dates|Set a return|No outbound|Narrow/.test(warning),
    ) &&
    (cacheOnly || liveCalls > 0 || cachedCalls > 0 || returnLookups > 0)
  );
}
