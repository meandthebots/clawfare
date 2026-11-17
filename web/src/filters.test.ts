import assert from "node:assert/strict";
import { test } from "node:test";
import type { Itinerary, ResultSet } from "./domain.ts";
import { carrierMatch, matchesFilter, toFilterSpec, visibleItineraries, type FilterUiState } from "./filters.ts";

const baseState: FilterUiState = {
  maxStops: "", maxLayoverHours: "", excludeReturn: "", excludeOut: "",
  allow: "", block: "", maxTravelHours: "", departAfter: "", departBefore: "",
  returnAfter: "", nonstop: false, eu: false, hideMock: true, cashRefund: false,
  cabin: "all", nights: "all", origin: "all", hide: false, lhr: false,
};
const baseItinerary: Itinerary = {
  id: "lh", passengers: 2, cabin: "Premium economy", cabinKey: "premium_economy",
  euPremium: false, origin: "LHR", destination: "TPA", outboundDate: "2026-12-20",
  returnDate: "2026-12-28", nights: 8, airlineSummary: "British Airways",
  outbound: { legs: [{
    flightNumber: "BA216", airline: "British Airways", airlineCode: "BA",
    operatingCarrier: "British Airways", from: "LHR", to: "TPA",
    departTime: "09:30", arriveTime: "14:30", departDate: "2026-12-20",
    arriveDate: "2026-12-20",
  }], layovers: [], stops: 0 },
  returnMissing: false, stopsOut: 0, stopsRet: 0, layoverMinutes: [],
  travelMinutes: 720, outboundDepart: "09:30", returnDepart: "18:00",
  airlineCodes: ["BA"], source: "serpapi", cashRefundProofVerified: false,
  sourceLabel: "sample", livePrice: false, links: [], notes: "",
  assumedReturn: false, priceCurrency: "GBP", pricePerPerson: 500,
};
const result: ResultSet = {
  id: "fixture", title: "Fixture", subtitle: "", currency: "GBP",
  checkedBags: 0, passengers: 2,
  itineraries: [baseItinerary],
  addons: [{ ...baseItinerary, id: "mex" }],
  cells: [{ primaryId: "lh", addonId: "mex", status: "ok", total: 1200,
    reasonsNo: [], catches: [], oks: [] }],
  vendors: [], sellerOptions: [], vendorIntro: [], headerNotes: [],
  suggestedFilters: {}, warnings: [], fx: { asOf: "2026-10-11", perGbp: { GBP: 1 } },
};
const notVerified = () => false;

test("UI filters retain empty/null semantics and EU cabin choice", () => {
  const filters = toFilterSpec({ ...baseState, cabin: "EU", maxLayoverHours: "2.5",
    excludeReturn: "2026-12-24, 2026-12-25", lhr: true });
  assert.equal(filters.cabin, null);
  assert.equal(filters.europeanPremiumOnly, true);
  assert.equal(filters.maxLayoverMinutes, 150);
  assert.equal(filters.origin, "LHR");
  assert.deepEqual(filters.excludeReturnDates, ["2026-12-24", "2026-12-25"]);
});
test("airline selection uses marketing/operating codes and blocks unwanted carriers", () => {
  assert.equal(carrierMatch(baseItinerary.outbound.legs[0]!, "BA"), true);
  assert.equal(matchesFilter(baseItinerary, { airlineAllow: ["BA"] }, notVerified), true);
  assert.equal(matchesFilter(baseItinerary, { airlineAllow: ["AA"] }, notVerified), false);
  assert.equal(matchesFilter(baseItinerary, { airlineBlock: ["BA"] }, notVerified), false);
});
test("unknown refunds fail closed and no travel data means exclusion when required", () => {
  assert.equal(matchesFilter(baseItinerary, { verifiedCashRefundOnly: true }, notVerified), false);
  assert.equal(matchesFilter(baseItinerary, { maxTravelMinutes: 500 }, notVerified), false);
  assert.equal(matchesFilter({ ...baseItinerary, travelMinutes: null },
    { maxTravelMinutes: 800 }, notVerified), false);
});
test("date, nonstop, origins, cabin and mock filters are deterministic", () => {
  assert.equal(matchesFilter(baseItinerary, { maxStops: 0, origin: "lhr" }, notVerified), true);
  assert.equal(matchesFilter(baseItinerary, { excludeOutboundDates: ["2026-12-20"] }, notVerified), false);
  assert.equal(matchesFilter(baseItinerary, { cabin: "business" }, notVerified), false);
  assert.equal(matchesFilter(baseItinerary, { outboundDepartAfter: "10:00" }, notVerified), false);
  assert.equal(matchesFilter({ ...baseItinerary, sourceLabel: "mock" }, { hideMock: true }, notVerified), false);
});
test("hide-infeasible only keeps primary flights with feasible add-ons", () => {
  assert.deepEqual(visibleItineraries(result, {}, true, notVerified).map((it) => it.id), ["lh"]);
  const impossible: ResultSet = { ...result, cells: result.cells.map((cell) => ({ ...cell, status: "no" })) };
  assert.deepEqual(visibleItineraries(impossible, {}, true, notVerified), []);
  assert.equal(visibleItineraries(impossible, {}, false, notVerified).length, 1);
});
