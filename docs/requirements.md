# Requirements

Recurve: recurring billing and subscription management engine.

This document consolidates what has been decided so far. Items marked
**[open]** are not yet defined. It describes behavior, not implementation;
names here are business terms, not code identifiers.

## Actors

| Actor | Description |
|---|---|
| Operator | Person who uses the system. Access limited by granular permissions |
| Subscriber | Customer who subscribes to a plan. Does not access the system |
| Payment gateway | External service that processes charges. **[open]** which one |

## Functional requirements

### FR-01 Operators

- FR-01.1 Register an operator with name, unique email and password.
- FR-01.2 Grant and revoke permissions of an operator.
- FR-01.3 Deactivate an operator without deleting. Inactive operator cannot
  sign in.
- FR-01.4 List operators and their permissions (see FR-06.1).

### FR-02 Plans

- FR-02.1 Register a plan with name and optional description.
- FR-02.2 Register one or more prices for a plan, each with amount,
  currency and billing cycle (monthly, yearly).
- FR-02.3 Deactivate a price. Inactive price accepts no new subscribers;
  existing subscribers stay on it.
- FR-02.4 Deactivate a plan. Inactive plan accepts no new subscribers.
- FR-02.5 List plans with their prices (see FR-06.2).

### FR-03 Subscribers

- FR-03.1 Register a subscriber with name, unique email and a plan price.
- FR-03.2 New subscriber starts as active, with start date set to now and
  next billing date one cycle ahead, according to the chosen price.
- FR-03.3 Cancel a subscriber. Status becomes canceled and cancellation
  date is recorded. No further charges are generated.
- FR-03.4 List subscribers (see FR-06.3).
- FR-03.5 **[open]** Move a subscriber to another price (upgrade/downgrade).
- FR-03.6 **[open]** Reactivate a canceled subscriber.

### FR-04 Payments

- FR-04.1 Generate a pending charge for a subscriber when the next billing
  date is reached. Amount and currency are copied from the price at that
  moment.
- FR-04.2 Confirm a payment: status becomes paid, payment date is
  recorded, subscriber becomes or stays active, next billing date advances
  one cycle.
- FR-04.3 Record a failure: status becomes failed. Subscriber becomes past
  due.
- FR-04.4 Refund a paid payment: status becomes refunded.
- FR-04.5 Record a manual payment (no gateway involved).
- FR-04.6 List payments by subscriber and by status.
- FR-04.7 **[open]** Gateway integration: create charge, receive
  confirmation/failure webhook, match the payment by the gateway's
  transaction id.
- FR-04.8 **[open]** Retry policy and grace period before a past-due
  subscriber is canceled.

### FR-05 Authentication and authorization

- FR-05.1 Operator signs in with email and password.
- FR-05.2 Every operation requires the corresponding permission (see BR-01).
- FR-05.3 Sessionless: every request carries the operator's credentials
  via HTTP Basic. A token mechanism is deferred until there is a client
  that needs one.

### FR-06 Search, filter and sort

Applies to listings. Search is free text, case-insensitive, by substring.
Filters combine with AND; multiple values of the same filter combine with
OR. Sort accepts one field and a direction (ascending/descending); a field
outside the allowed list is a validation error. Every listing is paginated
(FR-07).

#### FR-06.1 Operators

- Search by name and email.
- Filter by active/inactive; absent, the filter matches both.
- Sort by name, email or creation date.
- Default sort: name ascending.

#### FR-06.2 Plans

- Search by name.
- Filter by billing cycle: plan that has at least one **active** price with
  the given cycle.
- Sort by number of subscriptions (see BR-10).
- **[open]** Sort by price. A plan has many prices; depends on fixing cycle
  and currency.
- Default sort: name ascending.

#### FR-06.3 Subscribers

- Search by name and email.
- Filter by status (multiple).
- Filter by plan: subscriber whose price belongs to the plan.
- Sort by start date.
- Sort by billed amount: amount of the price the subscriber is linked to.
- Default sort: start date descending.

### FR-07 Pagination

Applies to every listing (operators, plans, subscribers, payments).
Offset-based.

- FR-07.1 Parameters: page number (zero-based, default 0) and page size
  (default 20, maximum 100). Out-of-range value is a validation error.
- FR-07.2 Response contains the page items, page number, page size and
  total count (after search and filter, before pagination).
- FR-07.3 Page beyond the end returns an empty list with the correct
  total; not an error.
- FR-07.4 Stable ordering: ties on the sort field are broken by a fixed
  secondary key, so an item never repeats or disappears between pages.
- FR-07.5 Search, filter and sort (FR-06) apply before pagination.

## Business rules

- BR-01 Permissions per resource: a view permission and a manage
  permission for each of operators, plans, subscribers and payments.
  Manage implies view.
- BR-02 Operator email is unique. Subscriber email is unique.
- BR-03 A plan has no price of its own; amount and cycle live in the
  price. A plan may have several active prices, at most one per
  (cycle, currency).
- BR-04 Changing a price never edits an existing one: a new price is
  created and the old one deactivated. History is preserved.
- BR-05 A payment stores a snapshot of amount and currency. Changing a
  price does not alter charges already generated.
- BR-06 Next billing date is persisted state, not derived from start date.
  It changes only by a domain event (payment confirmed, price change,
  reactivation).
- BR-07 A canceled subscriber generates no charge.
- BR-08 Only a paid payment can be refunded.
- BR-09 An inactive operator can neither sign in nor perform operations.
- BR-10 A plan's number of subscriptions counts active and past-due
  subscribers; canceled ones do not count.

## Subscriber status

| Status | Meaning |
|---|---|
| Active | up to date |
| Past due | a charge was due and not paid |
| Canceled | ended; cancellation date recorded |

```
active ──charge fails──► past due ──payment confirmed──► active
                            │
                            └──grace period ends (FR-04.8 open)──► canceled
```

## Payment status

| Status | Meaning |
|---|---|
| Pending | created, awaiting payment |
| Paid | confirmed, payment date recorded |
| Failed | gateway declined or due date passed without payment |
| Refunded | reversed |

## Non-functional requirements

- NFR-01 Backend: Spring Boot, Java 25, PostgreSQL.
- NFR-02 Layered architecture, organized by feature. Documented separately
  in the server.
- NFR-03 Configuration via environment variables; no real credential in
  the repository.
- NFR-04 Operator password stored as a BCrypt hash. A password longer
  than 72 bytes is rejected rather than silently truncated.
- NFR-05 Timestamps in UTC.
- NFR-06 Monetary amounts with two decimal places; currency as ISO 4217
  code.
- NFR-07 Business logic testable without database and without HTTP.
- NFR-08 Schema migrations with Flyway. The migration is the source of
  truth; the application only validates the mapping against it and never
  alters the schema on its own.
- NFR-09 **[open]** Frontend: stack and scope.

## Out of scope for now

- Multiple tenants / companies.
- Coupons, discounts, trial.
- Subscriber notifications (email, WhatsApp).
- Invoicing.
