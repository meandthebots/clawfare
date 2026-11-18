/** Unknown seller trust never implies verified safety. */
export type TrustLevel = "airline" | "trusted" | "caution" | "risky";
export function parseTrustLevel(value: unknown): TrustLevel {
  switch (value) {
    case "airline":
    case "trusted":
    case "caution":
    case "risky":
      return value;
    default:
      return "risky";
  }
}
