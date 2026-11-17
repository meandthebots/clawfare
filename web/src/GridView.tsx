import { Fragment, useMemo } from "react";
import * as stylex from "@stylexjs/stylex";
import type { BookLink, ComboCell, Direction, Itinerary } from "./domain.ts";

const labels = { ok: "Works", catch: "Works with a catch", no: "Doesn’t work" } as const;
const styles = stylex.create({
  tag: {
    display: "inline-block",
    fontSize: "10.5px",
    padding: "1px 6px",
    borderRadius: "999px",
    backgroundColor: "var(--neutral-bg)",
    color: "var(--neutral-ink)",
    margin: "2px 3px 0 0",
    fontWeight: 600,
  },
  tagBusiness: { backgroundColor: "var(--pink-bg)", color: "var(--pink-ink)" },
  tagPremium: { backgroundColor: "var(--info-bg)", color: "var(--info-ink)" },
  tagNonstop: { backgroundColor: "var(--green-bg)", color: "var(--green-ink)" },
  tagGatwick: { backgroundColor: "var(--amber-bg)", color: "var(--amber-ink)" },
  tagEuropean: {
    backgroundColor: "var(--info-bg)",
    color: "var(--info-ink)",
    border: "1px solid var(--info-border)",
  },
  tagMock: { backgroundColor: "var(--red-bg)", color: "var(--red-ink)" },
  tagCached: { backgroundColor: "var(--neutral-bg)", color: "var(--neutral-ink)" },
  tagLive: { backgroundColor: "var(--green-bg)", color: "var(--green-ink)" },
  badge: {
    fontSize: "10px",
    fontWeight: 700,
    borderRadius: "999px",
    padding: "0 6px",
    marginTop: "2px",
    display: "inline-block",
  },
  badgeFeasible: { backgroundColor: "var(--gold)", color: "var(--gold-ink)" },
  badgeWorks: { backgroundColor: "var(--green-solid)", color: "#fff" },
  selectionButton: {
    width: "100%",
    display: "flex",
    flexDirection: "column",
    alignItems: "center",
    justifyContent: "center",
    cursor: "pointer",
    font: "inherit",
    color: "inherit",
  },
  desktopGrid: { display: { default: "table", "@media (max-width: 760px)": "none" } },
  mobileGrid: {
    display: { default: "none", "@media (max-width: 760px)": "grid" },
    gap: { default: "0", "@media (max-width: 760px)": "12px" },
  },
  mobileFlight: {
    border: "1px solid var(--line)",
    borderRadius: "12px",
    backgroundColor: "var(--card)",
    overflow: "hidden",
  },
  mobileFlightMain: {
    display: "block",
    width: "100%",
    textAlign: "left",
    border: 0,
    borderRadius: 0,
    padding: "14px",
    backgroundColor: "var(--mobile-header-bg)",
  },
  mobileTitle: { display: "block", fontWeight: 700, fontSize: "15px" },
  mobilePrice: { fontSize: "14px", fontWeight: 700, margin: "5px 0" },
  mobileDetails: { display: "block", color: "var(--accent)", fontWeight: 700, marginTop: "6px" },
  mobileAddons: { display: "grid", gap: "7px", padding: "10px" },
  mobileAddon: {
    width: "100%",
    display: "flex",
    flexWrap: "wrap",
    justifyContent: "space-between",
    alignItems: "center",
    gap: "5px 8px",
    border: "1px solid var(--line)",
    padding: "10px",
    textAlign: "left",
  },
  mobileAddonOk: { backgroundColor: "var(--green-bg)" },
  mobileAddonCatch: { backgroundColor: "var(--amber-bg)" },
  mobileAddonNo: { backgroundColor: "var(--neutral-bg)" },
  mobileAddonLabel: { display: "block", flex: "1 1 50%", minWidth: 0 },
  mobileAddonDate: { display: "block", color: "var(--muted)" },
  mobileAddonTotal: { fontWeight: 800, whiteSpace: "nowrap" },
  mobileAddonStatus: { flex: "1 0 100%", fontSize: "11px", fontWeight: 600 },
  assistive: {
    position: "absolute",
    width: "1px",
    height: "1px",
    overflow: "hidden",
    clip: "rect(0, 0, 0, 0)",
    whiteSpace: "nowrap",
  },
});
export interface GridViewProps {
  rows: Itinerary[];
  addons: Itinerary[];
  cells: ComboCell[];
  sort: "date" | "price";
  hideInfeasible: boolean;
  selectedKey: string | null;
  onSelect: (primaryId: string, addonId: string) => void;
}
const keyFor = (primary: string, addon?: string | null): string =>
  primary + String.fromCharCode(0) + (addon ?? "");

export function gbp(value: number | null | undefined): string {
  return typeof value === "number" && Number.isFinite(value)
    ? "£" + Math.round(value).toLocaleString("en-GB")
    : "—";
}
export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso + "T12:00:00Z");
  if (Number.isNaN(date.getTime())) return iso;
  return (
    ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"][date.getUTCDay()] +
    " " +
    date.getUTCDate() +
    " " +
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][
      date.getUTCMonth()
    ]
  );
}
export function fmtRange(start: string, end?: string | null): string {
  return start ? fmtDate(start) + (end ? " → " + fmtDate(end) : "") : "";
}
function layText(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  return (hours ? hours + "h " : "") + (minutes % 60) + "m";
}
export function directionLine(direction?: Direction | null): string {
  return (direction?.legs ?? [])
    .map((leg, i) => {
      const lay = direction?.layovers?.[i];
      const operating =
        leg.operatingCarrier && leg.operatingCarrier !== leg.airline
          ? " (" + leg.operatingCarrier + ")"
          : "";
      return (
        leg.flightNumber +
        " " +
        leg.from +
        " " +
        leg.departTime +
        "–" +
        leg.to +
        " " +
        leg.arriveTime +
        operating +
        (lay ? " · " + layText(lay.minutes) + " " + lay.airport : "")
      );
    })
    .join(" · ");
}
export function stopsLabel(it: Itinerary): string {
  const text = (n: number) => (n === 0 ? "Nonstop" : n + " stop" + (n > 1 ? "s" : ""));
  if (it.stopsRet == null) return text(it.stopsOut) + (it.returnMissing ? " (out)" : "");
  if (it.stopsRet === it.stopsOut) return text(it.stopsOut);
  return text(it.stopsOut) + " out · " + text(it.stopsRet) + " back";
}
export function quoteable(it: Itinerary, now = Date.now()): boolean {
  if (it.sourceLabel !== "live" || !it.livePrice) return false;
  const observed = Date.parse(it.fetchedAt ?? "");
  if (!Number.isFinite(observed) || observed > now || now - observed > 7200000) return false;
  if (it.refundEvidence == null) return true;
  const expiry = Date.parse(it.refundEvidence.expiresAt ?? "");
  return Number.isFinite(expiry) && expiry > now;
}
export function bookingUrl(link?: BookLink): string | undefined {
  if (!link) return undefined;
  try {
    const url = new URL(link.url);
    // Booking links are untrusted provider output; never activate JS/data URLs.
    return url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}
function LinkIcon({ links }: { links: BookLink[] }) {
  const first = links[0];
  const url = bookingUrl(first);
  return url ? (
    <a
      className="licon"
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      title={first?.label}
      onClick={(event) => event.stopPropagation()}
    >
      🔗
    </a>
  ) : null;
}
function Change({ current, previous }: { current?: number | null; previous?: number | null }) {
  if (previous == null || current == null) return null;
  const delta = Math.round(current - previous);
  return (
    <span className={"chg " + (delta > 0 ? "up" : delta < 0 ? "down" : "same")}>
      {delta === 0 ? "= previous" : (delta > 0 ? "▲ " : "▼ ") + gbp(Math.abs(delta))}
    </span>
  );
}
export function SourceTag({ it }: { it: Itinerary }) {
  let label = it.sourceLabel;
  if (label === "live" && !quoteable(it)) label = "cached";
  const cls = label === "mock" ? "mock" : label === "live" ? "live" : "cached";
  let text =
    label === "mock"
      ? "Mock"
      : label === "cached"
        ? "Cached"
        : label === "sample"
          ? "Sample"
          : label === "live"
            ? "Live"
            : label;
  if (!it.livePrice && label !== "mock" && label !== "sample") text += " · not a live fare";
  return (
    <span
      className={
        "tag " +
        cls +
        " " +
        stylex.props(
          styles.tag,
          cls === "mock" && styles.tagMock,
          cls === "live" && styles.tagLive,
          cls === "cached" && styles.tagCached,
        ).className
      }
    >
      {text}
    </span>
  );
}
function FlightTags({ it }: { it: Itinerary }) {
  const cabinCls =
    it.cabinKey === "business" ? "biz" : it.cabinKey === "premium_economy" ? "pe" : "";
  return (
    <>
      <span
        className={
          "tag " +
          cabinCls +
          " " +
          stylex.props(
            styles.tag,
            cabinCls === "biz" && styles.tagBusiness,
            cabinCls === "pe" && styles.tagPremium,
          ).className
        }
      >
        {it.cabin || it.cabinKey}
      </span>
      <span
        className={
          "tag " +
          (it.stopsOut === 0 && it.stopsRet === 0 ? "ns " : "") +
          stylex.props(styles.tag, it.stopsOut === 0 && it.stopsRet === 0 && styles.tagNonstop)
            .className
        }
      >
        {stopsLabel(it)}
      </span>
      <span
        className={
          "tag " +
          (it.origin === "LGW" ? "lgw " : "") +
          stylex.props(styles.tag, it.origin === "LGW" && styles.tagGatwick).className
        }
      >
        {it.origin}
        {it.destination ? "→" + it.destination : ""}
      </span>
      {it.euPremium && (
        <span className={"tag eu " + stylex.props(styles.tag, styles.tagEuropean).className}>
          European-operated
        </span>
      )}
      <SourceTag it={it} />
    </>
  );
}

export function GridView({
  rows,
  addons,
  cells,
  sort,
  hideInfeasible,
  selectedKey,
  onSelect,
}: GridViewProps) {
  const { map, feasible, works } = useMemo(() => {
    const map = new Map(cells.map((cell) => [keyFor(cell.primaryId, cell.addonId), cell]));
    const feasible = new Set<string>(),
      works = new Set<string>();
    let lowestFeasible = Infinity,
      lowestWorks = Infinity;
    for (const it of rows) {
      if (!quoteable(it)) continue;
      for (const addon of addons) {
        const k = keyFor(it.id, addon.id),
          cell = map.get(k);
        if (!cell) continue;
        if (cell.status !== "no" && cell.total < lowestFeasible) {
          lowestFeasible = cell.total;
          feasible.clear();
          feasible.add(k);
        } else if (cell.status !== "no" && cell.total === lowestFeasible) feasible.add(k);
        if (cell.status === "ok" && cell.total < lowestWorks) {
          lowestWorks = cell.total;
          works.clear();
          works.add(k);
        } else if (cell.status === "ok" && cell.total === lowestWorks) works.add(k);
      }
    }
    return { map, feasible, works };
  }, [rows, addons, cells]);
  const span = addons.length + 2,
    buttonClass = stylex.props(styles.selectionButton).className ?? "";
  const statusLabel = (cell: ComboCell) => labels[cell.status] ?? cell.status;

  function cellView(it: Itinerary, addon: Itinerary | null) {
    const id = addon?.id ?? "LH",
      k = keyFor(it.id, addon?.id);
    const cell = map.get(k);
    if (!cell) return <td key={id} />;
    const hidden = hideInfeasible && cell.status === "no";
    const bestFeasible = addon != null && feasible.has(k);
    const bestWorks = addon != null && works.has(k);
    const tip =
      statusLabel(cell) +
      (cell.reasonsNo.length
        ? ": " + cell.reasonsNo.join(" ")
        : cell.catches.length
          ? ": " + cell.catches.join(" ")
          : "");
    return (
      <td
        key={id}
        data-p={it.id}
        data-a={id}
        title={hidden ? "" : tip}
        className={
          "cell s-" +
          cell.status +
          (addon ? "" : " lhonly") +
          (hidden ? " s-hidden" : "") +
          (bestFeasible ? " best-any" : "") +
          (bestWorks ? " best-green" : "") +
          (selectedKey === k ? " sel" : "")
        }
        onClick={() => onSelect(it.id, id)}
      >
        <button
          type="button"
          className={"in " + buttonClass}
          aria-label={
            "Open " +
            (addon ? "Mexico " + addon.id : "long-haul") +
            " details for " +
            it.id +
            ": " +
            statusLabel(cell) +
            ", " +
            gbp(cell.total)
          }
          onClick={(event) => {
            event.stopPropagation();
            onSelect(it.id, id);
          }}
        >
          {hidden ? (
            <span {...stylex.props(styles.assistive)}>{statusLabel(cell)}</span>
          ) : (
            <>
              <span className="tot">{gbp(cell.total)}</span>
              <span className="st">{statusLabel(cell)}</span>
              {bestFeasible && (
                <span
                  className={
                    "badge ba " + stylex.props(styles.badge, styles.badgeFeasible).className
                  }
                >
                  Cheapest feasible
                </span>
              )}
              {bestWorks && (
                <span
                  className={"badge bg " + stylex.props(styles.badge, styles.badgeWorks).className}
                >
                  Cheapest works
                </span>
              )}
            </>
          )}
        </button>
      </td>
    );
  }

  return (
    <>
      <table id="grid" className={stylex.props(styles.desktopGrid).className}>
        <thead>
          <tr>
            <th className="rowh">
              Long-haul option<span className="sub">flights, per person, couple total</span>
            </th>
            {addons.map((addon) => (
              <th key={addon.id}>
                Mexico {addon.id}
                <LinkIcon links={addon.links} />
                <span className="sub">
                  {fmtRange(addon.outboundDate, addon.returnDate)} · {addon.nights ?? ""}n
                </span>
                <span className="sub">{gbp(addon.pricePerPerson)}</span>
              </th>
            ))}
            <th className="lhonly">
              Long-haul only<span className="sub">couple, no add-on</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td className="empty" colSpan={span}>
                No itineraries match these filters.
              </td>
            </tr>
          )}
          {rows.map((it, index) => {
            const group = it.outboundDate + "|" + (it.returnDate ?? "");
            const previous = index > 0 ? rows[index - 1] : undefined;
            const newGroup =
              sort === "date" &&
              (!previous || group !== previous.outboundDate + "|" + (previous.returnDate ?? ""));
            return (
              <Fragment key={it.id}>
                {newGroup && (
                  <tr className="group">
                    <td className="gh" colSpan={span}>
                      {fmtRange(it.outboundDate, it.returnDate)}
                      {it.nights != null ? " · " + it.nights + " nights" : ""}
                    </td>
                  </tr>
                )}
                <tr>
                  <th scope="row">
                    <div className="lhTop">
                      <span className="lhDates">
                        #{it.id} · {fmtRange(it.outboundDate, it.returnDate)}
                        <LinkIcon links={it.links} />
                      </span>
                      <span className="lhPrice">
                        {gbp(it.pricePerPerson)} pp{" "}
                        <Change current={it.pricePerPerson} previous={it.prevPrice} />
                      </span>
                    </div>
                    <div className="lhTop lhMeta">
                      <span>{it.airlineSummary}</span>
                      <span>
                        couple{" "}
                        {gbp(it.pricePerPerson == null ? null : it.pricePerPerson * it.passengers)}
                      </span>
                    </div>
                    <div className="flights">{directionLine(it.outbound)}</div>
                    {it.returning ? (
                      <div className="flights">↩ {directionLine(it.returning)}</div>
                    ) : it.returnMissing ? (
                      <div className="flights">Return not retrieved</div>
                    ) : null}
                    <div>
                      <FlightTags it={it} />
                    </div>
                  </th>
                  {addons.map((addon) => cellView(it, addon))}
                  {cellView(it, null)}
                </tr>
              </Fragment>
            );
          })}
        </tbody>
      </table>
      <div
        className={"mobile-grid " + stylex.props(styles.mobileGrid).className}
        id="mobileGrid"
        aria-label="Flight combinations"
      >
        {rows.map((it) => {
          const title =
            it.origin + " → " + it.destination + " " + fmtRange(it.outboundDate, it.returnDate);
          return (
            <article
              className={"mobile-flight " + stylex.props(styles.mobileFlight).className}
              key={it.id}
            >
              <button
                className={"mobile-flight-main " + stylex.props(styles.mobileFlightMain).className}
                type="button"
                data-p={it.id}
                data-a="LH"
                aria-label={"Open long-haul details for " + title}
                onClick={() => onSelect(it.id, "LH")}
              >
                <span className={"mobile-title " + stylex.props(styles.mobileTitle).className}>
                  {title}
                </span>
                <span className="lhMeta">
                  {it.airlineSummary} · {it.cabin || it.cabinKey}
                </span>
                <span className={"mobile-price " + stylex.props(styles.mobilePrice).className}>
                  {gbp(it.pricePerPerson)} pp ·{" "}
                  {gbp(it.pricePerPerson == null ? null : it.pricePerPerson * it.passengers)} total
                </span>
                <span className={"mobile-details " + stylex.props(styles.mobileDetails).className}>
                  View long-haul details
                </span>
              </button>
              <div
                className={"mobile-addon-list " + stylex.props(styles.mobileAddons).className}
                aria-label="Add-on combinations"
              >
                {addons.map((addon) => {
                  const k = keyFor(it.id, addon.id),
                    cell = map.get(k);
                  if (!cell) return null;
                  return (
                    <button
                      type="button"
                      key={addon.id}
                      data-p={it.id}
                      data-a={addon.id}
                      className={
                        "mobile-addon s-" +
                        cell.status +
                        (selectedKey === k ? " sel " : " ") +
                        stylex.props(
                          styles.mobileAddon,
                          cell.status === "ok" && styles.mobileAddonOk,
                          cell.status === "catch" && styles.mobileAddonCatch,
                          cell.status === "no" && styles.mobileAddonNo,
                        ).className
                      }
                      aria-label={
                        "Open Mexico " +
                        addon.id +
                        " combination details: " +
                        statusLabel(cell) +
                        ", " +
                        gbp(cell.total)
                      }
                      onClick={() => onSelect(it.id, addon.id)}
                    >
                      <span
                        className={
                          "mobile-addon-label " + stylex.props(styles.mobileAddonLabel).className
                        }
                      >
                        Mexico {addon.id}
                        <small {...stylex.props(styles.mobileAddonDate)}>
                          {fmtRange(addon.outboundDate, addon.returnDate)}
                        </small>
                      </span>
                      <span
                        className={
                          "mobile-addon-total " + stylex.props(styles.mobileAddonTotal).className
                        }
                      >
                        {gbp(cell.total)}
                      </span>
                      <span
                        className={
                          "mobile-addon-status " + stylex.props(styles.mobileAddonStatus).className
                        }
                      >
                        {statusLabel(cell)}
                        {feasible.has(k) ? " · Cheapest feasible" : ""}
                        {works.has(k) ? " · Cheapest works" : ""}
                      </span>
                    </button>
                  );
                })}
              </div>
            </article>
          );
        })}
      </div>
    </>
  );
}
