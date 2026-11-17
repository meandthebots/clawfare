import * as stylex from "@stylexjs/stylex";
import type { ResultSet } from "./domain.ts";
import {
  filterChoices,
  initialFilters,
  suggestedFilters,
  type FilterControlsState,
} from "./filterForm.ts";

const styles = stylex.create({
  section: { display: "flex", flexWrap: "wrap", gap: "10px 14px" },
  heading: { fontSize: "12px", fontWeight: 700 },
  actions: { marginTop: 0 },
});
export interface FilterControlsProps {
  state: FilterControlsState;
  result: ResultSet | null;
  onChange: (state: FilterControlsState) => void;
}
export function FilterControls({ state, result, onChange }: FilterControlsProps) {
  const update = <K extends keyof FilterControlsState>(field: K, value: FilterControlsState[K]) =>
    onChange({ ...state, [field]: value });
  const values = filterChoices(result);
  const input = (
    field: keyof FilterControlsState,
    id: string,
    type = "text",
    extra: Record<string, string> = {},
  ) => (
    <input
      id={id}
      type={type}
      value={String(state[field])}
      onChange={(event) => update(field, event.target.value as never)}
      {...extra}
    />
  );
  const checkbox = (field: keyof FilterControlsState, id: string, text: string) => (
    <label>
      <input
        id={id}
        type="checkbox"
        checked={Boolean(state[field])}
        onChange={(event) => update(field, event.target.checked as never)}
      />{" "}
      {text}
    </label>
  );
  const segments = (
    id: string,
    options: { value: string; label: string }[],
    current: string,
    onClick: (value: string) => void,
  ) => (
    <div className="seg" id={id}>
      {options.map((option) => (
        <button
          type="button"
          data-v={option.value}
          key={option.value}
          className={current === option.value ? "on" : ""}
          aria-pressed={current === option.value}
          onClick={() => onClick(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
  const cabinSegments = [
    { value: "all", label: "All" },
    { value: "premium_economy", label: "Premium economy" },
    { value: "EU", label: "European premium" },
    { value: "business", label: "Business" },
  ];
  return (
    <section className={"card filters " + stylex.props(styles.section).className} id="filterBar">
      <label>
        Max stops{" "}
        <select
          id="fStops"
          value={state.maxStops}
          onChange={(event) => update("maxStops", event.target.value)}
        >
          <option value="">Any</option>
          <option value="0">Nonstop</option>
          <option value="1">Up to 1</option>
          <option value="2">Up to 2</option>
        </select>
      </label>
      <label>
        Max layover (hours){" "}
        {input("maxLayoverHours", "fLayover", "number", {
          min: "0",
          step: "0.5",
          placeholder: "5",
        })}
      </label>
      <label>Depart after {input("departAfter", "fAfter", "time")}</label>
      <label>Depart before {input("departBefore", "fBefore", "time")}</label>
      <label>Return after {input("returnAfter", "fRetAfter", "time")}</label>
      <label>
        Max travel (hours){" "}
        {input("maxTravelHours", "fTravel", "number", {
          min: "0",
          step: "0.5",
        })}
      </label>
      <label>
        Exclude return dates{" "}
        {input("excludeReturn", "fExRet", "text", {
          placeholder: "2026-12-24, 2026-12-25",
        })}
      </label>
      <label>
        Exclude outbound{" "}
        {input("excludeOut", "fExOut", "text", {
          placeholder: "2026-12-24",
        })}
      </label>
      <label>
        Airline allow{" "}
        {input("allow", "fAllow", "text", {
          placeholder: "BA, KL, Virgin",
        })}
      </label>
      <label>
        Airline block{" "}
        {input("block", "fBlock", "text", {
          placeholder: "AA",
        })}
      </label>
      <div className="checks">
        {checkbox("nonstop", "fNonstop", "Nonstop only")}
        {checkbox("eu", "fEu", "European premium")}
        {checkbox("hideMock", "fHideMock", "Hide mocks")}
        {checkbox("cashRefund", "fCashRefund", "Verified cash refund only")}
        {checkbox("hide", "fHide", "Hide infeasible")}
        {checkbox("lhr", "fLhr", "Heathrow only")}
      </div>
      <div className={"actions " + stylex.props(styles.actions).className}>
        <span className={"muted " + stylex.props(styles.heading).className}>CABIN</span>
        {segments("cabinSeg", cabinSegments, state.cabin, (cabin) =>
          onChange({ ...state, cabin, eu: cabin === "EU" }),
        )}
        <span className={"muted " + stylex.props(styles.heading).className}>NIGHTS</span>
        {segments(
          "nightSeg",
          values.nights.map((value) => ({
            value,
            label: value === "all" ? "All" : value + " nights",
          })),
          state.nights,
          (nights) => update("nights", nights),
        )}
        <span className={"muted " + stylex.props(styles.heading).className}>ORIGIN</span>
        {segments(
          "originSeg",
          values.origins.map((value) => ({
            value,
            label: value === "all" ? "All" : value,
          })),
          state.origin,
          (origin) => onChange({ ...state, origin, lhr: false }),
        )}
        <span className={"muted " + stylex.props(styles.heading).className}>SORT</span>
        {segments(
          "sortSeg",
          [
            { value: "date", label: "By date" },
            { value: "price", label: "Cheapest feasible" },
          ],
          state.sort,
          (sort) => update("sort", sort as FilterControlsState["sort"]),
        )}
        <button
          type="button"
          id="btnSuggested"
          disabled={!result}
          onClick={() =>
            result && onChange({ ...state, ...suggestedFilters(result.suggestedFilters) })
          }
        >
          Suggested filters
        </button>
        <button
          type="button"
          id="btnClear"
          onClick={() => onChange({ ...initialFilters(state.hideMock) })}
        >
          Clear filters
        </button>
      </div>
    </section>
  );
}
