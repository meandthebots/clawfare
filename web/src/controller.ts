// @ts-nocheck
import { createClawfareClient } from "./api";
import { createElement } from "react";
import { createRoot } from "react-dom/client";
import { GridView } from "./GridView";
import { ItineraryView, MatrixView } from "./ItineraryViews";
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
  function normTime(raw) {
    if (!raw) return "";
    var parts = String(raw).trim().slice(0, 5).split(":");
    if (parts.length < 2) return "00:00";
    return parts[0].padStart(2, "0") + ":" + parts[1].padStart(2, "0");
  }
  function cabinKey(raw) {
    var s = String(raw || "").toLowerCase();
    if (!s) return "economy";
    if (s.indexOf("prem") >= 0 || s === "w") return "premium_economy";
    if (s.indexOf("bus") >= 0 || s === "j") return "business";
    if (s.indexOf("first") >= 0 || s === "f") return "first";
    return "economy";
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
  function carrierMatch(leg, token) {
    if (token.toLowerCase() === String(leg.airlineCode || "").toLowerCase()) return true;
    if (token.toLowerCase() === String(leg.airline || "").toLowerCase()) return true;
    if (token.toLowerCase() === String(leg.operatingCarrier || "").toLowerCase()) return true;
    if (token.length > 2 && ((leg.airline || "").toLowerCase().indexOf(token.toLowerCase()) >= 0 || (leg.operatingCarrier || "").toLowerCase().indexOf(token.toLowerCase()) >= 0)) return true;
    return false;
  }
  function legsOf(it) { return (it.outbound.legs || []).concat(it.returning && it.returning.legs ? it.returning.legs : []); }
  function allows(it, tokens) {
    var legs = legsOf(it);
    if (!legs.length) return tokens.some(function (t) { return (it.airlineSummary || "").toLowerCase().indexOf(t.toLowerCase()) >= 0; });
    return legs.every(function (leg) { return tokens.some(function (t) { return carrierMatch(leg, t); }); });
  }
  function blocked(it, tokens) {
    var legs = legsOf(it);
    if (!legs.length) return tokens.some(function (t) { return (it.airlineSummary || "").toLowerCase().indexOf(t.toLowerCase()) >= 0; });
    return legs.some(function (leg) { return tokens.some(function (t) { return carrierMatch(leg, t); }); });
  }
  function currentFilters() {
    var cabin = state.cabin === "all" || state.cabin === "EU" ? null : state.cabin;
    return {
      maxStops: state.maxStops === "" ? null : Number(state.maxStops),
      maxLayoverMinutes: state.maxLayoverHours === "" ? null : Math.round(Number(state.maxLayoverHours) * 60),
      excludeOutboundDates: splitList(state.excludeOut),
      excludeReturnDates: splitList(state.excludeReturn),
      europeanPremiumOnly: !!(state.eu || state.cabin === "EU"),
      airlineAllow: splitList(state.allow),
      airlineBlock: splitList(state.block),
      maxTravelMinutes: state.maxTravelHours === "" ? null : Math.round(Number(state.maxTravelHours) * 60),
      outboundDepartAfter: state.departAfter || null,
      outboundDepartBefore: state.departBefore || null,
      returnDepartAfter: state.returnAfter || null,
      nonstopOnly: !!state.nonstop,
      hideMock: state.hideMock !== false,
      verifiedCashRefundOnly: !!state.cashRefund,
      cabin: cabin,
      nights: state.nights === "all" ? null : Number(state.nights),
      origin: state.lhr ? "LHR" : (state.origin === "all" ? null : state.origin)
    };
  }
  function matches(it, f) {
    if (f.hideMock && it.sourceLabel === "mock") return false;
    if (f.verifiedCashRefundOnly && !verifiedCashRefund(it)) return false;
    var stops = it.stopsRet == null ? it.stopsOut : Math.max(it.stopsOut, it.stopsRet);
    if (f.nonstopOnly && (it.stopsOut !== 0 || it.returnMissing || it.stopsRet !== 0)) return false;
    if (f.maxStops != null && stops > f.maxStops) return false;
    if (f.maxLayoverMinutes != null && (it.layoverMinutes || []).some(function (m) { return m > f.maxLayoverMinutes; })) return false;
    if ((f.excludeOutboundDates || []).indexOf(it.outboundDate) >= 0) return false;
    if (it.returnDate && (f.excludeReturnDates || []).indexOf(it.returnDate) >= 0) return false;
    if (f.europeanPremiumOnly && !it.euPremium) return false;
    if (f.maxTravelMinutes != null && (it.travelMinutes == null || it.travelMinutes > f.maxTravelMinutes)) return false;
    if (f.outboundDepartAfter && it.outboundDepart && it.outboundDepart < normTime(f.outboundDepartAfter)) return false;
    if (f.outboundDepartBefore && it.outboundDepart && it.outboundDepart > normTime(f.outboundDepartBefore)) return false;
    if (f.returnDepartAfter && it.returnDepart && it.returnDepart < normTime(f.returnDepartAfter)) return false;
    if (f.cabin && f.cabin !== "all" && it.cabinKey !== cabinKey(f.cabin)) return false;
    if (f.nights != null && it.nights !== f.nights) return false;
    if (f.origin && it.origin !== String(f.origin).toUpperCase()) return false;
    if (f.airlineAllow && f.airlineAllow.length && !allows(it, f.airlineAllow)) return false;
    if (f.airlineBlock && f.airlineBlock.length && blocked(it, f.airlineBlock)) return false;
    return true;
  }
  function visibleRows() {
    var f = currentFilters();
    var rows = (result.itineraries || []).filter(function (it) { return matches(it, f); });
    if (state.hide && (result.addons || []).length) {
      var cells = cellMap();
      rows = rows.filter(function (it) {
        return (result.addons || []).some(function (mx) {
          var c = cells[cellKey(it.id, mx.id)];
          return c && c.status !== "no";
        });
      });
    }
    return rows;
  }
  function quoteable(it) {
    if (it.sourceLabel !== "live" || it.livePrice !== true) return false;
    var observed = Date.parse(it.fetchedAt || "");
    var age = Date.now() - observed;
    if (!Number.isFinite(observed) || age < 0 || age > 2 * 60 * 60 * 1000) return false;
    var expiry = it.refundEvidence && it.refundEvidence.expiresAt;
    return !it.refundEvidence || (expiry && Date.parse(expiry) > Date.now());
  }
  function verifiedCashRefund(it) {
    var e = it.refundEvidence;
    // A provider's airline-level refund fields are NEVER sufficient.
    // The exact itinerary hash is validated by the server when loading data.
    // Unknown/manual-only, static exports, and stale proof fail closed.
    if (!it.cashRefundProofVerified || !e || !e.sellerProof) return false;
    var p = e.sellerProof;
    if (!p.sellerId || p.sellerId !== e.sellerId || p.offerId !== e.offerId ||
        p.partySize !== it.passengers || p.fareBrand !== (it.fareBrand || "") ||
        p.issuerPenaltyAmount !== 0 || p.sellerServicingFeeAmount !== 0 ||
        p.refundToTravellerOriginalPayment !== true || p.coversFullTicketBeforeOutbound !== true ||
        it.sourceLabel !== "live" || !it.livePrice || e.allowed !== true ||
        e.penaltyAmount !== 0 || e.method !== "original_payment" ||
        e.servicingFeeAmount !== 0 || !e.expiresAt) return false;
    var expires = Date.parse(e.expiresAt);
    var proofExpires = Date.parse(p.expiresAt);
    var deadline = Date.parse(p.cancellationDeadlineAt);
    var review = Date.parse(p.reviewedAt);
    var now = Date.now();
    if (![expires, proofExpires, deadline, review].every(Number.isFinite)) return false;
    if (expires <= now || proofExpires <= now || deadline <= now || review > now ||
        now - review > 48 * 60 * 60 * 1000) return false;
    return e.availablePassengers == null || e.availablePassengers >= it.passengers;
  }
  function refundDetails(it) {
    var e = it.refundEvidence;
    if (!e) return "<div class='sec'><h3>Cancellation / refund evidence</h3><div class='muted'>No verified cancellation terms were provided for this fare. The refund method, penalties and servicing fees are unknown.</div></div>";
    var permission = e.allowed === true ? "Allowed by airline" : (e.allowed === false ? "Airline says no refund" : "Unknown");
    var penalty = e.penaltyAmount == null ? "Unknown" : money(e.penaltyAmount, e.penaltyCurrency || "");
    var method = e.method === "original_payment" ? "Original payment method" : (e.method === "travel_credit" ? "Travel credit" : "Unknown");
    var serviceFee = e.servicingFeeAmount == null ? "Unknown" : money(e.servicingFeeAmount, e.servicingFeeCurrency || "");
    var heading = verifiedCashRefund(it) ? "Cash-refund evidence complete — reconfirm at booking" : "Fully refundable to original payment method: NOT VERIFIED";
    return "<div class='sec'><h3>Cancellation / refund evidence</h3>" +
      "<div class='muted'><b>" + esc(heading) + "</b></div>" +
      "<div>Airline pre-departure cancellation: " + esc(permission) + "</div>" +
      "<div>Airline penalty: " + esc(penalty) + "; refund method: " + esc(method) + "; servicing fees: " + esc(serviceFee) + "</div>" +
      "<div class='muted'>Offer: " + esc(e.offerId || "not supplied") + " · Expires: " + esc(e.expiresAt || "unknown") +
      " · Observed: " + esc(e.observedAt || "unknown") + " · Source: " + esc(e.sourceDescription || e.provider || "unknown") + "</div>" +
      (e.baggageEvidence && e.baggageEvidence.length ? "<div class='muted'>Bag allowance evidence: " + esc(e.baggageEvidence.join(", ")) + "</div>" : "") +
      "<div class='muted'>A Duffel zero-penalty condition does not by itself prove cash reimbursement or absence of ticketing/agency fees.</div></div>";
  }
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
      $("summary").innerHTML = "";
      $("legend").innerHTML = "";
      return;
    }
    renderHeader();
    renderSummary();
    renderLegend();
    markSeg("cabinSeg", state.cabin);
    markSeg("sortSeg", state.sort);
    buildDynamicSegs();
    $("fresh").textContent = result.fetchedAt ? ("Data " + result.fetchedAt) : "";
    $("view").classList.remove("mobile-grid-view");
    if (tab === "grid") renderGrid();
    else if (tab === "itineraries") renderTable();
    else if (tab === "matrix") renderMatrix();
    else { unmountGrid(); renderVendors(); }
    Array.prototype.forEach.call($("tabs").querySelectorAll("button"), function (b) {
      b.classList.toggle("on", b.getAttribute("data-tab") === tab);
    });
  }
  function renderHeader() {
    var vendorMode = tab === "vendors" && result.scenarioLabel === "florida";
    $("title").textContent = vendorMode
      ? "Tampa ⇄ Mexico City nonstop · Fri 25 Dec → Tue 29 Dec 2026 · 1 adult · 1 checked bag each way"
      : (result.title || "Clawfare");
    $("subtitle").textContent = vendorMode
      ? "Out AM 1741 TPA 09:23 → MEX 11:50 · Back AM 1740 MEX 15:40 → TPA 19:49. All-in prices include one checked bag. Round trip beside two one-ways. Sample seller check, not a new lookup."
      : (result.subtitle || "");
    var notes = vendorMode ? (result.vendorIntro || []) : (result.headerNotes || []);
    $("notes").innerHTML = notes.map(function (n) {
      return "<div class='note" + (n.warn ? " warn" : "") + "'><span class='nt'>" + esc(n.title) + "</span>" + esc(n.body) + "</div>";
    }).join("");
    var banners = [];
    if (result.scenarioLabel === "sample" || (result.itineraries || []).some(function (it) { return it.sourceLabel === "sample"; })) {
      banners.push("<div class='banner'>Sample saved from Google Flights. Long-haul fares 11 Oct 2026, seller table 15 Oct 2026. This is not a live search.</div>");
    }
    (result.warnings || []).forEach(function (w) {
      banners.push("<div class='banner" + (/mock|fail|credit|error/i.test(w) ? " bad" : "") + "'>" + esc(w) + "</div>");
    });
    if ((result.itineraries || []).some(function (it) { return it.sourceLabel === "mock"; })) {
      banners.push("<div class='banner bad'>Duffel rows are test-mode mocks. They are hidden from the cheapest highlight and, with “Hide mocks” on, from the tables.</div>");
    }
    if ((result.itineraries || []).some(function (it) { return it.sourceLabel === "cached"; })) {
      banners.push("<div class='banner'>Cached fares (including historic Duffel live quotes and Travelpayouts) are not verified bookable at the displayed price.</div>");
    }
    $("banners").innerHTML = banners.join("");
  }
  function card(label, hit) {
    if (!hit) return "<div class='sumcard' style='cursor:default'><div class='k'>" + esc(label) + "</div><div class='v'>—</div><div class='d'>None in the current filters</div></div>";
    var who = "#" + hit.it.id + " " + (hit.it.airlineSummary || "") + " " + fmtRange(hit.it.outboundDate, hit.it.returnDate);
    if (hit.mx) who += " + Mexico " + hit.mx.id;
    var attrs = " data-p='" + esc(hit.it.id) + "' data-a='" + esc(hit.mx ? hit.mx.id : "LH") + "'";
    return "<div class='sumcard'" + attrs + "><div class='k'>" + esc(label) + "</div><div class='v'>" + gbp(hit.total) + "</div><div class='d'>" + esc(who) + "</div></div>";
  }
  function renderSummary() {
    var rows = visibleRows();
    var feasible = (result.addons || []).length
      ? cheapestCell(rows, function (c) { return c.status !== "no"; })
      : cheapestItin(rows, function () { return true; });
    var works = (result.addons || []).length ? cheapestCell(rows, function (c) { return c.status === "ok"; }) : null;
    var nonstop = cheapestItin(rows, function (it) { return it.stopsOut === 0 && !it.returnMissing && it.stopsRet === 0; });
    var eu = cheapestItin(rows, function (it) { return it.euPremium; });
    $("summary").innerHTML = [
      card((result.addons || []).length ? "Cheapest feasible combination" : "Cheapest", feasible),
      (result.addons || []).length ? card("Cheapest fully works", works) : "",
      card("Cheapest nonstop", nonstop),
      card("Cheapest European premium", eu)
    ].join("");
  }
  function renderLegend() {
    if ((result.addons || []).length && (result.cells || []).length && tab === "grid") {
      $("legend").innerHTML = [
        "<span><i class='sw' style='background:var(--green-bg);border-color:var(--green)'></i>Works</span>",
        "<span><i class='sw' style='background:var(--amber-bg);border-color:var(--amber)'></i>Works with a catch</span>",
        "<span><i class='sw' style='background:#f2f4f7'></i>Doesn’t work</span>",
        "<span><i class='sw' style='background:#fff;border:2px solid var(--gold)'></i>Cheapest feasible</span>",
        "<span><i class='sw' style='background:#fff;border:2px solid #027a48'></i>Cheapest that fully works</span>",
        "<span>Cell = 2 × long-haul + 1 × Mexico. 🔗 opens the booking link.</span>"
      ].join("");
    } else if (tab === "vendors") {
      $("legend").innerHTML = "<span><i class='sw' style='background:#ecfdf3;border-color:#027a48'></i>Cheapest all-in in this market filter</span><span class='trust risky'>Risky</span><span class='trust caution'>Caution</span><span class='trust trusted'>Trusted</span><span class='trust airline'>Airline</span><span>Notes and trust flags are editable and travel with the HTML export.</span>";
    } else {
      $("legend").innerHTML = "<span>Prices are per person in GBP unless a row says otherwise. Mocks are excluded from the cheapest cards.</span>";
    }
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
          String(a.returnDate || "").localeCompare(String(b.returnDate || "");
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
    (result.vendors || []).forEach(function (v) { markets[v.market] = v.marketLabel || v.market; });
    var rows = vendorRows();
    var cheapest = null;
    rows.forEach(function (v) { if (v.allInGbp != null && (cheapest == null || v.allInGbp < cheapest)) cheapest = v.allInGbp; });
    var html = "<div class='actions' style='padding:8px'><span class='muted'>Market</span> <select id='vMarket'><option value='all'>All</option>";
    Object.keys(markets).sort().forEach(function (m) {
      html += "<option value='" + esc(m) + "'" + (state.market === m ? " selected" : "") + ">" + esc(markets[m]) + "</option>";
    });
    html += "</select> <span class='muted'>" + rows.length + " sellers</span></div>";
    html += "<table class='data' id='vendors'><thead><tr><th>#</th><th class='num'>All-in incl. bag</th><th>Vendor / fare</th><th>Type</th><th>Market · fetched</th><th class='num'>Δ all-in</th><th class='num'>Round trip</th><th class='num'>One-way out</th><th class='num'>One-way back</th><th class='num'>2 one-ways incl. bags</th><th>Google Flights</th></tr></thead><tbody>";
    if (!rows.length) html += "<tr><td class='empty' colspan='11'>No seller rows. Open a sample, or fetch sellers for one saved itinerary.</td></tr>";
    rows.forEach(function (v, i) {
      var best = v.allInGbp != null && v.allInGbp === cheapest;
      var chg = v.isNew && v.priceChangeGbp == null ? "<span class='chg new'>new</span>" : (v.priceChangeGbp == null ? "" : delta(v.priceChangeGbp, 0).replace("£0", "£" + Math.abs(v.priceChangeGbp)).replace("previous", "14 Oct"));
      if (v.priceChangeGbp != null) {
        var d = v.priceChangeGbp;
        chg = d === 0 ? "<span class='chg same'>£0</span>" : "<span class='chg " + (d > 0 ? "up" : "down") + "'>" + (d > 0 ? "▲ £" : "▼ £") + Math.abs(d) + "</span>";
      }
      html += "<tr class='" + (best ? "bestv" : "") + (v.fresh ? "" : " stale") + "'><td>" + (i + 1) + "</td>" +
        "<td class='num'><b>" + gbp(v.allInGbp) + "</b><div class='muted'>" + esc(v.how || "") + "</div></td>" +
        "<td><b>" + esc(v.vendor) + "</b><div class='muted'>" + esc(v.fareBrand || "") + (v.bags ? " · " + esc(v.bags) : "") + "</div>" +
        "<textarea class='noteedit' data-market='" + esc(v.market) + "' data-vendor='" + esc(v.vendor) + "'>" + esc(v.note || "") + "</textarea></td>" +
        "<td><span class='trust " + esc(v.trust) + "'>" + esc(v.trustLabel || v.trust) + "</span><div><select class='trustpick' data-market='" + esc(v.market) + "' data-vendor='" + esc(v.vendor) + "'>" +
        ["airline", "trusted", "caution", "risky"].map(function (t) { return "<option value='" + t + "'" + (v.trust === t ? " selected" : "") + ">" + t + "</option>"; }).join("") +
        "</select></div></td>" +
        "<td>" + esc(v.marketLabel || v.market) + "<div class='muted'>" + esc(v.fetchedAt || "") + (v.fresh ? "" : " · carried forward") + "</div></td>" +
        "<td class='num'>" + chg + "</td>" +
        "<td class='num'>" + money(v.roundTrip, v.currency) + (v.roundTripWithBag != null ? "<div class='muted'>with bag " + money(v.roundTripWithBag, v.currency) + "</div>" : "") + "</td>" +
        "<td class='num'>" + money(v.oneWayOut, v.currency) + "</td>" +
        "<td class='num'>" + money(v.oneWayBack, v.currency) + "</td>" +
        "<td class='num'>" + money(v.oneWaysWithBag, v.currency) + "</td>" +
        "<td>" + (v.links || []).map(function (l) {
          return "<a class='lbtn' href='" + esc(l.url) + "' target='_blank' rel='noopener noreferrer'>" + esc(l.label) + "</a> ";
        }).join("") + "</td></tr>";
    });
    html += "</tbody></table>";
    var options = (result.sellerOptions || []).filter(function (option) {
      return state.market === "all" || option.market === state.market;
    });
    if (options.length) {
      html += "<div class='sec'><h3>Exact seller fare options (" + options.length + ")</h3>" +
        "<div class='muted'>Provider booking options are read-only observations. Fare name and airline cancellation flags do not prove a cash refund to your original payment method. Split tickets, telephone fees, unknown baggage and missing prices are not verified all-in offers.</div>" +
        "<table class='data'><thead><tr><th>Seller / fare</th><th>Market</th><th>Journey</th><th>Whole-party quote</th><th>Details</th><th>Refund</th></tr></thead><tbody>";
      options.forEach(function (option) {
        var label = option.seller + (option.fareBrand || option.fareType ? " — " + (option.fareBrand || option.fareType) : "");
        var details = [
          option.splitTickets ? "Separate tickets" : "Ticketing not independently verified",
          option.directAirline === true ? "Provider marked airline direct" : "Seller/issuer needs confirmation",
          option.bags || "Baggage fee unknown",
          option.phoneServiceFee != null ? "Possible telephone service fee " + money(option.phoneServiceFee, option.currency) : ""
        ].filter(Boolean).join("; ");
        html += "<tr><td>" + esc(label) + "<div class='muted'>" + esc(option.id) + "</div></td>" +
          "<td>" + esc(option.market) + "</td><td>" + esc(option.journey) + "</td>" +
          "<td class='num'>" + money(option.priceTotal, option.currency) + " <span class='muted'>/ " + esc(option.passengers) + " adults</span></td>" +
          "<td>" + esc(details) + "</td><td>Unverified</td></tr>";
      });
      html += "</tbody></table></div>";
    }
    $("view").innerHTML = html;
  }

  function openPanel(primaryId, addonId) {
    var it = byId(result.itineraries, primaryId);
    var mx = addonId && addonId !== "LH" ? byId(result.addons || [], addonId) : null;
    if (!it) return;
    var cells = cellMap();
    var c = cells[cellKey(it.id, mx ? mx.id : null)] || cells[cellKey(it.id, null)];
    state.sel = cellKey(it.id, mx ? mx.id : null);
    $("pTitle").textContent = (it.origin || "") + " → " + (it.destination || "") + " " + fmtRange(it.outboundDate, it.returnDate) + (mx ? " + " + mx.id : "");
    $("pStatus").innerHTML = c ? "<span class='status s-" + c.status + "'>" + esc(c.status === "ok" ? "Works" : (c.status === "catch" ? "Works with a catch" : "Doesn’t work")) + "</span>" : "";
    var html = "";
    if (c) html += timeline(it, mx, c) + reasons(c);
    html += "<div class='sec'><h3>Flights</h3>" + directionBox(it, "Outbound", it.outbound) + (it.returning ? directionBox(it, "Return", it.returning) : (it.returnMissing ? "<div class='muted'>Return was not retrieved.</div>" : ""));
    if (it.notes) html += "<div class='muted'>" + esc(it.notes) + "</div>";
    html += linkBtns(it.links);
    if (mx) {
      html += "<div class='box'><div class='t'><span>Add-on " + esc(mx.id) + " · " + esc(mx.airlineSummary) + "</span><span>" + gbp(mx.pricePerPerson) + "</span></div>" +
        "<div>" + esc(fmtRange(mx.outboundDate, mx.returnDate)) + " · " + esc(mx.cabin) + "</div>" +
        "<div class='flights'>" + esc(dirLine(mx.outbound)) + "</div>" +
        (mx.returning ? "<div class='flights'>↩ " + esc(dirLine(mx.returning)) + "</div>" : "") +
        (mx.assumedReturn ? "<div class='muted'>Return time is carried from the earlier check.</div>" : "") +
        linkBtns(mx.links) + "</div>";
    }
    html += "</div>";
    if (c) {
      html += "<div class='sec'><h3>Price</h3><table class='price'><tr><td>Long-haul × " + it.passengers + "</td><td>" + gbp(totalOf(it)) + "</td></tr>" +
        (mx ? "<tr><td>Add-on × " + (mx.passengers || 1) + "</td><td>" + gbp((mx.pricePerPerson || 0) * (mx.passengers || 1)) + "</td></tr>" : "") +
        "<tr class='total'><td>Total</td><td>" + gbp(c.total) + "</td></tr></table></div>";
    } else {
      html += "<div class='sec'><h3>Price</h3><table class='price'><tr><td>" + gbp(it.pricePerPerson) + " per person × " + it.passengers + "</td><td>" + gbp(totalOf(it)) + "</td></tr></table></div>";
    }
    html += refundDetails(it);
    html += "<div class='booknote'>Links open for <b>1 adult</b>. Set the passenger count on Google Flights before choosing a booking option. " + esc(it.sourceLabel === "mock" ? "This Duffel price is a mock." : "Re-check the fare before paying.") + "</div>";
    html += "<div class='actions'><button type='button' id='btnVendorsFor'>Seller comparison</button> <button type='button' id='btnTrack'>Save to a trip</button></div>";
    $("pBody").innerHTML = html;
    $("panel").classList.add("open");
    $("overlay").classList.add("open");
    $("panel").setAttribute("aria-hidden", "false");
    var vend = $("btnVendorsFor");
    if (vend) vend.addEventListener("click", function () { askVendors(it); });
    var track = $("btnTrack");
    if (track) track.addEventListener("click", function () { trackIt(it); });
  }
  function directionBox(it, title, dir) {
    var rows = (dir.legs || []).map(function (leg) {
      return "<tr><td><b>" + esc(leg.flightNumber) + "</b></td><td>" + esc(leg.from) + "</td><td>" + esc(leg.departTime) + "</td><td>" + esc(leg.to) + "</td><td>" + esc(leg.arriveTime) + "</td><td>" + esc(leg.departDate) + (leg.arriveDate && leg.arriveDate !== leg.departDate ? "→" + leg.arriveDate : "") + "</td><td class='muted'>" + esc(leg.operatingCarrier || leg.airline) + (leg.durationMinutes ? " · " + layText(leg.durationMinutes) : "") + (leg.cabin ? " · " + esc(leg.cabin) : "") + "</td></tr>";
    }).join("");
    return "<div class='box'><div class='t'><span>" + esc(title) + "</span><span>" + esc(stopsLabel(it)) + "</span></div>" +
      "<table class='seglist'><thead><tr><th>Flight</th><th>From</th><th>Dep</th><th>To</th><th>Arr</th><th>Date</th><th>Operated by</th></tr></thead><tbody>" + rows + "</tbody></table></div>";
  }
  function reasons(c) {
    var items = (c.reasonsNo || []).map(function (r) { return "<li class='no'>✗ " + esc(r) + "</li>"; })
      .concat((c.catches || []).map(function (r) { return "<li class='catch'>⚠ " + esc(r) + "</li>"; }))
      .concat((c.oks || []).map(function (r) { return "<li class='ok'>✓ " + esc(r) + "</li>"; }));
    if (!items.length) return "";
    return "<div class='sec'><h3>Why</h3><ul class='reasons'>" + items.join("") + "</ul></div>";
  }
  function timeline(it, mx, c) {
    var start = "2026-12-16", end = "2027-01-01";
    var a = Date.parse(start + "T00:00:00Z"), b = Date.parse(end + "T00:00:00Z");
    var span = Math.round((b - a) / 86400000);
    function pos(iso) { return ((Date.parse(iso + "T00:00:00Z") - a) / 86400000) / span * 100; }
    function width(from, to) {
      var days = Math.round((Date.parse(to + "T00:00:00Z") - Date.parse(from + "T00:00:00Z")) / 86400000) + 1;
      return Math.max(4, days / span * 100);
    }
    var days = "";
    for (var i = 0; i <= span; i++) {
      var dt = new Date(a + i * 86400000);
      var iso = dt.toISOString().slice(0, 10);
      days += "<div class='" + (iso === "2026-12-24" ? "xmas" : "") + "'>" + dt.getUTCDate() + "</div>";
    }
    var x = pos("2026-12-24") + (100 / (span + 1) / 2);
    var couple = it.returnDate ? "<div class='tl-row'><div class='tl-mark' style='left:" + x + "%'></div><div class='tl-bar couple" + (c && c.status === "no" ? " bad" : "") + "' style='left:" + pos(it.outboundDate) + "%;width:" + width(it.outboundDate, it.returnDate) + "%'>Florida</div></div>" : "";
    var mex = mx && mx.returnDate ? "<div class='tl-row'><div class='tl-mark' style='left:" + x + "%'></div><div class='tl-bar mex" + (c && c.status === "no" ? " bad" : "") + "' style='left:" + pos(mx.outboundDate) + "%;width:" + width(mx.outboundDate, mx.returnDate) + "%'>Mexico " + esc(mx.id) + "</div></div>" : "";
    return "<div class='sec'><h3>Timeline</h3><div class='tl-days' style='grid-template-columns:repeat(" + (span + 1) + ",1fr)'>" + days + "</div>" + couple + mex + "<div class='muted'>Red line is Christmas Eve.</div></div>";
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
  $("summary").addEventListener("click", function (e) {
    var card = e.target.closest(".sumcard[data-p]");
    if (card) openPanel(card.getAttribute("data-p"), card.getAttribute("data-a"));
  });
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
