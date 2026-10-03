-- Scope: the subscriber (FR-03) and nothing else.
--
-- A subscriber points at the price it pays, which belongs to the plan
-- aggregate: by id in the entity, by foreign key here. Nothing in Recurve
-- deletes a subscriber or a price, so no ON DELETE.

CREATE TABLE subscribers (
    id              uuid         PRIMARY KEY,
    plan_price_id   uuid         NOT NULL REFERENCES plan_prices (id),
    name            varchar(120) NOT NULL,
    email           varchar(255) NOT NULL,
    status          varchar(10)  NOT NULL,
    started_at      timestamptz  NOT NULL,
    next_billing_at timestamptz  NOT NULL,
    canceled_at     timestamptz,
    created_at      timestamptz  NOT NULL,
    -- BR-02, across every status: a canceled subscriber keeps its email.
    CONSTRAINT uq_subscribers_email UNIQUE (email),
    -- FR-03.3: a cancellation date exactly when canceled.
    CONSTRAINT ck_subscribers_canceled CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL))
);

-- FK; the subscribers of a price, and through it of a plan.
CREATE INDEX ix_subscribers_plan_price_id ON subscribers (plan_price_id);

-- The billing job (FR-04.1) and the subscription count (BR-10).
CREATE INDEX ix_subscribers_status ON subscribers (status);
CREATE INDEX ix_subscribers_next_billing_at ON subscribers (next_billing_at);
