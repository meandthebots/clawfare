import { useEffect } from "react";
import * as stylex from "@stylexjs/stylex";
import { initializeClawfare } from "./controller";

// Incremental DOM-controller adapter: React owns the application shell.
// Data-intensive fare tables are still rendered by the existing controller
// until they can be converted to typed, declarative React components.
const styles = stylex.create({
  header: { boxShadow: "0 2px 12px rgba(16, 24, 40, 0.08)" },
  main: { maxWidth: "1800px", marginInline: "auto" },
  footer: { maxWidth: "1800px", marginInline: "auto" },
});

export function App() {
  useEffect(() => {
    initializeClawfare();
  }, []);

  return (
    <>
      <header {...stylex.props(styles.header)}>
        <h1 id="title">Clawfare flight search</h1>
        <p id="subtitle">London, Tampa, Sarasota, and a Mexico City add-on. Prices in GBP. API keys stay on the server.</p>
        <div className="notes" id="notes"></div>
      </header>
      <main {...stylex.props(styles.main)}>
        <section className="card" id="searchCard">
          <div className="search">
            <label>Origins <input id="origins" defaultValue="LHR, LGW" placeholder="LHR, LGW" /></label>
            <label>Destinations <input id="destinations" defaultValue="TPA, SRQ" placeholder="TPA, SRQ" /></label>
            <label>Outbound from <input id="outFrom" type="date" defaultValue="2026-12-16" /></label>
            <label>Outbound to <input id="outTo" type="date" defaultValue="2026-12-23" /></label>
            <label>Nights <input id="nights" type="number" min="1" max="60" defaultValue="8" title="Each outbound date returns this many nights later. Leave empty to use the return range." /></label>
            <label>Return from <input id="retFrom" type="date" /></label>
            <label>Return to <input id="retTo" type="date" /></label>
            <label>Trip <select id="tripType"><option value="round_trip">Round trip</option><option value="one_way">One way</option></select></label>
            <label>Adults <input id="adults" type="number" min="1" max="9" defaultValue="2" /></label>
            <label>Checked bags <input id="bags" type="number" min="0" max="4" defaultValue="1" /></label>
            <label>Long-haul cabin <select id="cabin" defaultValue="premium_economy"><option value="economy">Economy</option><option value="premium_economy" >Premium economy</option><option value="business">Business</option><option value="first">First</option></select></label>
            <label>Short-haul cabin <select id="cabinShort" defaultValue="economy"><option value="economy" >Economy</option><option value="premium_economy">Premium economy</option><option value="business">Business</option></select></label>
            <label>Return choices <input id="returnChoices" type="number" min="1" max="8" defaultValue="3" title="Cheapest outbounds expanded with a return lookup" /></label>
          </div>
          <div className="checks" style={{ marginTop: "10px" }}>
            <label><input type="checkbox" id="pSerp" defaultChecked /> SerpApi</label>
            <label><input type="checkbox" id="pSearch" defaultChecked /> SearchAPI</label>
            <label><input type="checkbox" id="pTp" defaultChecked /> Travelpayouts <span className="muted">(cached)</span></label>
            <label><input type="checkbox" id="pDuffel" defaultChecked /> Duffel <span className="muted">(mock)</span></label>
            <label><input type="checkbox" id="qNonstop" /> Nonstop only</label>
            <label><input type="checkbox" id="cacheOnly" /> Cache only</label>
          </div>
          <div className="actions">
            <button type="button" id="btnEstimate">Estimate calls</button>
            <button type="button" className="primary" id="btnRun" disabled>Run search</button>
            <button type="button" id="btnSample">Load Florida sample</button>
            <button type="button" id="btnQuota">Check quota</button>
            <button type="button" id="btnStorage" title="Check the persisted public sample row in hosted D1">Check storage</button>
            <label className="muted">Investigations <select id="saved"><option value="">—</option></select></label>
            <button type="button" id="btnOpen">Open</button>
          </div>
          <div id="invList" className="checks"></div>
          <div id="statusLine" className="est"></div>
          <div id="estimate" className="est"></div>
        </section>
      
        <div id="banners"></div>
      
        <section className="card filters" id="filterBar">
          <label>Max stops <select id="fStops"><option value="">Any</option><option value="0">Nonstop</option><option value="1">Up to 1</option><option value="2">Up to 2</option></select></label>
          <label>Max layover (hours) <input id="fLayover" type="number" min="0" step="0.5" placeholder="5" /></label>
          <label>Depart after <input id="fAfter" type="time" /></label>
          <label>Depart before <input id="fBefore" type="time" /></label>
          <label>Return after <input id="fRetAfter" type="time" /></label>
          <label>Max travel (hours) <input id="fTravel" type="number" min="0" step="0.5" /></label>
          <label>Exclude return dates <input id="fExRet" placeholder="2026-12-24, 2026-12-25" /></label>
          <label>Exclude outbound <input id="fExOut" placeholder="2026-12-24" /></label>
          <label>Airline allow <input id="fAllow" placeholder="BA, KL, Virgin" /></label>
          <label>Airline block <input id="fBlock" placeholder="AA" /></label>
          <div className="checks">
            <label><input type="checkbox" id="fNonstop" /> Nonstop only</label>
            <label><input type="checkbox" id="fEu" /> European premium</label>
            <label><input type="checkbox" id="fHideMock" defaultChecked /> Hide mocks</label>
            <label><input type="checkbox" id="fCashRefund" /> Verified cash refund only</label>
            <label><input type="checkbox" id="fHide" /> Hide infeasible</label>
            <label><input type="checkbox" id="fLhr" /> Heathrow only</label>
          </div>
          <div className="actions" style={{ marginTop: "0" }}>
            <span className="muted" style={{ fontSize: "12px", fontWeight: "700" }}>CABIN</span>
            <div className="seg" id="cabinSeg"><button type="button" data-v="all" className="on">All</button><button type="button" data-v="premium_economy">Premium economy</button><button type="button" data-v="EU">European premium</button><button type="button" data-v="business">Business</button></div>
            <span className="muted" style={{ fontSize: "12px", fontWeight: "700" }}>NIGHTS</span>
            <div className="seg" id="nightSeg"></div>
            <span className="muted" style={{ fontSize: "12px", fontWeight: "700" }}>ORIGIN</span>
            <div className="seg" id="originSeg"></div>
            <span className="muted" style={{ fontSize: "12px", fontWeight: "700" }}>SORT</span>
            <div className="seg" id="sortSeg"><button type="button" data-v="date" className="on">By date</button><button type="button" data-v="price">Cheapest feasible</button></div>
            <button type="button" id="btnSuggested">Suggested filters</button>
            <button type="button" id="btnClear">Clear filters</button>
          </div>
        </section>
      
        <div className="summary" id="summary"></div>
        <div className="legend" id="legend"></div>
        <div className="tabs" id="tabs">
          <button type="button" data-tab="grid" className="on">Grid</button>
          <button type="button" data-tab="itineraries">Itineraries</button>
          <button type="button" data-tab="matrix">Matrix</button>
          <button type="button" data-tab="vendors">Vendors</button>
        </div>
        <div className="actions" id="exportBar">
          <button type="button" id="btnHtml">Export HTML</button>
          <button type="button" id="btnCsv">Export CSV</button>
          <span className="muted" id="fresh"></span>
        </div>
        <div className="wrap" id="view"></div>
      </main>
      <footer {...stylex.props(styles.footer)} id="foot">Fares move. Nothing here is a booking. Re-check the airline before paying. Google Flights links open for one adult — set the passenger count on that page.</footer>
      <div id="overlay"></div>
      <aside id="panel" aria-hidden="true">
        <div className="ph"><div><h2 id="pTitle">Details</h2><div id="pStatus"></div></div><button className="close" id="pClose" type="button" aria-label="Close">×</button></div>
        <div className="pb" id="pBody"></div>
      </aside>
    </>
  );
}
