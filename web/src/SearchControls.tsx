import type { SearchQuery } from "./domain.ts";
import { describeSearchPolicy } from "./searchForm.ts";
import * as stylex from "@stylexjs/stylex";
import type { SearchForm } from "./searchForm.ts";
import type { SavedInvestigation, Estimate, QuotaView } from "./domain.ts";

const styles = stylex.create({
  inputGrid: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit,minmax(160px,1fr))",
    gap: "10px 12px",
  },
  providerChecks: { marginTop: "10px" },
  muted: { fontSize: "12px", color: "var(--muted)" },
  button: { cursor: "pointer" },
  estimate: { fontSize: "13px", color: "var(--muted)", marginTop: "8px" },
  estimateListItem: { margin: "2px 0" },
});
export interface SearchControlsProps {
  form: SearchForm;
  onChange: (form: SearchForm) => void;
  saved: SavedInvestigation[];
  selectedSaved: string;
  onSavedChange: (id: string) => void;
  onOpen: (id: string) => void;
  onReuseSearch?: () => void;
  approvedPolicy?: SearchQuery;
  approvedPolicyDigest?: string;
  invalidSearchField?: keyof SearchForm;
  onEstimate: () => void;
  onSearch: () => void;
  onSample: () => void;
  onQuota: () => void;
  onStorage: () => void;
  runEnabled: boolean;
  hostedReadOnly: boolean;
  hostedExecutionAvailable: boolean;
  providerStatus: QuotaView[];
  isSearching: boolean;
  hostedEstimateOnly: boolean;
  guest: boolean;
  estimate: Estimate | null;
  estimateError: string;
  statusLines: string[];
}
export function SearchControls({
  form,
  onChange,
  saved,
  selectedSaved,
  onSavedChange,
  onOpen,
  onReuseSearch,
  approvedPolicy,
  approvedPolicyDigest,
  invalidSearchField,
  onEstimate,
  onSearch,
  onSample,
  onQuota,
  onStorage,
  runEnabled,
  hostedReadOnly,
  hostedExecutionAvailable,
  providerStatus,
  isSearching,
  hostedEstimateOnly,
  guest,
  estimate,
  estimateError,
  statusLines,
}: SearchControlsProps) {
  const update = <K extends keyof SearchForm>(field: K, value: SearchForm[K]) => {
    if (field === "tripType" && value === "one_way") {
      onChange({
        ...form,
        tripType: "one_way",
        nights: "",
        retFrom: "",
        retTo: "",
        fetchReturns: false,
      });
      return;
    }
    onChange({ ...form, [field]: value });
  };
  const input = (
    field: keyof SearchForm,
    id: string,
    type = "text",
    extra: Record<string, string> = {},
  ) => (
    <input
      id={id}
      data-search-field={field}
      aria-invalid={invalidSearchField === field || undefined}
      aria-describedby={invalidSearchField === field ? "estimate" : undefined}
      type={type}
      value={String(form[field])}
      onChange={(event) => update(field, event.target.value as never)}
      {...extra}
    />
  );
  const checked = (
    field: keyof SearchForm,
    id: string,
    text: string,
    suffix = "",
    unavailable = false,
  ) => (
    <label>
      <input
        id={id}
        data-search-field={field}
        aria-invalid={invalidSearchField === field || undefined}
        aria-describedby={invalidSearchField === field ? "estimate" : undefined}
        type="checkbox"
        checked={
          field === "fetchReturns" && form.tripType === "one_way" ? false : Boolean(form[field])
        }
        disabled={unavailable || (field === "fetchReturns" && form.tripType === "one_way")}
        onChange={(event) => update(field, event.target.checked as never)}
      />{" "}
      {text}
      {suffix && <span className="muted"> ({suffix})</span>}
    </label>
  );
  const provider = (
    field: "serpapi" | "searchapi" | "travelpayouts" | "duffel",
    id: string,
    label: string,
    mode = "",
  ) => {
    if (!hostedReadOnly) return checked(field, id, label, mode);
    const capability = providerStatus.find((entry) => entry.provider === field)?.hosted;
    const canEstimate =
      field === "serpapi" || capability?.oneWay === true || capability?.roundTrip === true;
    const available = capability?.state === "ready";
    const daily = capability?.dailyRemaining;
    const monthly = capability?.monthlyRemaining;
    const allowance = available && daily != null ? ` · ${daily} D1 requests left today` : "";
    const monthlyAllowance = available && monthly != null ? ` · ${monthly} this month` : "";
    const status = !canEstimate
      ? "unavailable on this Site"
      : available
        ? capability.priceMode + allowance + monthlyAllowance
        : "estimate only";
    return checked(
      field,
      id,
      label,
      [mode, status].filter(Boolean).join(" · "),
      guest || !canEstimate || !hostedEstimateOnly,
    );
  };
  const select = <K extends keyof SearchForm>(
    field: K,
    id: string,
    options: { value: string; label: string }[],
  ) => (
    <select
      id={id}
      data-search-field={field}
      aria-invalid={invalidSearchField === field || undefined}
      aria-describedby={invalidSearchField === field ? "estimate" : undefined}
      value={String(form[field])}
      onChange={(event) => update(field, event.target.value as SearchForm[K])}
    >
      {options.map(({ value, label }) => (
        <option key={value} value={value}>
          {label}
        </option>
      ))}
    </select>
  );
  const cabins = [
    { value: "economy", label: "Economy" },
    { value: "premium_economy", label: "Premium economy" },
    { value: "business", label: "Business" },
    { value: "first", label: "First" },
  ];
  return (
    <section className="card" id="searchCard">
      <div className={"search " + stylex.props(styles.inputGrid).className}>
        <label>Origins {input("origins", "origins", "text", { placeholder: "LHR, LGW" })}</label>
        <label>
          Destinations {input("destinations", "destinations", "text", { placeholder: "TPA, SRQ" })}
        </label>
        <label>Outbound from {input("outFrom", "outFrom", "date")}</label>
        <label>Outbound to {input("outTo", "outTo", "date")}</label>
        <label>
          Nights{" "}
          {input("nights", "nights", "number", {
            min: "1",
            max: "60",
            title:
              "Each outbound date returns this many nights later. Leave empty to use the return range.",
          })}
        </label>
        <label>Return from {input("retFrom", "retFrom", "date")}</label>
        <label>Return to {input("retTo", "retTo", "date")}</label>
        <label>
          Trip{" "}
          {select("tripType", "tripType", [
            { value: "round_trip", label: "Round trip" },
            { value: "one_way", label: "One way" },
          ])}
        </label>
        <label>Adults {input("adults", "adults", "number", { min: "1", max: "9" })}</label>
        <label>Checked bags {input("bags", "bags", "number", { min: "0", max: "4" })}</label>
        <label>Long-haul cabin {select("cabin", "cabin", cabins)}</label>
        <label>Short-haul cabin {select("cabinShort", "cabinShort", cabins.slice(0, 3))}</label>
        <label>
          Market (country){" "}
          {input("market", "market", "text", { placeholder: "uk", maxLength: "2" })}
        </label>
        <label>
          Currency {input("currency", "currency", "text", { placeholder: "GBP", maxLength: "3" })}
        </label>
        <label>
          Investigation ID (optional){" "}
          {input("investigation", "investigation", "text", { placeholder: "tokyo-spring" })}
        </label>
        <label>
          Hard provider-call cap{" "}
          {input("maxLiveCalls", "maxLiveCalls", "number", { min: "0", max: "2000" })}
        </label>
        <label>
          Return choices{" "}
          {input("returnChoices", "returnChoices", "number", {
            min: "1",
            max: "8",
            title: "Cheapest outbounds expanded with a return lookup",
          })}
        </label>
      </div>
      <div className={"checks " + stylex.props(styles.providerChecks).className}>
        {provider("serpapi", "pSerp", "SerpApi")}
        {provider("searchapi", "pSearch", "SearchAPI")}
        {provider("travelpayouts", "pTp", "Travelpayouts", "cached")}
        {provider("duffel", "pDuffel", "Duffel", "mock")}
        {checked("nonstop", "qNonstop", "Nonstop only")}
        {checked("cacheOnly", "cacheOnly", "Cache only")}
      </div>
      <fieldset className="checks" aria-label="Hard eligibility policy">
        <legend>Hard search constraints (applied before ranking, not table filters)</legend>
        <div className={"search " + stylex.props(styles.inputGrid).className}>
          <label>
            Exclude airlines (IATA codes){" "}
            {input("excludedAirlines", "excludedAirlines", "text", { placeholder: "LO, XX" })}
          </label>
          <label>
            Exclude transit airports{" "}
            {input("excludedTransitAirports", "excludedTransitAirports", "text", {
              placeholder: "WAW, IST",
            })}
          </label>
          <label>
            Exclude transit countries (ISO codes){" "}
            {input("excludedTransitCountries", "excludedTransitCountries", "text", {
              placeholder: "PL, TR",
            })}
          </label>
        </div>
        {checked("strictPerLegCabin", "strictPerLegCabin", "Require verified cabin on every leg")}
        {checked(
          "strictOperatingCarrier",
          "strictOperatingCarrier",
          "Require verified operating carrier on every leg",
        )}
        {checked("fetchReturns", "fetchReturns", "Fetch return selections", "round-trip only")}
        <div {...stylex.props(styles.muted)}>
          These restrictions change which fares can be retrieved and accepted; the filters below
          only hide already-retrieved itineraries. Estimate again after editing any constraint.
        </div>
      </fieldset>
      <div className="actions">
        <button
          type="button"
          id="btnEstimate"
          disabled={hostedReadOnly && !hostedEstimateOnly}
          onClick={onEstimate}
        >
          Estimate calls
        </button>
        <button
          type="button"
          id="btnRun"
          className="primary"
          disabled={(hostedReadOnly && !hostedExecutionAvailable) || !runEnabled || isSearching}
          aria-busy={isSearching}
          onClick={onSearch}
        >
          {isSearching
            ? "Searching…"
            : hostedExecutionAvailable
              ? "Confirm and run search"
              : "Run search"}
        </button>
        <button type="button" id="btnSample" onClick={onSample}>
          Load Florida sample
        </button>
        <button type="button" id="btnQuota" onClick={onQuota}>
          Check quota
        </button>
        <button
          type="button"
          id="btnStorage"
          onClick={onStorage}
          disabled={guest}
          title={
            guest
              ? "Guest mode has no D1 storage"
              : "Check the persisted public sample row in hosted D1"
          }
        >
          Check storage
        </button>
        <label className="muted">
          Investigations{" "}
          <select
            id="saved"
            value={selectedSaved}
            onChange={(event) => onSavedChange(event.target.value)}
          >
            <option value="">—</option>
            {saved.map((item) => (
              <option key={item.id} value={item.id}>
                {item.title || item.id}
              </option>
            ))}
          </select>
        </label>
        <button type="button" id="btnOpen" onClick={() => onOpen(selectedSaved)}>
          Open
        </button>
        {onReuseSearch && (
          <button type="button" onClick={onReuseSearch}>
            Use search settings
          </button>
        )}
      </div>
      <div id="invList" className="checks">
        {saved.map((item) => (
          <button type="button" key={item.id} data-inv={item.id} onClick={() => onOpen(item.id)}>
            {item.title || item.id}
          </button>
        ))}
      </div>
      <div id="statusLine" className={"est " + stylex.props(styles.estimate).className}>
        {statusLines.map((line, index) => (
          <div key={index}>{line}</div>
        ))}
      </div>
      <div
        id="estimate"
        role="status"
        aria-live="polite"
        className={"est " + stylex.props(styles.estimate).className}
      >
        {estimateError && <div>{estimateError}</div>}
        {approvedPolicy && (
          <details>
            <summary>Review the approved search settings</summary>
            <ul>
              {describeSearchPolicy(approvedPolicy).map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
            {approvedPolicyDigest && (
              <div title={approvedPolicyDigest}>
                Query fingerprint {approvedPolicyDigest.slice(0, 16)}
              </div>
            )}
          </details>
        )}
        {estimate && (
          <>
            {hostedReadOnly ? (
              <>
                <b>{estimate.liveCalls}</b> potential provider requests (not executed),{" "}
                <b>{estimate.skippedCalls}</b> skipped
              </>
            ) : (
              <>
                <b>{estimate.liveCalls}</b> live calls, <b>{estimate.cachedCalls}</b> already
                cached, <b>{estimate.skippedCalls}</b> skipped
              </>
            )}
            {estimate.returnLookupsUpperBound > 0 && (
              <>
                {" "}
                · up to <b>{estimate.returnLookupsUpperBound}</b> return lookups
              </>
            )}
            {estimate.quota.length > 0 && (
              <div>
                {estimate.quota.map((q: QuotaView) => (
                  <span key={q.provider}>
                    {q.provider}:{" "}
                    {q.remaining == null
                      ? "remaining unknown"
                      : q.remaining + (q.hosted ? " D1 requests remaining" : " remaining")}
                    {q.note ? " (" + q.note + ")" : ""} ·{" "}
                  </span>
                ))}
              </div>
            )}
            {estimate.warnings.map((warning, index) => (
              <div key={index}>{warning}</div>
            ))}
            {estimate.lines.length > 0 && (
              <ul>
                {estimate.lines.slice(0, 12).map((line, index) => (
                  <li key={index} {...stylex.props(styles.estimateListItem)}>
                    {line}
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </section>
  );
}
