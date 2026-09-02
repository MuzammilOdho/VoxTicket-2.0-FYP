-- Phase 1: core e-commerce domain (spec §22-34). Table set matches spec §59.
-- Flyway owns schema evolution; Hibernate runs with ddl-auto=validate and
-- must never create or alter schema itself (spec §60).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE customers (
                           id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           first_name      VARCHAR(100) NOT NULL,
                           last_name       VARCHAR(100) NOT NULL,
                           email           VARCHAR(255) NOT NULL,
                           phone           VARCHAR(20)  NOT NULL,
                           status          VARCHAR(20)  NOT NULL,
                           created_at      TIMESTAMPTZ  NOT NULL,
                           CONSTRAINT uk_customers_email UNIQUE (email),
                           CONSTRAINT uk_customers_phone UNIQUE (phone)
);

CREATE TABLE orders (
                        id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        order_number        VARCHAR(30)   NOT NULL,
                        customer_id         UUID          NOT NULL REFERENCES customers(id),
                        order_status        VARCHAR(20)   NOT NULL,
                        fulfillment_status  VARCHAR(25)   NOT NULL,
                        currency            VARCHAR(3)    NOT NULL,
                        subtotal            NUMERIC(12,2) NOT NULL,
                        shipping_amount     NUMERIC(12,2) NOT NULL,
                        total_amount        NUMERIC(12,2) NOT NULL,
                        placed_at           TIMESTAMPTZ   NOT NULL,
                        cancelled_at        TIMESTAMPTZ,
                        completed_at        TIMESTAMPTZ,
                        CONSTRAINT uk_orders_order_number UNIQUE (order_number)
);
CREATE INDEX ix_orders_customer_id ON orders(customer_id);

CREATE TABLE order_items (
                             id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                             order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                             product_name    VARCHAR(255)  NOT NULL,
                             sku             VARCHAR(64)   NOT NULL,
                             quantity        INTEGER       NOT NULL CHECK (quantity > 0),
                             unit_price      NUMERIC(12,2) NOT NULL,
                             returnable      BOOLEAN       NOT NULL DEFAULT TRUE,
                             final_sale      BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_order_items_order_id ON order_items(order_id);

CREATE TABLE payments (
                          id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          order_id            UUID NOT NULL REFERENCES orders(id),
                          provider_reference  VARCHAR(64),
                          method              VARCHAR(20)   NOT NULL,
                          amount              NUMERIC(12,2) NOT NULL,
                          currency            VARCHAR(3)    NOT NULL,
                          status              VARCHAR(20)   NOT NULL,
                          authorized_at       TIMESTAMPTZ,
                          captured_at         TIMESTAMPTZ,
                          created_at          TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_payments_order_id ON payments(order_id);

CREATE TABLE shipments (
                           id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           order_id                UUID NOT NULL REFERENCES orders(id),
                           carrier                 VARCHAR(100),
                           tracking_number         VARCHAR(100),
                           status                  VARCHAR(20) NOT NULL,
                           shipped_at              TIMESTAMPTZ,
                           estimated_delivery_at   TIMESTAMPTZ,
                           delivered_at            TIMESTAMPTZ,
                           updated_at              TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_shipments_order_id ON shipments(order_id);

CREATE TABLE returns (
                         id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         return_number   VARCHAR(30) NOT NULL,
                         order_id        UUID NOT NULL REFERENCES orders(id),
                         status          VARCHAR(20) NOT NULL,
                         reason          VARCHAR(30) NOT NULL,
                         requested_at    TIMESTAMPTZ NOT NULL,
                         approved_at     TIMESTAMPTZ,
                         received_at     TIMESTAMPTZ,
                         inspected_at    TIMESTAMPTZ,
                         completed_at    TIMESTAMPTZ,
                         CONSTRAINT uk_returns_return_number UNIQUE (return_number)
);
CREATE INDEX ix_returns_order_id ON returns(order_id);

CREATE TABLE return_items (
                              id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              return_id       UUID NOT NULL REFERENCES returns(id) ON DELETE CASCADE,
                              order_item_id   UUID NOT NULL REFERENCES order_items(id),
                              quantity        INTEGER NOT NULL CHECK (quantity > 0),
                              reason          VARCHAR(30) NOT NULL,
                              condition       VARCHAR(20)
);
CREATE INDEX ix_return_items_return_id ON return_items(return_id);
CREATE INDEX ix_return_items_order_item_id ON return_items(order_item_id);

CREATE TABLE refunds (
                         id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         refund_number       VARCHAR(30) NOT NULL,
                         order_id            UUID NOT NULL REFERENCES orders(id),
                         payment_id          UUID NOT NULL REFERENCES payments(id),
                         return_id           UUID REFERENCES returns(id),
                         amount              NUMERIC(12,2) NOT NULL,
                         reason              VARCHAR(20) NOT NULL,
                         status              VARCHAR(20) NOT NULL,
                         provider_reference  VARCHAR(64),
                         initiated_at        TIMESTAMPTZ NOT NULL,
                         completed_at        TIMESTAMPTZ,
                         failed_at           TIMESTAMPTZ,
                         CONSTRAINT uk_refunds_refund_number UNIQUE (refund_number)
);
CREATE INDEX ix_refunds_order_id ON refunds(order_id);
CREATE INDEX ix_refunds_payment_id ON refunds(payment_id);

CREATE TABLE order_claims (
                              id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              claim_number            VARCHAR(30) NOT NULL,
                              order_id                UUID NOT NULL REFERENCES orders(id),
                              order_item_id           UUID NOT NULL REFERENCES order_items(id),
                              reason                  VARCHAR(20) NOT NULL,
                              requested_resolution    VARCHAR(20) NOT NULL,
                              status                  VARCHAR(20) NOT NULL,
                              support_ticket_id       UUID,
                              created_at              TIMESTAMPTZ NOT NULL,
                              CONSTRAINT uk_order_claims_claim_number UNIQUE (claim_number)
);
CREATE INDEX ix_order_claims_order_id ON order_claims(order_id);

CREATE TABLE support_tickets (
                                 id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 ticket_number   VARCHAR(30) NOT NULL,
                                 customer_id     UUID NOT NULL REFERENCES customers(id),
                                 order_id        UUID REFERENCES orders(id),
                                 category        VARCHAR(20) NOT NULL,
                                 priority        VARCHAR(10) NOT NULL,
                                 status          VARCHAR(15) NOT NULL,
                                 summary         TEXT NOT NULL,
                                 created_at      TIMESTAMPTZ NOT NULL,
                                 resolved_at     TIMESTAMPTZ,
                                 CONSTRAINT uk_support_tickets_ticket_number UNIQUE (ticket_number)
);
CREATE INDEX ix_support_tickets_customer_id ON support_tickets(customer_id);

ALTER TABLE order_claims
    ADD CONSTRAINT fk_order_claims_support_ticket
        FOREIGN KEY (support_ticket_id) REFERENCES support_tickets(id);