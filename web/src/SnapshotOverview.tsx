import { createPortal } from "react-dom";
import * as stylex from "@stylexjs/stylex";
import type { ComboCell, Itinerary, NoteCard, ResultSet } from "./domain.ts";
import { fmtRange, gbp, quoteable } from "./GridView.tsx";

export type ResultsTab = "grid" | "itineraries" | "matrix" | "vendors";
export interface SnapshotOverviewProps {
  result: ResultSet;
  rows: Itinerary[];
  tab: ResultsTab;
  notesTarget: HTMLElement;
  bannersTarget: HTMLElement;
  legendTarget: HTMLElement;
  onSelect: (primaryId: string, addonId: string) => void;
}
interface Hit {
  total: number;
  it: Itinerary;
  addon?: Itinerary;
}
const styles = stylex.create({
  cardButton: {
    width: "100%",
    textAlign: "left",
    cursor: "pointer",
    font: "inherit",
    color: "inherit",
    backgroundColor: "transparent",
    border: 0,
    padding: 0,
  },
  staticCard: { cursor: "default" },
  legendGreen: { backgroundColor: "var(--green-bg)", borderColor: "var(--green)" },
  legendAmber: { backgroundColor: "var(--amber-bg)", borderColor: "var(--amber)" },
  legendNo: { backgroundColor: "#f2f4f7" },
  legendFeasible: { backgroundColor: "#fff", border: "2px solid var(--gold)" },
  legendWorks: { backgroundColor: "#fff", border: "2px solid #027a48" },
  legendVendor: { backgroundColor: "#ecfdf3", borderColor: "#027a48" },
});

function bestCells(
  result: ResultSet,
  rows: Itinerary[],
  accept: (cell: ComboCell, it: Itinerary) => boolean,
): Hit | null {
  const cells = new Map(
    result.cells.map((cell) => [cell.primaryId + "\0" + (cell.addonId ?? ""), cell]),
  );
  let best: Hit | null = null;
  for (const it of rows) {
    if (!quoteable(it)) continue;
    for (const addon of result.addons) {
      const cell = cells.get(it.id + "\0" + addon.id);
      if (!cell || !accept(cell, it)) continue;
      if (!best || cell.total < best.total) best = { it, addon, total: cell.total };
    }
  }
  return best;
}

function bestItinerary(
  result: ResultSet,
  rows: Itinerary[],
  accept: (it: Itinerary) => boolean,
): Hit | null {
  let best: Hit | null = null;
  for (const it of rows) {
    if (!quoteable(it) || it.pricePerPerson == null || !accept(it)) continue;
    const hit = result.addons.length
      ? bestCells(result, [it], (cell) => cell.status !== "no")
      : { it, total: it.pricePerPerson * it.passengers };
    if (hit && (!best || hit.total < best.total)) best = hit;
  }
  return best;
}

function SummaryCard({
  label,
  hit,
  onSelect,
}: {
  label: string;
  hit: Hit | null;
  onSelect: SnapshotOverviewProps["onSelect"];
}) {
  if (!hit)
    return (
      <div className={"sumcard " + stylex.props(styles.staticCard).className}>
        <div className="k">{label}</div>
        <div className="v">—</div>
        <div className="d">None in the current filters</div>
      </div>
    );
  const title =
    "#" +
    hit.it.id +
    " " +
    hit.it.airlineSummary +
    " " +
    fmtRange(hit.it.outboundDate, hit.it.returnDate) +
    (hit.addon ? " + Mexico " + hit.addon.id : "");
  return (
    <div className="sumcard" data-p={hit.it.id} data-a={hit.addon?.id ?? "LH"}>
      <button
        type="button"
        {...stylex.props(styles.cardButton)}
        aria-label={label + ": " + gbp(hit.total) + ". View " + title}
        onClick={() => onSelect(hit.it.id, hit.addon?.id ?? "LH")}
      >
        <div className="k">{label}</div>
        <div className="v">{gbp(hit.total)}</div>
        <div className="d">{title}</div>
      </button>
    </div>
  );
}
function Notes({ notes }: { notes: NoteCard[] }) {
  return (
    <>
      {notes.map((note, index) => (
        <div key={index} className={"note" + (note.warn ? " warn" : "")}>
          <span className="nt">{note.title}</span>
          {note.body}
        </div>
      ))}
    </>
  );
}
function Warnings({ result }: { result: ResultSet }) {
  return (
    <>
      {(result.scenarioLabel === "sample" ||
        result.itineraries.some((it) => it.sourceLabel === "sample")) && (
        <div className="banner">
          Sample saved from Google Flights. Long-haul fares 11 Oct 2026, seller table 15 Oct 2026.
          This is not a live search.
        </div>
      )}
      {result.warnings.map((warning, index) => (
        <div
          className={"banner" + (/mock|fail|credit|error/i.test(warning) ? " bad" : "")}
          key={index}
        >
          {warning}
        </div>
      ))}
      {result.itineraries.some((it) => it.sourceLabel === "mock") && (
        <div className="banner bad">
          Duffel rows are test-mode mocks. They are hidden from the cheapest highlight and, with
          “Hide mocks” on, from the tables.
        </div>
      )}
      {result.itineraries.some((it) => it.sourceLabel === "cached") && (
        <div className="banner">
          Cached fares (including historic Duffel live quotes and Travelpayouts) are not verified
          bookable at the displayed price.
        </div>
      )}
    </>
  );
}
function Legend({ result, tab }: Pick<SnapshotOverviewProps, "result" | "tab">) {
  if (result.addons.length && result.cells.length && tab === "grid")
    return (
      <>
        <span>
          <i className="sw" {...stylex.props(styles.legendGreen)} />
          Works
        </span>
        <span>
          <i className="sw" {...stylex.props(styles.legendAmber)} />
          Works with a catch
        </span>
        <span>
          <i className="sw" {...stylex.props(styles.legendNo)} />
          Doesn’t work
        </span>
        <span>
          <i className="sw" {...stylex.props(styles.legendFeasible)} />
          Cheapest feasible
        </span>
        <span>
          <i className="sw" {...stylex.props(styles.legendWorks)} />
          Cheapest that fully works
        </span>
        <span>Cell = 2 × long-haul + 1 × Mexico. 🔗 opens the booking link.</span>
      </>
    );
  if (tab === "vendors")
    return (
      <>
        <span>
          <i className="sw" {...stylex.props(styles.legendVendor)} />
          Cheapest all-in in this market filter
        </span>
        <span className="trust risky">Risky</span>
        <span className="trust caution">Caution</span>
        <span className="trust trusted">Trusted</span>
        <span className="trust airline">Airline</span>
        <span>Notes and trust flags are editable and travel with the HTML export.</span>
      </>
    );
  return (
    <span>
      Prices are per person in GBP unless a row says otherwise. Mocks are excluded from the cheapest
      cards.
    </span>
  );
}

export function SnapshotOverview({
  result,
  rows,
  tab,
  notesTarget,
  bannersTarget,
  legendTarget,
  onSelect,
}: SnapshotOverviewProps) {
  const feasible = result.addons.length
    ? bestCells(result, rows, (cell) => cell.status !== "no")
    : bestItinerary(result, rows, () => true);
  const works = result.addons.length
    ? bestCells(result, rows, (cell) => cell.status === "ok")
    : null;
  const nonstop = bestItinerary(
    result,
    rows,
    (it) => it.stopsOut === 0 && !it.returnMissing && it.stopsRet === 0,
  );
  const european = bestItinerary(result, rows, (it) => it.euPremium);
  const vendorMode = tab === "vendors" && result.scenarioLabel === "florida";
  const notes = vendorMode ? result.vendorIntro : result.headerNotes;
  return (
    <>
      {createPortal(<Notes notes={notes} />, notesTarget)}
      {createPortal(<Warnings result={result} />, bannersTarget)}
      {createPortal(<Legend result={result} tab={tab} />, legendTarget)}
      <SummaryCard
        label={result.addons.length ? "Cheapest feasible combination" : "Cheapest"}
        hit={feasible}
        onSelect={onSelect}
      />
      {result.addons.length > 0 && (
        <SummaryCard label="Cheapest fully works" hit={works} onSelect={onSelect} />
      )}
      <SummaryCard label="Cheapest nonstop" hit={nonstop} onSelect={onSelect} />
      <SummaryCard label="Cheapest European premium" hit={european} onSelect={onSelect} />
    </>
  );
}
