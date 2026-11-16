# Exact seller-option observations (issue #13)

A flight itinerary is not a sellable fare product, and a booking option is not
proof of refundability or ticket issuance. Read-only provider lookups stay
behind the existing seller-confirmation and per-market quota upper-bound checks.

## Documented provider differences

- [SerpApi Google Flights Booking Options](https://serpapi.com/google-flights-booking-options)
  returns `booking_options[*].together`, optionally `departing` and
  `returning` for separate tickets. `booking_request` can include a POST
  body; **do not execute it** from flight research, and never persist the
  `post_data` or opaque booking/departure tokens in a browser model or export.
- [SearchAPI Google Flights](https://www.searchapi.io/docs/google-flights-api)
  returns flat `booking_options[*]` with `fare_type`, `option_title`,
  `baggage_prices`, and optional `is_split_booking` with `departure` and
  `arrival` subproducts. Its `departure_token` selects a leg; the
  `booking_token` is obtained after choosing the final leg.

The parser supports both schemas. A separate `SellerOption` record captures
the seller, fare brand/type, original market currency, whole-party price,
passenger count, split-ticket status, baggage text, optional phone-service
fee, observation time and the existence of a booking action. These records
are persisted in private SQLite investigation presentation JSON and
available in the read-only web seller table and `seller_options` CSV export.

**No option's `refundStatus` is ever upgraded from `unverified` using
a name such as “Flex,” or merely because a booking action exists.** Full
cash-refund proof requires the distinct seller verification process in
`docs/REFUND-PROOF.md`. No claim is made that a quoted market can be
purchased with an overseas card.

## Product identity and safe ranking

The observation ID includes selected itinerary, provider, market, journey
type, seller metadata, and provider option index; this keeps otherwise
unresolved alternatives distinct. The product key drops index and price to
permit matching the same fare type across observations, but is not treated as
a legal fare-basis identifier. Prices are whole-party amounts in the market
currency requested from the provider (not actual card-settlement amounts).

Split-ticket, no-price, and separately priced telephone-service options are
retained for review but excluded from the ordinary comparable all-in ranking.
The existing ranking also requires enough baggage evidence. Unknown fees
remain unknown. The program never chooses another itinerary's cheapest
booking token when an exact leg match cannot be found.

## Remaining limitations

Provider responses may omit fare basis, refund terms, booking expiry,
issuer currency, baggage allowance per traveller or seller service charges.
Those must remain `unverified`. Relative option ordering may change, so
option IDs are observation-scoped, not durable booking IDs. The verified
fare-rule and seller-identity workflow, conditional seller verification
calls, explicit source-version fields, richer per-option price history,
and country-specific purchase restrictions remain open acceptance work.
