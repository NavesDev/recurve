# Plans and prices — design

Date: 2026-09-30. Branch: `feat/server-plan`.

## Goal

Implement the `plan` feature (FR-02, FR-06.2): the `Plan` aggregate with
its `PlanPrice`s, their write use cases, the search-index listing and the
API contract. It is the next feature in dependency order: `Subscriber`
points at a `PlanPrice`, and `Payment` copies a price's amount. Follows
the patterns the `user` feature set (`server/docs/architecture.md`).

## Decisions

| Question | Decision |
|---|---|
| Changing a price's amount (BR-04) | An explicit route, `POST /api/prices/{priceId}/replace`: creates the successor with the same cycle and currency and deactivates the old price, in one transaction. Never a `PUT`/`PATCH`: the result is a new resource with a new id, and subscribers on the old price must not change. |
| Creating a price that clashes (BR-03) | `POST /api/plans/{planId}/prices` with a (cycle, currency) that already has an active price is a 422. Nothing is replaced by accident. |
| Route shape | Shallow nesting. Only creation is nested under the plan (the price does not exist yet and needs a parent). Acting on an existing price uses its globally unique id alone: `/api/prices/{priceId}`. No redundant `planId` to cross-check. |
| Editing a plan | Allowed: `PUT /api/plans/{id}` with name and description (new FR-02.6). A plan has no amount or cycle, so renaming it charges nobody differently. |
| Deactivating a plan (FR-02.4) | Flips the plan only; prices keep their own state. Accepting a new subscriber requires an active plan **and** an active price. An inactive plan refuses a new price and a replace (422). No reactivation for now. |
| Minimum amount | `price > 0`, at most two decimal places (NFR-06). No free plans for now. |
| Listing filter "has an active price with cycle X" | A derived field in the read model, `activeIntervals`, so the existing `SearchQueries` `terms` filter serves it unchanged. No `nested` mapping. |
| Sort by number of subscriptions (BR-10) | Deferred to the `subscriber` feature, which is what will keep that count. |
| Sort by price | Still **[open]** in the requirements. Not built. |
| Concurrent writes on one plan | Optimistic lock (`@Version` on `Plan`); a lost race is a 409. The partial unique index is the last line for BR-03. |

## Domain

`Plan` is the aggregate root. `PlanPrice` belongs to it and is reached
only through it, because BR-03 (one active price per cycle and currency)
is a rule over the set of a plan's prices.

The architecture says a relationship between aggregates is by id. This is
**one** aggregate, so `Plan` holds its prices by object:
`@OneToMany(mappedBy = "plan", cascade = {PERSIST, MERGE})` with
`@BatchSize`, no `orphanRemoval` (nothing is ever deleted).

```java
Plan.create(name, description, now)
plan.update(name, description)                    // FR-02.6
plan.deactivate()                                 // FR-02.4; PlanAlreadyInactiveException
plan.addPrice(amount, currency, interval, now)    // FR-02.2
    // PlanInactiveException; PriceAlreadyActiveException (BR-03)
plan.replacePrice(priceId, amount, now)           // FR-02.7, BR-04
    // old one deactivated, successor with the same cycle and currency
    // PlanInactiveException; PriceInactiveException if the old one is inactive
plan.deactivatePrice(priceId)                     // FR-02.3; PriceAlreadyInactiveException
```

Each returns or exposes what the service needs; a price lookup by id
inside the plan that misses throws `PriceNotFoundException`. `PlanPrice`
has no public mutator; its only transition, `deactivate()`, is
package-private and called by `Plan`.

`PlanValidator`, beside the entity, as `UserValidator`:

| Attribute | Rule |
|---|---|
| name | required, trimmed, at most 120 |
| description | optional, trimmed, at most 500; blank becomes `null` |
| price | not null, `> 0`, scale at most 2, at most `numeric(12,2)` |
| currency | required, upper-cased, a valid ISO 4217 code (`java.util.Currency`) |
| interval | required |

The request records take their limits from its constants.

`BillingInterval` (`MONTHLY`, `YEARLY`) carries `advance(Instant)` in UTC
(`plusMonths` / `plusYears` on the UTC date-time), which the `subscriber`
feature will use; month-end clamps (31 Jan + 1 month = 28/29 Feb).

`Permission` already has `VIEW_PLANS` and `MANAGE_PLANS` with the
manage-implies-view expansion.

Exceptions, under `plan/domain/exception`:

| Exception | Base | HTTP |
|---|---|---|
| `PlanNotFoundException` | `NotFoundException` | 404 |
| `PriceNotFoundException` | `NotFoundException` | 404 |
| `InvalidPlanException` | `BusinessRuleException` | 422 |
| `PlanInactiveException` | `BusinessRuleException` | 422 |
| `PlanAlreadyInactiveException` | `BusinessRuleException` | 422 |
| `PriceAlreadyActiveException` | `BusinessRuleException` | 422 |
| `PriceInactiveException` | `BusinessRuleException` | 422 |
| `PriceAlreadyInactiveException` | `BusinessRuleException` | 422 |

## Schema

`V2__create_plans.sql`, one migration for the aggregate:

```sql
CREATE TABLE plans (
    id          uuid         PRIMARY KEY,
    name        varchar(120) NOT NULL,
    description varchar(500),
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL,
    created_at  timestamptz  NOT NULL
);
CREATE INDEX ix_plans_active ON plans (active);

CREATE TABLE plan_prices (
    id         uuid          PRIMARY KEY,
    plan_id    uuid          NOT NULL REFERENCES plans (id),
    price      numeric(12,2) NOT NULL CHECK (price > 0),
    currency   char(3)       NOT NULL,
    interval   varchar(10)   NOT NULL,
    active     boolean       NOT NULL,
    created_at timestamptz   NOT NULL
);
CREATE INDEX ix_plan_prices_plan_id ON plan_prices (plan_id);
CREATE UNIQUE INDEX ux_plan_prices_active
    ON plan_prices (plan_id, interval, currency) WHERE active;
```

No `ON DELETE CASCADE`: nothing deletes a plan. The partial unique index
is a backstop for two requests racing past the domain check; the
optimistic lock normally catches that race first.

## Application

`PlanService`, same three-step pattern as `UserService`; every write goes
to PostgreSQL and to the index in one transaction (fail-fast).

```java
Plan create(CreatePlanCommand)           // name, description
Plan update(UpdatePlanCommand)           // id, name, description
Plan deactivate(UUID planId)
Plan findById(UUID planId)
Plan addPrice(AddPriceCommand)           // planId, price, currency, interval
Plan replacePrice(ReplacePriceCommand)   // priceId, price
Plan deactivatePrice(UUID priceId)
Page<PlanSummary> search(SearchFilter, Pageable)
long reindex()
```

Price operations load the owning plan by
`PlanRepository.findByPriceId(priceId)`; none found is
`PriceNotFoundException`. Every operation returns the whole `Plan`, so a
replace answers with the old price inactive and the successor active.

`findActivePrice(priceId)` — what `subscriber` will call to start a
subscription — is **not** built here; it arrives with its caller.

`PlanIndexBootstrap` mirrors `UserIndexBootstrap`, `@Order(2)`.

## Persistence

`PlanRepository extends BaseRepository<Plan, UUID>`:

- `Optional<Plan> findByPriceId(UUID priceId)` — `select p from Plan p join p.prices pr where pr.id = :priceId`.
- `Stream<Plan> streamAll()` — for the reindex.

`PlanSearchRepository`, same surface as `UserSearchRepository`; `q`
matches `name` only (FR-06.2).

### Read model

`PlanSummary` (`plan/domain`), indexed as `plans` with the configurable
prefix:

| Field | Mapping | Can be |
|---|---|---|
| `id` | keyword, `index: false` | sort tie-breaker |
| `name` | text, `prefix` analyzer + `.keyword` | searched by `q`; sorted on `name.keyword` (default) |
| `description` | text, `index: false`, `doc_values: false` | returned only |
| `active` | boolean, `index: false` | filtered, sorted |
| `createdAt` | date, `index: false` | filtered, sorted |
| `activeIntervals` | keyword, `index: false` | filtered: `filter=activeIntervals:MONTHLY` |
| `prices` | object, `enabled: false` | returned only |

`activeIntervals` is the distinct cycles of the plan's **active** prices.
It ignores the plan's own `active`; a caller combines the two filters.
Each price in the document carries its amount as a string (`"59.90"`) so
it never passes through a `double`.

`search/plans-settings.json` repeats the analyzers of
`users-settings.json`. Sharing one settings file across features is a
separate refactor, not part of this work.

## Presentation

`PlanController` (`/api/plans`) and `PriceController` (`/api/prices`):

| Route | Status | Permission |
|---|---|---|
| `POST /api/plans` | 201 + `Location` | `MANAGE_PLANS` |
| `PUT /api/plans/{id}` | 200 | `MANAGE_PLANS` |
| `DELETE /api/plans/{id}` | 200, deactivates | `MANAGE_PLANS` |
| `GET /api/plans/{id}` | 200 | `VIEW_PLANS` |
| `GET /api/plans` | 200, `PageResponse` | `VIEW_PLANS` |
| `POST /api/plans/reindex` | 200 | `MANAGE_SYSTEM` |
| `POST /api/plans/{id}/prices` | 201 | `MANAGE_PLANS` |
| `POST /api/prices/{id}/replace` | 201 | `MANAGE_PLANS` |
| `DELETE /api/prices/{id}` | 200, deactivates | `MANAGE_PLANS` |

A price route answers with the whole plan and no `Location`: a price has
no `GET` of its own. The listing's default sort is `name.keyword`.

`PlanResponse { id, name, description, active, createdAt, prices }`;
`PriceResponse { id, price, currency, interval, active, createdAt }`.
Every price is listed, inactive ones included: the history is the point
of BR-04. `price` is a JSON number with two decimals in the response and
in requests.

`SecurityConfig`, most specific first:

```java
.requestMatchers(HttpMethod.POST, "/api/plans/reindex").hasAuthority("MANAGE_SYSTEM")
.requestMatchers(HttpMethod.GET, "/api/plans/**").hasAuthority("VIEW_PLANS")
.requestMatchers("/api/plans/**", "/api/prices/**").hasAuthority("MANAGE_PLANS")
```

`GlobalExceptionHandler` gains `OptimisticLockingFailureException` → 409
with the `ApiError` body.

## Tests

| Test | Root | Covers |
|---|---|---|
| `PlanTest` | test | create, update, deactivate; `addPrice` on an inactive plan and on a clash (BR-03); `replacePrice` keeps cycle and currency, deactivates the old one, refuses an inactive one; `deactivatePrice` |
| `PlanValidatorTest` | test | each rule in the validator table |
| `BillingIntervalTest` | test | `advance` monthly and yearly, month-end clamp |
| `PlanSummaryTest` | test | `activeIntervals` from active prices only, distinct; amount as string |
| `PlanServiceTest` | test | orchestration, indexing on every write, 404s, a price found through its plan |
| `PlanControllerTest`, `PriceControllerTest` | test | request validation, status, serialization |
| `GlobalExceptionHandlerTest` or the controller test | test | optimistic lock → 409 |
| `PlanRepositoryIT` | testIntegration | `findByPriceId`, `streamAll`, the partial unique index refusing two active prices on one pair |
| `PlanSearchRepositoryIT` | testIntegration | FR-06.2: `q` on name, `activeIntervals` and `active` filters, default sort, paging |
| `PlanIndexBootstrapIT`, `PlanServiceIT` | testIntegration | index created at boot; rollback when indexing fails |
| `PlanEndpointAuthorizationIT` | testIntegration | `VIEW_PLANS` reads and cannot write; `MANAGE_PLANS` writes; reindex needs `MANAGE_SYSTEM`; `/api/prices/**` needs `MANAGE_PLANS` |
| `PlanContractIT` | testIntegration | every real exchange matches `openapi.yaml` |
| `ApiContractTest` | test | existing; now holds the new routes to the contract |

## Documentation

- `openapi.yaml`, first: the `Plan`, `Price` and request schemas, the nine
  routes, one success example each.
- `docs/requirements.md`: FR-02.6 (edit a plan's name and description),
  FR-02.7 (replace a price, BR-04), `price > 0`, an inactive plan refuses
  new prices.
- `server/docs/data-model.md`: `Plan.version`, `description` limit,
  prices held by object inside the aggregate as the stated exception to
  "by id".
- `server/docs/architecture.md`: shallow nesting in the route
  conventions, `V2__create_plans.sql` in the migration table, the plan
  rules in the authorization example.

## Out of scope

- Sort by number of subscriptions (BR-10) and `findActivePrice`: the
  `subscriber` feature.
- Sort by price: **[open]**.
- Reactivating a plan or a price.
