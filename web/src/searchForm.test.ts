import assert from "node:assert/strict";
import { test } from "node:test";
import { buildSearchQuery, estimateIsRunnable, INITIAL_SEARCH } from "./searchForm.ts";
import { filterChoices, initialFilters, suggestedFilters } from "./filterForm.ts";
import type { ResultSet } from "./domain.ts";

test("search form creates exactly the JVM SearchQuery contract", () => {
  const query = buildSearchQuery({
    ...INITIAL_SEARCH,
    origins: "LHR, LGW",
    destinations: "TPA SRQ",
    nights: "",
    outTo: "",
    duffel: false,
    serpapi: false,
    searchapi: false,
  });
  assert.deepEqual(query.origins, ["LHR", "LGW"]);
  assert.deepEqual(query.destinations, ["TPA", "SRQ"]);
  assert.equal(query.nights, null);
  assert.equal(query.outboundTo, query.outboundFrom);
  assert.deepEqual(query.providers, ["travelpayouts"]);
  assert.equal(query.confirmed, false);
  assert.equal(query.currency, "GBP");
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
