/**
 * Resolve the published Kotlin/JS module emitted by Gradle and Webpack.
 * The module's namespaced API is explicit; no reflective or guessed fallback
 * to a handwritten JS implementation is permitted.
 */
export function kotlinEngineFromBundle(bundle) {
  const root = bundle?.default ?? bundle;
  const search = root?.["fare-core"]?.com?.clawfare?.search;
  const methods = [
    "normalizeSearchForHost",
    "planSearchForHost",
    "projectBudgetForHost",
    "parseGoogleFlightsOffersForHost",
    "evaluateOfferEligibilityForHost",
    "toGbpForHost",
  ];
  if (!search || methods.some(name => typeof search[name] !== "function")) {
    throw new Error("Incompatible Kotlin/JS Clawfare fare-core bundle; check compiler and exports");
  }
  return Object.freeze({
    normalizeSearchJson: queryJson => search.normalizeSearchForHost(queryJson),
    planSearchJson: queryJson => search.planSearchForHost(queryJson),
    projectBudgetJson: (queryJson, providersJson) =>
      search.projectBudgetForHost(queryJson, providersJson),
    parseGoogleFlightsOffersJson: body => search.parseGoogleFlightsOffersForHost(body),
    evaluateOfferEligibilityJson: request => search.evaluateOfferEligibilityForHost(request),
    toGbpOrNull: (amount, unitsPerGbp) => search.toGbpForHost(amount, unitsPerGbp),
  });
}
