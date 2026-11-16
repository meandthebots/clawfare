# One-way, genuine return and unused-return comparisons (#16)

This module is **read-only**. It never books, amends, cancels or no-shows a
ticket. A hypothetical unused return is not a recommended fare strategy,
and it does not imply that unused flight coupons yield a half-fare refund.

## Why these are different products

The [British Airways Conditions of Carriage](https://www.britishairways.com/content/information/legal/conditions-of-carriage/general-conditions-of-carriage),
section 3c, explicitly addresses using coupons in sequence and fares
recalculated after changing transportation. Its [Customer Commitment](https://www.britishairways.com/content/information/legal/our-customer-commitment)
warns about remaining coupons after a no-show. These contractual terms
are **not** a universal tariff for every airline or fare basis.

[Duffel's offer/order conditions](https://duffel.com/docs/guides/displaying-offer-and-order-conditions)
document how top-level `refund_before_departure` applies to the whole
unflown ticket, while changing just one slice can involve different rules;
the API does not supply a standard refundable-inbound amount for all
partially used orders. [Duffel's order cancellation documentation](https://duffel.com/docs/api/order-cancellations)
also distinguishes a refund to the booking agent's account from a
traveller's own original payment method.

Do not infer partial-use rights from the fare name “Flex”, a marketing
“refundable” flag, a whole-ticket before-departure refund field, or a
generic airline cancellation clause.

## Strategy modes

Add an optional `ticketUsePlan` to a private `BatchScenario`:

- `one_way`: requires a genuine one-way offer. Purchase price is the
  cost; no unused coupon exists.
- `genuine_return`: requires both actual directions and a valid
  return date. Purchase price remains the ticket cost; no speculative
  resale or leftover-coupon value is subtracted.
- `cancel_inbound`: outbound would be flown and the inbound would be
  proactively cancelled **with seller authorization**. Net
  ticket cost is calculated only from fresh evidence for that exact
  offer, seller, party, fare brand and full itinerary.
- `no_show_inbound`: explicitly **not computable**. The gross ticket
  purchase price is shown, but there may be forfeiture, repricing,
  cancellation of later coupons and servicing consequences.
- `cancel_whole_before_outbound`: invokes separate issue #12
  original-payment, entire-ticket cash-refund proof; no post-departure
  amount is applied.

Example private JSON fragment:

```json
{
  "id": "example-return",
  "query": {
    "origins": ["LHR"],
    "destinations": ["HND"],
    "outboundFrom": "2028-06-01",
    "returnFrom": "2028-06-20",
    "tripType": "round_trip",
    "providers": ["serpapi"],
    "cabin": "business",
    "adults": 2,
    "cacheOnly": true
  },
  "positioningGbp": 0,
  "hotelsGbp": 0,
  "groundTransfersGbp": 0,
  "baggageGbp": 0,
  "ticketUsePlan": {"mode": "cancel_inbound"}
}
```

Without `partialRefundProof` this produces an explicit
`not_computable` ticket-use state and **no net all-in total**. The
`partyFareGbp` (gross price paid) is still presented. The report CSV
includes mode, gross/net GBP, traveller refund, status and explanation.

## Exact partially used coupon evidence

The optional `PartialUseRefundProof` is *manually reviewed*, bound to an
actual seller ID and provider offer ID and hashed full itinerary
(flights, departure dates/times, fare brand, passenger count). It records
a safe HTTPS source URL and a SHA-256 of privately stored exact terms,
a review/expiry timestamp, an explicit seller-confirmed right to cancel
the unused inbound, and all five **whole-party GBP** amounts:

- unused-coupon gross refund;
- post-departure fare repricing deduction;
- airline cancellation penalty;
- seller/agent servicing fee;
- final actual cash refund to the traveller.

The calculated traveller refund is permitted only when all amounts are
known/nonnegative, match the seller's stated net result, and the proof
indicates cash returned to the traveller's original payment method.
The proof must be no older than 48 hours and unexpired, and must match
the offer/seller listed in the observed provider data. No inferred zero
fee, automatic 50% coupon refund or cross-currency subtraction is
permitted. This is still a **manual evidence calculation**, not a
technical guarantee that a live seller will honor the terms.

Optional min/max stay constraints are applied only with a supplied
fare-specific source. A return that violates such a recorded condition
is excluded from a computed net cost; an incomplete outbound/return
coupon sequence is likewise excluded.

## Future work

Issue #16 stays open for verified live fare-basis/issuer retrieval, policy
source versioning, fare-construction and tax decomposition, a capped
return-date sampler, seller-post-purchase partial-use quotes where
legitimate, original-currency evidence with dated FX, and web UI
controls for comparing valid ticket products. Exact cash-refund
proof (#12), provider activation (#11) and seller variants (#13) remain
separate dependencies. No live searches or booking mutation were run.
