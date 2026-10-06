-- Phase 7 (order context/query performance review): composite indexes for the
-- hot order-read paths. The single-column FK indexes from V1 stay in place;
-- these composites let Postgres satisfy the ORDER BY ... LIMIT queries with
-- an index scan and no separate sort step.
--
-- findFirstByOrderIdOrderByCreatedAtDesc (latest payment per order - read on
-- almost every order-context and procedure turn):
CREATE INDEX ix_payments_order_created_desc ON payments(order_id, created_at DESC);

-- findByCustomerIdOrderByPlacedAtDesc (recent-orders listing, DB-level limit):
CREATE INDEX ix_orders_customer_placed_desc ON orders(customer_id, placed_at DESC);

-- existsByOrderItemIdAndStatusIn (active-claim write-boundary pre-check with
-- caller-supplied statuses). The V7 partial unique index only covers
-- OPEN/IN_REVIEW; this plain index serves the lookup for any status set.
CREATE INDEX ix_order_claims_order_item_id ON order_claims(order_item_id);
