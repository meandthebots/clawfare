// Fixed, completely synthetic data for the offline credential-free guest page.
// Never import live/cached provider responses or user investigations into this fixture.
const leg = (flightNumber, airline, from, to, departDate, departTime, arriveDate, arriveTime) => ({
  flightNumber, airline, airlineCode: "GX", operatingCarrier: airline,
  from, to, departDate, departTime, arriveDate, arriveTime,
  operatingCarrierVerified: false,
});
const direction = (legs, layovers = []) => ({
  legs, layovers, stops: Math.max(0, legs.length - 1),
});
const outbound1 = direction([
  leg("GX 101", "Guest Air (fictional)", "LHR", "TPA", "2026-12-16", "09:00", "2026-12-16", "15:00"),
]);
const returning1 = direction([
  leg("GX 102", "Guest Air (fictional)", "TPA", "LHR", "2026-12-24", "18:00", "2026-12-25", "07:00"),
]);
const outbound2 = direction([
  leg("GX 201", "Guest Connect (fictional)", "LGW", "AMS", "2026-12-17", "08:00", "2026-12-17", "10:30"),
  leg("GX 202", "Guest Connect (fictional)", "AMS", "TPA", "2026-12-17", "12:00", "2026-12-17", "17:00"),
], [{ airport: "AMS", minutes: 90 }]);
const returning2 = direction([
  leg("GX 203", "Guest Connect (fictional)", "TPA", "AMS", "2026-12-25", "18:00", "2026-12-26", "08:00"),
  leg("GX 204", "Guest Connect (fictional)", "AMS", "LGW", "2026-12-26", "09:30", "2026-12-26", "10:30"),
], [{ airport: "AMS", minutes: 90 }]);
function itinerary({ id, origin, destination, outboundDate, returnDate, outbound, returning,
  pricePerPerson, passengers, cabin, stops, airlineSummary, notes }) {
  return {
    id, origin, destination, outboundDate, returnDate,
    nights: 8, pricePerPerson, priceCurrency: "GBP", passengers,
    cabin, cabinKey: "premium_economy", euPremium: false,
    airlineSummary, outbound, returning, returnMissing: false,
    stopsOut: stops, stopsRet: returning.stops, layoverMinutes: outbound.layovers.map(l => l.minutes),
    maxLayoverMinutes: outbound.layovers.length ? Math.max(...outbound.layovers.map(l => l.minutes)) : 0,
    airlineCodes: ["GX"], source: "sample", sourceLabel: "sample",
    livePrice: false, cashRefundProofVerified: false,
    links: [], notes, assumedReturn: false,
  };
}
const flights = [
  itinerary({
    id: "guest-lh-1", origin: "LHR", destination: "TPA",
    outboundDate: "2026-12-16", returnDate: "2026-12-24",
    outbound: outbound1, returning: returning1, pricePerPerson: 1250,
    passengers: 2, cabin: "Premium economy", stops: 0,
    airlineSummary: "Guest Air (fictional)",
    notes: "Synthetic itinerary; not a real fare, airline, or booking offer.",
  }),
  itinerary({
    id: "guest-lh-2", origin: "LGW", destination: "TPA",
    outboundDate: "2026-12-17", returnDate: "2026-12-25",
    outbound: outbound2, returning: returning2, pricePerPerson: 990,
    passengers: 2, cabin: "Premium economy", stops: 1,
    airlineSummary: "Guest Connect (fictional)",
    notes: "Synthetic connection for testing flight filters and details.",
  }),
];
const addons = [
  itinerary({
    id: "guest-mx-1", origin: "TPA", destination: "MEX",
    outboundDate: "2026-12-21", returnDate: "2026-12-23",
    outbound: direction([leg("GX 301", "Guest Mexico (fictional)", "TPA", "MEX", "2026-12-21", "11:00", "2026-12-21", "14:00")]),
    returning: direction([leg("GX 302", "Guest Mexico (fictional)", "MEX", "TPA", "2026-12-23", "16:00", "2026-12-23", "20:00")]),
    pricePerPerson: 470, passengers: 1, cabin: "Economy", stops: 0,
    airlineSummary: "Guest Mexico (fictional)", notes: "Entirely synthetic add-on.",
  }),
  itinerary({
    id: "guest-mx-2", origin: "TPA", destination: "MEX",
    outboundDate: "2026-12-22", returnDate: "2026-12-24",
    outbound: direction([leg("GX 303", "Guest Mexico (fictional)", "TPA", "MEX", "2026-12-22", "09:00", "2026-12-22", "12:00")]),
    returning: direction([leg("GX 304", "Guest Mexico (fictional)", "MEX", "TPA", "2026-12-24", "13:00", "2026-12-24", "17:00")]),
    pricePerPerson: 430, passengers: 1, cabin: "Economy", stops: 0,
    airlineSummary: "Guest Mexico (fictional)", notes: "Entirely synthetic add-on.",
  }),
];
const cell = (primaryId, addonId, status, total, description) => ({
  primaryId, addonId, status, total, reasonsNo: status === "no" ? [description] : [],
  catches: status === "catch" ? [description] : [],
  oks: status === "ok" ? [description] : [],
});
const vendor = (vendor, market, price, trust) => ({
  vendor, market, marketLabel: market === "uk" ? "United Kingdom" : "United States",
  currency: "GBP", fareBrand: "Synthetic only", trust,
  trustLabel: trust === "airline" ? "Airline" : "Caution",
  note: "Fabricated seller for UI testing. No inventory or refund guarantee.",
  roundTrip: price, roundTripWithBag: price,
  allInGbp: price, how: "Guest fixture", bags: "Not verified",
  mix: false, fresh: false, isNew: true, links: [],
});
export const GUEST_SAMPLE = {
  id: "guest-florida-synthetic",
  title: "Guest preview — synthetic Florida itinerary",
  subtitle: "Fictional flights and prices only. No real search, booking, or refund evidence.",
  fetchedAt: "2026-11-17T00:00:00Z",
  currency: "GBP", checkedBags: 1, passengers: 2,
  itineraries: flights, addons,
  cells: [
    cell("guest-lh-1", null, "ok", 2500, "Long-haul only."),
    cell("guest-lh-1", "guest-mx-1", "ok", 2970, "Connection works in this fabricated scenario."),
    cell("guest-lh-1", "guest-mx-2", "catch", 2930, "Short connection; verify in a real search."),
    cell("guest-lh-2", null, "ok", 1980, "Long-haul only."),
    cell("guest-lh-2", "guest-mx-1", "no", 2450, "Connection is incompatible in this scenario."),
    cell("guest-lh-2", "guest-mx-2", "ok", 2410, "Connection works in this fabricated scenario."),
  ],
  vendors: [vendor("Guest Air Direct (fictional)", "uk", 1270, "airline"),
    vendor("Demo Seller (fictional)", "us", 1310, "caution")],
  sellerOptions: [],
  vendorIntro: [{ title: "Guest sellers", body: "Fabricated, no booking URLs or policy evidence.", warn: true }],
  headerNotes: [{ title: "Credential-free demo", body: "No actual flight searches. All prices and airlines are synthetic.", warn: true }],
  suggestedFilters: {},
  warnings: ["GUEST PREVIEW ONLY — all flights, prices, and sellers are fictional. No paid calls or booking capability."],
  fx: { perGbp: { GBP: 1, USD: 1.25, MXN: 24 }, asOf: "2026-11-17" },
  scenarioLabel: "guest",
};
