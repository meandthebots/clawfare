/**
 * Compile the existing Kotlin JVM web template for trusted read-only Sites
 * hosting. The original localhost application is unchanged by default.
 * The flag is an explicit display/HTTP-client mode, NOT an auth credential.
 */
export function renderHostedReadOnlyPage(templateHtml, { allowEstimate = false } = {}) {
  if (typeof templateHtml !== "string") throw new TypeError("HTML template required");
  const marker = '<script>window.__SNAPSHOT__ = null;</script>';
  if (templateHtml.split(marker).length !== 2) {
    throw new Error("Expected exactly one original Kotlin web UI snapshot marker");
  }
  // Enabling an informational estimate never unlocks paid search or other
  // mutations. The matching hosted API requires a compiled Kotlin engine.
  const estimateFlag = allowEstimate ? " window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ = true;" : "";
  return templateHtml.replace(marker,
    '<script>window.__SNAPSHOT__ = null; window.__CLAWFARE_HOSTED_READ_ONLY__ = true;' +
      estimateFlag + '</script>');
}
