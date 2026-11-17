// This classic script is embedded ONLY into build/guest-preview.html.
// It replaces fetch before React starts, has no network fallback, and never
// appears in the production Sites/Ktor/HTML bundle.
(() => {
  "use strict";
  const sample = __CLAWFARE_GUEST_SAMPLE_JSON__;
  const snapshots = new Map([[sample.id, sample]]);
  const consents = new Map();
  let serial = 0;
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const json = (value, status = 200, extraHeaders = {}) => new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...extraHeaders },
  });
  const problem = (message, status = 400) => json({ error: message }, status);
  const normalQuery = (value) => JSON.stringify({ ...value, confirmed: false });
  const validQuery = (value) => value && typeof value === "object" &&
    Array.isArray(value.origins) && Array.isArray(value.destinations) &&
    typeof value.outboundFrom === "string" && Number.isInteger(value.adults) &&
    value.adults > 0 && value.adults <= 20;
  const readBody = (init) => {
    try { return JSON.parse(init?.body ?? "{}"); } catch { return null; }
  };
  const csvField = (value) => '"' + String(value ?? "").replaceAll('"', '""') + '"';
  const escapeHtml = (value) => String(value ?? "").replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");

  window.__CLAWFARE_GUEST__ = true;
  window.fetch = async (input, init = {}) => {
    // No fallback to native fetch: a guest preview is never allowed to
    // contact a live provider, API, third-party domain or owner-only Site.
    const raw = typeof input === "string" ? input : input?.url;
    if (typeof raw !== "string" || !raw.startsWith("/api/")) {
      throw new Error("Guest preview blocks all non-demo network requests.");
    }
    const url = new URL(raw, "https://guest.invalid");
    const path = url.pathname;
    const method = (init.method ?? "GET").toUpperCase();
    if (path === "/api/session" && method === "GET") {
      return json({ token: "offline-guest-session" });
    }
    if (init.headers?.["X-Clawfare-Session"] !== "offline-guest-session") {
      return problem("Guest session required.", 401);
    }
    if (method === "GET") {
      if (path === "/api/sample") return json(clone(sample));
      if (path === "/api/status") {
        return json({ cacheDir: "guest memory", searchesDir: "guest memory", providers: [] });
      }
      if (path === "/api/quota") return json([]);
      if (path === "/api/searches") {
        return json([...snapshots.values()].filter((result) => result.id !== sample.id).map((result) => ({
          id: result.id, title: result.title, subtitle: result.subtitle,
          fetchedAt: result.fetchedAt,
        })));
      }
      const resultPath = /^\/api\/results\/([a-z0-9][a-z0-9_-]{0,99})$/.exec(path);
      if (resultPath) {
        const result = snapshots.get(resultPath[1]);
        return result ? json(clone(result)) : problem("Guest investigation not found.", 404);
      }
      if (/^\/api\/history\//.test(path)) return json([]);
      return problem("Not available in guest mode.", 404);
    }
    if (method !== "POST") return problem("Unsupported guest method.", 405);
    const body = readBody(init);
    if (!body) return problem("Invalid JSON request body.");
    if (path === "/api/estimate") {
      if (!validQuery(body)) return problem("Invalid guest search query.");
      const token = "guest-consent-" + (++serial);
      consents.set(token, { query: normalQuery(body), expires: Date.now() + 300000 });
      return json({
        liveCalls: 0, cachedCalls: 1, skippedCalls: body.providers?.length ?? 0,
        returnLookupsUpperBound: 0, quota: [], canRun: true, warnings: [],
        lines: ["Guest-only synthetic result. Zero real provider calls, zero charges."],
      }, 200, { "X-Clawfare-Consent": token });
    }
    if (path === "/api/search") {
      if (!validQuery(body) || body.confirmed !== true) {
        return problem("Explicit guest search confirmation required.");
      }
      const token = init.headers?.["X-Clawfare-Consent"];
      const permit = consents.get(token);
      // Consume consent even on mismatch, preventing replay.
      consents.delete(token);
      if (!permit || permit.expires < Date.now() || permit.query !== normalQuery(body)) {
        return problem("Guest estimate has expired or differs from the confirmed query.", 403);
      }
      const result = clone(sample);
      result.id = "guest-search-" + (++serial);
      result.title = "Synthetic guest search " + (serial - 1);
      result.query = clone(body);
      snapshots.set(result.id, result);
      return json({ result });
    }
    const vendorPath = /^\/api\/results\/([a-z0-9][a-z0-9_-]{0,99})\/vendors$/.exec(path);
    if (vendorPath) {
      const result = snapshots.get(vendorPath[1]);
      return result ? json({ result: clone(result) }) : problem("Not found.", 404);
    }
    const trackPath = /^\/api\/results\/([a-z0-9][a-z0-9_-]{0,99})\/track$/.exec(path);
    if (trackPath) {
      const result = snapshots.get(trackPath[1]);
      if (!result) return problem("Not found.", 404);
      // Synthetic acknowledgment, never persisted to disk or D1.
      return json({ slug: "guest-only-" + trackPath[1] });
    }
    if (path === "/api/export/csv") {
      const rows = [ "id,origin,destination,date,synthetic_price_gbp",
        ...sample.itineraries.map((it) => [
          it.id, it.origin, it.destination, it.outboundDate, it.pricePerPerson,
        ].map(csvField).join(",")) ];
      return new Response(rows.join("\n") + "\n", {
        headers: {
          "Content-Type": "text/csv; charset=utf-8",
          "Content-Disposition": 'attachment; filename="guest-preview.csv"',
        },
      });
    }
    if (path === "/api/export/html") {
      const rows = sample.itineraries.map((it) =>
        "<tr><td>" + escapeHtml(it.id) + "</td><td>" +
        escapeHtml(it.origin + " → " + it.destination) + "</td><td>" +
        escapeHtml(it.pricePerPerson) + "</td></tr>").join("");
      const html = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">" +
        "<title>Synthetic guest export</title><h1>Guest export — synthetic only</h1>" +
        "<p>No real flights, fares, refunds or booking offers.</p><table>" + rows + "</table></html>";
      return new Response(html, {
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Content-Disposition": 'attachment; filename="guest-preview.html"',
        },
      });
    }
    return problem("Guest preview cannot make real API or provider calls.", 405);
  };
})();
