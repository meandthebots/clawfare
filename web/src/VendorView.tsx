import * as stylex from "@stylexjs/stylex";
import { TrustBadge } from "./TrustBadge.tsx";
import type { SellerOption, VendorRow } from "./domain.ts";
import { bookingUrl, gbp } from "./GridView.tsx";

const styles = stylex.create({
  toolbar: { padding: "8px", display: "flex", alignItems: "center", gap: "8px" },
  delta: { fontWeight: 700 },
  sellerDescription: { marginBlock: "12px" },
});
const currencySymbols: Record<string, string> = {
  GBP: "£",
  USD: "US$",
  MXN: "MX$",
  CAD: "C$",
  EUR: "€",
};
function money(value: number | null | undefined, currency: string): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return (currencySymbols[currency] ?? currency + " ") + Math.round(value).toLocaleString("en-GB");
}
function Change({ amount, isNew }: { amount?: number | null; isNew: boolean }) {
  if (amount == null) return isNew ? <span className="chg new">new</span> : null;
  return (
    <span className={"chg " + (amount > 0 ? "up" : amount < 0 ? "down" : "same")}>
      {amount === 0 ? "£0" : (amount > 0 ? "▲ £" : "▼ £") + Math.abs(amount)}
    </span>
  );
}
export interface VendorViewProps {
  rows: VendorRow[];
  markets: Record<string, string>;
  market: string;
  options: SellerOption[];
  onMarketChange: (market: string) => void;
  onNoteChange: (vendor: VendorRow, note: string) => void;
  onTrustChange: (vendor: VendorRow, trust: string) => void;
}

export function VendorView({
  rows,
  markets,
  market,
  options,
  onMarketChange,
  onNoteChange,
  onTrustChange,
}: VendorViewProps) {
  const prices = rows
    .map((row) => row.allInGbp)
    .filter((amount): amount is number => amount != null);
  const cheapest = prices.length ? Math.min(...prices) : null;
  return (
    <>
      <div className="actions" {...stylex.props(styles.toolbar)}>
        <label htmlFor="vMarket" className="muted">
          Market
        </label>
        <select
          id="vMarket"
          value={market}
          onChange={(event) => onMarketChange(event.target.value)}
        >
          <option value="all">All</option>
          {Object.entries(markets)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([key, value]) => (
              <option value={key} key={key}>
                {value}
              </option>
            ))}
        </select>
        <span className="muted">{rows.length} sellers</span>
      </div>
      <table className="data" id="vendors">
        <thead>
          <tr>
            <th>#</th>
            <th className="num">All-in incl. bag</th>
            <th>Vendor / fare</th>
            <th>Type</th>
            <th>Market · fetched</th>
            <th className="num">Δ all-in</th>
            <th className="num">Round trip</th>
            <th className="num">One-way out</th>
            <th className="num">One-way back</th>
            <th className="num">2 one-ways incl. bags</th>
            <th>Google Flights</th>
          </tr>
        </thead>
        <tbody>
          {!rows.length && (
            <tr>
              <td className="empty" colSpan={11}>
                No seller rows. Open a sample, or fetch sellers for one saved itinerary.
              </td>
            </tr>
          )}
          {rows.map((vendor, index) => (
            <tr
              key={vendor.market + ":" + vendor.vendor}
              className={
                (vendor.allInGbp != null && vendor.allInGbp === cheapest ? "bestv" : "") +
                (vendor.fresh ? "" : " stale")
              }
            >
              <td>{index + 1}</td>
              <td className="num">
                <b>{gbp(vendor.allInGbp)}</b>
                <div className="muted">{vendor.how}</div>
              </td>
              <td>
                <b>{vendor.vendor}</b>
                <div className="muted">
                  {vendor.fareBrand}
                  {vendor.bags ? " · " + vendor.bags : ""}
                </div>
                <textarea
                  className="noteedit"
                  data-market={vendor.market}
                  data-vendor={vendor.vendor}
                  aria-label={"Note for " + vendor.vendor + " in " + vendor.market}
                  defaultValue={vendor.note}
                  onChange={(event) => onNoteChange(vendor, event.target.value)}
                />
              </td>
              <td>
                <TrustBadge trust={vendor.trust} label={vendor.trustLabel || vendor.trust} />
                <div>
                  <select
                    className="trustpick"
                    data-market={vendor.market}
                    data-vendor={vendor.vendor}
                    aria-label={"Trust level for " + vendor.vendor}
                    value={vendor.trust}
                    onChange={(event) => onTrustChange(vendor, event.target.value)}
                  >
                    {["airline", "trusted", "caution", "risky"].map((trust) => (
                      <option key={trust} value={trust}>
                        {trust}
                      </option>
                    ))}
                  </select>
                </div>
              </td>
              <td>
                {vendor.marketLabel || vendor.market}
                <div className="muted">
                  {vendor.fetchedAt ?? ""}
                  {vendor.fresh ? "" : " · carried forward"}
                </div>
              </td>
              <td className="num">
                <Change amount={vendor.priceChangeGbp} isNew={vendor.isNew} />
              </td>
              <td className="num">
                {money(vendor.roundTrip, vendor.currency)}
                {vendor.roundTripWithBag != null && (
                  <div className="muted">
                    with bag {money(vendor.roundTripWithBag, vendor.currency)}
                  </div>
                )}
              </td>
              <td className="num">{money(vendor.oneWayOut, vendor.currency)}</td>
              <td className="num">{money(vendor.oneWayBack, vendor.currency)}</td>
              <td className="num">{money(vendor.oneWaysWithBag, vendor.currency)}</td>
              <td>
                {vendor.links.map((link, i) => {
                  const url = bookingUrl(link);
                  return url ? (
                    <a
                      key={i}
                      className="lbtn"
                      href={url}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {link.label}
                    </a>
                  ) : null;
                })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {options.length > 0 && (
        <div className="sec">
          <h3>Exact seller fare options ({options.length})</h3>
          <div className="muted">
            Provider booking options are read-only observations. Fare name and airline cancellation
            flags do not prove a cash refund to your original payment method. Split tickets,
            telephone fees, unknown baggage and missing prices are not verified all-in offers.
          </div>
          <table className="data">
            <thead>
              <tr>
                <th>Seller / fare</th>
                <th>Market</th>
                <th>Journey</th>
                <th>Whole-party quote</th>
                <th>Details</th>
                <th>Refund</th>
              </tr>
            </thead>
            <tbody>
              {options.map((option) => {
                const detail = [
                  option.splitTickets ? "Separate tickets" : "Ticketing not independently verified",
                  option.directAirline === true
                    ? "Provider marked airline direct"
                    : "Seller/issuer needs confirmation",
                  option.bags || "Baggage fee unknown",
                  option.phoneServiceFee != null
                    ? "Possible telephone service fee " +
                      money(option.phoneServiceFee, option.currency)
                    : "",
                ]
                  .filter(Boolean)
                  .join("; ");
                return (
                  <tr key={option.id}>
                    <td>
                      {option.seller}
                      {option.fareBrand || option.fareType
                        ? " — " + (option.fareBrand || option.fareType)
                        : ""}
                      <div className="muted">{option.id}</div>
                    </td>
                    <td>{option.market}</td>
                    <td>{option.journey}</td>
                    <td className="num">
                      {money(option.priceTotal, option.currency)}
                      <span className="muted"> / {option.passengers} adults</span>
                    </td>
                    <td>{detail}</td>
                    <td>Unverified</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
