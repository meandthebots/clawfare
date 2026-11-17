import type {
  Estimate,
  ExportRequest,
  QuotaView,
  ResultSet,
  SavedInvestigation,
  SearchQuery,
  StatusResponse,
  StorageDiagnostic,
  VendorRequest,
  TrackRequest,
} from "./domain.ts";
import { isEstimate, isQuotaList, isRecord, isResultSet, isSavedList } from "./domain.ts";
import { createGeneratedClient, type ApiRoute, type ExportRoute } from "./generated/openapi-client.ts";

/**
 * Hosted Sites auth is handled by the trusted ingress. In hosted mode we must
 * never fetch a JVM session token or send a user-supplied identity header.
 * This client cannot authorize paid provider requests.
 */
export interface ApiMode {
  readonly hostedReadOnly: boolean;
  readonly hostedEstimateOnly: boolean;
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
    /^\/api\/(?:sample|status|searches|quota|storage|estimate|search)$/.test(path) ||
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

export interface ClawfareClient {
  request(path: ApiRoute, body?: unknown, consent?: string | null): Promise<ApiResponse<unknown>>;
  sample(): Promise<Parsed<ResultSet>>;
  searches(): Promise<Parsed<SavedInvestigation[]>>;
  status(): Promise<Parsed<StatusResponse>>;
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
  download(path: ExportRoute, request: ExportRequest, fallback: string): Promise<void>;
}
export function createClawfareClient(mode: ApiMode, fetcher: HttpClient = fetch): ClawfareClient {
  const hosted = mode.hostedReadOnly;
  const allowEstimate = hosted && mode.hostedEstimateOnly;
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
    path: ApiRoute,
    body?: unknown,
    consent?: string | null,
  ): Promise<ApiResponse<unknown>> {
    // Defense in depth: only same-origin, explicitly known API routes.
    if (!allowedRoute(path)) throw new Error("Unsupported Clawfare API route.");
    if (hosted && body !== undefined && !(allowEstimate && path === "/api/estimate")) {
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
    const match = /filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") ?? "");
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = match?.[1] ?? fallback;
    a.click();
    // Leave time for the browser to process the click before revoking the URL.
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const generated = createGeneratedClient(request);
  const isStatus = (value: unknown): value is StatusResponse =>
    isRecord(value) &&
    typeof value.cacheDir === "string" &&
    typeof value.searchesDir === "string" &&
    isQuotaList(value.providers);
  const isStorage = (value: unknown): value is StorageDiagnostic =>
    isRecord(value) && value.backend === "d1" && typeof value.sampleStored === "boolean";
  return Object.freeze({
    request,
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
    saveSearch: (query: SearchQuery, consent: string | null) =>
      generated.search(query, consent),
    vendors: (id: string, payload: VendorRequest, consent?: string | null) =>
      generated.vendors(id, payload, consent),
    track: (id: string, payload: TrackRequest) =>
      generated.track(id, payload),
    download,
  });
}
