import { digestFor, readProviderJson, SerpApiTransportError } from "./serpapi-metered-transport.mjs";

const TRAVELPAYOUTS_URL = "https://api.travelpayouts.com/aviasales/v3/prices_for_dates";
const DUFFEL_URL = "https://api.duffel.com/air/offer_requests?return_offers=false";
const AIRPORT = /^[A-Z]{3}$/;
const CARRIER = /^[A-Z0-9]{2,3}$/;
const DATE_TIME = /^\d{4}-\d\d-\d\dT\d\d:\d\d/;
const CURRENCY = /^[A-Z]{3}$/;
const currencyCode = value => typeof value === "string" ? value.toUpperCase() : "";
const integer = value => Number.isSafeInteger(value) && value >= 0;
const positiveAmount = value => {
  const number = typeof value === "string" && /^\d+(?:\.\d+)?$/.test(value) ? Number(value) : value;
  return typeof number === "number" && Number.isFinite(number) && number > 0 ? number : null;
};

export function directProviderRequest(provider, query, route, key) {
  const { origin, destination, outboundDate, returnDate } = route;
  if (provider === "travelpayouts") {
    const url = new URL(TRAVELPAYOUTS_URL);
    const params = {
      origin, destination, departure_at: outboundDate,
      currency: query.currency.toLowerCase(), market: query.market,
      one_way: String(!returnDate), direct: String(query.nonstopOnly),
      sorting: "price", limit: "30", unique: "false",
      ...(returnDate ? { return_at: returnDate } : {}),
    };
    for (const [name, value] of Object.entries(params)) url.searchParams.set(name, value);
    return { url: url.href, init: { headers: { "X-Access-Token": key, Accept: "application/json" } } };
  }
  const slices = [{ origin, destination, departure_date: outboundDate }];
  if (returnDate) slices.push({ origin: destination, destination: origin, departure_date: returnDate });
  return {
    url: DUFFEL_URL,
    init: {
      method: "POST",
      headers: {
        Authorization: `Bearer ${key}`, "Duffel-Version": "v2",
        Accept: "application/json", "Content-Type": "application/json",
      },
      body: JSON.stringify({ data: {
        slices, cabin_class: query.cabin,
        passengers: Array.from({ length: query.adults }, () => ({ type: "adult" })),
        ...(query.nonstopOnly ? { max_connections: 0 } : {}),
      } }),
    },
  };
}

function segmentLeg(segment, adults) {
  const from = segment?.origin?.iata_code;
  const to = segment?.destination?.iata_code;
  const dep = segment?.departing_at;
  const arr = segment?.arriving_at;
  const code = segment?.marketing_carrier?.iata_code;
  const number = segment?.marketing_carrier_flight_number;
  if (!AIRPORT.test(from ?? "") || !AIRPORT.test(to ?? "") ||
      !DATE_TIME.test(dep ?? "") || !DATE_TIME.test(arr ?? "") ||
      !CARRIER.test(code ?? "") || typeof number !== "string" || !/^\d{1,5}[A-Z]?$/.test(number)) return null;
  const operating = segment?.operating_carrier;
  const passengers = segment?.passengers;
  const cabins = Array.isArray(passengers) && passengers.length === adults
    ? [...new Set(passengers.map(passenger => passenger?.cabin_class))] : [];
  return {
    from, to, departDate: dep.slice(0, 10), arriveDate: arr.slice(0, 10),
    departTime: dep.slice(11, 16), arriveTime: arr.slice(11, 16),
    flightNumber: `${code} ${number}`, airline: segment.marketing_carrier.name || code, airlineCode: code,
    operatingCarrier: operating?.name || operating?.iata_code || "",
    operatingCarrierVerified: Boolean(operating?.name || operating?.iata_code),
    cabin: cabins.length === 1 && ["economy", "premium_economy", "business", "first"].includes(cabins[0])
      ? cabins[0] : null,
  };
}

function direction(slice, origin, destination, date, adults) {
  const segments = slice?.segments;
  if (!Array.isArray(segments) || !segments.length || segments.length > 12) return null;
  const legs = segments.map(segment => segmentLeg(segment, adults));
  if (legs.some(leg => !leg) || legs[0].from !== origin || legs.at(-1).to !== destination ||
      legs[0].departDate !== date || legs.some((leg, i) => i > 0 && legs[i - 1].to !== leg.from)) return null;
  const duration = /^PT(?:(\d+)H)?(?:(\d+)M)?$/.exec(slice.duration ?? "");
  return {
    legs, stops: legs.length - 1,
    durationMinutes: duration ? (Number(duration[1] ?? 0) * 60 + Number(duration[2] ?? 0)) : null,
  };
}

async function duffelDraft(offer, query, route, mode, fetchedAt, cryptoApi) {
  if (offer?.live_mode !== (mode === "live") || !Array.isArray(offer.slices) ||
      offer.slices.length !== (route.returnDate ? 2 : 1) ||
      !Array.isArray(offer.passengers) || offer.passengers.length !== query.adults ||
      !(Date.parse(offer.expires_at) > Date.parse(fetchedAt))) return null;
  const price = positiveAmount(offer.total_amount);
  const currency = offer.total_currency;
  if (price == null || !CURRENCY.test(currency ?? "")) return null;
  const { origin, destination, outboundDate, returnDate } = route;
  const outbound = direction(offer.slices[0], origin, destination, outboundDate, query.adults);
  const returning = returnDate ? direction(offer.slices[1], destination, origin, returnDate, query.adults) : null;
  if (!outbound || (returnDate && !returning)) return null;
  const fareBrand = offer.slices[0].fare_brand_name;
  const id = await digestFor(JSON.stringify([mode, currency, fareBrand,
    ...[outbound, returning].flatMap(dir => dir?.legs ?? []).map(leg =>
      [leg.flightNumber, leg.from, leg.to, leg.departDate, leg.departTime]),
  ]), cryptoApi);
  return {
    id: "duffel-" + id.slice(0, 40), source: "duffel", sourceLabel: mode,
    livePrice: mode === "live", observedAt: fetchedAt, priceCurrency: currency,
    originalPartyPrice: price, originalPerPersonPrice: price / query.adults,
    passengers: query.adults, cabinRaw: query.cabin,
    fareBrand: typeof fareBrand === "string" ? fareBrand.slice(0, 100) : null,
    outbound, returning, returnDateHint: returnDate, validUntil: new Date(offer.expires_at).toISOString(),
  };
}

async function travelpayoutsLead(item, responseCurrency, query, route, fetchedAt, cryptoApi) {
  const currency = currencyCode(item?.currency ?? responseCurrency ?? query.currency);
  if (!CURRENCY.test(currency) || (responseCurrency != null && currency !== responseCurrency)) return null;
  const { origin, destination, outboundDate, returnDate } = route;
  const date = time => DATE_TIME.test(time ?? "") ? time.slice(0, 10) : null;
  const price = positiveAmount(item?.price);
  if (price == null || (item.origin_airport ?? item.origin) !== origin ||
      (item.destination_airport ?? item.destination) !== destination ||
      date(item.departure_at) !== outboundDate || date(item.return_at) !== returnDate ||
      !integer(item.transfers) || (returnDate && !integer(item.return_transfers)) ||
      (query.nonstopOnly && (item.transfers !== 0 || (returnDate && item.return_transfers !== 0)))) return null;
  const carrier = CARRIER.test(item.airline ?? "") ? item.airline : "";
  const id = await digestFor(JSON.stringify([origin, destination, outboundDate, returnDate,
    item.departure_at, item.return_at, carrier, item.flight_number, item.transfers, item.return_transfers, currency,
  ]), cryptoApi);
  return {
    id: "travelpayouts-" + id.slice(0, 40), origin, destination, outboundDate, returnDate,
    source: "travelpayouts", sourceLabel: "cached", livePrice: false, fetchedAt,
    priceCurrency: currency, pricePerPerson: currency === "GBP" ? price : null,
    priceOriginal: currency === "GBP" ? null : price, passengers: 1,
    cabin: "Unverified", cabinKey: "economy", euPremium: false,
    outbound: { legs: [], layovers: [], stops: item.transfers },
    returning: returnDate ? { legs: [], layovers: [], stops: item.return_transfers } : null,
    stopsOut: item.transfers, stopsRet: returnDate ? item.return_transfers : null,
    airlineSummary: carrier, airlineCodes: carrier ? [carrier] : [],
    outboundDepart: item.departure_at.slice(11, 16), returnDepart: returnDate ? item.return_at.slice(11, 16) : null,
    layoverMinutes: [], links: [], returnMissing: false, cashRefundProofVerified: false,
    notes: "Cached calendar price lead. Cabin, operating carrier, fare rules and availability are unverified.",
  };
}

export async function duffelListRequest(response, mode, headers) {
  const { data } = await readProviderJson(response, 65_536);
  const id = data.data?.id;
  if (data.data?.live_mode !== (mode === "live") || !/^orq_[A-Za-z0-9]{1,64}$/.test(id ?? "")) {
    throw new SerpApiTransportError("invalid_response");
  }
  const url = new URL("https://api.duffel.com/air/offers");
  url.searchParams.set("offer_request_id", id);
  url.searchParams.set("limit", "25");
  url.searchParams.set("sort", "total_amount");
  return { url: url.href, init: { headers } };
}

export async function parseDirectProvider(response, provider, query, route, mode, fetchedAt, cryptoApi) {
  const { data: root } = await readProviderJson(response, 1_048_576);
  const duffel = provider === "duffel";
  const rows = root.data;
  const currency = root.currency == null ? null : currencyCode(root.currency);
  if (!Array.isArray(rows) || (!duffel && (root.success !== true || (currency != null && !CURRENCY.test(currency))))) {
    throw new SerpApiTransportError("invalid_response");
  }
  const normalize = duffel
    ? row => duffelDraft(row, query, route, mode, fetchedAt, cryptoApi)
    : row => travelpayoutsLead(row, currency, query, route, fetchedAt, cryptoApi);
  const items = (await Promise.all(rows.slice(0, 25).map(normalize))).filter(Boolean);
  return { items, providerRows: rows.length, moreAvailable: duffel ? Boolean(root.meta?.after) : rows.length >= 30 };
}
