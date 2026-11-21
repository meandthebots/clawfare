// Platform-independent Fetch API boundary for the read-only Clawfare Site.
// The Sites/Workers adapter must supply a D1 binding and trusted identity
// callback from authenticated platform ingress. No generic header trust.
import { renderHostedReadOnlyPage } from "./hosted-page.mjs";
import { handleReadOnlyApi } from "./read-only-api.mjs";
import { validatePublicSample } from "./public-sample.mjs";

/**
 * Return a Fetch-compatible request handler for the public read-only preview.
 *
 * Unknown non-API paths (including /signin-with-chatgpt and /signout-with-chatgpt)
 * return null for the hosting platform to handle; the wrapper must return its
 * own 404 or static-asset response if it receives null.
 */
export function createReadOnlySitesHandler({
  templateHtml,
  buildProvenance = null,
  db,
  getVerifiedEmail,
  ownerKeySecret,
  cryptoApi = globalThis.crypto,
  bundledPublicSample = null,
  kotlinEngine = null,
  providerKeyPresence = null,
  searchPrepareConfig = null,
  searchExecuteConfig = null,
}) {
  if (!db?.prepare || typeof getVerifiedEmail !== "function" ||
      typeof ownerKeySecret !== "string" ||
      new TextEncoder().encode(ownerKeySecret).length < 32) {
    throw new Error("Trusted Sites D1, identity and owner-key secret are required");
  }
  // Fail before accepting requests if the Kotlin UI version does not support
  // hosted mode. No silent fallback to localhost token auth.
  const allowEstimate = kotlinEngine != null;
  if (allowEstimate && (typeof kotlinEngine.planSearchJson !== "function" ||
      typeof kotlinEngine.projectBudgetJson !== "function")) {
    throw new Error("Hosted estimates require compiled Kotlin flight planning and budget exports");
  }
  const page = renderHostedReadOnlyPage(templateHtml, { allowEstimate });
  // The fixture is provided by the server build, never supplied by the
  // request. Copy and validate before accepting requests so a caller cannot
  // mutate the in-memory object after handler initialization.
  const trustedSample = bundledPublicSample == null
    ? null : JSON.parse(validatePublicSample(bundledPublicSample));
  return async function handle(request) {
    const path = new URL(request.url).pathname;
    if (path === "/" || path === "/index.html") {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return new Response(null, { status: 405, headers: { allow: "GET, HEAD" } });
      }
      return new Response(request.method === "HEAD" ? null : page, {
        status: 200,
        headers: {
          "content-type": "text/html; charset=utf-8",
          "cache-control": "no-store",
          "x-content-type-options": "nosniff",
          "referrer-policy": "no-referrer",
        },
      });
    }
    if (path.startsWith("/api/")) {
      return handleReadOnlyApi(request, {
        db, getVerifiedEmail, ownerKeySecret, cryptoApi, buildProvenance,
        bundledPublicSample: trustedSample, kotlinEngine, providerKeyPresence,
        searchPrepareConfig, searchExecuteConfig,
      });
    }
    if (path === "/favicon.ico") return new Response(null, { status: 204 });
    return null;
  };
}
