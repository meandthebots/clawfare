/**
 * Worker-runtime assembly, not an independently public Worker.
 *
 * The hosting platform MUST supply a callback that extracts a verified
 * ChatGPT identity from its trusted ingress. We deliberately provide no
 * default export/fetch or raw-header identity fallback.
 */
import { createReadOnlySitesHandler } from "./site-handler.mjs";
import { kotlinEngineFromBundle } from "./kotlin-engine.mjs";
import { validatePublicSample } from "./public-sample.mjs";

export function createClawfareWorkerFactory({ templateHtml, bundledPublicSample, kotlinBundle }) {
  if (typeof templateHtml !== "string") throw new TypeError("Trusted Kotlin HTML required");
  const fixture = JSON.parse(validatePublicSample(bundledPublicSample));
  // Validate that this is the *real* compiled Kotlin/JS artifact. No
  // handwritten fallback implementation is permitted for the host.
  const kotlin = kotlinEngineFromBundle(kotlinBundle);
  if (typeof kotlin.planSearchJson !== "function") {
    throw new Error("Kotlin/JS bridge unavailable");
  }
  return Object.freeze({
    kotlin,
    createTrustedWorker({ getVerifiedEmail }) {
      if (typeof getVerifiedEmail !== "function") {
        throw new TypeError("Verified Sites identity callback is required");
      }
      return Object.freeze({
        async fetch(request, env, context) {
          // A missing binding or secret is a configuration error; don't fall
          // back to in-memory D1, static sample responses, or shared tenants.
          if (!env?.DB?.prepare || typeof env.OWNER_KEY_SECRET !== "string" ||
              new TextEncoder().encode(env.OWNER_KEY_SECRET).length < 32) {
            return new Response("Site runtime not configured", {
              status: 503,
              headers: { "cache-control": "no-store", "x-content-type-options": "nosniff" },
            });
          }
          const handle = createReadOnlySitesHandler({
            templateHtml,
            bundledPublicSample: fixture,
            kotlinEngine: kotlin,
            db: env.DB,
            ownerKeySecret: env.OWNER_KEY_SECRET,
            // The hosting adapter, not the visitor, must authenticate.
            getVerifiedEmail: req => getVerifiedEmail(req, context),
          });
          const response = await handle(request);
          if (response) return response;
          // Sites-native sign-in/out routes must be intercepted by its
          // platform adapter BEFORE this factory. Never serve a permissive
          // fallback for unknown paths.
          return new Response("Not found", {
            status: 404,
            headers: { "cache-control": "no-store", "x-content-type-options": "nosniff" },
          });
        },
      });
    },
  });
}
