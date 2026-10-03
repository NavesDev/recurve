# Subscribers — design

Date: 2026-10-03. Branch: `feat/server-subscriber`.

## Goal

Implement the `subscriber` feature (FR-03, FR-06.3): the `Subscriber`
entity, its write use cases, the search-index listing and the API
contract. It is the next feature in dependency order: `Payment` points at
a `Subscriber`. Follows the patterns the `user` and `plan` features set
(`server/docs/architecture.md`).

## Decisions

| Question | Decision |
|---|---|
| Scope | The whole feature, as `plan` did: domain, schema, use cases, search index, HTTP, contract, docs. |
| Email uniqueness (BR-02) | Unique across every status. A canceled subscriber keeps the email; a returning customer is FR-03.6 (reactivation, still **[open]**). Canonical form is trimmed and lower-cased, as for operators. |
| Editing a subscriber | Allowed: `PUT /api/subscribers/{id}` with name and email (new FR-03.7). A canceled subscriber refuses it (422): canceled is final until FR-03.6 exists. |
| Payment data on the subscriber | None. The gateway is **[open]** (FR-04.7). When it is chosen, a new migration adds the gateway's customer id (and a tax document, if the gateway needs one for Pix or boleto). Card data is never stored by Recurve. |
| `PAST_DUE` | In the enum, but nothing moves a subscriber there yet: that is FR-04.3, with `payment`. `confirmPayment` arrives with `payment` too. |
| Concurrent writes | No `@Version`: no rule spans a set, and two cancels racing end the same. The unique index is the backstop for two creations racing on one email; it answers 409. |
| Read model for FR-06.3 | Denormalized. The summary copies `planId`, `price`, `currency` and `interval` from the subscriber's price at write time. Safe because a `PlanPrice` never changes amount, currency, cycle or plan (BR-04); only its `active` flag changes, and the listing does not show it. So no write in `plan` has to touch the subscriber index. The plan's **name** is not copied: renaming a plan (FR-02.6) would then cascade. |
| Sort by billed amount | `price` mapped as `scaled_float` (`scaling_factor: 100`), written as a string so it never passes through a `double`. Sorting mixes currencies; combining with `filter=currency:BRL` is the caller's choice. |
| "May this price take a new subscriber" | A rule of the `Plan` aggregate (`plan.subscribablePrice`), not of the service: active plan **and** active price (FR-02.3, FR-02.4). |
| Cancel route | `DELETE /api/subscribers/{id}` cancels, consistent with `DELETE` deactivating in `user` and `plan`. Nothing is deleted. |
| What a use case returns | `SubscriberSummary`, not the entity. The response shows the price's plan, amount and cycle, and a controller may not call the plan feature; the summary already carries them. |
| Default sort `startedAt` descending | `@Listing(defaultSort)` is spelled as `sort` is, so `"startedAt:desc"`. Until now a default was always ascending. |
| Unique violation in the database | `DataIntegrityViolationException` → 409 in `GlobalExceptionHandler`. Today it is a 500 in `user` too; this fixes both. |

## Domain

`Subscriber` (`subscriber/domain`) references its price by id
(`planPriceId`), as every relationship between aggregates does.

```java
Subscriber.start(name, email, PlanPrice price, now)
    // status ACTIVE, startedAt = createdAt = now,
    // nextBillingAt = price.getInterval().advance(now)          FR-03.2
subscriber.update(name, email)    // FR-03.7; SubscriberCanceledException when canceled
subscriber.cancel(now)            // FR-03.3; SubscriberAlreadyCanceledException
```

Every attribute goes through `SubscriberValidator` on creation and on
every change, as `UserValidator` does for operators:

| Attribute | Rule |
|---|---|
| name | required, trimmed, at most 120 |
| email | required, canonical form (`normalizeEmail`: trimmed, lower-cased), at most 255, `^[^@\s]+@[^@\s]+$` |

The email rules repeat `UserValidator`'s. Moving them to one shared place
is a separate refactor, not part of this work. The request records take
their limits from the validator's constants.

`SubscriberStatus` (`ACTIVE`, `PAST_DUE`, `CANCELED`), persisted as a
string.

`Plan` gains:

```java
public PlanPrice subscribablePrice(UUID priceId)
    // PriceNotFoundException; PlanInactiveException; PriceInactiveException
```

Exceptions, under `subscriber/domain/exception`:

| Exception | Base | HTTP |
|---|---|---|
| `SubscriberNotFoundException` | `NotFoundException` | 404 |
| `InvalidSubscriberException` | `BusinessRuleException` | 422 |
| `SubscriberEmailAlreadyInUseException` | `BusinessRuleException` | 422 |
| `SubscriberAlreadyCanceledException` | `BusinessRuleException` | 422 |
| `SubscriberCanceledException` | `BusinessRuleException` | 422 |

## Schema

`V3__create_subscribers.sql`:

```sql
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
    CONSTRAINT uq_subscribers_email UNIQUE (email),
    CONSTRAINT ck_subscribers_canceled CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL))
);
CREATE INDEX ix_subscribers_plan_price_id   ON subscribers (plan_price_id);
CREATE INDEX ix_subscribers_status          ON subscribers (status);
CREATE INDEX ix_subscribers_next_billing_at ON subscribers (next_billing_at);
```

Departures from `data-model.md`, recorded there by this work:

- No index on `started_at`. The default sort is served by the search
  index; PostgreSQL never sorts by it.
- The `CHECK` ties `canceled_at` to the `CANCELED` status in the schema
  too.

`status` and `next_billing_at` are indexed for the billing job (FR-04.1)
and the subscription count (BR-10), both still to come; `data-model.md`
already lists them.

## Application

`SubscriberService`, the same three-step pattern as `PlanService`; every
write goes to PostgreSQL and to the index in one transaction (fail-fast).

```java
SubscriberSummary create(CreateSubscriberCommand)   // name, email, planPriceId
SubscriberSummary update(UpdateSubscriberCommand)   // id, name, email
SubscriberSummary cancel(UUID id)
SubscriberSummary findById(UUID id)
Page<SubscriberSummary> search(SearchFilter, Pageable)
long reindex()
```

- `create`: the email in canonical form → `existsByEmail` (BR-02) →
  `planService.findForSubscription(planPriceId)` → `Subscriber.start` →
  persist and index `SubscriberSummary.of(subscriber, plan)`.
- `update`: load → if the email changed, `existsByEmailAndIdNot` →
  `subscriber.update` → persist and index.
- `cancel`: load → `subscriber.cancel` → persist and index.
- `update` and `cancel` load the plan through
  `planService.findByPriceIds(Set.of(planPriceId))`. Whether the price is
  still active does not matter: an existing subscriber stays on it
  (FR-02.3).
- `reindex`: streams every subscriber in batches of 500, resolving each
  batch's plans with one `findByPriceIds`.

`PlanService` gains:

```java
Plan findForSubscription(UUID priceId)          // plan.subscribablePrice(priceId) or throws
Map<UUID, Plan> findByPriceIds(Set<UUID> ids)   // price id → its plan; a price nobody holds throws
```

Both return the `Plan`, not the `PlanPrice`: the summary needs the
plan's id, and a price holds no reference back to its plan.
`PlanRepository` gains the query behind `findByPriceIds`.

`SubscriberIndexBootstrap` mirrors `PlanIndexBootstrap`, `@Order(1)`.

## Persistence

`SubscriberRepository extends BaseRepository<Subscriber, UUID>`:

- `boolean existsByEmail(String email)`
- `boolean existsByEmailAndIdNot(String email, UUID id)`
- `Stream<Subscriber> streamAll()`

`SubscriberSearchRepository`, the same surface as `PlanSearchRepository`;
`q` matches `name` and `email` (FR-06.3).

### Read model

`SubscriberSummary` (`subscriber/domain`), indexed as `subscribers` with
the configurable prefix:

| Field | Mapping | Can be |
|---|---|---|
| `id` | keyword, `index: false` | sort tie-breaker |
| `name` | text, `prefix` analyzer + `.keyword` | searched by `q`; sorted on `name.keyword` |
| `email` | text, `prefix` analyzer + `.keyword` | searched by `q`; sorted on `email.keyword` |
| `status` | keyword, `index: false` | filtered (several values: OR) |
| `planId` | keyword, `index: false` | filtered: `filter=planId:<uuid>` |
| `planPriceId` | keyword, `index: false` | filtered |
| `price` | `scaled_float`, `scaling_factor: 100`, `index: false` | sorted |
| `currency` | keyword, `index: false` | filtered |
| `interval` | keyword, `index: false` | filtered |
| `startedAt` | date, `index: false` | sorted (default, descending), filtered |
| `nextBillingAt` | date, `index: false` | sorted, filtered |
| `canceledAt` | date, `index: false` | returned only |
| `createdAt` | date, `index: false` | sorted, filtered |

`search/subscribers-settings.json` repeats the analyzers of
`users-settings.json`, as `plans-settings.json` does.

## Presentation

`SubscriberController` (`/api/subscribers`):

| Route | Status | Permission |
|---|---|---|
| `POST /api/subscribers` | 201 + `Location` | `MANAGE_SUBSCRIBERS` |
| `PUT /api/subscribers/{id}` | 200 | `MANAGE_SUBSCRIBERS` |
| `DELETE /api/subscribers/{id}` | 200, cancels | `MANAGE_SUBSCRIBERS` |
| `GET /api/subscribers/{id}` | 200 | `VIEW_SUBSCRIBERS` |
| `GET /api/subscribers` | 200, `PageResponse`, default sort `-startedAt` | `VIEW_SUBSCRIBERS` |
| `POST /api/subscribers/reindex` | 200, `ReindexResponse` | `MANAGE_SYSTEM` |

`SubscriberResponse { id, name, email, status, planId, planPriceId, price,
currency, interval, startedAt, nextBillingAt, canceledAt, createdAt }`,
the same shape for one subscriber and for the listing. `price` is a JSON
number with two decimals.

`SecurityConfig`, most specific first:

```java
.requestMatchers(HttpMethod.POST, "/api/users/reindex", "/api/plans/reindex", "/api/subscribers/reindex")
        .hasAuthority("MANAGE_SYSTEM")
.requestMatchers(HttpMethod.GET, "/api/subscribers/**").hasAuthority("VIEW_SUBSCRIBERS")
.requestMatchers("/api/subscribers/**").hasAuthority("MANAGE_SUBSCRIBERS")
```

`VIEW_SUBSCRIBERS` and `MANAGE_SUBSCRIBERS`, with the manage-implies-view
expansion, already exist in `Permission`.

`GlobalExceptionHandler` gains `DataIntegrityViolationException` → 409
with the `ApiError` body.

## Tests

| Test | Root | Covers |
|---|---|---|
| `SubscriberTest` | test | `start` (status, dates, `nextBillingAt` monthly and yearly); `update`; `update` on a canceled one; `cancel`; canceling twice |
| `SubscriberValidatorTest` | test | each rule in the validator table |
| `PlanTest` (existing) | test | `subscribablePrice`: inactive plan, inactive price, unknown price |
| `SubscriberSummaryTest` | test | fields copied from the price and the plan; amount as string |
| `SubscriberServiceTest` | test | orchestration, indexing on every write, duplicate email on create and on update (keeping one's own email is fine), 404s |
| `PlanServiceTest` (existing) | test | `findForSubscription`, `findByPriceIds` |
| `SubscriberControllerTest` | test | request validation, status, serialization |
| `GlobalExceptionHandlerTest` or a controller test | test | unique violation → 409 |
| `SubscriberRepositoryIT` | testIntegration | `existsByEmail*`, `streamAll`, the unique email, the cancellation `CHECK`, the price FK |
| `SubscriberSearchRepositoryIT` | testIntegration | FR-06.3: `q` on name and email, status filter with several values, `planId` filter, sort by `price` and by `startedAt`, default sort, paging |
| `SubscriberIndexBootstrapIT`, `SubscriberServiceIT` | testIntegration | index created at boot; rollback when indexing fails |
| `SubscriberEndpointAuthorizationIT` | testIntegration | `VIEW_SUBSCRIBERS` reads and cannot write; `MANAGE_SUBSCRIBERS` writes; reindex needs `MANAGE_SYSTEM` |
| `SubscriberContractIT` | testIntegration | every real exchange matches `openapi.yaml` |
| `ApiContractTest` (existing) | test | holds the new routes to the contract |

## Documentation

- `openapi.yaml`, first: the `Subscriber` and request schemas, the six
  routes, one success example each.
- `docs/requirements.md`: FR-03.7 (edit name and email; a canceled
  subscriber refuses it); in FR-06.3, that sorting by amount mixes
  currencies.
- `server/docs/data-model.md`: no `started_at` index, the cancellation
  `CHECK`, `V3` in the migration paragraph, payment data deferred to
  FR-04.7.
- `server/docs/architecture.md`: the subscriber rules in the
  authorization example, `V3__create_subscribers.sql` in the migration
  table, the `Subscriber` sketch brought in line with the code.

## Out of scope

- BR-10: the subscription count in `PlanSummary` and sorting plans by it.
- FR-03.5 (move to another price) and FR-03.6 (reactivate).
- `confirmPayment`, the move to `PAST_DUE`, the billing job: `payment`.
- Gateway customer id and tax document: FR-04.7.
- One shared place for email validation.
