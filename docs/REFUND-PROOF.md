# Exact fare cash-refund evidence (pre-purchase)

This workflow is deliberately **read-only**. It does not book tickets,
submit passenger identity, create orders, quote order cancellations or
authorize payment. Treat unavailable/undated seller evidence as unknown.

## What can be asserted

A zero airline penalty does **not** prove the traveller will receive a
fee-free refund to their original card. The issuer/agency may apply servicing
fees; an airline or provider may return money to the **agent's** original
payment method (e.g. their balance), leaving traveller reimbursement to the
agent. Refund rules for the unused return after flying outbound are separate.

The official Duffel documentation is explicit:
- [Displaying offer/order conditions](https://duffel.com/docs/guides/displaying-offer-and-order-conditions)
  describes `conditions.refund_before_departure.allowed` and airline penalties.
- [Order Cancellations API](https://duffel.com/docs/api/order-cancellations)
  distinguishes a refund to the agent's original payment method and a
  separate obligation for the agent to refund the traveller.
- [Cancelling an Order](https://duffel.com/docs/guides/cancelling-an-order)
  uses an **already placed order** and two-step cancellation. **Do not**
  call the order/cancellation endpoints while researching fares to purchase.

## Reviewed-proof checklist

For a specific fare and **two adult** passengers (or the actual party),
a reviewer should capture the following **privately** from exact seller terms:

1. The **seller identity**, operating airline and **exact fare brand and
   offer ID**, booking itinerary including all segments/dates/cabin/party.
2. A source-backed, dated statement that **voluntary cancellation of the
   entire ticket before outbound departure** is allowed without **airline
   penalty**; record its exact deadline and time zone.
3. The actual **traveller reimbursement channel** is the original payment
   method, not voucher, airline credit or agent account balance. Distinguish
   an airline's obligation from a third-party seller's obligation.
4. Separate seller/servicing, card-processing, cancellation and other costs:
   explicit **zero** for all. Unknown is not zero.
5. Exact captured terms stored on the user's **private** disk. Include the
   source HTTPS URL without authentication tokens or query parameters, its
   SHA-256 digest, review time, deadline and expiry. Do not commit the
   captured text or an actual traveller's itinerary to GitHub.

### Airline-direct representative workflow

At the exact airline-direct checkout for a selected **fare family**, review
the full fare rules and cancellation/refund screen. Confirm whether that
specific fare and entire booking can be voluntarily cancelled, the original
card receives cash, and whether the seller charges additional fees. Merely
finding the word “flexible” on a general airline page is insufficient. If
the actual checkout terms cannot be captured safely before payment, retain
`unverified`.

### Agency/OTA representative workflow

Identify the contractual seller (not just the marketing airline); capture
both airline fare rules and the agency's own customer cancellation/reimbursement
terms for the **same fare product**. The seller must independently confirm
original-payment reimbursement and **no seller fee** for full-ticket
pre-departure cancellation. An order-cancellation API returning funds to
an agency balance is not sufficient proof. If different parties' terms
conflict, label the product `unverified` and investigate manually.

## Application data contract

`RefundEvidence` stores provider/airline conditions. Optional
`sellerId` and `sellerProof` attach a reviewed, time-bounded
`SellerRefundProof` that is bound to the exact flight segments, fare brand,
policy key, cabin, passenger count, seller and offer ID. Verified-only
filters require **both** independent sources and zero airline/seller fees,
fresh source snapshots, original-payment refund and a future cancellation
deadline. Proof expires conservatively within 48 hours of review and never
turns mock, cached or unavailable-seat quotes into verified bookable fares.

Current limitations: the proof is a **manually reviewed record**, not
cryptographic assurance that a seller will honor it, and the application
cannot automatically retrieve exact seller pre-purchase terms from all
providers. The application does not make a cash-refund guarantee, validate
seller legal enforceability, or infer the destination payment method from
Duffel alone. See issue #13 for seller option identity and #12 for proof
capture UX and direct airline/OTA integration.
