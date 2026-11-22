export const HOSTED_PROVIDERS = Object.freeze({
  serpapi: { googleFlights: true, priceMode: "live" },
  searchapi: { googleFlights: true, priceMode: "live" },
  travelpayouts: { googleFlights: false, priceMode: "cached" },
  duffel: { googleFlights: false, priceMode: "mock", callsPerRoute: 2 },
});

export function hostedProvider(query) {
  const ids = query?.providers;
  return Array.isArray(ids) && ids.length === 1 && Object.hasOwn(HOSTED_PROVIDERS, ids[0])
    ? ids[0] : null;
}

export function hostedProviderProblem(query) {
  const provider = hostedProvider(query);
  if (!provider) return "Choose exactly one supported hosted provider.";
  if (query.currency !== "GBP") return "Hosted searches require GBP as the display currency.";
  if (query.cacheOnly) return "This search requires a provider request.";
  if (query.tripType === "round_trip" && HOSTED_PROVIDERS[provider].googleFlights && !query.fetchReturns) {
    return "Google Flights round trips require return selection.";
  }
  if (provider === "travelpayouts" && (query.adults !== 1 || query.cabin !== "economy" ||
      query.checkedBags > 0 || query.strictPerLegCabin || query.strictOperatingCarrier ||
      query.excludedAirlines?.length || query.excludedTransitAirports?.length ||
      query.excludedTransitCountries?.length)) {
    return "Travelpayouts supports cached price leads for one adult without cabin, baggage, carrier or transit requirements.";
  }
  return null;
}

export function providerSecret(value) {
  return typeof value === "string" && value.length > 0 && value.length <= 512 && !/\s/.test(value);
}

export function duffelMode(token, liveEnabled) {
  if (!providerSecret(token)) return null;
  if (token.startsWith("duffel_test_")) return "mock";
  if (liveEnabled === true && token.startsWith("duffel_live_")) return "live";
  return null;
}
