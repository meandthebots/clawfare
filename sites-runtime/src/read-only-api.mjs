// Read-only hosted API adapter for Clawfare's existing browser UI.
// This is NOT a public entrypoint: Sites must supply an authenticated email
// from a trusted identity gateway. Raw user headers are never accepted here.
import { D1Investigations } from "./investigations.mjs";
import { ensureBundledPublicSample, loadPublicSample } from "./public-sample.mjs";
import { estimateHostedSearch } from "./search-estimate.mjs";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "private, no-store",
  "x-content-type-options": "nosniff",
  "vary": "Cookie, oai-authenticated-user-email",
};

function respond(value, status = 200, additionalHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { ...JSON_HEADERS, ...additionalHeaders },
  });
}

function parseLimit(searchParams) {
  const raw = searchParams.get("limit");
  if (raw == null) return 50;
  if (!/^[1-9][0-9]?$/.test(raw)) return null;
  const value = Number(raw);
  return value <= 50 ? value : null;
}

/**
 * Pseudonymous tenant key without storing raw email addresses in D1.
 * The secret must be a random per-Site secret of >=32 bytes, server-side only.
 */
export async function ownerKeyFromVerifiedEmail(email, secret, cryptoApi = globalThis.crypto) {
  if (typeof email !== "string" || email.length < 3 || email.length > 320 ||
      !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    throw new Error("Missing verified ChatGPT identity");
  }
  if (typeof secret !== "string" || new TextEncoder().encode(secret).length < 32) {
    throw new Error("Hosted owner-key secret is missing or too short");
  }
  const key = await cryptoApi.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const bytes = new Uint8Array(
    await cryptoApi.subtle.sign("HMAC", key, new TextEncoder().encode(email.trim().toLowerCase())),
  );
  return "acct_" + Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Called ONLY after the actual Sites platform has authenticated the request.
 *
 * getVerifiedEmail must be a trusted hosting integration that returns null
 * for unsigned visitors and cannot be bypassed through direct public access
 * to the Worker. Never implement it as an unchecked request-header lookup.
 *
 * Unknown or unsupported paths fail closed; no paid search/POST routes are
 * available. This handler neither uses provider keys nor initiates egress.
 */
export async function handleReadOnlyApi(
  request,
  { db, getVerifiedEmail, ownerKeySecret, cryptoApi = globalThis.crypto,
    bundledPublicSample = null, kotlinEngine = null },
) {
  if (typeof getVerifiedEmail !== "function") {
    throw new Error("Trusted identity gateway integration is required");
  }
  const url = new URL(request.url);
  if (!url.pathname.startsWith("/api/")) return null;

  // Only informational, no-egress Kotlin search estimates may POST. No
  // other mutation, provider search, write or consent-granting route exists.
  const isEstimate = url.pathname === "/api/estimate" && request.method === "POST" &&
    kotlinEngine != null;
  if (request.method !== "GET" && !isEstimate) {
    return respond({ error: "Hosted preview: paid searches and mutations are disabled." }, 405,
      { allow: url.pathname === "/api/estimate" && kotlinEngine != null ? "POST" : "GET" });
  }

  let email;
  try {
    email = await getVerifiedEmail(request);
  } catch {
    return respond({ error: "Sign in with ChatGPT required." }, 401);
  }
  if (typeof email !== "string" || !email.trim()) {
    return respond({ error: "Sign in with ChatGPT required." }, 401);
  }

  // Invalid identity is unauthenticated; a misconfigured owner-key secret
  // remains a server error and must never fall back to a shared tenant.
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || email.length > 320) {
    return respond({ error: "Sign in with ChatGPT required." }, 401);
  }
  const ownerId = await ownerKeyFromVerifiedEmail(email, ownerKeySecret, cryptoApi);
  // Authentication precedes parsing and Kotlin planning. The estimator
  // creates no token, no reservation, no network request and no D1 write.
  if (isEstimate) return estimateHostedSearch(request, kotlinEngine);
  const store = new D1Investigations(db, { cryptoApi });

  if (url.pathname === "/api/status") {
    return respond({ cacheDir: "d1", searchesDir: "d1", providers: [] });
  }
  if (url.pathname === "/api/quota") {
    return respond([]);
  }
  if (url.pathname === "/api/searches") {
    const limit = parseLimit(url.searchParams);
    if (limit == null) return respond({ error: "Invalid result limit." }, 400);
    const rows = await store.listSnapshots(ownerId, limit);
    return respond(rows.map(row => ({
      id: row.slug,
      title: row.title,
      fetchedAt: row.fetched_at,
      subtitle: "Saved investigation",
    })));
  }
  if (url.pathname === "/api/sample") {
    // Immutable public fixture stored separately from private investigations.
    // No user snapshot is read, written, or searched for this route.
    const result = bundledPublicSample == null
      ? await loadPublicSample(db, { cryptoApi })
      : await ensureBundledPublicSample(db, bundledPublicSample, { cryptoApi });
    return result ? respond(result) : respond({ error: "Bundled sample has not been seeded." }, 404);
  }

  const resultPath = /^\/api\/results\/([a-z0-9][a-z0-9_-]{0,99})$/.exec(url.pathname);
  if (resultPath) {
    const result = await store.loadSnapshot(ownerId, resultPath[1]);
    return result ? respond(result) : respond({ error: "Not found." }, 404);
  }

  const historyPath = /^\/api\/history\/([a-z0-9][a-z0-9_-]{0,99})\/([A-Za-z0-9._:-]{1,128})$/.exec(url.pathname);
  if (historyPath) {
    const limit = parseLimit(url.searchParams);
    if (limit == null) return respond({ error: "Invalid result limit." }, 400);
    return respond(await store.priceHistory(ownerId, historyPath[1], historyPath[2], limit));
  }

  return respond({ error: "Not found." }, 404);
}
