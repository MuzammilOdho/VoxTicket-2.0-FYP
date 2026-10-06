-- Phase 1A: active-claim deduplication as a domain invariant.
--
-- At most one active (OPEN or IN_REVIEW) claim may exist per order item.
-- A partial unique index enforces this at the write boundary and is
-- race-safe by construction: two concurrent inserts for the same item
-- cannot both commit. RESOLVED/REJECTED claims are unaffected, so refiling
-- after resolution/rejection keeps working.
--
-- The application pre-checks in ClaimService.fileClaim and raises a clean
-- IllegalStateException; the index is the backstop for the concurrent race.

CREATE UNIQUE INDEX ux_order_claims_one_active_per_item
    ON order_claims (order_item_id)
    WHERE status IN ('OPEN', 'IN_REVIEW');
