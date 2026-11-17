import * as stylex from "@stylexjs/stylex";
import { parseTrustLevel } from "./trustLevel.ts";

const styles = stylex.create({
  badge: {
    display: "inline-block",
    fontSize: "11px",
    fontWeight: 700,
    borderRadius: "999px",
    padding: "2px 8px",
  },
  airline: { backgroundColor: "var(--info-bg)", color: "var(--info-ink)" },
  trusted: { backgroundColor: "var(--green-bg)", color: "var(--green-ink)" },
  caution: { backgroundColor: "var(--amber-bg)", color: "var(--amber-ink)" },
  risky: { backgroundColor: "var(--red-bg)", color: "var(--red-ink)" },
});

export interface TrustBadgeProps {
  trust: string;
  label?: string;
}

/** A parsed, fail-closed trust display shared by legend and vendor rows. */
export function TrustBadge({ trust, label }: TrustBadgeProps) {
  const level = parseTrustLevel(trust);
  const cls = stylex.props(
    styles.badge,
    level === "airline" && styles.airline,
    level === "trusted" && styles.trusted,
    level === "caution" && styles.caution,
    level === "risky" && styles.risky,
  ).className;
  return <span className={"trust " + level + " " + cls}>{label || trust || level}</span>;
}
