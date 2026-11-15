# Duffel offer verification and cash refunds

This module is **search/read-only** and cannot order, issue tickets or charge cards.

A Duffel test token (`duffel_test_...`) produces mock offers. A live token
(`duffel_live_...`) is **disabled** unless `DUFFEL_LIVE_ENABLED=true`
is also present in the private runtime environment. Never commit either token.

## Search and exact-offer recheck

To search real inventory, first activate a live account directly with Duffel.
Use `fare estimate` or a saved investigation to budget the search calls.
For a particular live offer returned by a search:

```sh
fare verify-duffel off_<offer-id>
fare verify-duffel off_<offer-id> --confirm
```

The first invocation issues **no API call**. The confirmed invocation makes
one read-only `GET /air/offers/{id}`, then reports the whole-party total,
currency, observation timestamp, offer expiry and cancellation terms.
It performs no order, payment or reservation action.

A refreshed price is a **quote**, not guaranteed availability. Repeat
checks immediately before requesting the actual ticket from an authorized
seller.

## Refund claims must be evidence-based

Duffel `conditions.refund_before_departure` can report whether an airline
permits a refund before departure and any airline cancellation penalty.
The following are independent requirements:

- Airline refund allowed before outbound departure.
- Airline cancellation penalty equal to zero.
- Refund expressly to the **original payment method** rather than credit.
- No airline-agent or ticketing/servicing cancellation fee.
- The ticket is priced for the full party and remains available/unexpired.

If any element is unknown, do **not** label the offer `verified fully
refundable in cash`. A zero-penalty Duffel response normally has an
**unknown method** and **unknown servicing fee**, so it is *not* sufficient
proof for the fully-refundable cash filter. A marketing fare name such as
Business Flex is not sufficient proof either.

The snapshot stores the supplier evidence, fare-policy fingerprint and
flight product identity in SQLite, including when the source is test/mock.
A reported number of available passengers is checked if present; lack of
that field is not affirmative availability verification.

A partially used return ticket is another issue entirely. The unused
coupon may be repriced, and skipping it may trigger no-show consequences.
Never extrapolate proportional refunds from a before-departure condition.

### Sources
- https://duffel.com/docs/guides/displaying-offer-and-order-conditions
- https://duffel.com/docs/api/offers
- https://duffel.com/docs/guides/getting-started
