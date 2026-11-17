// @ts-nocheck
import { createClawfareClient } from "./api";
import { createElement } from "react";
import { createRoot } from "react-dom/client";
import { GridView } from "./GridView";
import { ItineraryView, MatrixView } from "./ItineraryViews";
import { VendorView } from "./VendorView";
import { DetailsContent, isCashRefundVerified } from "./DetailsContent";
import { SnapshotOverview } from "./SnapshotOverview";
import { toFilterSpec, matchesFilter, visibleItineraries } from "./filters";
import { flushSync } from "react-dom";
// TODO: Migrate the imperative fare-grid renderer to typed React components.
// Extracted from the former monolithic JVM HTML. This transitional controller
// retains all existing search, filtering, export, mobile touch and Sites rules.
export function initializeClawfare(): void {

  "use strict";
  var result = null;
  var tab = "grid";
  var state = blankState();
  var pendingQuery = null;
  var gridRoot = null;
  var detailsRoot = null;
  var overviewRoot = null;
  function unmountGrid() {
    if (gridRoot) { gridRoot.unmount(); gridRoot = null; }
  }

  function blankState() {
    return {
      maxStops: "", maxLayoverHours: "", excludeReturn: "", excludeOut: "",
      allow: "", block: "", maxTravelHours: "", departAfter: "", departBefore: "", returnAfter: "",
      nonstop: false, eu: false, hideMock: true, cashRefund: false, cabin: "all", nights: "all", origin: "all",
      hide: false, lhr: false, sort: "date", market: "all", vsort: "allin", sel: null
    };
  }

  function $(id) { return document.getElementById(id); }
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  function gbp(n) {
    if (n == null || isNaN(n)) return "—";
    return "£" + Math.round(Number(n)).toLocaleString("en-GB");
  }
  function money(n, cur) {
    if (n == null || isNaN(n)) return "—";
    var sym = { GBP: "£", USD: "US$", MXN: "MX$", CAD: "C$", EUR: "€" }[cur] || ((cur || "") + " ");
    return sym + Math.round(Number(n)).toLocaleString("en-GB");
  }
  function splitList(raw) {
    return String(raw || "").split(/[,/\s]+/).map(function (s) { return s.trim(); }).filter(Boolean);
  }
  function fmtDate(iso) {
    if (!iso) return "";
    var d = new Date(iso + "T12:00:00Z");
    if (isNaN(d.getTime())) return iso;
    var days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    var months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    return days[d.getUTCDay()] + " " + d.getUTCDate() + " " + months[d.getUTCMonth()];
  }
  function fmtRange(a, b) {
    if (!a) return "";
    if (!b) return fmtDate(a);
    return fmtDate(a) + " → " + fmtDate(b);
  }
  function stopsLabel(it) {
    function sl(n) { return n === 0 ? "Nonstop" : (n + " stop" + (n > 1 ? "s" : "")); }
    if (it.stopsRet == null) return sl(it.stopsOut) + (it.returnMissing ? " (out)" : "");
    if (it.stopsRet === it.stopsOut) return sl(it.stopsOut);
    return sl(it.stopsOut) + " out · " + sl(it.stopsRet) + " back";
  }
  function layText(mins) {
    if (mins == null) return "";
    var h = Math.floor(mins / 60), m = mins % 60;
    return (h ? h + "h " : "") + m + "m";
  }
  function dirLine(dir) {
    if (!dir || !dir.legs || !dir.legs.length) return "";
    return dir.legs.map(function (leg, i) {
      var lay = dir.layovers && dir.layovers[i] ? " · " + layText(dir.layovers[i].minutes) + " " + dir.layovers[i].airport : "";
      var op = leg.operatingCarrier && leg.operatingCarrier !== leg.airline ? " (" + leg.operatingCarrier + ")" : "";
      return leg.flightNumber + " " + leg.from + " " + leg.departTime + "–" + leg.to + " " + leg.arriveTime + op + lay;
    }).join(" · ");
  }
  function byId(list, id) {
    for (var i = 0; i < list.length; i++) if (String(list[i].id) === String(id)) return list[i];
    return null;
  }
  function cellKey(p, a) { return String(p) + "\0" + (a || ""); }
  function cellMap() {
    var map = {};
    (result.cells || []).forEach(function (c) { map[cellKey(c.primaryId, c.addonId)] = c; });
    return map;
  }
  function currentFilters() {
    return toFilterSpec(state);
  }
  function matches(it, f) {
    return matchesFilter(it, f, isCashRefundVerified);
  }
  function visibleRows() {
    return visibleItineraries(result, currentFilters(), state.hide, isCashRefundVerified);
  }
  function quoteable(it) {
    if (it.sourceLabel !== "live" || it.livePrice !== true) return false;
    var observed = Date.parse(it.fetchedAt || "");
    var age = Date.now() - observed;
    if (!Number.isFinite(observed) || age < 0 || age > 2 * 60 * 60 * 1000) return false;
    var expiry = it.refundEvidence && it.refundEvidence.expiresAt;
    return !it.refundEvidence || (expiry && Date.parse(expiry) > Date.now());
  }
  function verifiedCashRefund(it) { return isCashRefundVerified(it); }
  function totalOf(it) { return (it.pricePerPerson || 0) * (it.passengers || 1); }
  function cheapestCell(rows, pred) {
    var cells = cellMap();
    var best = null;
    rows.forEach(function (it) {
      if (!quoteable(it)) return;
      (result.addons || []).forEach(function (mx) {
        var c = cells[cellKey(it.id, mx.id)];
        if (!c || !pred(c, it)) return;
        if (!best || c.total < best.total) best = { total: c.total, cell: c, it: it, mx: mx };
      });
    });
    return best;
  }
  function cheapestItin(rows, pred) {
    var best = null;
    rows.forEach(function (it) {
      if (!quoteable(it) || it.pricePerPerson == null || !pred(it)) return;
      var t = (result.addons || []).length ? null : totalOf(it);
      if ((result.addons || []).length) {
        var hit = cheapestCell([it], function (c) { return c.status !== "no"; });
        if (!hit) return;
        if (!best || hit.total < best.total) best = hit;
      } else if (!best || t < best.total) {
        best = { total: t, it: it, cell: null, mx: null };
      }
    });
    return best;
  }

  function applySuggested(f) {
    f = f || {};
    state.maxStops = f.maxStops == null ? "" : String(f.maxStops);
    state.maxLayoverHours = f.maxLayoverMinutes == null ? "" : String(f.maxLayoverMinutes / 60);
    state.excludeReturn = (f.excludeReturnDates || []).join(", ");
    state.excludeOut = (f.excludeOutboundDates || []).join(", ");
    state.allow = (f.airlineAllow || []).join(", ");
    state.block = (f.airlineBlock || []).join(", ");
    state.maxTravelHours = f.maxTravelMinutes == null ? "" : String(f.maxTravelMinutes / 60);
    state.departAfter = f.outboundDepartAfter || "";
    state.departBefore = f.outboundDepartBefore || "";
    state.returnAfter = f.returnDepartAfter || "";
    state.nonstop = !!f.nonstopOnly;
    state.eu = !!f.europeanPremiumOnly;
    state.hideMock = f.hideMock !== false;
    state.cabin = f.europeanPremiumOnly ? "EU" : (f.cabin || "all");
    state.nights = f.nights == null ? "all" : String(f.nights);
    state.origin = f.origin || "all";
    state.lhr = f.origin === "LHR";
    syncControls();
  }
  function syncControls() {
    $("fStops").value = state.maxStops;
    $("fLayover").value = state.maxLayoverHours;
    $("fAfter").value = state.departAfter;
    $("fBefore").value = state.departBefore;
    $("fRetAfter").value = state.returnAfter;
    $("fTravel").value = state.maxTravelHours;
    $("fExRet").value = state.excludeReturn;
    $("fExOut").value = state.excludeOut;
    $("fAllow").value = state.allow;
    $("fBlock").value = state.block;
    $("fNonstop").checked = state.nonstop;
    $("fEu").checked = state.eu || state.cabin === "EU";
    $("fHideMock").checked = state.hideMock;
    $("fCashRefund").checked = state.cashRefund;
    $("fHide").checked = state.hide;
    $("fLhr").checked = state.lhr;
    markSeg("cabinSeg", state.cabin);
    markSeg("sortSeg", state.sort);
    buildDynamicSegs();
  }
  function readControls() {
    state.maxStops = $("fStops").value;
    state.maxLayoverHours = $("fLayover").value;
    state.departAfter = $("fAfter").value;
    state.departBefore = $("fBefore").value;
    state.returnAfter = $("fRetAfter").value;
    state.maxTravelHours = $("fTravel").value;
    state.excludeReturn = $("fExRet").value;
    state.excludeOut = $("fExOut").value;
    state.allow = $("fAllow").value;
    state.block = $("fBlock").value;
    state.nonstop = $("fNonstop").checked;
    state.eu = $("fEu").checked;
    state.hideMock = $("fHideMock").checked;
    state.cashRefund = $("fCashRefund").checked;
    state.hide = $("fHide").checked;
    state.lhr = $("fLhr").checked;
    if (state.eu && state.cabin !== "business" && state.cabin !== "premium_economy") state.cabin = "EU";
    if (!state.eu && state.cabin === "EU") state.cabin = "all";
  }
  function markSeg(id, value) {
    var root = $(id);
    if (!root) return;
    Array.prototype.forEach.call(root.querySelectorAll("button"), function (b) {
      b.classList.toggle("on", b.getAttribute("data-v") === String(value));
    });
  }
  function buildDynamicSegs() {
    var nights = {};
    var origins = {};
    (result ? result.itineraries : []).forEach(function (it) {
      if (it.nights != null) nights[it.nights] = true;
      if (it.origin) origins[it.origin] = true;
    });
    $("nightSeg").innerHTML = segButtons(["all"].concat(Object.keys(nights).sort(function (a, b) { return a - b; })), function (v) {
      return v === "all" ? "All" : (v + " nights");
    }, state.nights);
    $("originSeg").innerHTML = segButtons(["all"].concat(Object.keys(origins).sort()), function (v) { return v === "all" ? "All" : v; }, state.origin);
  }
  function segButtons(values, label, current) {
    return values.map(function (v) {
      return "<button type='button' data-v='" + esc(v) + "'" + (String(v) === String(current) ? " class='on'" : "") + ">" + esc(label(v)) + "</button>";
    }).join("");
  }

  function delta(now, prev) {
    if (prev == null) return "<span class='chg new'>new</span>";
    var d = Math.round(now - prev);
    if (d === 0) return "<span class='chg same'>= previous</span>";
    return "<span class='chg " + (d > 0 ? "up" : "down") + "'>" + (d > 0 ? "▲ " : "▼ ") + gbp(Math.abs(d)) + "</span>";
  }
  function linkIcon(links) {
    if (!links || !links.length) return "";
    return " <a class='licon' href='" + esc(links[0].url) + "' target='_blank' rel='noopener noreferrer' title='" + esc(links[0].label) + "' onclick='event.stopPropagation()'>🔗</a>";
  }
  function linkBtns(links) {
    return "<div class='links'>" + (links || []).map(function (k, i) {
      return "<a class='lbtn " + (i === 0 ? "primary" : "") + "' href='" + esc(k.url) + "' target='_blank' rel='noopener noreferrer'>" + esc(k.label) + " ↗</a>";
    }).join("") + "</div>";
  }
  function sourceTag(it) {
    var label = it.sourceLabel || "";
    if (label === "live" && !quoteable(it)) label = "cached";
    var cls = label === "mock" ? "mock" : (label === "live" ? "live" : "cached");
    var text = label === "mock" ? "Mock" : (label === "cached" ? "Cached" : (label === "sample" ? "Sample" : (label === "live" ? "Live" : label)));
    if (!it.livePrice && label !== "mock" && label !== "sample") text += " · not a live fare";
    return "<span class='tag " + cls + "'>" + esc(text) + "</span>";
  }

  function render() {
    if (!result) {
      unmountGrid();
      $("view").innerHTML = "<div class='empty'>Load the Florida sample, or estimate a search. Live providers are not called until you confirm.</div>";
      if (overviewRoot) { overviewRoot.unmount(); overviewRoot = null; }
      $("notes").textContent = "";
      $("banners").textContent = "";
      $("legend").textContent = "";
      return;
    }
    renderOverview();
    markSeg("cabinSeg", state.cabin);
    markSeg("sortSeg", state.sort);
    buildDynamicSegs();
    $("fresh").textContent = result.fetchedAt ? ("Data " + result.fetchedAt) : "";
    $("view").classList.remove("mobile-grid-view");
    if (tab === "grid") renderGrid();
    else if (tab === "itineraries") renderTable();
    else if (tab === "matrix") renderMatrix();
    else renderVendors();
    Array.prototype.forEach.call($("tabs").querySelectorAll("button"), function (b) {
      b.classList.toggle("on", b.getAttribute("data-tab") === tab);
    });
  }
  function renderOverview() {
    var vendorMode = tab === "vendors" && result.scenarioLabel === "florida";
    $("title").textContent = vendorMode
      ? "Tampa ⇄ Mexico City nonstop · Fri 25 Dec → Tue 29 Dec 2026 · 1 adult · 1 checked bag each way"
      : (result.title || "Clawfare");
    $("subtitle").textContent = vendorMode
      ? "Out AM 1741 TPA 09:23 → MEX 11:50 · Back AM 1740 MEX 15:40 → TPA 19:49. All-in prices include one checked bag. Round trip beside two one-ways. Sample seller check, not a new lookup."
      : (result.subtitle || "");
    if (!overviewRoot) overviewRoot = createRoot($("summary"));
    overviewRoot.render(createElement(SnapshotOverview, {
      result: result, rows: visibleRows(), tab: tab,
      notesTarget: $("notes"), bannersTarget: $("banners"), legendTarget: $("legend"),
      onSelect: openPanel,
    }));
  }

  function renderGrid() {
    if (!(result.addons || []).length || !(result.cells || []).length) {
      unmountGrid();
      renderTable();
      return;
    }
    var rows = visibleRows();
    var cells = cellMap();
    if (state.sort === "price") {
      rows.sort(function (a, b) {
        function min(it) {
          var m = Infinity;
          (result.addons || []).forEach(function (mx) {
            var c = cells[cellKey(it.id, mx.id)];
            if (c && c.status !== "no" && c.total < m) m = c.total;
          });
          return m;
        }
        return min(a) - min(b) || a.outboundDate.localeCompare(b.outboundDate);
      });
    } else {
      rows.sort(function (a, b) {
        return a.outboundDate.localeCompare(b.outboundDate) || String(a.returnDate || "").localeCompare(String(b.returnDate || "")) || (a.pricePerPerson || 0) - (b.pricePerPerson || 0);
      });
    }
    // Rendering and native button semantics are owned by React. The legacy
    // controller still owns the filters, sorting and detail-panel transition.
    if (!gridRoot) gridRoot = createRoot($("view"));
    $("view").classList.add("mobile-grid-view");
    gridRoot.render(createElement(GridView, {
      rows: rows, addons: result.addons, cells: result.cells,
      sort: state.sort, hideInfeasible: state.hide, selectedKey: state.sel,
      onSelect: openPanel,
    }));
  }

  function renderTable() {
    var rows = visibleRows().slice();
    if (state.sort === "price") {
      rows.sort(function (a, b) {
        return (a.pricePerPerson == null ? Infinity : a.pricePerPerson) -
          (b.pricePerPerson == null ? Infinity : b.pricePerPerson);
      });
    } else {
      rows.sort(function (a, b) {
        return a.outboundDate.localeCompare(b.outboundDate) ||
          String(a.returnDate || "").localeCompare(String(b.returnDate || ""));
      });
    }
    if (!gridRoot) gridRoot = createRoot($("view"));
    gridRoot.render(createElement(ItineraryView, { rows: rows, onSelect: openPanel }));
  }

  function renderMatrix() {
    var rows = visibleRows().filter(quoteable);
    if (!gridRoot) gridRoot = createRoot($("view"));
    gridRoot.render(createElement(MatrixView, { rows: rows, onSelect: openPanel }));
  }

  function vendorRows() {
    applyStoredNotes();
    var rows = (result.vendors || []).slice();
    if (state.market !== "all") rows = rows.filter(function (v) { return v.market === state.market; });
    rows.sort(function (a, b) {
      if (state.vsort === "vendor") return a.vendor.localeCompare(b.vendor);
      if (state.vsort === "market") return a.market.localeCompare(b.market) || ((a.allInGbp || 1e12) - (b.allInGbp || 1e12));
      return (a.allInGbp == null) - (b.allInGbp == null) || ((a.allInGbp || 1e12) - (b.allInGbp || 1e12));
    });
    return rows;
  }
  function noteStorageKey(v) { return "cf:" + (result.id || "") + ":" + v.market + ":" + v.vendor; }
  function applyStoredNotes() {
    (result.vendors || []).forEach(function (v) {
      try {
        var note = localStorage.getItem(noteStorageKey(v) + ":note");
        var trust = localStorage.getItem(noteStorageKey(v) + ":trust");
        if (note != null) v.note = note;
        if (trust) {
          v.trust = trust;
          v.trustLabel = { airline: "Airline", trusted: "Trusted OTA", caution: "OTA · caution", risky: "Risky OTA" }[trust] || v.trustLabel;
        }
      } catch (e) { /* file snapshots have no storage */ }
    });
  }
  function renderVendors() {
    var markets = {};
    (result.vendors || []).forEach(function (v) {
      markets[v.market] = v.marketLabel || v.market;
    });
    var rows = vendorRows();
    var options = (result.sellerOptions || []).filter(function (option) {
      return state.market === "all" || option.market === state.market;
    });
    if (!gridRoot) gridRoot = createRoot($("view"));
    gridRoot.render(createElement(VendorView, {
      rows: rows, markets: markets, market: state.market, options: options,
      onMarketChange: function (market) {
        state.market = market;
        renderVendors();
      },
      onNoteChange: function (vendor, note) {
        vendor.note = note;
        try { localStorage.setItem(noteStorageKey(vendor) + ":note", note); } catch (error) {}
      },
      onTrustChange: function (vendor, trust) {
        vendor.trust = trust;
        vendor.trustLabel = { airline: "Airline", trusted: "Trusted OTA", caution: "OTA · caution", risky: "Risky OTA" }[trust] || vendor.trustLabel;
        try { localStorage.setItem(noteStorageKey(vendor) + ":trust", trust); } catch (error) {}
        render();
      },
    }));
  }

  function openPanel(primaryId, addonId) {
    var it = byId(result.itineraries, primaryId);
    var mx = addonId && addonId !== "LH" ? byId(result.addons || [], addonId) : null;
    if (!it) return;
    var cells = cellMap();
    var c = cells[cellKey(it.id, mx ? mx.id : null)] || cells[cellKey(it.id, null)];
    state.sel = cellKey(it.id, mx ? mx.id : null);
    $("pTitle").textContent = (it.origin || "") + " → " + (it.destination || "") +
      " " + fmtRange(it.outboundDate, it.returnDate) + (mx ? " + " + mx.id : "");
    $("pStatus").innerHTML = c
      ? "<span class='status s-" + c.status + "'>" +
        esc(c.status === "ok" ? "Works" : c.status === "catch" ? "Works with a catch" : "Doesn’t work") +
        "</span>"
      : "";
    if (!detailsRoot) detailsRoot = createRoot($("pBody"));
    flushSync(function () {
      detailsRoot.render(createElement(DetailsContent, {
        it: it, addon: mx, cell: c || null,
        onVendors: function () { askVendors(it); },
        onTrack: function () { trackIt(it); },
      }));
    });
    $("panel").classList.add("open");
    $("overlay").classList.add("open");
    $("panel").setAttribute("aria-hidden", "false");
  }
  function closePanel() {
    $("panel").classList.remove("open");
    $("overlay").classList.remove("open");
    $("panel").setAttribute("aria-hidden", "true");
  }

  function queryFromForm() {
    var providers = [];
    if ($("pSerp").checked) providers.push("serpapi");
    if ($("pSearch").checked) providers.push("searchapi");
    if ($("pTp").checked) providers.push("travelpayouts");
    if ($("pDuffel").checked) providers.push("duffel");
    var nights = $("nights").value.trim();
    return {
      origins: splitList($("origins").value),
      destinations: splitList($("destinations").value),
      outboundFrom: $("outFrom").value,
      outboundTo: $("outTo").value || $("outFrom").value,
      returnFrom: $("retFrom").value || null,
      returnTo: $("retTo").value || null,
      nights: nights ? Number(nights) : null,
      tripType: $("tripType").value,
      adults: Number($("adults").value || 1),
      cabin: $("cabin").value,
      cabinShortHaul: $("cabinShort").value,
      checkedBags: Number($("bags").value || 0),
      currency: "GBP",
      providers: providers,
      nonstopOnly: $("qNonstop").checked,
      returnChoices: Number($("returnChoices").value || 3),
      fetchReturns: true,
      cacheOnly: $("cacheOnly").checked,
      confirmed: false
    };
  }
  function showEstimate(est, canRun) {
    if (!est) { $("estimate").innerHTML = ""; return; }
    var bits = hostedEstimate
      ? ["<b>" + est.liveCalls + "</b> potential provider requests (not executed), <b>" + est.skippedCalls + "</b> skipped"]
      : ["<b>" + est.liveCalls + "</b> live calls, <b>" + est.cachedCalls + "</b> already cached, <b>" + est.skippedCalls + "</b> skipped"];
    if (est.returnLookupsUpperBound) bits.push("up to <b>" + est.returnLookupsUpperBound + "</b> return lookups");
    var lines = (est.lines || []).slice(0, 12).map(function (l) { return "<li>" + esc(l) + "</li>"; }).join("");
    var warns = (est.warnings || []).map(function (w) { return "<div>" + esc(w) + "</div>"; }).join("");
    var quota = (est.quota || []).map(function (q) {
      var remaining = q.remaining == null ? "remaining unknown" : q.remaining + " remaining";
      return "<span>" + esc(q.provider) + ": " + esc(remaining) + (q.note ? " (" + esc(q.note) + ")" : "") + "</span>";
    }).join(" · ");
    $("estimate").innerHTML = bits.join(" · ") + (quota ? "<div>" + quota + "</div>" : "") + warns + (lines ? "<ul>" + lines + "</ul>" : "");
    $("btnRun").disabled = !canRun;
    pendingQuery = canRun ? queryFromForm() : null;
  }
  // The server validates identity and tenant scoping. A hosted preview
  // cannot request a localhost session or authorize paid API mutations.
  var hostedReadOnly = window.__CLAWFARE_HOSTED_READ_ONLY__ === true;
  var hostedEstimate = hostedReadOnly && window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ === true;
  var client = createClawfareClient({ hostedReadOnly, hostedEstimateOnly: hostedEstimate });
  var searchConsent = null;
  function api(path, body, consent) {
    return client.request(path, body, consent);
  }
  function setResult(data) {
    result = data;
    applySuggested(data.suggestedFilters || { hideMock: true });
    tab = (data.cells || []).length ? "grid" : "itineraries";
    if ((data.vendors || []).length && !(data.itineraries || []).length) tab = "vendors";
    render();
  }
  function loadSample() {
    return api("/api/sample").then(function (res) {
      if (res.body && res.body.itineraries) setResult(res.body);
      else $("estimate").textContent = res.body.error || "Sample failed to load.";
    });
  }
  function askVendors(it) {
    if (!result) return;
    if (result.scenarioLabel === "florida" || result.id === "florida-dec-2026") {
      closePanel();
      tab = "vendors";
      render();
      return;
    }
    var vendorMarkets = ["uk", "us"];
    api("/api/results/" + encodeURIComponent(result.id) + "/vendors", {
      itineraryId: it.id, markets: vendorMarkets, cacheOnly: $("cacheOnly").checked, confirmed: false
    }).then(function (res) {
      if (res.body.needsConfirmation) {
        var go = window.confirm((res.body.estimate && res.body.estimate.lines || []).join("\n") + "\n\nFetch seller offers now?");
        if (!go) return;
        return api("/api/results/" + encodeURIComponent(result.id) + "/vendors", {
          itineraryId: it.id, markets: vendorMarkets, cacheOnly: $("cacheOnly").checked, confirmed: true
        }, res.consent);
      }
      return res;
    }).then(function (res) {
      if (!res) return;
      if (res.body && res.body.result) {
        result = res.body.result;
        closePanel();
        tab = "vendors";
        render();
      } else if (res.body && res.body.error) {
        window.alert(res.body.error);
      }
    });
  }
  function trackIt(it) {
    var slug = window.prompt("Trip slug to store this fare against", "florida-dec-2026");
    if (!slug) return;
    api("/api/results/" + encodeURIComponent(result.id) + "/track", { itineraryId: it.id, tripSlug: slug }).then(function (res) {
      if (res.body && res.body.slug) window.alert("Saved on investigation " + res.body.slug + ". The CLI can show it with: clawfare flight list " + res.body.slug);
      else window.alert((res.body && res.body.error) || "Could not save.");
    });
  }
  function editedResult() {
    applyStoredNotes();
    return result;
  }
  function download(path, body, fallback) {
    // This route is local-only even if someone enables a disabled HTML button.
    return client.download(path, body, fallback);
  }

  function boot(data) {
    if (data) setResult(data);
  }

  $("btnEstimate").addEventListener("click", function () {
    var q = queryFromForm();
    searchConsent = null;
    api("/api/estimate", q).then(function (res) {
      if (res.status !== 200) {
        $("estimate").textContent = (res.body && res.body.error) || "Search estimate unavailable.";
        $("btnRun").disabled = true;
        return;
      }
      searchConsent = hostedEstimate ? null : res.consent;
      var est = res.body;
      var runnable = est && !((est.warnings || []).some(function (w) { return /Enter at least|Check the dates|Set a return|No outbound|Narrow/.test(w); })) && (q.cacheOnly || est.liveCalls > 0 || est.cachedCalls > 0 || est.returnLookupsUpperBound > 0);
      showEstimate(est, !hostedReadOnly && !!runnable);
    }).catch(function () {
      $("estimate").textContent = "Estimate request failed; no search was run.";
      $("btnRun").disabled = true;
    });
  });
  $("btnRun").addEventListener("click", function () {
    var q = pendingQuery || queryFromForm();
    q.confirmed = true;
    $("btnRun").disabled = true;
    api("/api/search", q, searchConsent).then(function (res) {
      if (res.body && res.body.result) {
        setResult(res.body.result);
        $("estimate").textContent = "Search saved as " + res.body.result.id + ".";
        refreshSaved();
      } else if (res.body && res.body.needsConfirmation) {
        showEstimate(res.body.estimate, true);
      } else {
        $("estimate").textContent = (res.body && res.body.error) || "Search failed.";
        $("btnRun").disabled = false;
      }
    });
  });
  $("btnSample").addEventListener("click", loadSample);
  $("btnQuota").addEventListener("click", function () {
    $("statusLine").textContent = "Checking quota…";
    api("/api/quota").then(function (res) {
      var rows = Array.isArray(res.body) ? res.body : [];
      $("statusLine").innerHTML = rows.map(function (q) {
        var rem = q.remaining == null ? "" : (" · " + q.remaining + " left" + (q.limit != null ? " of " + q.limit : ""));
        return "<div><b>" + esc(q.provider) + "</b>" + (q.configured ? " configured" : " not configured") + esc(rem) + (q.note ? " — " + esc(q.note) : "") + "</div>";
      }).join("");
    });
  });
  $("btnStorage").addEventListener("click", function () {
    $("statusLine").textContent = "Checking D1 public sample record…";
    api("/api/storage").then(function (res) {
      if (res.status !== 200) {
        $("statusLine").textContent = (res.body && res.body.error) || "D1 storage check failed.";
        return;
      }
      var data = res.body || {};
      $("statusLine").textContent = data.sampleStored
        ? "D1 sample stored since " + data.firstImportedAt +
          " · verified SHA-256 " + String(data.sha256 || "").slice(0, 16) +
          "… (compare this timestamp after reloading)."
        : "No sample row in D1 yet. Load the public Florida sample, then check again.";
    }).catch(function () {
      $("statusLine").textContent = "D1 storage check could not be completed.";
    });
  });
  var invList = $("invList");
  if (invList) invList.addEventListener("click", function (e) {
    var b = e.target.closest("button");
    if (!b) return;
    api("/api/results/" + encodeURIComponent(b.getAttribute("data-inv"))).then(function (res) {
      if (res.body && res.body.itineraries) setResult(res.body);
    });
  });
  $("btnOpen").addEventListener("click", function () {
    var id = $("saved").value;
    if (!id) return;
    api("/api/results/" + encodeURIComponent(id)).then(function (res) {
      if (res.body && res.body.itineraries) setResult(res.body);
      else $("estimate").textContent = (res.body && res.body.error) || "Not found.";
    });
  });
  function refreshSaved() {
    api("/api/searches").then(function (res) {
      var rows = Array.isArray(res.body) ? res.body : [];
      $("saved").innerHTML = "<option value=''>—</option>" + rows.map(function (r) {
        return "<option value='" + esc(r.id) + "'>" + esc(r.title || r.id) + "</option>";
      }).join("");
      var list = $("invList");
      if (list) {
        list.innerHTML = rows.map(function (r) {
          return "<button type='button' data-inv='" + esc(r.id) + "'>" + esc(r.title || r.id) + "</button>";
        }).join("");
      }
    }).catch(function () {});
  }
  function refreshStatus() {
    api("/api/status").then(function (res) {
      var p = (res.body && res.body.providers) || [];
      $("statusLine").innerHTML = p.map(function (q) { return "<div>" + esc(q.provider) + ": " + esc(q.note || (q.configured ? "configured" : "not configured")) + "</div>"; }).join("");
    }).catch(function () {});
  }
  $("filterBar").addEventListener("change", function () { if (!result) return; readControls(); render(); });
  $("filterBar").addEventListener("input", function (e) {
    if (!result || !e.target || e.target.tagName === "SELECT") return;
    readControls();
    render();
  });
  $("cabinSeg").addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b) return;
    state.cabin = b.getAttribute("data-v");
    state.eu = state.cabin === "EU";
    $("fEu").checked = state.eu;
    markSeg("cabinSeg", state.cabin);
    if (result) render();
  });
  $("sortSeg").addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b) return;
    state.sort = b.getAttribute("data-v");
    markSeg("sortSeg", state.sort);
    if (result) render();
  });
  $("filterBar").addEventListener("click", function (e) {
    var b = e.target.closest("#nightSeg button, #originSeg button");
    if (!b) return;
    var seg = b.parentNode.id;
    if (seg === "nightSeg") state.nights = b.getAttribute("data-v");
    if (seg === "originSeg") { state.origin = b.getAttribute("data-v"); state.lhr = false; $("fLhr").checked = false; }
    if (result) render();
  });
  $("btnSuggested").addEventListener("click", function () {
    if (!result) return;
    applySuggested(result.suggestedFilters || {});
    render();
  });
  $("btnClear").addEventListener("click", function () {
    var hide = state.hideMock;
    state = blankState();
    state.hideMock = hide;
    syncControls();
    if (result) render();
  });
  $("tabs").addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b) return;
    tab = b.getAttribute("data-tab");
    render();
  });
  $("view").addEventListener("click", function (e) {
    if (gridRoot) return; // React result tables own their interactions.
    var cell = e.target.closest("[data-p]");
    if (!cell || !result) return;
    // Mobile combinations are semantic buttons; other interactive controls
    // must continue to work without opening itinerary details.
    if (e.target.closest("a, textarea, select, button") &&
        !e.target.closest("button[data-p]")) return;
    openPanel(cell.getAttribute("data-p"), cell.getAttribute("data-a"));
  });
  $("view").addEventListener("change", function (e) {
    if (gridRoot) return; // React vendor controls own change events.
    if (e.target.id === "vMarket") { state.market = e.target.value; renderVendors(); return; }
    if (e.target.classList.contains("trustpick")) {
      var v = findVendor(e.target.getAttribute("data-market"), e.target.getAttribute("data-vendor"));
      if (!v) return;
      v.trust = e.target.value;
      try { localStorage.setItem(noteStorageKey(v) + ":trust", v.trust); } catch (err) {}
      render();
    }
  });
  $("view").addEventListener("input", function (e) {
    if (gridRoot) return;
    if (!e.target.classList.contains("noteedit")) return;
    var v = findVendor(e.target.getAttribute("data-market"), e.target.getAttribute("data-vendor"));
    if (!v) return;
    v.note = e.target.value;
    try { localStorage.setItem(noteStorageKey(v) + ":note", v.note); } catch (err) {}
  });
  function findVendor(market, vendor) {
    var rows = result && result.vendors || [];
    for (var i = 0; i < rows.length; i++) if (rows[i].market === market && rows[i].vendor === vendor) return rows[i];
    return null;
  }
  $("pClose").addEventListener("click", closePanel);
  $("overlay").addEventListener("click", closePanel);
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closePanel(); });
  $("btnHtml").addEventListener("click", function () {
    if (!result) return;
    download("/api/export/html", { result: editedResult(), filters: currentFilters(), kind: "itineraries" }, "clawfare.html");
  });
  $("btnCsv").addEventListener("click", function () {
    if (!result) return;
    download("/api/export/csv", { result: editedResult(), filters: currentFilters(), kind: tab === "vendors" ? "vendors" : "itineraries" }, "clawfare.csv");
  });

  if (hostedReadOnly) {
    ["btnRun", "btnHtml", "btnCsv"].concat(hostedEstimate ? [] : ["btnEstimate"]).forEach(function (id) {
      var el = $(id); if (el) { el.disabled = true; el.title = "Hosted preview: paid searches and exports disabled"; }
    });
    $("estimate").textContent = hostedEstimate
      ? "Search estimates are available at no cost. Run search and all paid provider calls are still disabled."
      : "Hosted read-only preview: explore saved flights and filters; live searches and exports are disabled.";
  }
  if (window.__SNAPSHOT__) {
    boot(window.__SNAPSHOT__);
  } else {
    refreshStatus();
    loadSample().then(refreshSaved);
  }

}
