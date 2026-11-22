import * as stylex from "@stylexjs/stylex";
import type { Itinerary } from "./domain.ts";
import {
  bookingUrl,
  directionLine,
  fareAmount,
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
  itineraryDesktop: { display: { default: "table", "@media (max-width: 760px)": "none" } },
  itineraryMobile: {
    display: { default: "none", "@media (max-width: 760px)": "grid" },
    gap: "12px",
  },
  itineraryCard: {
    border: "1px solid var(--line)",
    borderRadius: "12px",
    backgroundColor: "var(--card)",
    overflow: "hidden",
  },
  itineraryCardButton: {
    width: "100%",
    border: 0,
    borderRadius: 0,
    backgroundColor: "var(--mobile-header-bg)",
    color: "inherit",
    padding: "14px",
    textAlign: "left",
    cursor: "pointer",
    font: "inherit",
    display: "flex",
    flexDirection: "column",
    gap: "7px",
  },
  itineraryCardTitle: { fontWeight: 750, fontSize: "15px" },
  itineraryCardFare: { fontWeight: 750, fontSize: "16px" },
  itineraryCardDetail: { color: "var(--accent)", fontWeight: 700 },
  itineraryCardMeta: { color: "var(--muted)", fontSize: "12px" },
  itineraryCardLink: { display: "inline-block", margin: "0 14px 14px" },
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
  emptyMessage?: string;
  onSelect: (primaryId: string, addonId: string) => void;
}
const layText = (mins: number) => {
  const h = Math.floor(mins / 60);
  return (h ? h + "h " : "") + (mins % 60) + "m";
};

/** Read-only React itinerary listing, preserving the existing table/CSV semantics. */
export function ItineraryView({
  rows,
  onSelect,
  emptyMessage = "No itineraries match these filters.",
}: ItineraryViewProps) {
  return (
    <>
      <table className={"data " + stylex.props(styles.itineraryDesktop).className} id="itineraries">
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
                {emptyMessage}
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
                  <div>{directionLine(it.outbound) || "Flight segments unavailable"}</div>
                  {it.returning?.legs.length ? (
                    <div className="muted">↩ {directionLine(it.returning)}</div>
                  ) : null}
                </td>
                <td>{stopsLabel(it)}</td>
                <td>{it.maxLayoverMinutes == null ? "—" : layText(it.maxLayoverMinutes)}</td>
                <td className="num">{fareAmount(it)}</td>
                <td className="num">{fareAmount(it, true)}</td>
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
      <section
        className={stylex.props(styles.itineraryMobile).className}
        id="mobileItineraries"
        aria-label="Flight itineraries"
      >
        {rows.length === 0 && <p>{emptyMessage}</p>}
        {rows.map((it) => {
          const url = bookingUrl(it.links[0]);
          const title =
            it.origin + " → " + it.destination + " " + fmtRange(it.outboundDate, it.returnDate);
          return (
            <article key={it.id} className={stylex.props(styles.itineraryCard).className}>
              <button
                type="button"
                className={stylex.props(styles.itineraryCardButton).className}
                data-p={it.id}
                data-a="LH"
                aria-label={"View details for " + it.id + ": " + title}
                onClick={() => onSelect(it.id, "LH")}
              >
                <span className={stylex.props(styles.itineraryCardTitle).className}>{title}</span>
                <span>
                  {it.airlineSummary} · {stopsLabel(it)} · {it.cabin}
                  {it.nights != null ? " · " + it.nights + " nights" : ""}
                </span>
                <span className={stylex.props(styles.itineraryCardFare).className}>
                  {fareAmount(it)} per person · {fareAmount(it, true)} party
                </span>
                <span className={stylex.props(styles.itineraryCardMeta).className}>
                  <SourceTag it={it} />
                  {it.fetchedAt ?? ""}
                </span>
                <span className={stylex.props(styles.itineraryCardDetail).className}>
                  View itinerary details
                </span>
              </button>
              {url && (
                <a
                  className={"lbtn primary " + stylex.props(styles.itineraryCardLink).className}
                  href={url}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Book
                </a>
              )}
            </article>
          );
        })}
      </section>
    </>
  );
}

/**
 * Calendar matrix intentionally shows only quoteable, fresh live fares.
 * Saved samples and stale provider data must not be presented as live prices.
 */
export function MatrixView({
  rows,
  onSelect,
  emptyMessage = "No dated itineraries in the current filters.",
}: ItineraryViewProps) {
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
            <td className="empty">{emptyMessage}</td>
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
