-- Scope: the charge for a subscriber's cycle (FR-04) and nothing else.
--
-- A payment points at the subscriber it charges: by id in the entity, by
-- foreign key here. Amount and currency are a snapshot of the price (BR-05).
-- Nothing in Recurve deletes a payment, so no ON DELETE.

CREATE TABLE payments (
    id            uuid          PRIMARY KEY,
    subscriber_id uuid          NOT NULL REFERENCES subscribers (id),
    amount        numeric(12,2) NOT NULL CHECK (amount > 0),
    currency      varchar(3)    NOT NULL,
    status        varchar(10)   NOT NULL,
    due_at        timestamptz   NOT NULL,
    paid_at       timestamptz,
    refunded_at   timestamptz,
    external_id   varchar(64),
    invoice_url   varchar(500),
    version       bigint        NOT NULL,
    created_at    timestamptz   NOT NULL,
    -- One charge per cycle. Its prefix also serves "the payments of a
    -- subscriber" (FR-04.6), so subscriber_id needs no index of its own.
    CONSTRAINT uq_payments_cycle UNIQUE (subscriber_id, due_at),
    -- One payment per gateway charge; serves the webhook's lookup (FR-04.7).
    CONSTRAINT uq_payments_external_id UNIQUE (external_id),
    -- A payment date exactly when it was paid, a refund date exactly when refunded.
    CONSTRAINT ck_payments_paid CHECK ((status IN ('PAID', 'REFUNDED')) = (paid_at IS NOT NULL)),
    CONSTRAINT ck_payments_refunded CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);

-- FR-04.6: payments by status.
CREATE INDEX ix_payments_status ON payments (status);
