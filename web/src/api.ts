import type {
  Estimate,
  HistoryObservation,
  ExportRequest,
  QuotaView,
  ResultSet,
  SavedInvestigation,
  SavedExportRequest,
  SearchQuery,
  StatusResponse,
  StorageDiagnostic,
  VendorRequest,
  TrackRequest,
  TrackResponse,
} from "./domain.ts";
import { isEstimate, isQuotaList, isRecord, isResultSet, isSavedList } from "./domain.ts";
import { GUEST_SAMPLE } from "./guest-sample.ts";
import {
  createGeneratedClient,
  type ApiRoute,
  type ExportRoute,
} from "./generated/openapi-client.ts";

/**
 * Hosted Sites auth is handled by the trusted ingress. In hosted mode we must
 * never fetch a JVM session token or send a user-supplied identity header.
 * This client cannot authorize paid provider requests.
 */
export interface ApiMode {
  readonly hostedReadOnly: boolean;
  readonly hostedEstimateOnly: boolean;
  /** Browser-only fixture mode: never authenticates, reads D1 or sends HTTP requests. */
  readonly guest?: boolean;
}
export type { ApiRoute, ExportRoute } from "./generated/openapi-client.ts";
export interface ApiResponse<T> {
  readonly status: number;
  readonly body: T;
  readonly text: string;
  readonly consent: string | null;
}
export type Parsed<T> =
  | {
      readonly ok: true;
      readonly data: T;
      readonly status: number;
      readonly consent: string | null;
    }
  | { readonly ok: false; readonly error: string; readonly status: number };
export type HttpClient = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

function allowedRoute(path: string): boolean {
  return (
    /^\/api\/(?:sample|status|searches|quota|storage|estimate|prepare-search|search)$/.test(path) ||
    /^\/api\/history\/[a-z0-9][a-z0-9_-]{0,99}\/[A-Za-z0-9._:%-]{1,384}(?:\?limit=[1-9][0-9]?)?$/.test(
      path,
    ) ||
    /^\/api\/results\/[a-z0-9][a-z0-9_-]{0,99}(?:\/(?:vendors|track))?$/.test(path)
  );
}
function serverError(value: unknown, status: number): string {
  if (isRecord(value) && typeof value.error === "string") return value.error;
  return `Server returned HTTP ${status}.`;
}

/** The result is only typed after an explicit structural check. */
function decode<T>(res: ApiResponse<unknown>, guard: (value: unknown) => value is T): Parsed<T> {
  if (res.status !== 200)
    return { ok: false, error: serverError(res.body, res.status), status: res.status };
  if (!guard(res.body))
    return { ok: false, error: "Unexpected API response shape.", status: res.status };
  return { ok: true, data: res.body, status: res.status, consent: res.consent };
}

/** Only the authenticated server can advertise this narrow hosted capability. */
export interface HostedStatus extends StatusResponse {
  readonly hostedTracking?: boolean;
  readonly hostedExports?: boolean;
  readonly hostedSearch?: {
    readonly canExecuteOneWayGbpSerpApi: boolean;
    readonly canExecuteRoundTripGbpSerpApi?: boolean;
  };
}
export interface HostedSearchQuote {
  readonly canonicalQuery: SearchQuery;
  readonly consentToken: string;
  readonly expiresAt: string;
  readonly totalCallsUpperBound: number;
  readonly baseCallsUpperBound: number;
  readonly returnLookupsUpperBound: number;
  readonly canExecuteHere: boolean;
  readonly cost: { readonly vendorBillingKnown: boolean; readonly message: string };
}
export interface HostedSearchExecution {
  readonly ok: boolean;
  readonly result: ResultSet;
  readonly providerObservations: readonly unknown[];
  readonly error?: string;
}

export interface ClawfareClient {
  request(
    path: ApiRoute | "/api/prepare-search",
    body?: unknown,
    consent?: string | null,
  ): Promise<ApiResponse<unknown>>;
  sample(): Promise<Parsed<ResultSet>>;
  searches(): Promise<Parsed<SavedInvestigation[]>>;
  status(): Promise<Parsed<HostedStatus>>;
  prepareHostedSearch(query: SearchQuery): Promise<Parsed<HostedSearchQuote>>;
  executeHostedSearch(quote: HostedSearchQuote): Promise<Parsed<HostedSearchExecution>>;
  quota(): Promise<Parsed<QuotaView[]>>;
  storage(): Promise<Parsed<StorageDiagnostic>>;
  estimate(query: SearchQuery): Promise<Parsed<Estimate>>;
  saveSearch(query: SearchQuery, consent: string | null): Promise<ApiResponse<unknown>>;
  vendors(
    id: string,
    request: VendorRequest,
    consent?: string | null,
  ): Promise<ApiResponse<unknown>>;
  track(id: string, request: TrackRequest): Promise<ApiResponse<unknown>>;
  trackHosted(id: string, request: TrackRequest): Promise<Parsed<TrackResponse>>;
  history(id: string, itineraryId: string): Promise<Parsed<HistoryObservation[]>>;
  download(path: ExportRoute, request: ExportRequest, fallback: string): Promise<void>;
  downloadSaved(id: string, format: "html" | "csv", request: SavedExportRequest): Promise<void>;
}
export function createClawfareClient(mode: ApiMode, fetcher: HttpClient = fetch): ClawfareClient {
  const guest = mode.guest === true;
  const hosted = mode.hostedReadOnly || guest;
  const allowEstimate = hosted && !guest && mode.hostedEstimateOnly;

  // Never consult the network, cookies, credentials or local-session endpoint
  // in guest mode. These are only in-memory, public fixture responses.
  function guestResponse(
    path: ApiRoute | "/api/prepare-search",
    body?: unknown,
  ): ApiResponse<unknown> {
    const response = (status: number, value: unknown): ApiResponse<unknown> => ({
      status,
      body: value,
      text: JSON.stringify(value),
      consent: null,
    });
    if (body !== undefined)
      return response(405, {
        error: "Guest mode: all API mutations and provider estimates are disabled.",
      });
    if (path === "/api/sample" || path === "/api/results/" + GUEST_SAMPLE.id)
      return response(200, GUEST_SAMPLE);
    if (path === "/api/searches")
      return response(200, [
        {
          id: GUEST_SAMPLE.id,
          title: GUEST_SAMPLE.title,
          subtitle: "Built-in guest fixture",
        },
      ]);
    if (path === "/api/status")
      return response(200, { cacheDir: "none", searchesDir: "memory", providers: [] });
    if (path === "/api/quota") return response(200, []);
    return response(404, { error: "Guest mode does not access hosted or local storage." });
  }
  let session: Promise<string | null> | null = null;

  function localSession(): Promise<string | null> {
    if (hosted) return Promise.resolve(null);
    if (!session) {
      session = fetcher("/api/session", { cache: "no-store" }).then(async (response) => {
        if (!response.ok) throw new Error("Local session unavailable");
        const value: unknown = await response.json();
        if (!isRecord(value) || typeof value.token !== "string" || !value.token) {
          throw new Error("Local session unavailable");
        }
        return value.token;
      });
    }
    return session;
  }
  async function request(
    path: ApiRoute | "/api/prepare-search",
    body?: unknown,
    consent?: string | null,
    hostedAction?: "search" | "track",
  ): Promise<ApiResponse<unknown>> {
    // Defense in depth: only same-origin, explicitly known API routes.
    if (!allowedRoute(path)) throw new Error("Unsupported Clawfare API route.");
    if (guest) return guestResponse(path, body);
    const hostedAllowedPost =
      (allowEstimate &&
        (path === "/api/estimate" ||
          (hostedAction === "search" &&
            (path === "/api/prepare-search" || path === "/api/search")))) ||
      (hostedAction === "track" && path.endsWith("/track"));
    if (hosted && body !== undefined && !hostedAllowedPost) {
      return {
        status: 405,
        body: { error: "Paid search and mutations are disabled in hosted mode." },
        text: "",
        consent: null,
      };
    }
    const token = await localSession();
    const headers: Record<string, string> = { Accept: "application/json" };
    if (token) headers["X-Clawfare-Session"] = token;
    const opts: RequestInit = { headers };
    if (body !== undefined) {
      opts.method = "POST";
      headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    if (consent && !hosted) headers["X-Clawfare-Consent"] = consent;
    const response = await fetcher(path, opts);
    const text = await response.text();
    let parsed: unknown = {};
    try {
      parsed = text ? (JSON.parse(text) as unknown) : {};
    } catch {
      parsed = { error: text };
    }
    return {
      status: response.status,
      body: parsed,
      text,
      consent: response.headers.get("X-Clawfare-Consent"),
    };
  }
  async function download(path: ExportRoute, body: ExportRequest, fallback: string): Promise<void> {
    if (guest) throw new Error("Guest exports are disabled");
    if (hosted) throw new Error("Hosted exports are disabled");
    const token = await localSession();
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (token) headers["X-Clawfare-Session"] = token;
    const response = await fetcher(path, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error("Export rejected by local access controls");
    await saveAttachment(response, fallback);
  }
  async function saveAttachment(response: Response, fallback: string) {
    const match = /filename="([A-Za-z0-9._-]+)"/.exec(
      response.headers.get("Content-Disposition") ?? "",
    );
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = match?.[1] ?? fallback;
    a.click();
    // Leave time for the browser to process the click before revoking the URL.
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function downloadSaved(id: string, format: "html" | "csv", body: SavedExportRequest) {
    if (!hosted || guest || !/^[a-z0-9][a-z0-9_-]{0,99}$/.test(id)) {
      throw new Error("Hosted saved exports are unavailable.");
    }
    const response = await fetcher(`/api/results/${id}/export.${format}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const detail: unknown = await response.json().catch(() => null);
      throw new Error(serverError(detail, response.status));
    }
    const expectedType = format === "html" ? "text/html" : "text/csv";
    if (response.headers.get("content-type")?.split(";")[0] !== expectedType) {
      throw new Error("The server returned an unexpected export format.");
    }
    await saveAttachment(response, `clawfare-${id}.${format}`);
  }
  const generated = createGeneratedClient(request);
  const isStatus = (value: unknown): value is HostedStatus =>
    isRecord(value) &&
    typeof value.cacheDir === "string" &&
    typeof value.searchesDir === "string" &&
    (value.hostedExports === undefined || typeof value.hostedExports === "boolean") &&
    isQuotaList(value.providers) &&
    (value.hostedTracking === undefined || typeof value.hostedTracking === "boolean") &&
    (value.hostedSearch === undefined ||
      (isRecord(value.hostedSearch) &&
        typeof value.hostedSearch.canExecuteOneWayGbpSerpApi === "boolean" &&
        (value.hostedSearch.canExecuteRoundTripGbpSerpApi === undefined ||
          typeof value.hostedSearch.canExecuteRoundTripGbpSerpApi === "boolean")));
  const isStorage = (value: unknown): value is StorageDiagnostic =>
    isRecord(value) && value.backend === "d1" && typeof value.sampleStored === "boolean";
  const hostedDisabled = <T>(): Parsed<T> => ({
    ok: false,
    status: 405,
    error: "This authenticated Site has not enabled paid hosted search.",
  });
  async function serverAllowsHostedExecution(query: SearchQuery): Promise<boolean> {
    if (!hosted || guest || !allowEstimate) return false;
    const status = decode(await generated.getStatus(), isStatus);
    return (
      status.ok &&
      (query.tripType === "round_trip"
        ? status.data.hostedSearch?.canExecuteRoundTripGbpSerpApi === true
        : status.data.hostedSearch?.canExecuteOneWayGbpSerpApi === true)
    );
  }
  const isQuote = (value: unknown): value is HostedSearchQuote =>
    isRecord(value) &&
    isRecord(value.canonicalQuery) &&
    Array.isArray(value.canonicalQuery.origins) &&
    Array.isArray(value.canonicalQuery.destinations) &&
    typeof value.canonicalQuery.outboundFrom === "string" &&
    Array.isArray(value.canonicalQuery.providers) &&
    typeof value.consentToken === "string" &&
    typeof value.expiresAt === "string" &&
    Number.isSafeInteger(value.totalCallsUpperBound) &&
    Number.isSafeInteger(value.baseCallsUpperBound) &&
    Number.isSafeInteger(value.returnLookupsUpperBound) &&
    value.canExecuteHere === true &&
    isRecord(value.cost) &&
    typeof value.cost.message === "string" &&
    typeof value.cost.vendorBillingKnown === "boolean";
  const isExecution = (value: unknown): value is HostedSearchExecution =>
    isRecord(value) &&
    typeof value.ok === "boolean" &&
    isResultSet(value.result) &&
    Array.isArray(value.providerObservations) &&
    (value.error === undefined || typeof value.error === "string");
  const tracking = createGeneratedClient((path, body) => request(path, body, null, "track"));
  const isTracked = (value: unknown): value is TrackResponse =>
    isRecord(value) && typeof value.slug === "string" && /^tracked-[a-f0-9]{40}$/.test(value.slug);
  const isHistory = (value: unknown): value is HistoryObservation[] =>
    Array.isArray(value) &&
    value.every(
      (row: unknown) =>
        isRecord(row) &&
        typeof row.observation_id === "string" &&
        typeof row.itinerary_id === "string" &&
        typeof row.provider === "string" &&
        typeof row.source_label === "string" &&
        ["live", "cached", "mock", "sample", "manual"].includes(row.source_label) &&
        (row.live_price === 0 || row.live_price === 1) &&
        typeof row.captured_at === "string" &&
        Number.isFinite(Date.parse(row.captured_at)) &&
        typeof row.original_currency === "string" &&
        /^[A-Z]{3}$/.test(row.original_currency) &&
        typeof row.amount_decimal === "string" &&
        /^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?$/.test(row.amount_decimal),
    );
  return Object.freeze({
    request,
    async trackHosted(id: string, body: TrackRequest): Promise<Parsed<TrackResponse>> {
      if (!hosted || guest)
        return { ok: false, status: 405, error: "Hosted tracking is unavailable." };
      return decode(await tracking.track(id, body), isTracked);
    },
    async history(id: string, itineraryId: string) {
      return decode(await generated.getHistory(id, itineraryId), isHistory);
    },
    async prepareHostedSearch(query: SearchQuery) {
      if (!(await serverAllowsHostedExecution(query))) return hostedDisabled<HostedSearchQuote>();
      return decode(await request("/api/prepare-search", query, null, "search"), isQuote);
    },
    async executeHostedSearch(quote: HostedSearchQuote) {
      if (!(await serverAllowsHostedExecution(quote.canonicalQuery))) {
        return hostedDisabled<HostedSearchExecution>();
      }
      return decode(
        await request(
          "/api/search",
          {
            canonicalQuery: quote.canonicalQuery,
            consentToken: quote.consentToken,
          },
          null,
          "search",
        ),
        isExecution,
      );
    },
    async sample() {
      return decode(await generated.getSample(), isResultSet);
    },
    async searches() {
      return decode(await generated.getSearches(), isSavedList);
    },
    async status() {
      return decode(await generated.getStatus(), isStatus);
    },
    async quota() {
      return decode(await generated.getQuota(), isQuotaList);
    },
    async storage() {
      return decode(await generated.getStorage(), isStorage);
    },
    async estimate(query: SearchQuery) {
      return decode(await generated.estimate(query), isEstimate);
    },
    saveSearch: (query: SearchQuery, consent: string | null) => generated.search(query, consent),
    vendors: (id: string, payload: VendorRequest, consent?: string | null) =>
      generated.vendors(id, payload, consent),
    track: (id: string, payload: TrackRequest) => generated.track(id, payload),
    download,
    downloadSaved,
  });
}
