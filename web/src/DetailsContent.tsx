import type { BookLink, ComboCell, Direction, Itinerary } from "./domain.ts";
import { bookingUrl, directionLine, fmtRange, gbp, stopsLabel } from "./GridView.tsx";

function money(value: number | null | undefined, currency?: string | null): string {
  if (value == null || !Number.isFinite(value)) return "Unknown";
  const symbols: Record<string, string> = { GBP: "£", USD: "US$", EUR: "€", MXN: "MX$", CAD: "C$" };
  return (
    (symbols[currency ?? ""] ?? (currency ?? "") + " ") + Math.round(value).toLocaleString("en-GB")
  );
}

/**
 * Browser display guard only. The JVM server validates exact seller proof,
 * including the cryptographic itinerary key, before setting the flag.
 * Airline-level cancellation fields alone are never cash-refund evidence.
 */
export function isCashRefundVerified(it: Itinerary, now = Date.now()): boolean {
  const e = it.refundEvidence,
    p = e?.sellerProof;
  if (
    !it.cashRefundProofVerified ||
    !e ||
    !p ||
    p.sellerId !== e.sellerId ||
    p.offerId !== e.offerId ||
    p.partySize !== it.passengers ||
    p.fareBrand !== (it.fareBrand ?? "") ||
    p.issuerPenaltyAmount !== 0 ||
    p.sellerServicingFeeAmount !== 0 ||
    !p.refundToTravellerOriginalPayment ||
    !p.coversFullTicketBeforeOutbound ||
    it.sourceLabel !== "live" ||
    !it.livePrice ||
    e.allowed !== true ||
    e.penaltyAmount !== 0 ||
    e.method !== "original_payment" ||
    e.servicingFeeAmount !== 0 ||
    !e.expiresAt
  )
    return false;
  const expires = Date.parse(e.expiresAt);
  const proofExpires = Date.parse(p.expiresAt);
  const deadline = Date.parse(p.cancellationDeadlineAt);
  const review = Date.parse(p.reviewedAt);
  if (![expires, proofExpires, deadline, review].every(Number.isFinite)) return false;
  if (
    expires <= now ||
    proofExpires <= now ||
    deadline <= now ||
    review > now ||
    now - review > 48 * 60 * 60 * 1000
  )
    return false;
  return e.availablePassengers == null || e.availablePassengers >= it.passengers;
}
function BookLinks({ links }: { links: BookLink[] }) {
  return (
    <div className="links">
      {links.map((link, index) => {
        const url = bookingUrl(link);
        return url ? (
          <a
            className={"lbtn " + (index === 0 ? "primary" : "")}
            key={index}
            href={url}
            target="_blank"
            rel="noopener noreferrer"
          >
            {link.label} ↗
          </a>
        ) : null;
      })}
    </div>
  );
}
function DirectionBox({
  it,
  title,
  direction,
}: {
  it: Itinerary;
  title: string;
  direction: Direction;
}) {
  return (
    <div className="box">
      <div className="t">
        <span>{title}</span>
        <span>{stopsLabel(it)}</span>
      </div>
      <table className="seglist">
        <thead>
          <tr>
            <th>Flight</th>
            <th>From</th>
            <th>Dep</th>
            <th>To</th>
            <th>Arr</th>
            <th>Date</th>
            <th>Operated by</th>
          </tr>
        </thead>
        <tbody>
          {direction.legs.map((leg, index) => (
            <tr key={leg.flightNumber + "-" + index}>
              <td>
                <b>{leg.flightNumber}</b>
              </td>
              <td>{leg.from}</td>
              <td>{leg.departTime}</td>
              <td>{leg.to}</td>
              <td>{leg.arriveTime}</td>
              <td>
                {leg.departDate}
                {leg.arriveDate !== leg.departDate ? "→" + leg.arriveDate : ""}
              </td>
              <td className="muted">
                {leg.operatingCarrier || leg.airline}
                {leg.durationMinutes
                  ? " · " +
                    Math.floor(leg.durationMinutes / 60) +
                    "h " +
                    (leg.durationMinutes % 60) +
                    "m"
                  : ""}
                {leg.cabin ? " · " + leg.cabin : ""}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Reasons({ cell }: { cell: ComboCell }) {
  if (!(cell.reasonsNo.length || cell.catches.length || cell.oks.length)) return null;
  return (
    <div className="sec">
      <h3>Why</h3>
      <ul className="reasons">
        {cell.reasonsNo.map((reason, i) => (
          <li className="no" key={"no" + i}>
            ✗ {reason}
          </li>
        ))}
        {cell.catches.map((reason, i) => (
          <li className="catch" key={"catch" + i}>
            ⚠ {reason}
          </li>
        ))}
        {cell.oks.map((reason, i) => (
          <li className="ok" key={"ok" + i}>
            ✓ {reason}
          </li>
        ))}
      </ul>
    </div>
  );
}
function Timeline({
  it,
  addon,
  cell,
}: {
  it: Itinerary;
  addon: Itinerary | null;
  cell: ComboCell;
}) {
  const start = Date.parse("2026-12-16T00:00:00Z");
  const end = Date.parse("2027-01-01T00:00:00Z");
  const span = Math.round((end - start) / 86400000);
  const days = Array.from({ length: span + 1 }, (_, i) => new Date(start + i * 86400000));
  const pos = (iso: string) => ((Date.parse(iso + "T00:00:00Z") - start) / 86400000 / span) * 100;
  const width = (from: string, to: string) => {
    const days =
      Math.round((Date.parse(to + "T00:00:00Z") - Date.parse(from + "T00:00:00Z")) / 86400000) + 1;
    return Math.max(4, (days / span) * 100);
  };
  const marker = pos("2026-12-24") + 100 / (span + 1) / 2;
  const barClass = cell.status === "no" ? " bad" : "";
  return (
    <div className="sec">
      <h3>Timeline</h3>
      <div className="tl-days" style={{ gridTemplateColumns: "repeat(" + days.length + ", 1fr)" }}>
        {days.map((date) => {
          const iso = date.toISOString().slice(0, 10);
          return (
            <div key={iso} className={iso === "2026-12-24" ? "xmas" : ""}>
              {date.getUTCDate()}
            </div>
          );
        })}
      </div>
      {it.returnDate && (
        <div className="tl-row">
          <div className="tl-mark" style={{ left: marker + "%" }} />
          <div
            className={"tl-bar couple" + barClass}
            style={{
              left: pos(it.outboundDate) + "%",
              width: width(it.outboundDate, it.returnDate) + "%",
            }}
          >
            Florida
          </div>
        </div>
      )}
      {addon?.returnDate && (
        <div className="tl-row">
          <div className="tl-mark" style={{ left: marker + "%" }} />
          <div
            className={"tl-bar mex" + barClass}
            style={{
              left: pos(addon.outboundDate) + "%",
              width: width(addon.outboundDate, addon.returnDate) + "%",
            }}
          >
            Mexico {addon.id}
          </div>
        </div>
      )}
      <div className="muted">Red line is Christmas Eve.</div>
    </div>
  );
}
function RefundDetails({ it }: { it: Itinerary }) {
  const e = it.refundEvidence;
  return (
    <div className="sec">
      <h3>Cancellation / refund evidence</h3>
      {!e ? (
        <div className="muted">
          No verified cancellation terms were provided for this fare. The refund method, penalties
          and servicing fees are unknown.
        </div>
      ) : (
        <>
          <div className="muted">
            <b>
              {isCashRefundVerified(it)
                ? "Cash-refund evidence complete — reconfirm at booking"
                : "Fully refundable to original payment method: NOT VERIFIED"}
            </b>
          </div>
          <div>
            Airline pre-departure cancellation:{" "}
            {e.allowed === true
              ? "Allowed by airline"
              : e.allowed === false
                ? "Airline says no refund"
                : "Unknown"}
          </div>
          <div>
            Airline penalty: {money(e.penaltyAmount, e.penaltyCurrency)}; refund method:{" "}
            {e.method === "original_payment"
              ? "Original payment method"
              : e.method === "travel_credit"
                ? "Travel credit"
                : "Unknown"}
            ; servicing fees: {money(e.servicingFeeAmount, e.servicingFeeCurrency)}
          </div>
          <div className="muted">
            Offer: {e.offerId ?? "not supplied"} · Expires: {e.expiresAt ?? "unknown"} · Observed:{" "}
            {e.observedAt ?? "unknown"} · Source: {e.sourceDescription || e.provider || "unknown"}
          </div>
          {e.baggageEvidence.length > 0 && (
            <div className="muted">Bag allowance evidence: {e.baggageEvidence.join(", ")}</div>
          )}
          <div className="muted">
            A Duffel zero-penalty condition does not by itself prove cash reimbursement or absence
            of ticketing/agency fees.
          </div>
        </>
      )}
    </div>
  );
}
export interface DetailsContentProps {
  it: Itinerary;
  addon: Itinerary | null;
  cell: ComboCell | null;
  onVendors: () => void;
  onTrack: () => void;
  canTrack?: boolean;
}
export function DetailsContent({ it, addon, cell, onVendors, onTrack, canTrack = true }: DetailsContentProps) {
  const total = (flight: Itinerary) =>
    flight.pricePerPerson == null ? null : flight.pricePerPerson * flight.passengers;
  return (
    <>
      {cell && (
        <>
          <Timeline it={it} addon={addon} cell={cell} />
          <Reasons cell={cell} />
        </>
      )}
      <div className="sec">
        <h3>Flights</h3>
        <DirectionBox it={it} title="Outbound" direction={it.outbound} />
        {it.returning ? (
          <DirectionBox it={it} title="Return" direction={it.returning} />
        ) : it.returnMissing ? (
          <div className="muted">Return was not retrieved.</div>
        ) : null}
        {it.notes && <div className="muted">{it.notes}</div>}
        <BookLinks links={it.links} />
        {addon && (
          <div className="box">
            <div className="t">
              <span>
                Add-on {addon.id} · {addon.airlineSummary}
              </span>
              <span>{gbp(addon.pricePerPerson)}</span>
            </div>
            <div>
              {fmtRange(addon.outboundDate, addon.returnDate)} · {addon.cabin}
            </div>
            <div className="flights">{directionLine(addon.outbound)}</div>
            {addon.returning && <div className="flights">↩ {directionLine(addon.returning)}</div>}
            {addon.assumedReturn && (
              <div className="muted">Return time is carried from the earlier check.</div>
            )}
            <BookLinks links={addon.links} />
          </div>
        )}
      </div>
      <div className="sec">
        <h3>Price</h3>
        <table className="price">
          <tbody>
            <tr>
              <td>Long-haul × {it.passengers}</td>
              <td>{gbp(total(it))}</td>
            </tr>
            {addon && (
              <tr>
                <td>Add-on × {addon.passengers}</td>
                <td>{gbp(total(addon))}</td>
              </tr>
            )}
            {cell && (
              <tr className="total">
                <td>Total</td>
                <td>{gbp(cell.total)}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <RefundDetails it={it} />
      <div className="booknote">
        Links open for <b>1 adult</b>. Set the passenger count on Google Flights before choosing a
        booking option.{" "}
        {it.sourceLabel === "mock"
          ? "This Duffel price is a mock."
          : "Re-check the fare before paying."}
      </div>
      <div className="actions">
        <button type="button" id="btnVendorsFor" onClick={onVendors}>
          Seller comparison
        </button>
        <button type="button" id="btnTrack" disabled={!canTrack} onClick={onTrack}>
          Save to a trip
        </button>
      </div>
    </>
  );
}
