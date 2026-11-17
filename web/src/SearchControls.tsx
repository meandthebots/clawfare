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
});
export interface SearchControlsProps {
  form: SearchForm;
  onChange: (form: SearchForm) => void;
  saved: SavedInvestigation[];
  selectedSaved: string;
  onSavedChange: (id: string) => void;
  onOpen: (id: string) => void;
  onEstimate: () => void;
  onSearch: () => void;
  onSample: () => void;
  onQuota: () => void;
  onStorage: () => void;
  runEnabled: boolean;
  hostedReadOnly: boolean;
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
  onEstimate,
  onSearch,
  onSample,
  onQuota,
  onStorage,
  runEnabled,
  hostedReadOnly,
  hostedEstimateOnly,
  guest,
  estimate,
  estimateError,
  statusLines,
}: SearchControlsProps) {
  const update = <K extends keyof SearchForm>(field: K, value: SearchForm[K]) =>
    onChange({ ...form, [field]: value });
  const input = (
    field: keyof SearchForm,
    id: string,
    type = "text",
    extra: Record<string, string> = {},
  ) => (
    <input
      id={id}
      type={type}
      value={String(form[field])}
      onChange={(event) => update(field, event.target.value as never)}
      {...extra}
    />
  );
  const checked = (field: keyof SearchForm, id: string, text: string, suffix = "") => (
    <label>
      <input
        id={id}
        type="checkbox"
        checked={Boolean(form[field])}
        onChange={(event) => update(field, event.target.checked as never)}
      />{" "}
      {text}
      {suffix && <span className="muted"> ({suffix})</span>}
    </label>
  );
  const select = <K extends keyof SearchForm>(
    field: K,
    id: string,
    options: { value: string; label: string }[],
  ) => (
    <select
      id={id}
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
          Return choices{" "}
          {input("returnChoices", "returnChoices", "number", {
            min: "1",
            max: "8",
            title: "Cheapest outbounds expanded with a return lookup",
          })}
        </label>
      </div>
      <div className={"checks " + stylex.props(styles.providerChecks).className}>
        {checked("serpapi", "pSerp", "SerpApi")}
        {checked("searchapi", "pSearch", "SearchAPI")}
        {checked("travelpayouts", "pTp", "Travelpayouts", "cached")}
        {checked("duffel", "pDuffel", "Duffel", "mock")}
        {checked("nonstop", "qNonstop", "Nonstop only")}
        {checked("cacheOnly", "cacheOnly", "Cache only")}
      </div>
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
          disabled={hostedReadOnly || !runEnabled}
          onClick={onSearch}
        >
          Run search
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
          title={guest ? "Guest mode has no D1 storage" : "Check the persisted public sample row in hosted D1"}
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
      </div>
      <div id="invList" className="checks">
        {saved.map((item) => (
          <button type="button" key={item.id} data-inv={item.id} onClick={() => onOpen(item.id)}>
            {item.title || item.id}
          </button>
        ))}
      </div>
      <div id="statusLine" className="est">
        {statusLines.map((line, index) => (
          <div key={index}>{line}</div>
        ))}
      </div>
      <div id="estimate" className="est">
        {estimateError && <div>{estimateError}</div>}
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
                    {q.remaining == null ? "remaining unknown" : q.remaining + " remaining"}
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
                  <li key={index}>{line}</li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </section>
  );
}
