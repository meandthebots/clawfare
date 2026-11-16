# Airport-country reference provenance

The transit safety filter uses a protected built-in IATA-country map and optionally a **local** OurAirports `airports.csv`. No airport mapping downloads occur during flight searches.

Set `CLAWFARE_AIRPORTS_CSV` to the local CSV path. Optionally pin its verified bytes with `CLAWFARE_AIRPORTS_SHA256` (64 hex characters) and record an operator-confirmed source date in `CLAWFARE_AIRPORTS_AS_OF` (ISO YYYY-MM-DD). A source hash is a reproducibility check, not independent authentication; an operator-provided date does not establish freshness.

An explicitly configured missing, unreadable, empty, malformed, hash-mismatched, or built-in-conflicting CSV fails closed. Its IATA entries cannot override protected built-in countries such as HKG/HK, MFM/MO, or PVG/CN. Duplicate IATA records with the same country consolidate; contradictory country mappings are rejected. Unknown intermediate transit airports remain blocked when country exclusions apply.

`TransitRules.referenceMetadata()` exposes safe source kind, count, hash and date; `TransitRules.countryCoverage(codes)` enumerates the known and unknown airport codes. Neither includes private paths or passenger details. Restart the process after changing the local CSV because the reference is loaded once per process. The built-in map remains the default when no optional CSV is configured.
