import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import * as stylex from "@stylexjs/stylex";
import { createClawfareClient, type HostedSearchQuote } from "./api.ts";
import { DetailsContent } from "./DetailsContent.tsx";
import { FareTracking } from "./FareTracking.tsx";
import { FilterControls } from "./FilterControls.tsx";
import { initialFilters, suggestedFilters, type FilterControlsState } from "./filterForm.ts";
import { isCashRefundVerified } from "./DetailsContent.tsx";
import { GridView, fmtRange, quoteable } from "./GridView.tsx";
import { ItineraryView, MatrixView } from "./ItineraryViews.tsx";
import { hasComparableGbpFare, itineraryEmptyMessage } from "./SearchCoverage.tsx";
import { SearchControls } from "./SearchControls.tsx";
import {
  INITIAL_SEARCH,
  buildSearchQuery,
  estimateIsRunnable,
  type SearchForm,
} from "./searchForm.ts";
import { SnapshotOverview, type ResultsTab } from "./SnapshotOverview.tsx";
import { VendorView } from "./VendorView.tsx";
import { toFilterSpec, visibleItineraries } from "./filters.ts";
import { isEstimate, isRecord, isResultSet } from "./domain.ts";
import type {
  ComboCell,
  Estimate,
  Itinerary,
  QuotaView,
  ResultSet,
  SavedInvestigation,
  SearchQuery,
} from "./domain.ts";

// URL selection affects only this browser client. It cannot grant server-side
// access: every guest API response comes from a bundled synthetic fixture.
const GUEST_MODE = new URLSearchParams(window.location.search).get("guest") === "1";
const HOSTED_READ_ONLY = GUEST_MODE || window.__CLAWFARE_HOSTED_READ_ONLY__ === true;
const HOSTED_ESTIMATE_ONLY =
  !GUEST_MODE && HOSTED_READ_ONLY && window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ === true;
const client = createClawfareClient({
  hostedReadOnly: HOSTED_READ_ONLY,
  hostedEstimateOnly: HOSTED_ESTIMATE_ONLY,
  guest: GUEST_MODE,
});
const styles = stylex.create({
  header: {
    boxShadow: "0 2px 12px rgba(16, 24, 40, 0.08)",
    backgroundImage: "var(--header-gradient)",
    color: "#fff",
    paddingTop: "22px",
    paddingBottom: "18px",
    paddingLeft: { default: "28px", "@media (max-width: 760px)": "14px" },
    paddingRight: { default: "28px", "@media (max-width: 760px)": "14px" },
  },
  heading: { margin: "0 0 6px", fontSize: "22px", fontWeight: 700, letterSpacing: "-0.01em" },
  subtitle: { margin: "4px 0", maxWidth: "1100px", opacity: 0.95 },
  main: {
    maxWidth: "1800px",
    marginInline: "auto",
    paddingTop: "16px",
    paddingBottom: "40px",
    paddingLeft: { default: "28px", "@media (max-width: 760px)": "14px" },
    paddingRight: { default: "28px", "@media (max-width: 760px)": "14px" },
  },
  footer: {
    maxWidth: "1800px",
    marginInline: "auto",
    color: "var(--muted)",
    fontSize: "12px",
    paddingBottom: "28px",
    paddingLeft: { default: "28px", "@media (max-width: 760px)": "14px" },
    paddingRight: { default: "28px", "@media (max-width: 760px)": "14px" },
  },
  notes: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit,minmax(260px,1fr))",
    gap: "10px",
    marginTop: "12px",
    maxWidth: "1400px",
  },
  legend: {
    display: "flex",
    flexWrap: "wrap",
    gap: "14px",
    alignItems: "center",
    margin: "0 0 10px",
    fontSize: "13px",
    color: "var(--muted)",
  },
  results: { minWidth: 0 },
  summary: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit,minmax(240px,1fr))",
    gap: "12px",
    marginBottom: "12px",
  },
  tabs: { display: "flex", gap: "6px", margin: "0 0 10px" },
  tabSelected: { backgroundColor: "var(--accent)", color: "#fff", borderColor: "var(--accent)" },
  overlay: {
    position: "fixed",
    inset: 0,
    backgroundColor: "rgba(16,24,40,0.35)",
    display: "none",
    zIndex: 20,
  },
  overlayOpen: { display: "block" },
  panel: {
    position: "fixed",
    top: 0,
    right: 0,
    height: "100%",
    width: { default: "min(580px, 100%)", "@media (max-width: 760px)": "100%" },
    maxWidth: "100%",
    backgroundColor: "var(--card)",
    zIndex: 21,
    boxShadow: "-8px 0 30px rgba(0,0,0,0.18)",
    transform: "translateX(105%)",
    transition: "transform 0.2s ease",
    overflow: "auto",
    overscrollBehavior: { default: "auto", "@media (max-width: 760px)": "contain" },
  },
  panelOpen: { transform: "none" },
  panelHeader: {
    paddingTop: { default: "18px", "@media (max-width: 760px)": "12px" },
    paddingBottom: { default: "18px", "@media (max-width: 760px)": "12px" },
    paddingLeft: { default: "22px", "@media (max-width: 760px)": "14px" },
    paddingRight: { default: "22px", "@media (max-width: 760px)": "14px" },
    borderBottom: "1px solid var(--line)",
    display: "flex",
    justifyContent: "space-between",
    alignItems: "flex-start",
    gap: "10px",
    position: "sticky",
    top: 0,
    backgroundColor: "var(--card)",
    zIndex: 2,
  },
  panelTitle: { margin: 0, fontSize: "18px" },
  panelClose: {
    border: 0,
    backgroundColor: "var(--neutral-bg)",
    width: "32px",
    height: "32px",
    fontSize: "18px",
  },
  panelBody: {
    paddingTop: { default: "16px", "@media (max-width: 760px)": "12px" },
    paddingBottom: { default: "30px", "@media (max-width: 760px)": "28px" },
    paddingLeft: { default: "22px", "@media (max-width: 760px)": "14px" },
    paddingRight: { default: "22px", "@media (max-width: 760px)": "14px" },
  },
});
type Selection = { primaryId: string; addonId: string };
interface PortalTargets {
  notes: HTMLElement;
  banners: HTMLElement;
  legend: HTMLElement;
}
function initialResult(): ResultSet | null {
  // A query-string guest request never exposes a pre-rendered personal snapshot.
  if (GUEST_MODE) return null;
  return isResultSet(window.__SNAPSHOT__) ? window.__SNAPSHOT__ : null;
}
function defaultTab(data: ResultSet | null): ResultsTab {
  if (!data) return "grid";
  if (data.vendors.length && !data.itineraries.length) return "vendors";
  return data.cells.length ? "grid" : "itineraries";
}
function noteKey(resultId: string, market: string, vendor: string): string {
  return "cf:" + resultId + ":" + market + ":" + vendor;
}
function hydratedResult(data: ResultSet): ResultSet {
  return {
    ...data,
    vendors: data.vendors.map((vendor) => {
      try {
        const key = noteKey(data.id, vendor.market, vendor.vendor);
        const note = localStorage.getItem(key + ":note");
        const trust = localStorage.getItem(key + ":trust");
        const labels: Record<string, string> = {
          airline: "Airline",
          trusted: "Trusted OTA",
          caution: "OTA · caution",
          risky: "Risky OTA",
        };
        return {
          ...vendor,
          note: note ?? vendor.note,
          trust: trust || vendor.trust,
          trustLabel: trust && labels[trust] ? labels[trust] : vendor.trustLabel,
        };
      } catch {
        // Standalone file snapshots may not provide Web Storage.
        return vendor;
      }
    }),
  };
}
function responseError(response: unknown, fallback: string): string {
  return isRecord(response) && typeof response.error === "string" ? response.error : fallback;
}
function sortVisible(rows: Itinerary[], result: ResultSet, sort: "date" | "price"): Itinerary[] {
  const sorted = [...rows];
  if (sort === "price") {
    if (result.addons.length && result.cells.length) {
      const lookup = new Map(
        result.cells.map((cell) => [cell.primaryId + "\0" + (cell.addonId ?? ""), cell]),
      );
      const cheapest = (it: Itinerary) => {
        let best = Infinity;
        for (const addon of result.addons) {
          const cell = lookup.get(it.id + "\0" + addon.id);
          if (cell && cell.status !== "no") best = Math.min(best, cell.total);
        }
        return best;
      };
      sorted.sort(
        (a, b) => cheapest(a) - cheapest(b) || a.outboundDate.localeCompare(b.outboundDate),
      );
    } else sorted.sort((a, b) => (a.pricePerPerson ?? Infinity) - (b.pricePerPerson ?? Infinity));
  } else
    sorted.sort(
      (a, b) =>
        a.outboundDate.localeCompare(b.outboundDate) ||
        (a.returnDate ?? "").localeCompare(b.returnDate ?? "") ||
        (a.pricePerPerson ?? Infinity) - (b.pricePerPerson ?? Infinity),
    );
  return sorted;
}
function sortedVendors(result: ResultSet, market: string, sort: FilterControlsState["vsort"]) {
  const rows = result.vendors.filter((vendor) => market === "all" || vendor.market === market);
  return rows.sort((a, b) => {
    const pa = a.allInGbp ?? Infinity,
      pb = b.allInGbp ?? Infinity;
    if (sort === "vendor") return a.vendor.localeCompare(b.vendor);
    if (sort === "market") return a.market.localeCompare(b.market) || pa - pb;
    return pa - pb;
  });
}

export function App() {
  const snapshot = useMemo(initialResult, []);
  const [result, setResult] = useState<ResultSet | null>(() =>
    snapshot ? hydratedResult(snapshot) : null,
  );
  const [filter, setFilter] = useState<FilterControlsState>(() =>
    snapshot ? suggestedFilters(snapshot.suggestedFilters) : initialFilters(),
  );
  const [form, setForm] = useState<SearchForm>({ ...INITIAL_SEARCH });
  const [tab, setTab] = useState<ResultsTab>(() => defaultTab(snapshot));
  const [theme, setTheme] = useState<"dark" | "light">(() =>
    document.documentElement.dataset.theme === "light" ? "light" : "dark",
  );
  const [selection, setSelection] = useState<Selection | null>(null);
  const [saved, setSaved] = useState<SavedInvestigation[]>([]);
  const [selectedSaved, setSelectedSaved] = useState("");
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  const [estimateError, setEstimateError] = useState("");
  const [statusLines, setStatusLines] = useState<string[]>([]);
  const [pendingQuery, setPendingQuery] = useState<SearchQuery | null>(null);
  const [searchConsent, setSearchConsent] = useState<string | null>(null);
  const [hostedTracking, setHostedTracking] = useState(false);
  const [hostedExecutionEnabled, setHostedExecutionEnabled] = useState(false);
  const [hostedExports, setHostedExports] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [hostedQuote, setHostedQuote] = useState<HostedSearchQuote | null>(null);
  const [searching, setSearching] = useState(false);
  const searchLock = useRef(false);
  const estimateEpoch = useRef(0);
  const [portals, setPortals] = useState<PortalTargets | null>(null);
  const closeButton = useRef<HTMLButtonElement | null>(null);
  const formerFocus = useRef<HTMLElement | null>(null);

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    setTheme(next);
    try {
      localStorage.setItem("clawfare-theme", next);
    } catch {
      // Local storage is optional (e.g. browser privacy settings / file previews).
    }
  };

  const acceptResult = useCallback((data: ResultSet) => {
    setResult(hydratedResult(data));
    setFilter(suggestedFilters(data.suggestedFilters));
    setTab(defaultTab(data));
    setSelection(null);
  }, []);
  const refreshSaved = useCallback(async () => {
    const response = await client.searches();
    if (response.ok) setSaved(response.data);
  }, []);

  useEffect(() => {
    const notes = document.getElementById("notes");
    const banners = document.getElementById("banners");
    const legend = document.getElementById("legend");
    if (notes && banners && legend) setPortals({ notes, banners, legend });
  }, []);
  useEffect(() => {
    if (snapshot) return;
    let active = true;
    void client
      .status()
      .then((response) => {
        if (!active || !response.ok) return;
        setHostedTracking(HOSTED_READ_ONLY && !GUEST_MODE && response.data.hostedTracking === true);
        setHostedExecutionEnabled(
          !GUEST_MODE &&
            HOSTED_ESTIMATE_ONLY &&
            (response.data.hostedSearch?.canExecuteOneWayGbpSerpApi === true ||
              response.data.hostedSearch?.canExecuteRoundTripGbpSerpApi === true),
        );
        setHostedExports(response.data.hostedExports === true);
        setStatusLines(
          response.data.providers.map(
            (quota) =>
              quota.provider +
              ": " +
              (quota.note || (quota.configured ? "configured" : "not configured")),
          ),
        );
      })
      .catch(() => {});
    void client
      .sample()
      .then((response) => {
        if (!active) return;
        if (response.ok) acceptResult(response.data);
        else setEstimateError(response.error);
      })
      .catch(() => {
        if (active) setEstimateError("Sample failed to load.");
      })
      .finally(() => {
        if (active) void refreshSaved().catch(() => {});
      });
    return () => {
      active = false;
    };
  }, [snapshot, acceptResult, refreshSaved]);
  useEffect(() => {
    if (selection) {
      closeButton.current?.focus();
    } else if (formerFocus.current) {
      formerFocus.current.focus();
      formerFocus.current = null;
    }
  }, [selection]);
  useEffect(() => {
    const onEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSelection(null);
    };
    document.addEventListener("keydown", onEscape);
    return () => document.removeEventListener("keydown", onEscape);
  }, []);

  const currentFilter = useMemo(() => toFilterSpec(filter), [filter]);
  const visibleRows = useMemo(
    () =>
      result ? visibleItineraries(result, currentFilter, filter.hide, isCashRefundVerified) : [],
    [result, currentFilter, filter.hide],
  );
  const displayRows = useMemo(
    () => (result ? sortVisible(visibleRows, result, filter.sort) : []),
    [visibleRows, result, filter.sort],
  );
  const openDetails = useCallback((primaryId: string, addonId: string) => {
    formerFocus.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelection({ primaryId, addonId });
  }, []);
  const activeItinerary =
    selection && result
      ? (result.itineraries.find((item) => item.id === selection.primaryId) ?? null)
      : null;
  const activeAddon =
    selection?.addonId !== "LH" && result
      ? (result.addons.find((item) => item.id === selection?.addonId) ?? null)
      : null;
  const activeCell: ComboCell | null =
    selection && result
      ? (result.cells.find(
          (item) =>
            item.primaryId === selection.primaryId &&
            (item.addonId ?? "") === (activeAddon?.id ?? ""),
        ) ??
        result.cells.find((item) => item.primaryId === selection.primaryId && !item.addonId) ??
        null)
      : null;
  const selectedKey = selection ? selection.primaryId + "\0" + (activeAddon?.id ?? "") : null;

  const changeForm = (next: SearchForm) => {
    estimateEpoch.current++;
    setForm(next);
    setHostedQuote(null);
    // Consent must correspond to the exact previously estimated query.
    setPendingQuery(null);
    setSearchConsent(null);
    setEstimate(null);
    setEstimateError("");
  };
  const estimateCalls = async () => {
    const epoch = ++estimateEpoch.current;
    setHostedQuote(null);
    setPendingQuery(null);
    setSearchConsent(null);
    setEstimate(null);
    setEstimateError("");
    try {
      // Parse the entire hard search policy before any server request.
      // No malformed input reaches Kotlin's normalizer or a paid consent path.
      const query = buildSearchQuery(form);
      const response = await client.estimate(query);
      if (epoch !== estimateEpoch.current) return;
      if (!response.ok) {
        setEstimateError(response.error);
        return;
      }
      setEstimate(response.data);
      if (HOSTED_READ_ONLY) {
        if (hostedExecutionEnabled) {
          const prepared = await client.prepareHostedSearch(query);
          if (epoch !== estimateEpoch.current) return;
          if (prepared.ok) setHostedQuote(prepared.data);
          else setEstimateError(prepared.error);
        }
        return;
      }
      const canRun =
        !HOSTED_READ_ONLY &&
        estimateIsRunnable(
          response.data.warnings,
          response.data.liveCalls,
          response.data.cachedCalls,
          response.data.returnLookupsUpperBound,
          query.cacheOnly,
        );
      if (canRun) {
        setPendingQuery(query);
        setSearchConsent(response.consent);
      }
    } catch (error) {
      if (epoch !== estimateEpoch.current) return;
      setEstimateError(
        error instanceof Error ? error.message : "Estimate request failed; no search was run.",
      );
    }
  };
  const runSearch = async () => {
    if (HOSTED_READ_ONLY) {
      const quote = hostedQuote;
      if (!quote || !hostedExecutionEnabled || searchLock.current) return;
      if (
        !Number.isFinite(Date.parse(quote.expiresAt)) ||
        Date.parse(quote.expiresAt) <= Date.now()
      ) {
        setHostedQuote(null);
        setEstimateError("The one-use approval expired. Estimate again.");
        return;
      }
      const confirmed = window.confirm(
        "Run the exact " +
          (quote.canonicalQuery.tripType === "round_trip" ? "round-trip" : "one-way") +
          " SerpApi search approved by the server?\n\n" +
          "Up to " +
          quote.totalCallsUpperBound +
          " provider requests may consume paid quota.\n" +
          "Base requests: " +
          quote.baseCallsUpperBound +
          ", return lookups: " +
          quote.returnLookupsUpperBound +
          ".\n" +
          quote.cost.message +
          "\n\nThis confirmation is single-use and cannot be retried.",
      );
      if (!confirmed) return;
      searchLock.current = true;
      setSearching(true);
      setHostedQuote(null);
      setEstimateError("");
      try {
        const response = await client.executeHostedSearch(quote);
        if (response.ok) {
          acceptResult(response.data.result);
          setEstimateError(
            response.data.ok
              ? "Real search saved as " + response.data.result.id + "."
              : "Incomplete provider search saved as " + response.data.result.id + ".",
          );
          void refreshSaved().catch(() => {});
        } else setEstimateError(response.error);
      } catch {
        setEstimateError(
          "Search status is uncertain. Check saved investigations; do not blindly retry paid calls.",
        );
      } finally {
        searchLock.current = false;
        setSearching(false);
      }
      return;
    }
    if (!pendingQuery || searchLock.current) return;
    const query: SearchQuery = { ...pendingQuery, confirmed: true };
    setPendingQuery(null);
    try {
      const response = await client.saveSearch(query, searchConsent);
      const body = response.body;
      if (isRecord(body) && isResultSet(body.result)) {
        acceptResult(body.result);
        setEstimateError("Search saved as " + body.result.id + ".");
        void refreshSaved().catch(() => {});
      } else if (isRecord(body) && body.needsConfirmation === true && isEstimate(body.estimate)) {
        setEstimate(body.estimate);
        setPendingQuery(query);
        setSearchConsent(response.consent);
      } else {
        setEstimateError(responseError(body, "Search failed."));
      }
    } catch {
      setEstimateError("Search failed.");
    }
  };
  const loadSample = async () => {
    try {
      const response = await client.sample();
      if (response.ok) acceptResult(response.data);
      else setEstimateError(response.error);
      void refreshSaved().catch(() => {});
    } catch {
      setEstimateError("Sample failed to load.");
    }
  };
  const openSaved = async (id: string) => {
    if (!id) return;
    try {
      const response = await client.request(`/api/results/${encodeURIComponent(id)}`);
      if (isResultSet(response.body)) acceptResult(response.body);
      else setEstimateError(responseError(response.body, "Investigation not found."));
    } catch {
      setEstimateError("Investigation could not be loaded.");
    }
  };
  const checkQuota = async () => {
    setStatusLines(["Checking quota…"]);
    try {
      const response = await client.quota();
      if (!response.ok) {
        setStatusLines([response.error]);
        return;
      }
      setStatusLines(
        response.data.map((quota: QuotaView) => {
          const rem =
            quota.remaining == null
              ? ""
              : " · " +
                quota.remaining +
                " left" +
                (quota.limit != null ? " of " + quota.limit : "");
          return (
            quota.provider +
            (quota.configured ? " configured" : " not configured") +
            rem +
            (quota.note ? " — " + quota.note : "")
          );
        }),
      );
    } catch {
      setStatusLines(["Quota check failed."]);
    }
  };
  const checkStorage = async () => {
    setStatusLines(["Checking D1 public sample record…"]);
    try {
      const response = await client.storage();
      if (!response.ok) {
        setStatusLines([response.error]);
        return;
      }
      const info = response.data;
      setStatusLines([
        info.sampleStored
          ? "D1 sample stored since " +
            info.firstImportedAt +
            " · verified SHA-256 " +
            String(info.sha256 ?? "").slice(0, 16) +
            "… (compare this timestamp after reloading)."
          : info.sampleCheckFailed
            ? "The D1 sample could not be read or verified."
            : "No sample row in D1 yet. Load the public Florida sample, then check again.",
        ...(info.build
          ? [
              `Deployed source ${info.build.sourceGitSha}${info.build.sourceDirty ? " (working tree modified at build)" : ""}`,
              info.build.schema === "tables_present"
                ? "D1 contains the tables expected by this build."
                : info.build.schema === "unavailable"
                  ? "D1 schema inspection was unavailable."
                  : "D1 is missing migration tables from " +
                    info.build.missingSchema.map((migration) => migration.file).join(", ") +
                    ".",
            ]
          : []),
      ]);
    } catch {
      setStatusLines(["D1 storage check could not be completed."]);
    }
  };
  const updateVendor = (
    market: string,
    vendor: string,
    change: { note?: string; trust?: string },
  ) => {
    if (!result) return;
    const key = noteKey(result.id, market, vendor);
    try {
      if (change.note !== undefined) localStorage.setItem(key + ":note", change.note);
      if (change.trust !== undefined) localStorage.setItem(key + ":trust", change.trust);
    } catch {
      // Local file snapshots can operate without Web Storage.
    }
    const labels: Record<string, string> = {
      airline: "Airline",
      trusted: "Trusted OTA",
      caution: "OTA · caution",
      risky: "Risky OTA",
    };
    setResult((current) =>
      current
        ? {
            ...current,
            vendors: current.vendors.map((v) =>
              v.market === market && v.vendor === vendor
                ? {
                    ...v,
                    ...change,
                    trustLabel: change.trust
                      ? (labels[change.trust] ?? v.trustLabel)
                      : v.trustLabel,
                  }
                : v,
            ),
          }
        : null,
    );
  };
  const showVendors = async (it: Itinerary) => {
    if (!result) return;
    if (GUEST_MODE || result.scenarioLabel === "florida" || result.id === "florida-dec-2026") {
      setSelection(null);
      setTab("vendors");
      return;
    }
    const vendorRequest = {
      itineraryId: it.id,
      markets: ["uk", "us"],
      cacheOnly: form.cacheOnly,
      confirmed: false,
    };
    try {
      let response = await client.vendors(result.id, vendorRequest);
      const body = response.body;
      if (isRecord(body) && body.needsConfirmation === true) {
        const estimate =
          isRecord(body.estimate) && Array.isArray(body.estimate.lines)
            ? body.estimate.lines.filter((line): line is string => typeof line === "string")
            : [];
        if (!window.confirm(estimate.join("\n") + "\n\nFetch seller offers now?")) return;
        response = await client.vendors(
          result.id,
          { ...vendorRequest, confirmed: true },
          response.consent,
        );
      }
      const updated = response.body;
      if (isRecord(updated) && isResultSet(updated.result)) {
        acceptResult(updated.result);
        setTab("vendors");
      } else window.alert(responseError(updated, "Seller comparison failed."));
    } catch {
      window.alert("Seller comparison failed.");
    }
  };
  const fareSaved = (id: string) => {
    setSelectedSaved(id);
    void refreshSaved().catch(() => {});
  };
  const canExport =
    !HOSTED_READ_ONLY || Boolean(hostedExports && result?.query && !result.scenarioLabel);
  const download = async (kind: "html" | "csv") => {
    if (!canExport || exporting || !result) return;
    if (HOSTED_READ_ONLY) {
      setExporting(true);
      setEstimateError("");
      try {
        await client.downloadSaved(result.id, kind, {
          itineraryIds: displayRows.map((row) => row.id),
          kind: tab === "vendors" ? "seller_options" : "itineraries",
        });
      } catch (error) {
        setEstimateError(
          error instanceof Error ? error.message : "Could not download the saved export.",
        );
      } finally {
        setExporting(false);
      }
      return;
    }
    const path = kind === "html" ? "/api/export/html" : "/api/export/csv";
    const exportKind = kind === "csv" && tab === "vendors" ? "vendors" : "itineraries";
    try {
      await client.download(
        path,
        { result, filters: currentFilter, kind: exportKind },
        kind === "html" ? "clawfare.html" : "clawfare.csv",
      );
    } catch {
      setEstimateError("Export rejected by local access controls.");
    }
  };
  const vendorMode = result && tab === "vendors" && result.scenarioLabel === "florida";
  const title = result
    ? vendorMode
      ? "Tampa ⇄ Mexico City nonstop · Fri 25 Dec → Tue 29 Dec 2026 · 1 adult · 1 checked bag each way"
      : result.title || "Clawfare"
    : "Clawfare flight search";
  const subtitle = result
    ? vendorMode
      ? "Out AM 1741 TPA 09:23 → MEX 11:50 · Back AM 1740 MEX 15:40 → TPA 19:49. All-in prices include one checked bag. Round trip beside two one-ways. Sample seller check, not a new lookup."
      : result.subtitle
    : "Compare flights across your chosen airports, dates and markets. Provider credentials stay on the server.";
  const renderView = () => {
    if (!result)
      return (
        <div className="empty">
          Enter airports and dates to estimate a search, or load the public flight sample. Searches
          with billable providers require explicit approval before any request.
        </div>
      );
    if (tab === "grid" && result.addons.length > 0 && result.cells.length > 0)
      return (
        <GridView
          rows={displayRows}
          addons={result.addons}
          cells={result.cells}
          sort={filter.sort}
          hideInfeasible={filter.hide}
          selectedKey={selectedKey}
          onSelect={openDetails}
          legacySample={result.scenarioLabel === "florida"}
        />
      );
    if (tab === "grid" || tab === "itineraries")
      return (
        <ItineraryView
          rows={displayRows}
          onSelect={openDetails}
          emptyMessage={result.coverage ? itineraryEmptyMessage(result) : undefined}
        />
      );
    if (tab === "matrix") {
      const rows = displayRows.filter(
        (it) => quoteable(it) && (!result.coverage || hasComparableGbpFare(it, result)),
      );
      return (
        <MatrixView
          rows={rows}
          onSelect={openDetails}
          emptyMessage={
            result.coverage
              ? !displayRows.length
                ? itineraryEmptyMessage(result)
                : "No comparable fresh GBP fares for these dates."
              : undefined
          }
        />
      );
    }
    const markets = Object.fromEntries(
      result.vendors.map((v) => [v.market, v.marketLabel || v.market]),
    );
    return (
      <VendorView
        rows={sortedVendors(result, filter.market, filter.vsort)}
        markets={markets}
        market={filter.market}
        options={result.sellerOptions.filter(
          (option) => filter.market === "all" || option.market === filter.market,
        )}
        onMarketChange={(market) => setFilter((f) => ({ ...f, market }))}
        onNoteChange={(v, note) => updateVendor(v.market, v.vendor, { note })}
        onTrustChange={(v, trust) => updateVendor(v.market, v.vendor, { trust })}
      />
    );
  };

  const overlayProps = stylex.props(styles.overlay, activeItinerary && styles.overlayOpen);
  const panelProps = stylex.props(styles.panel, activeItinerary && styles.panelOpen);
  return (
    <>
      <header {...stylex.props(styles.header)}>
        <div className="heading-row">
          <h1 id="title" {...stylex.props(styles.heading)}>
            {title}
          </h1>
          <button
            id="themeToggle"
            className="theme-toggle"
            type="button"
            aria-label="Dark mode"
            aria-pressed={theme === "dark"}
            onClick={toggleTheme}
          >
            {theme === "dark" ? "☾ Dark" : "☀ Light"}
          </button>
        </div>
        <p id="subtitle" {...stylex.props(styles.subtitle)}>
          {subtitle}
        </p>
        {GUEST_MODE && (
          <p id="guestMode" role="status" {...stylex.props(styles.subtitle)}>
            Guest mode — illustrative flights, no account or credentials. No API requests, D1
            access, provider searches or bookings.
          </p>
        )}
        <div className={"notes " + stylex.props(styles.notes).className} id="notes" />
      </header>
      <main {...stylex.props(styles.main)}>
        <SearchControls
          form={form}
          onChange={changeForm}
          saved={saved}
          selectedSaved={selectedSaved}
          onSavedChange={setSelectedSaved}
          onOpen={(id) => void openSaved(id)}
          onEstimate={() => void estimateCalls()}
          onSearch={() => void runSearch()}
          onSample={() => void loadSample()}
          onQuota={() => void checkQuota()}
          onStorage={() => void checkStorage()}
          runEnabled={pendingQuery !== null || hostedQuote !== null}
          hostedReadOnly={HOSTED_READ_ONLY}
          hostedExecutionAvailable={hostedExecutionEnabled}
          isSearching={searching}
          hostedEstimateOnly={HOSTED_ESTIMATE_ONLY}
          guest={GUEST_MODE}
          estimate={estimate}
          estimateError={
            estimateError ||
            (GUEST_MODE
              ? "Guest mode uses synthetic sample flights. Search estimates, live searches and exports are disabled."
              : HOSTED_READ_ONLY && hostedExecutionEnabled
                ? "GBP SerpApi searches can run after explicit confirmation; provider requests may consume quota."
                : HOSTED_READ_ONLY
                  ? HOSTED_ESTIMATE_ONLY
                    ? "Search estimates are available at no cost. Run search and all paid provider calls are still disabled."
                    : "Hosted read-only preview: explore saved flights and filters. Live searches are disabled."
                  : "")
          }
          statusLines={statusLines}
        />
        <div id="banners" />
        <FilterControls state={filter} onChange={setFilter} result={result} />
        <div className={"summary " + stylex.props(styles.summary).className} id="summary">
          {result && portals && (
            <SnapshotOverview
              result={result}
              rows={visibleRows}
              tab={tab}
              notesTarget={portals.notes}
              bannersTarget={portals.banners}
              legendTarget={portals.legend}
              onSelect={openDetails}
            />
          )}
        </div>
        <div className={"legend " + stylex.props(styles.legend).className} id="legend" />
        <div className={"tabs " + stylex.props(styles.tabs).className} id="tabs">
          {(["grid", "itineraries", "matrix", "vendors"] as const).map((choice) => (
            <button
              type="button"
              key={choice}
              data-tab={choice}
              className={
                (choice === tab ? "on " : "") +
                (choice === tab ? stylex.props(styles.tabSelected).className : "")
              }
              aria-pressed={choice === tab}
              onClick={() => setTab(choice)}
            >
              {choice === "grid"
                ? "Grid"
                : choice === "itineraries"
                  ? "Itineraries"
                  : choice === "matrix"
                    ? "Matrix"
                    : "Vendors"}
            </button>
          ))}
        </div>
        <div className="actions" id="exportBar">
          <button
            type="button"
            id="btnHtml"
            disabled={!canExport || exporting}
            onClick={() => void download("html")}
          >
            Export HTML
          </button>
          <button
            type="button"
            id="btnCsv"
            disabled={!canExport || exporting}
            onClick={() => void download("csv")}
          >
            Export CSV
          </button>
          <span className="muted" id="fresh">
            {result?.fetchedAt ? "Data " + result.fetchedAt : ""}
          </span>
        </div>
        <div
          className={
            "wrap " +
            (tab === "grid" && result?.addons.length && result.cells.length
              ? "mobile-grid-view"
              : "")
          }
          id="view"
          style={stylex.props(styles.results).style}
        >
          {renderView()}
        </div>
      </main>
      <footer {...stylex.props(styles.footer)} id="foot">
        Fares move. Nothing here is a booking. Re-check the airline before paying. Google Flights
        links open for one adult — set the passenger count on that page.
      </footer>
      <div
        id="overlay"
        {...overlayProps}
        className={(activeItinerary ? "open " : "") + overlayProps.className}
        onClick={() => setSelection(null)}
      />
      <aside
        id="panel"
        {...panelProps}
        className={(activeItinerary ? "open " : "") + panelProps.className}
        aria-hidden={!activeItinerary}
        aria-label="Itinerary details"
      >
        <div className={"ph " + stylex.props(styles.panelHeader).className}>
          <div>
            <h2 id="pTitle" {...stylex.props(styles.panelTitle)}>
              {activeItinerary
                ? activeItinerary.origin +
                  " → " +
                  activeItinerary.destination +
                  " " +
                  fmtRange(activeItinerary.outboundDate, activeItinerary.returnDate) +
                  (activeAddon ? " + " + activeAddon.id : "")
                : "Details"}
            </h2>
            <div id="pStatus">
              {activeCell && (
                <span className={"status s-" + activeCell.status}>
                  {activeCell.status === "ok"
                    ? "Works"
                    : activeCell.status === "catch"
                      ? "Works with a catch"
                      : "Doesn’t work"}
                </span>
              )}
            </div>
          </div>
          <button
            className={"close " + stylex.props(styles.panelClose).className}
            id="pClose"
            type="button"
            aria-label="Close"
            ref={closeButton}
            onClick={() => setSelection(null)}
          >
            ×
          </button>
        </div>
        <div className={"pb " + stylex.props(styles.panelBody).className} id="pBody">
          {activeItinerary && (
            <DetailsContent
              it={activeItinerary}
              addon={activeAddon}
              cell={activeCell}
              onVendors={() => void showVendors(activeItinerary)}
              tracking={
                result &&
                (!HOSTED_READ_ONLY || (hostedTracking && result.query && !result.scenarioLabel)) ? (
                  <FareTracking
                    key={result.id + "/" + activeItinerary.id}
                    client={client}
                    result={result}
                    fare={activeItinerary}
                    hosted={HOSTED_READ_ONLY}
                    canTrack={
                      !HOSTED_READ_ONLY ||
                      (!result.id.startsWith("tracked-") &&
                        !activeItinerary.returnMissing &&
                        quoteable(activeItinerary))
                    }
                    onSaved={fareSaved}
                  />
                ) : undefined
              }
              coveredSearch={result?.coverage != null}
              legacySample={result?.scenarioLabel === "florida"}
            />
          )}
        </div>
      </aside>
    </>
  );
}
