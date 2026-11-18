/**
 * Signed, single-use, bounded provider-search authorization.
 *
 * Internal server module: no route, provider egress or browser identity trust.
 * Only the verified hosted ingress may supply ownerId, and only compiled
 * Kotlin may supply canonicalQueryJson. Turning on consent alone does not
 * turn on any HTTP route; each provider HTTP request still needs D1 metering.
 */
import { readHostedProviderReadiness } from "./provider-http-budget.mjs";

const OWNER = /^acct_[a-f0-9]{64}$/;
const SHA256 = /^[a-f0-9]{64}$/;
const NONCE = /^[a-f0-9]{32}$/;
const PROVIDERS = new Set(["serpapi", "searchapi", "travelpayouts", "duffel"]);
const CATEGORIES = new Set(["base_search", "return_selection", "booking_options", "exact_offer", "quota"]);
const MAX_TOKEN_BYTES = 4096;
const MAX_QUERY_BYTES = 16 * 1024;
const MAX_CALLS_PER_CONSENT = 16;
const MAX_LIFETIME_MS = 5 * 60 * 1000;
const VERSION = 1;

export const INSERT_CONSENT_SQL = [
  "INSERT INTO hosted_search_consents",
  " (nonce, owner_id, query_digest, limits_json, created_at, expires_at, state)",
  " VALUES (?, ?, ?, ?, ?, ?, 'issued')",
].join("\n");

export const CONSUME_CONSENT_SQL = [
  "UPDATE hosted_search_consents",
  " SET state = 'consumed', consumed_at = ?",
  " WHERE nonce = ? AND owner_id = ? AND query_digest = ?",
  " AND limits_json = ? AND expires_at > ? AND state = 'issued'",
  " RETURNING nonce",
].join("\n");

export class SearchConsentError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "SearchConsentError";
    this.code = code;
  }
}
function deny(code, reason) {
  throw new SearchConsentError(code, reason);
}
function checkOwner(ownerId) {
  if (typeof ownerId !== "string" || !OWNER.test(ownerId)) deny("invalid_owner", "Verified owner key required.");
}
function safeNow(clock) {
  const date = clock();
  if (!(date instanceof Date) || !Number.isFinite(date.getTime())) deny("invalid_clock", "Invalid server UTC time.");
  return date;
}
function validateQueryJson(raw) {
  if (typeof raw !== "string" || new TextEncoder().encode(raw).length > MAX_QUERY_BYTES) {
    deny("invalid_query", "Canonical Kotlin query required within 16 KiB.");
  }
  let query;
  try { query = JSON.parse(raw); } catch { deny("invalid_query", "Malformed canonical Kotlin query."); }
  if (!query || typeof query !== "object" || Array.isArray(query) ||
      !Array.isArray(query.origins) || !Array.isArray(query.destinations) ||
      !Array.isArray(query.providers)) {
    deny("invalid_query", "Expected the full canonical Kotlin search query.");
  }
  // This is a defensive boundary, not a competing JS query normalizer.
  if (query.confirmed === true || query.origins.length < 1 || query.destinations.length < 1) {
    deny("invalid_query", "Quote must bind an unconfirmed, planned search.");
  }
  return raw;
}
function cleanLimits(callLimits) {
  if (!Array.isArray(callLimits) || callLimits.length < 1 || callLimits.length > 16) {
    deny("invalid_limits", "Expected a bounded provider call plan.");
  }
  const seen = new Set();
  let total = 0;
  const limits = callLimits.map(entry => {
    if (!entry || typeof entry !== "object" || Array.isArray(entry) ||
        !PROVIDERS.has(entry.provider) || !CATEGORIES.has(entry.category) ||
        !Number.isSafeInteger(entry.count) || entry.count < 1 ||
        entry.count > MAX_CALLS_PER_CONSENT) {
      deny("invalid_limits", "Invalid planned provider/category request count.");
    }
    const key = entry.provider + ":" + entry.category;
    if (seen.has(key)) deny("invalid_limits", "Duplicate provider/category request count.");
    seen.add(key);
    total += entry.count;
    return { provider: entry.provider, category: entry.category, count: entry.count };
  }).sort((a, b) => a.provider.localeCompare(b.provider) || a.category.localeCompare(b.category));
  if (total < 1 || total > MAX_CALLS_PER_CONSENT) {
    deny("invalid_limits", "Request plan exceeds the hosted per-consent maximum.");
  }
  return { limits, total };
}
function bytesToHex(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}
function hexToBytes(hex) {
  if (!/^(?:[a-f0-9]{2})+$/.test(hex)) deny("invalid_token", "Malformed authorization token.");
  return Uint8Array.from(hex.match(/../g), part => parseInt(part, 16));
}
function toBase64Url(bytes) {
  let raw = "";
  for (const byte of bytes) raw += String.fromCharCode(byte);
  return btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function fromBase64Url(encoded) {
  if (!/^[A-Za-z0-9_-]+$/.test(encoded) || encoded.length > MAX_TOKEN_BYTES) {
    deny("invalid_token", "Malformed authorization token.");
  }
  let raw;
  try {
    raw = atob(encoded.replace(/-/g, "+").replace(/_/g, "/") +
      "=".repeat((4 - encoded.length % 4) % 4));
  } catch {
    deny("invalid_token", "Malformed authorization token.");
  }
  return Uint8Array.from(raw, char => char.charCodeAt(0));
}
function requireSecret(secret) {
  if (typeof secret !== "string" || new TextEncoder().encode(secret).length < 32) {
    throw new Error("Private consent-signing secret of at least 32 bytes required.");
  }
  return secret;
}
async function keyFor(secret, cryptoApi) {
  return cryptoApi.subtle.importKey("raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}
async function digestOf(raw, cryptoApi) {
  return bytesToHex(new Uint8Array(await cryptoApi.subtle.digest("SHA-256",
    new TextEncoder().encode(raw))));
}
function parseToken(bytes) {
  let payload;
  try { payload = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); }
  catch { deny("invalid_token", "Malformed authorization payload."); }
  if (!payload || typeof payload !== "object" || Array.isArray(payload) ||
      payload.version !== VERSION || !OWNER.test(payload.ownerId || "") ||
      !SHA256.test(payload.queryDigest || "") || !NONCE.test(payload.nonce || "") ||
      typeof payload.policy !== "string" || payload.policy.length < 1 ||
      payload.policy.length > 80 ||
      typeof payload.createdAt !== "string" || typeof payload.expiresAt !== "string" ||
      !Array.isArray(payload.callLimits) ||
      !Number.isInteger(payload.totalCalls)) {
    deny("invalid_token", "Unexpected authorization payload.");
  }
  const { limits, total } = cleanLimits(payload.callLimits);
  if (total !== payload.totalCalls || JSON.stringify(limits) !== JSON.stringify(payload.callLimits)) {
    deny("invalid_token", "Invalid authorization call limits.");
  }
  const created = Date.parse(payload.createdAt), expires = Date.parse(payload.expiresAt);
  if (!Number.isFinite(created) || !Number.isFinite(expires) ||
      expires <= created || expires - created > MAX_LIFETIME_MS) {
    deny("invalid_token", "Invalid authorization validity period.");
  }
  return payload;
}

/** Never consider an unchecked request header as an authenticated identity. */
export function requireSameOrigin(request) {
  const origin = request.headers.get("origin");
  const expected = new URL(request.url).origin;
  if (origin !== expected) deny("cross_origin", "Same-origin confirmation required.");
  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") {
    deny("cross_origin", "Cross-site confirmation rejected.");
  }
}

/**
 * A service may issue signed quotes only with its own trusted owner,
 * Kotlin-produced query and complete call plan. The D1 cap read is advisory
 * here; the dispatch layer must still atomically reserve every attempted HTTP.
 */
export class D1SearchConsents {
  constructor(db, {
    secret, enabled = false, policy = "hosted-v1",
    credentialPresence = {}, clock = () => new Date(), cryptoApi = globalThis.crypto,
  } = {}) {
    if (!db?.prepare || !cryptoApi?.subtle || !cryptoApi?.getRandomValues) {
      throw new TypeError("D1 and WebCrypto required for search authorization.");
    }
    this.db = db;
    this.secret = requireSecret(secret);
    this.enabled = enabled === true;
    this.policy = policy;
    this.credentialPresence = credentialPresence;
    this.clock = clock;
    this.cryptoApi = cryptoApi;
  }
  ensureEnabled() {
    if (!this.enabled) deny("disabled", "Hosted search authorization disabled.");
  }
  async issue({ ownerId, canonicalQueryJson, callLimits, lifetimeMs = MAX_LIFETIME_MS }) {
    this.ensureEnabled();
    checkOwner(ownerId);
    validateQueryJson(canonicalQueryJson);
    if (!Number.isSafeInteger(lifetimeMs) || lifetimeMs < 1 ||
        lifetimeMs > MAX_LIFETIME_MS) {
      deny("invalid_lifetime", "Invalid consent lifetime.");
    }
    const { limits, total } = cleanLimits(callLimits);
    const readiness = await readHostedProviderReadiness(this.db, {
      keyPresent: this.credentialPresence, networkEnabled: true, clock: this.clock,
    });
    for (const provider of new Set(limits.map(limit => limit.provider))) {
      const status = readiness.find(entry => entry.provider === provider);
      const calls = limits.filter(entry => entry.provider === provider)
        .reduce((sum, entry) => sum + entry.count, 0);
      if (!status?.configured || status.remaining < calls) {
        deny("quota_denied", "No configured local cap and credential for the entire provider plan.");
      }
    }
    const now = safeNow(this.clock);
    const nonce = bytesToHex(this.cryptoApi.getRandomValues(new Uint8Array(16)));
    const payload = {
      version: VERSION, ownerId,
      queryDigest: await digestOf(canonicalQueryJson, this.cryptoApi),
      callLimits: limits, totalCalls: total, policy: this.policy,
      nonce, createdAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + lifetimeMs).toISOString(),
    };
    const json = JSON.stringify(payload);
    const key = await keyFor(this.secret, this.cryptoApi);
    const signature = new Uint8Array(await this.cryptoApi.subtle.sign(
      "HMAC", key, new TextEncoder().encode(json)));
    // SQLite UNIQUE nonce; no replacement on collisions or retries.
    await this.db.prepare(INSERT_CONSENT_SQL)
      .bind(nonce, ownerId, payload.queryDigest, JSON.stringify(limits),
        payload.createdAt, payload.expiresAt).run();
    return {
      token: toBase64Url(new TextEncoder().encode(json)) + "." + toBase64Url(signature),
      expiresAt: payload.expiresAt, totalCalls: total, callLimits: limits,
    };
  }
  async consume({ ownerId, canonicalQueryJson, token }) {
    this.ensureEnabled();
    checkOwner(ownerId);
    validateQueryJson(canonicalQueryJson);
    if (typeof token !== "string" || token.length > MAX_TOKEN_BYTES) {
      deny("invalid_token", "Missing or oversized consent token.");
    }
    const parts = token.split(".");
    if (parts.length !== 2) deny("invalid_token", "Malformed consent token.");
    const raw = fromBase64Url(parts[0]);
    const signature = fromBase64Url(parts[1]);
    if (signature.length !== 32) deny("invalid_token", "Invalid signature.");
    const key = await keyFor(this.secret, this.cryptoApi);
    const verified = await this.cryptoApi.subtle.verify(
      "HMAC", key, signature, raw);
    if (!verified) deny("invalid_token", "Untrusted consent token.");
    const payload = parseToken(raw);
    if (payload.ownerId !== ownerId) deny("wrong_owner", "Consent belongs to a different owner.");
    if (payload.policy !== this.policy) deny("invalid_policy", "Consent policy has changed.");
    const digest = await digestOf(canonicalQueryJson, this.cryptoApi);
    if (payload.queryDigest !== digest) deny("query_mismatch", "Search changed since approval.");
    const now = safeNow(this.clock).toISOString();
    if (payload.expiresAt <= now) deny("expired", "Search approval has expired.");
    // One atomic UPDATE ... RETURNING statement. No second Worker instance
    // can consume the same nonce, even across restarts or parallel requests.
    const consumed = await this.db.prepare(CONSUME_CONSENT_SQL)
      .bind(now, payload.nonce, ownerId, digest,
        JSON.stringify(payload.callLimits), now).first();
    if (!consumed || consumed.nonce !== payload.nonce) {
      deny("already_used", "Search approval already used or unavailable.");
    }
    return {
      totalCalls: payload.totalCalls, callLimits: payload.callLimits,
      queryDigest: digest, nonce: payload.nonce,
    };
  }
}
