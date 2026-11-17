/**
 * Deliberately synthetic, token-free flight data for credential-free UI testing.
 * All amounts and routes are illustrative, not current or bookable offers.
 * This fixture is bundled into the static client; guest mode makes zero requests.
 */
import type { ComboCell, Direction, Itinerary, Leg, ResultSet, VendorRow } from "./domain.ts";

function segment(
  flightNumber: string,
  airline: string,
  airlineCode: string,
  from: string,
  to: string,
  date: string,
  departTime: string,
  arriveTime: string,
): Leg {
  return {
    flightNumber,
    airline,
    airlineCode,
    operatingCarrier: airline,
    from,
    to,
    departDate: date,
    arriveDate: date,
    departTime,
    arriveTime,
    operatingCarrierVerified: false,
  };
}
function route(...legs: Leg[]): Direction {
  return { legs, layovers: [], stops: legs.length - 1 };
}
interface FlightInput {
  id: string;
  origin: string;
  destination: string;
  depart: string;
  returning: string;
  price: number;
  airline: string;
  code: string;
  outward: Direction;
  inbound: Direction;
}
function flight(input: FlightInput): Itinerary {
  return {
    id: input.id,
    pricePerPerson: input.price,
    priceOriginal: input.price,
    priceCurrency: "GBP",
    passengers: 1,
    cabin: "Premium economy",
    cabinKey: "premium_economy",
    euPremium: false,
    origin: input.origin,
    destination: input.destination,
    outboundDate: input.depart,
    returnDate: input.returning,
    airlineSummary: input.airline,
    outbound: input.outward,
    returning: input.inbound,
    returnMissing: false,
    stopsOut: input.outward.stops,
    stopsRet: input.inbound.stops,
    layoverMinutes: [],
    airlineCodes: [input.code],
    source: "sample",
    cashRefundProofVerified: false,
    sourceLabel: "sample",
    livePrice: false,
    links: [],
    notes: "Synthetic guest-test itinerary. No airline or seller was contacted.",
    assumedReturn: false,
  };
}
const itineraries: Itinerary[] = [
  flight({
    id: "guest-lhr-direct",
    origin: "LHR",
    destination: "TPA",
    depart: "2026-12-16",
    returning: "2026-12-27",
    price: 1140,
    airline: "Example Atlantic",
    code: "EX",
    outward: route(
      segment("EX 101", "Example Atlantic", "EX", "LHR", "TPA", "2026-12-16", "10:30", "15:15"),
    ),
    inbound: route(
      segment("EX 102", "Example Atlantic", "EX", "TPA", "LHR", "2026-12-27", "19:30", "08:45"),
    ),
  }),
  flight({
    id: "guest-lgw-connecting",
    origin: "LGW",
    destination: "TPA",
    depart: "2026-12-17",
    returning: "2026-12-28",
    price: 960,
    airline: "Example Air",
    code: "EA",
    outward: route(
      segment("EA 204", "Example Air", "EA", "LGW", "ATL", "2026-12-17", "09:00", "13:00"),
      segment("EA 716", "Example Air", "EA", "ATL", "TPA", "2026-12-17", "15:00", "16:30"),
    ),
    inbound: route(
      segment("EA 203", "Example Air", "EA", "TPA", "LGW", "2026-12-28", "18:00", "07:15"),
    ),
  }),
  flight({
    id: "guest-lhr-later",
    origin: "LHR",
    destination: "TPA",
    depart: "2026-12-20",
    returning: "2026-12-30",
    price: 1280,
    airline: "Example Atlantic",
    code: "EX",
    outward: route(
      segment("EX 105", "Example Atlantic", "EX", "LHR", "TPA", "2026-12-20", "11:00", "16:00"),
    ),
    inbound: route(
      segment("EX 106", "Example Atlantic", "EX", "TPA", "LHR", "2026-12-30", "20:00", "09:00"),
    ),
  }),
];
const addons: Itinerary[] = [
  flight({
    id: "guest-mex-25",
    origin: "TPA",
    destination: "MEX",
    depart: "2026-12-25",
    returning: "2026-12-26",
    price: 210,
    airline: "Example Mexico",
    code: "EM",
    outward: route(
      segment("EM 51", "Example Mexico", "EM", "TPA", "MEX", "2026-12-25", "09:30", "11:50"),
    ),
    inbound: route(
      segment("EM 52", "Example Mexico", "EM", "MEX", "TPA", "2026-12-26", "16:00", "19:45"),
    ),
  }),
  flight({
    id: "guest-mex-29",
    origin: "TPA",
    destination: "MEX",
    depart: "2026-12-29",
    returning: "2026-12-30",
    price: 195,
    airline: "Example Mexico",
    code: "EM",
    outward: route(
      segment("EM 53", "Example Mexico", "EM", "TPA", "MEX", "2026-12-29", "09:30", "11:50"),
    ),
    inbound: route(
      segment("EM 54", "Example Mexico", "EM", "MEX", "TPA", "2026-12-30", "16:00", "19:45"),
    ),
  }),
];
const cells: ComboCell[] = [
  {
    primaryId: "guest-lhr-direct",
    addonId: "guest-mex-25",
    status: "ok",
    total: 1350,
    reasonsNo: [],
    catches: [],
    oks: ["Add-on finishes before the flight home."],
  },
  {
    primaryId: "guest-lhr-direct",
    addonId: "guest-mex-29",
    status: "no",
    total: 1335,
    reasonsNo: ["The add-on returns after the transatlantic flight."],
    catches: [],
    oks: [],
  },
  {
    primaryId: "guest-lgw-connecting",
    addonId: "guest-mex-25",
    status: "catch",
    total: 1170,
    reasonsNo: [],
    catches: ["Separate tickets require a self-transfer."],
    oks: [],
  },
  {
    primaryId: "guest-lgw-connecting",
    addonId: "guest-mex-29",
    status: "no",
    total: 1155,
    reasonsNo: ["The add-on returns after the transatlantic flight."],
    catches: [],
    oks: [],
  },
  {
    primaryId: "guest-lhr-later",
    addonId: "guest-mex-25",
    status: "ok",
    total: 1490,
    reasonsNo: [],
    catches: [],
    oks: ["Add-on finishes before the flight home."],
  },
  {
    primaryId: "guest-lhr-later",
    addonId: "guest-mex-29",
    status: "no",
    total: 1475,
    reasonsNo: ["The add-on returns on the same day as the flight home."],
    catches: [],
    oks: [],
  },
];
function vendor(vendor: string, trust: string, trustLabel: string, price: number): VendorRow {
  return {
    vendor,
    market: "uk",
    marketLabel: "United Kingdom",
    currency: "GBP",
    fareBrand: "Illustrative",
    trust,
    trustLabel,
    note: "Synthetic seller row; no offer or booking is available.",
    roundTrip: price,
    allInGbp: price,
    how: "Round trip",
    bags: "Example baggage only",
    mix: false,
    fresh: false,
    isNew: false,
    links: [],
  };
}
export const GUEST_SAMPLE: ResultSet = {
  id: "guest-florida-demo",
  title: "Guest demo · London to Florida with Mexico City add-on",
  subtitle:
    "Entirely synthetic example for testing layout, filters, and itinerary details. No current fares.",
  fetchedAt: null,
  currency: "GBP",
  passengers: 1,
  checkedBags: 1,
  itineraries,
  addons,
  cells,
  vendors: [
    vendor("Demo Airline", "airline", "Airline", 1200),
    vendor("Demo Travel Agent", "caution", "OTA · caution", 1090),
  ],
  sellerOptions: [],
  vendorIntro: [],
  headerNotes: [
    {
      title: "Guest mode",
      body: "Illustrative flight data; no account, storage service or provider access.",
      warn: false,
    },
  ],
  suggestedFilters: {},
  warnings: ["Sample data only. Never book or rely on these prices."],
  fx: { perGbp: { GBP: 1 }, asOf: "2026-11-17" },
  scenarioLabel: "guest",
  query: null,
  vendorItineraryId: null,
};
