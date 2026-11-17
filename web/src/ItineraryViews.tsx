import * as stylex from "@stylexjs/stylex";
import type { Itinerary } from "./domain.ts";
import {
  bookingUrl,
  directionLine,
  fmtDate,
  fmtRange,
  gbp,
  SourceTag,
  stopsLabel,
} from "./GridView.tsx";

const styles = stylex.create({
  action: {
    cursor: "pointer",
    color: "var(--accent)",
    fontWeight: 700,
    whiteSpace: "nowrap",
  },
  matrixButton: {
    display: "flex",
    flexDirection: "column",
    alignItems: "flex-start",
    width: "100%",
    cursor: "pointer",
    background: "transparent",
    border: 0,
    textAlign: "left",
  },
});

export interface ItineraryViewProps {
  rows: Itinerary[];
  onSelect: (primaryId: string, addonId: string) => void;
}
const layText = (mins: number) => {
  const h = Math.floor(mins / 60);
  return (h ? h + "h " : "") + (mins % 60) + "m";
};

/** Read-only React itinerary listing, preserving the existing table/CSV semantics. */
export function ItineraryView({ rows, onSelect }: ItineraryViewProps) {
  return (
    <table className="data" id="itineraries">
      <thead>
        <tr>
          <th>Itinerary</th>
          <th>Flights</th>
          <th>Stops</th>
          <th>Layover</th>
          <th className="num">pp</th>
          <th className="num">Total</th>
          <th>Source</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {!rows.length && (
          <tr>
            <td className="empty" colSpan={8}>
              No itineraries match these filters.
            </td>
          </tr>
        )}
        {rows.map((it) => {
          const url = bookingUrl(it.links[0]);
          return (
            <tr key={it.id} data-p={it.id} data-a="LH" onClick={() => onSelect(it.id, "LH")}>
              <td>
                <b>{fmtRange(it.outboundDate, it.returnDate)}</b>
                <div className="muted">
                  {it.origin} → {it.destination}
                  {it.nights != null ? " · " + it.nights + "n" : ""} · {it.cabin}
                </div>
                <div>
                  {it.airlineSummary}
                  {it.euPremium ? " · European premium" : ""}
                </div>
              </td>
              <td>
                <div>{directionLine(it.outbound)}</div>
                {it.returning && <div className="muted">↩ {directionLine(it.returning)}</div>}
              </td>
              <td>{stopsLabel(it)}</td>
              <td>{it.maxLayoverMinutes == null ? "—" : layText(it.maxLayoverMinutes)}</td>
              <td className="num">{gbp(it.pricePerPerson)}</td>
              <td className="num">
                {gbp(it.pricePerPerson == null ? null : it.pricePerPerson * it.passengers)}
              </td>
              <td>
                <SourceTag it={it} />
                <div className="muted">{it.fetchedAt ?? ""}</div>
              </td>
              <td>
                <button
                  type="button"
                  {...stylex.props(styles.action)}
                  aria-label={"View details for " + it.id}
                  onClick={(event) => {
                    event.stopPropagation();
                    onSelect(it.id, "LH");
                  }}
                >
                  Details
                </button>
                {url && (
                  <a
                    className="lbtn primary"
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(event) => event.stopPropagation()}
                  >
                    Book
                  </a>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/**
 * Calendar matrix intentionally shows only quoteable, fresh live fares.
 * Saved samples and stale provider data must not be presented as live prices.
 */
export function MatrixView({ rows, onSelect }: ItineraryViewProps) {
  const outbound = [...new Set(rows.map((it) => it.outboundDate))].sort();
  const returns = [...new Set(rows.map((it) => it.returnDate ?? "—"))].sort();
  const best = new Map<string, Itinerary>();
  for (const it of rows) {
    const key = it.outboundDate + "|" + (it.returnDate ?? "—");
    const previous = best.get(key);
    if (
      !previous ||
      (it.pricePerPerson != null &&
        (previous.pricePerPerson == null || it.pricePerPerson < previous.pricePerPerson))
    ) {
      best.set(key, it);
    }
  }
  return (
    <table className="data" id="matrix">
      <thead>
        <tr>
          <th>Outbound \ return</th>
          {returns.map((date) => (
            <th key={date}>{date === "—" ? "one way" : fmtDate(date)}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {!outbound.length && (
          <tr>
            <td className="empty">No dated itineraries in the current filters.</td>
          </tr>
        )}
        {outbound.map((date) => (
          <tr key={date}>
            <th>{fmtDate(date)}</th>
            {returns.map((ret) => {
              const it = best.get(date + "|" + ret);
              if (!it)
                return (
                  <td key={ret} className="muted">
                    —
                  </td>
                );
              return (
                <td
                  key={ret}
                  className="mxcell"
                  data-p={it.id}
                  data-a="LH"
                  onClick={() => onSelect(it.id, "LH")}
                >
                  <button
                    type="button"
                    {...stylex.props(styles.matrixButton)}
                    aria-label={"Details for " + it.id + ": " + gbp(it.pricePerPerson)}
                    onClick={(event) => {
                      event.stopPropagation();
                      onSelect(it.id, "LH");
                    }}
                  >
                    {gbp(it.pricePerPerson)}
                    <span className="muted">{it.airlineSummary}</span>
                  </button>
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
