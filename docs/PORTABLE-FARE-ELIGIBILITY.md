# Shared JVM and Kotlin/JS fare eligibility

The compiled Kotlin/JS host now exports
`evaluateOfferEligibilityJson` from `fare-core/commonMain`.
The JVM `OfferEligibility`, `TransitRules` and airline display filters reuse
the same pure algorithm for observed operating-carrier verification,
carrier-code/alias matching, per-leg cabin classes, disconnected segments
and transit exclusions.

The bridge accepts `EligibilityEvaluationRequest` containing a typed
`SearchQuery`, observed outbound/return `Direction` segments and an
optional **trusted** `airportCountries` reference. Unlike the JVM, the
hosted Worker has no OurAirports CSV loader. A future authenticated executor
must provide a versioned and reviewed country reference or leave
`airportCountries` empty. Empty/missing coverage with country exclusions
fails closed (`unknown_transit_country`), **not** an inferred eligible fare.

`EligibilityEvaluation` returns only `eligible` and a stable exclusion
reason; no credentials, booking tokens or refundability claims are involved.
Until the full Kotlin ResultSet assembler and hosted executor are wired,
this remains an internal, no-egress bridge. Cross-target tests in
`PortableEligibilityTest` exercise country exclusions, airline aliases,
unknown operating carriers and strict business cabin requirements.
