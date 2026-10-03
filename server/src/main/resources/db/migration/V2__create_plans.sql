-- Scope: the plan aggregate (FR-02) and nothing else.
--
-- plan_prices belongs to that aggregate: a price has no meaning apart from
-- its plan, and BR-03 is a rule over a plan's set of prices. Nothing in
-- Recurve deletes a plan or a price, so no ON DELETE.

CREATE TABLE plans (
    id          uuid         PRIMARY KEY,
    name        varchar(120) NOT NULL,
    description varchar(500),
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL,
    created_at  timestamptz  NOT NULL
);

-- FR-02.4: an inactive plan accepts no new subscriber.
CREATE INDEX ix_plans_active ON plans (active);

CREATE TABLE plan_prices (
    id               uuid          PRIMARY KEY,
    plan_id          uuid          NOT NULL REFERENCES plans (id),
    price            numeric(12,2) NOT NULL CHECK (price > 0),
    currency         varchar(3)    NOT NULL,
    billing_interval varchar(10)   NOT NULL,
    active           boolean       NOT NULL,
    created_at       timestamptz   NOT NULL
);

-- FK; a plan's prices.
CREATE INDEX ix_plan_prices_plan_id ON plan_prices (plan_id);

-- BR-03: at most one active price per (plan, cycle, currency). Deferred to
-- commit, not checked per statement: a replace (BR-04) deactivates the old
-- price and inserts its successor in one transaction, and Hibernate flushes
-- the insert first. An exclusion constraint rather than a partial unique
-- index because only a constraint can be deferred; on equality alone it
-- means exactly what the unique index would.
ALTER TABLE plan_prices ADD CONSTRAINT ex_plan_prices_one_active
    EXCLUDE USING btree (plan_id WITH =, billing_interval WITH =, currency WITH =)
    WHERE (active)
    DEFERRABLE INITIALLY DEFERRED;
