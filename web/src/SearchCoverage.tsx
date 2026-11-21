import type { Itinerary, ResultSet, RouteCoverage, SearchCoverage } from "./domain.ts";

const stateLabel: Record<RouteCoverage["state"], string> = {
  not_attempted: "Not attempted",
  complete: "Completed",
  empty: "Provider returned no offers",
  partial: "Partial response",
  failed: "Provider request failed",
};

export function itineraryEmptyMessage(result: ResultSet): string {
  const coverage = result.coverage;
  if (result.itineraries.length) return "No itineraries match these filters.";
  if (!coverage || coverage.routes.every((route) => route.state === "not_attempted")) {
    return "No itineraries are stored for this search.";
  }
  if (coverage.state === "failed")
    return "The provider search failed before any offers could be evaluated.";
  if (coverage.state === "partial")
    return "The incomplete search produced no eligible itineraries.";
  if (coverage.routes.every((route) => route.state === "empty")) {
    return "The provider returned no offers for these routes and dates.";
  }
  return "None of the evaluated offers met the search requirements.";
}

export function hasComparableGbpFare(itinerary: Itinerary, result: ResultSet): boolean {
  const amount = itinerary.pricePerPerson;
  return (
    !itinerary.returnMissing &&
    itinerary.passengers === result.passengers &&
    itinerary.priceCurrency === "GBP" &&
    amount != null &&
    Number.isFinite(amount) &&
    amount > 0
  );
}

export function CoverageDetails({ coverage }: { coverage: SearchCoverage }) {
  const routes = coverage.routes;
  const attempted = routes.filter((route) => route.state !== "not_attempted").length;
  const finished = routes.filter(
    (route) => route.state === "complete" || route.state === "empty",
  ).length;
  const totals = routes.reduce(
    (sum, route) => ({
      returned: sum.returned + (route.providerRows ?? 0),
      parsed: sum.parsed + (route.parsedOffers ?? 0),
    }),
    { returned: 0, parsed: 0 },
  );
  const label =
    coverage.state === "complete"
      ? "Completed"
      : coverage.state === "partial"
        ? "Incomplete"
        : "Failed";
  return (
    <details className="banner">
      <summary>
        Search coverage · {label} · {finished} of {routes.length} routes completed ·{" "}
        {coverage.eligibleItineraries} eligible itineraries
      </summary>
      <p>
        {attempted} attempted · {totals.returned} provider rows received · {totals.parsed} parsed ·{" "}
        <time dateTime={coverage.observedAt}>{new Date(coverage.observedAt).toLocaleString()}</time>
      </p>
      <ul>
        {routes.map((route) => (
          <li
            key={[
              route.provider,
              route.market,
              route.origin,
              route.destination,
              route.outboundDate,
              route.returnDate,
            ].join("/")}
          >
            {route.origin} → {route.destination} · {route.outboundDate}
            {route.returnDate ? ` / ${route.returnDate}` : ""} · {route.provider} · {route.market} ·{" "}
            {stateLabel[route.state]}
            {route.sourceMode ? ` · ${route.sourceMode}` : ""}
            {route.errorKind ? ` · ${route.errorKind.replaceAll("_", " ")}` : ""}
            {route.parsedOffers != null
              ? ` · ${route.providerRows ?? "unknown"} received, ${route.parsedOffers} parsed, ${route.eligibleOffers} eligible`
              : ""}
            {route.unresolvedReturns
              ? ` · ${route.unresolvedReturns} missing return selections`
              : ""}
            {route.returnSelectionLimited ? " · return selection limited by the search cap" : ""}
          </li>
        ))}
      </ul>
      {Object.keys(coverage.excludedByReason).length > 0 && (
        <p>
          Excluded by the search requirements{" "}
          {Object.entries(coverage.excludedByReason)
            .map(
              ([reason, count]) =>
                `${reason
                  .replace(/([a-z])([A-Z])/g, "$1 $2")
                  .replaceAll("_", " ")
                  .toLowerCase()} (${count})`,
            )
            .join(", ")}
        </p>
      )}
    </details>
  );
}
