# FX and monetary-ranking guardrails (#18)

A numeric airfare in a foreign currency must never be treated as the same
number of pounds because the reference FX table lacks that currency.

- `Fx.toGbpOrNull` returns **null** for an unknown ISO currency, missing or
  non-positive FX rate, negative price, NaN or infinite numeric input.
- `Fx.toGbp`, retained for callers requiring a numeric result, now throws
  if the conversion is unknown instead of silently returning the original
  amount as an integer.
- `VendorMath.allIn` uses the nullable conversion: a foreign market
  without a usable rate is **excluded from the GBP ranking**, not shown
  as an implausibly cheap GBP fare. The original seller price and currency
  remain visible for manual review.
- Persisted `Presentation` metadata now stores a frozen `FxInfo` snapshot
  (`perGbp` rates and `asOf` description) when an investigation is saved.
  Reopening an investigation returns that same metadata instead of
  silently replacing it with the current reference set. Previously
  saved records lacking an FX field are legacy/unknown-snapshot records.
- These reference rates are *not* a card issuer's settlement or guaranteed
  payment conversion. The existing `FxInfo` stores informational
  reference rates only.

A saved normalized GBP fare is stored as a GBP value; reading it back
should not revalue it when today's reference FX rates change. Test
fixtures confirm persistence of frozen metadata and unknown-currency
exclusion from seller rankings. No actual provider or FX service request
is made to test this.

## Outstanding for full issue #18

This change does **not** establish source-currency scope for every
provider. Further work must add **observation-level** immutable original
amount, amount scope (one adult vs whole booking), taxes/fees and separate
FX reference fields; explicit FX conversion-date/source/age; snapshot-aware
batch comparison and alert behavior; and complete baggage allowances/
per-slice fees. Existing default historical fallback rates should be
replaced or made opt-in for decision-quality current pricing. Reports
must mark missing components unknown rather than free, and historical
price deltas must distinguish source-currency movement from changes
in exchange rate.
