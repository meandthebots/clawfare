import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildSearchQuery,
  describeSearchPolicy,
  estimateIsRunnable,
  INITIAL_SEARCH,
  restoreSearchForm,
} from "./searchForm.ts";
import { filterChoices, initialFilters, suggestedFilters } from "./filterForm.ts";
import type { ResultSet } from "./domain.ts";

const validForm = {
  ...INITIAL_SEARCH,
  origins: "LHR, LGW",
  destinations: "TPA SRQ",
  outFrom: "2027-04-12",
  outTo: "",
  nights: "8",
  serpapi: true,
  maxLiveCalls: "16",
};

test("search form parses the complete explicit Kotlin hard-policy contract", () => {
  const query = buildSearchQuery({
    ...validForm,
    tripType: "round_trip",
    adults: "2",
    bags: "1",
    cabin: "business",
    cabinShort: "economy",
    strictPerLegCabin: true,
    strictOperatingCarrier: true,
    excludedAirlines: "LO, ba",
    excludedTransitAirports: "WAW, ist",
    excludedTransitCountries: "pl tr",
    market: "jp",
    currency: "JPY",
    investigation: "japan-2027",
    fetchReturns: false,
  });
  assert.deepEqual(query.origins, ["LHR", "LGW"]);
  assert.deepEqual(query.destinations, ["TPA", "SRQ"]);
  assert.equal(query.nights, 8);
  assert.equal(query.outboundTo, query.outboundFrom);
  assert.deepEqual(query.providers, ["serpapi"]);
  assert.deepEqual(query.excludedAirlines, ["LO", "BA"]);
  assert.deepEqual(query.excludedTransitAirports, ["WAW", "IST"]);
  assert.deepEqual(query.excludedTransitCountries, ["PL", "TR"]);
  assert.equal(query.market, "jp");
  assert.equal(query.currency, "JPY");
  assert.equal(query.investigation, "japan-2027");
  assert.equal(query.adults, 2);
  assert.equal(query.strictPerLegCabin, true);
  assert.equal(query.strictOperatingCarrier, true);
  assert.equal(query.fetchReturns, false);
  assert.equal(query.maxLiveCalls, 16);
  assert.equal(query.confirmed, false);
});
test("saved policy restores search constraints without reusing the investigation ID", () => {
  const canonical = buildSearchQuery({
    ...validForm,
    investigation: "existing-result",
    excludedTransitCountries: "CN",
    strictOperatingCarrier: true,
    adults: "2",
    bags: "1",
    cabin: "business",
  });
  const restored = restoreSearchForm(canonical);
  assert.ok(restored);
  assert.deepEqual(buildSearchQuery(restored), { ...canonical, investigation: null });
  assert.match(
    describeSearchPolicy(canonical).join("\n"),
    /LHR, LGW → TPA, SRQ[\s\S]+2 adults[\s\S]+Transit countries excluded · CN/,
  );
  assert.equal(restoreSearchForm(null), null);
});

test("generic search has no Florida dates, airports, or preselected paid providers", () => {
  assert.equal(INITIAL_SEARCH.origins, "");
  assert.equal(INITIAL_SEARCH.destinations, "");
  assert.equal(INITIAL_SEARCH.outFrom, "");
  assert.equal(INITIAL_SEARCH.serpapi, false);
  assert.equal(INITIAL_SEARCH.duffel, false);
});
test("one-way removes return constraints and cannot silently carry stale nights", () => {
  const q = buildSearchQuery({ ...validForm, tripType: "one_way", nights: "", fetchReturns: true });
  assert.equal(q.returnFrom, null);
  assert.equal(q.nights, null);
  assert.equal(q.fetchReturns, false);
  assert.throws(() => buildSearchQuery({ ...validForm, tripType: "one_way" }), /One-way/);
});
test("invalid hard-policy fields fail before any backend call instead of coercing", () => {
  const cases = [
    [{ origins: "LHR, NOTANACODE" }, /origins codes/],
    [{ destinations: "LHR" }, /must not overlap/],
    [{ outTo: "2027-04-01" }, /end date/],
    [{ outFrom: "2027-02-30" }, /calendar date/],
    [{ nights: "", retFrom: "" }, /require nights or a return/],
    [{ retFrom: "2027-04-20", nights: "8" }, /either a fixed/],
    [{ adults: "2.5" }, /whole number/],
    [{ bags: "-1" }, /whole number/],
    [{ maxLiveCalls: "0" }, /below the base/],
    [{ excludedTransitCountries: "GB, GBR" }, /excludedTransitCountries codes/],
    [{ investigation: "../secret" }, /Investigation ID/],
    [{ currency: "US" }, /Currency must/],
    [{ market: "United Kingdom" }, /Market must/],
    [{ serpapi: false }, /Select at least one provider/],
    [{ outTo: "2027-05-31" }, /31-day range/],
  ] as const;
  for (const [changes, pattern] of cases) {
    assert.throws(() => buildSearchQuery({ ...validForm, ...changes }), pattern);
  }
});
test("estimates fail closed on validation warnings and require some potential work", () => {
  assert.equal(estimateIsRunnable(["No outbound dates"], 10, 0, 0, false), false);
  assert.equal(estimateIsRunnable([], 0, 0, 0, false), false);
  assert.equal(estimateIsRunnable([], 0, 2, 0, false), true);
  assert.equal(estimateIsRunnable([], 0, 0, 0, true), true);
});
test("suggested filters preserve defaults and correctly reset segment state", () => {
  assert.equal(initialFilters().hideMock, true);
  const f = suggestedFilters({
    cabin: "business",
    nights: 7,
    origin: "LHR",
    excludeReturnDates: ["2026-12-24"],
    maxLayoverMinutes: 120,
    hideMock: false,
  });
  assert.equal(f.cabin, "business");
  assert.equal(f.nights, "7");
  assert.equal(f.lhr, true);
  assert.equal(f.maxLayoverHours, "2");
  assert.equal(f.hideMock, false);
  assert.equal(f.sort, "date");
});
test("dynamic segments come from the current investigation only", () => {
  const fixture = {
    itineraries: [
      { nights: 10, origin: "LHR" },
      { nights: 7, origin: "LGW" },
      { nights: 7, origin: "LHR" },
      { nights: null, origin: "" },
    ],
  } as ResultSet;
  assert.deepEqual(filterChoices(fixture), {
    nights: ["all", "7", "10"],
    origins: ["all", "LGW", "LHR"],
  });
  assert.deepEqual(filterChoices(null), { nights: ["all"], origins: ["all"] });
});
