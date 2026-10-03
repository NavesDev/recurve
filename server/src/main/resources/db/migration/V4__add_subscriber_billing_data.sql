-- Scope: what the payment gateway needs to know of a subscriber (FR-04.7).
--
-- The gateway requires a tax document of every customer. Required by the
-- domain from now on; nullable here because subscribers registered before
-- have none, and cannot be charged until someone fills it in.

ALTER TABLE subscribers ADD COLUMN document varchar(14);

-- The subscriber's customer at the gateway, from the first charge sent.
ALTER TABLE subscribers ADD COLUMN gateway_customer_id varchar(64);
