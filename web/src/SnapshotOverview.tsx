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
  summaryCard: {
    backgroundColor: "var(--card)",
    border: "1px solid var(--line)",
    borderRadius: "12px",
    padding: "12px 16px",
    cursor: "pointer",
    borderColor: { default: "var(--line)", ":hover": "var(--accent)" },
  },
  summaryLabel: {
    fontSize: "12px",
    color: "var(--muted)",
    textTransform: "uppercase",
    letterSpacing: "0.04em",
    fontWeight: 600,
  },
  summaryValue: { fontSize: "22px", fontWeight: 700, margin: "2px 0" },
  summaryDescription: { color: "var(--muted)", fontSize: "13px" },
  note: {
    backgroundColor: "rgba(255,255,255,0.12)",
    border: "1px solid rgba(255,255,255,0.22)",
    borderRadius: "10px",
    padding: "9px 12px",
    fontSize: "13px",
  },
  noteWarning: {
    backgroundColor: "rgba(250,204,21,0.18)",
    borderColor: "rgba(250,204,21,0.5)",
  },
  noteTitle: { display: "block", fontWeight: 700, marginBottom: "2px" },
  banner: {
    backgroundColor: "var(--notice-bg)",
    border: "1px solid var(--notice-border)",
    color: "var(--notice-ink)",
    borderRadius: "10px",
    padding: "8px 12px",
    marginBottom: "10px",
    fontSize: "13px",
  },
  bannerBad: {
    backgroundColor: "var(--red-bg)",
    borderColor: "var(--red-border)",
    color: "var(--red-ink)",
  },
  swatch: {
    width: "16px",
    height: "16px",
    borderRadius: "4px",
    display: "inline-block",
    border: "1px solid rgba(0,0,0,0.08)",
  },
  legendGreen: { backgroundColor: "var(--green-bg)", borderColor: "var(--green)" },
  legendAmber: { backgroundColor: "var(--amber-bg)", borderColor: "var(--amber)" },
  legendNo: { backgroundColor: "var(--neutral-bg)" },
  legendFeasible: { backgroundColor: "var(--card)", border: "2px solid var(--gold)" },
  legendWorks: { backgroundColor: "var(--card)", border: "2px solid var(--green-solid)" },
  legendVendor: { backgroundColor: "var(--green-bg)", borderColor: "var(--green-solid)" },
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
      <div className={"sumcard " + stylex.props(styles.summaryCard, styles.staticCard).className}>
        <div className={"k " + stylex.props(styles.summaryLabel).className}>{label}</div>
        <div className={"v " + stylex.props(styles.summaryValue).className}>—</div>
        <div className={"d " + stylex.props(styles.summaryDescription).className}>
          None in the current filters
        </div>
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
    <div
      className={"sumcard " + stylex.props(styles.summaryCard).className}
      data-p={hit.it.id}
      data-a={hit.addon?.id ?? "LH"}
    >
      <button
        type="button"
        {...stylex.props(styles.cardButton)}
        aria-label={label + ": " + gbp(hit.total) + ". View " + title}
        onClick={() => onSelect(hit.it.id, hit.addon?.id ?? "LH")}
      >
        <div className={"k " + stylex.props(styles.summaryLabel).className}>{label}</div>
        <div className={"v " + stylex.props(styles.summaryValue).className}>{gbp(hit.total)}</div>
        <div className={"d " + stylex.props(styles.summaryDescription).className}>{title}</div>
      </button>
    </div>
  );
}
function Notes({ notes }: { notes: NoteCard[] }) {
  return (
    <>
      {notes.map((note, index) => (
        <div
          key={index}
          className={
            "note" +
            (note.warn ? " warn " : " ") +
            stylex.props(styles.note, note.warn && styles.noteWarning).className
          }
        >
          <span className={"nt " + stylex.props(styles.noteTitle).className}>{note.title}</span>
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
        <div className={"banner " + stylex.props(styles.banner).className}>
          Sample saved from Google Flights. Long-haul fares 11 Oct 2026, seller table 15 Oct 2026.
          This is not a live search.
        </div>
      )}
      {result.warnings.map((warning, index) => (
        <div
          className={
            "banner " +
            (/mock|fail|credit|error/i.test(warning) ? "bad " : "") +
            stylex.props(styles.banner, /mock|fail|credit|error/i.test(warning) && styles.bannerBad)
              .className
          }
          key={index}
        >
          {warning}
        </div>
      ))}
      {result.itineraries.some((it) => it.sourceLabel === "mock") && (
        <div className={"banner bad " + stylex.props(styles.banner, styles.bannerBad).className}>
          Duffel rows are test-mode mocks. They are hidden from the cheapest highlight and, with
          “Hide mocks” on, from the tables.
        </div>
      )}
      {result.itineraries.some((it) => it.sourceLabel === "cached") && (
        <div className={"banner " + stylex.props(styles.banner).className}>
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
          <i className={"sw " + stylex.props(styles.swatch, styles.legendGreen).className} />
          Works
        </span>
        <span>
          <i className={"sw " + stylex.props(styles.swatch, styles.legendAmber).className} />
          Works with a catch
        </span>
        <span>
          <i className={"sw " + stylex.props(styles.swatch, styles.legendNo).className} />
          Doesn’t work
        </span>
        <span>
          <i className={"sw " + stylex.props(styles.swatch, styles.legendFeasible).className} />
          Cheapest feasible
        </span>
        <span>
          <i className={"sw " + stylex.props(styles.swatch, styles.legendWorks).className} />
          Cheapest that fully works
        </span>
        <span>Cell = 2 × long-haul + 1 × Mexico. 🔗 opens the booking link.</span>
      </>
    );
  if (tab === "vendors")
    return (
      <>
        <span>
          <i className={"sw " + stylex.props(styles.swatch, styles.legendVendor).className} />
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
