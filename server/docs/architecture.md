# Architecture

Recurve uses a **layered architecture, organized by feature**. Each feature
is a self-contained package with four layers: presentation, application,
domain and persistence.

Principles:

- The JPA entity is the domain model. Business rules live in entity
  methods, not in the service.
- The service is the feature's single entry point. Controller, scheduler
  and other features talk only to the service.
- Interfaces only where swapping the implementation is plausible: external
  services (payment gateway, email). Repositories are plain Spring Data.
- Authorization at the HTTP boundary: one rule per route in
  `SecurityConfig`. Services carry no check.
- Two stores. PostgreSQL holds the write model and is the source of
  truth; Elasticsearch holds a read model per listing and serves every
  search, filter, sort and page (FR-06, FR-07). Nothing above the
  service knows there are two.
- Fail-fast. The system prefers to stop and make noise over continuing
  inconsistently in silence.

## Layers

```
┌────────────────────────────────────────────────────────────┐
│  PRESENTATION                                 controller/  │
│  Receives external input, validates shape, converts to a   │
│  command. Converts the result to external output.          │
└──────────────────────────┬─────────────────────────────────┘
                           ▼
┌────────────────────────────────────────────────────────────┐
│  APPLICATION                                     service/  │
│  One method per use case. Transaction and authorization.   │
│  Loads, calls the domain, persists. Coordinates; holds no  │
│  business rule.                                            │
└──────────────┬────────────────────────────────┬────────────┘
               ▼                                ▼
┌────────────────────────────┐   ┌──────────────────────────────┐
│  DOMAIN            domain/ │◄──│  PERSISTENCE     repository/ │
│  Model + rules. State      │   │  Stores and loads the model. │
│  changes only through      │   │  Turns filters into queries. │
│  business methods. Depends │   │  Knows no rule.              │
│  on nothing.               │   │                              │
└────────────────────────────┘   └──────────────────────────────┘
```

Arrows only point downward.

| Layer | Input | Output | May import | Forbidden |
|---|---|---|---|---|
| Presentation | external protocol (HTTP, JSON) | external protocol | Application, Domain | Persistence |
| Application | command, id, filter | domain model | Domain, Persistence, another feature's Application | Presentation |
| Domain | primitive values, another model | model, business exception | nothing from the feature | any layer |
| Persistence | model, filter, `Pageable` | model, read model | Domain | Application, Presentation |

## Layout

```
src/main/java/com/navesdev/recurve/
├── RecurveApplication.java
├── shared/               # cross-cutting; imports no feature
├── user/
├── plan/
├── payment/
│   └── gateway/          # external service interface + impl
└── subscriber/
    ├── domain/           # @Entity with rules, enums
    │   └── exception/    # the feature's business exceptions
    ├── repository/       # Spring Data, Specifications
    ├── service/          # Service, Commands, Filters
    └── controller/       # Controller, Request, Response
```

Every feature follows the four subpackages. An extra subpackage only for
external service adapters (`payment/gateway/`).

Cross-cutting code lives in `shared/`, laid out in the same layers as a
feature so the dependency rule reads the same way:

```
shared/
├── config/               # SecurityConfig, WebConfig, DocsConfig, ClockConfig
├── controller/           # GlobalExceptionHandler, ApiError, PageResponse, the listing argument
├── repository/           # BaseRepository, the contract every repository follows
└── domain/exception/     # the abstract exception bases
```

`shared/` is not a feature: it holds no state, no use case and no business
rule, and it never imports a feature. Features import it, never the
reverse. What lives here is either infrastructure or a contract —
`BaseRepository` is `@NoRepositoryBean`, a type to conform to rather than
a repository of its own. Anything that starts wanting business rules
belongs in a feature instead.

The schema lives outside Java, in `src/main/resources/db/migration`.

## Each layer in detail

### Domain (`domain/`)

Mutable `@Entity`, but mutation only through business methods. No public
setters.

- Constructor or static factory validates invariants:
  `User.create(name, email, passwordHash, now)`. The rules themselves
  live in a `<Entity>Validator` next to the entity (`UserValidator`):
  required, bounded by the column, an email that is one, in its
  canonical form. Every attribute goes through it on creation and on
  every change, so an entity that exists is a valid one **whoever built
  it** — the API, the startup bootstrap, a caller not written yet. Bean
  Validation on the request repeats the same limits for a different
  reason: the edge answers "what did the caller get wrong", field by
  field in one 400; the validator answers "can this exist". The request
  records take their limits from the validator's constants, so the two
  cannot drift.
- One canonical form per value that gets compared. An email is trimmed
  and lower-cased by `UserValidator.normalizeEmail`, and that one method
  serves the entity, the uniqueness check and the login lookup; no two
  places can disagree on whether two spellings are the same operator.
- State transitions are methods with business names: `subscriber.cancel(now)`,
  `subscriber.confirmPayment(interval)`, `plan.deactivate()`.
- A method throws a business exception when a rule is violated:
  `payment.refund()` on a status other than `PAID` throws
  `PaymentNotRefundableException`.
- A rule that needs external data (unique email) lives in the service,
  because the entity does not query the database.
- Time comes in as a parameter (`Instant now`). The entity never calls
  `Instant.now()`.

JPA in the domain: **mapping annotations only** (`@Entity`, `@Table`,
`@Column`, `@Id`, `@Enumerated`). No `EntityManager`, `@Query`,
`@Transactional`. The domain knows it is persisted; it does not know how.

A listing's read model lives here too — `UserSummary`, a record of what
the listing shows, with `@Document` and its mapping annotations and a
factory from the entity. It is a projection: it holds no rule and can be
rebuilt from the entity at any time. The same discipline applies as for
JPA: mapping annotations only, and the password hash has no field.

```java
@Entity
@Table(name = "subscribers")
public class Subscriber {

    // ... fields, protected constructor for JPA

    public static Subscriber start(String name, String email, PlanPrice price, Instant now) {
        var s = new Subscriber();
        s.id = UUID.randomUUID();
        s.planPriceId = price.getId();
        s.name = name;
        s.email = email;
        s.status = SubscriberStatus.ACTIVE;
        s.startedAt = now;
        s.nextBillingAt = price.getInterval().advance(now);
        s.createdAt = now;
        return s;
    }

    public void cancel(Instant now) {
        if (status == SubscriberStatus.CANCELED) {
            throw new SubscriberAlreadyCanceledException(id);
        }
        status = SubscriberStatus.CANCELED;
        canceledAt = now;
    }

    public void confirmPayment(BillingInterval interval) {
        status = SubscriberStatus.ACTIVE;
        nextBillingAt = interval.advance(nextBillingAt);
    }
}
```

### Persistence (`repository/`)

Two repositories per listed feature.

The JPA repository is a Spring Data interface extending
`shared/repository/BaseRepository`, which fixes the minimum surface —
`save`, `findById`, `existsById`, `count` — and stops there.
`BaseRepository` deliberately does not extend `JpaRepository`. Nothing in
Recurve deletes a record: an operator is deactivated, a plan and a price
are deactivated, a subscriber is canceled. A repository that offers no
`deleteAll` makes that a property of the type rather than of everyone's
discipline. It also offers no listing: every listing is paginated (FR-07)
and served from the search index. A feature's JPA repository adds only
what its use cases need — a lookup by a natural key, an existence check,
a stream of everything for rebuilding the index.

The search repository (`UserSearchRepository`) is a class over
`ElasticsearchOperations` with the same narrow spirit: `save`, `saveAll`,
`search`, `indexExists`, `recreateIndex`, `refresh`. No single-document
delete; an index is only ever rebuilt whole. The query itself comes from
`shared/repository/SearchQueries`, the same for every feature: the
repository only names the fields its free text matches against. Never
business logic; never calls an entity's business method.

The index's settings and mapping live outside Java, in
`src/main/resources/search/`, next to the schema in `db/migration`. They
are applied by the feature's `UserIndexBootstrap` at startup, which
creates a missing index and fills it from the database; an existing index
is left alone, and a mapping change is a deliberate rebuild through the
feature's reindex endpoint (`MANAGE_SYSTEM`).

### Application (`service/`)

`@Service`, `@Transactional`. One method per use case.

Three-step pattern for every state-changing use case:

```
1. repository.findById(id)                    load      (skipped on creation)
2. entity.businessMethod()                    domain changes state, or throws
3. repository.save(entity)                    persist
   searchRepository.save(Summary.of(entity))  index, same transaction
```

`save()` is always explicit, even on updates. For a managed entity it is a
no-op, but it keeps the intent visible without relying on dirty checking.
If step 2 throws, step 3 does not run and the transaction rolls back.
If indexing throws, the transaction rolls back too: the database and the
index never diverge, and the caller gets a 503 rather than a listing that
quietly stopped matching the data.

Before step 2 the service also:

- Validates rules that depend on the database or on another entity
  (email uniqueness, active price).
- Fetches from another feature whatever the domain rule needs
  (`planService.findActivePrice`).

Does not: validate format (Bean Validation on the request), serialize
HTTP, hold `if`s for rules that belong in the entity.

```java
@Service
@Transactional
public class SubscriberService {

    public Subscriber create(CreateSubscriberCommand cmd) {
        if (repository.existsByEmail(cmd.email())) {
            throw new EmailAlreadyInUseException(cmd.email());
        }
        PlanPrice price = planService.findActivePrice(cmd.planPriceId());
        Subscriber subscriber = Subscriber.start(cmd.name(), cmd.email(), price, clock.instant());
        return repository.save(subscriber);
    }

    public Subscriber cancel(UUID id) {
        Subscriber subscriber = findOrThrow(id);   // 1
        subscriber.cancel(clock.instant());        // 2
        return repository.save(subscriber);        // 3
    }
}
```

Service input is a command `record` (`CreateSubscriberCommand`), ids, or a
filter `record`. An HTTP request never reaches the service. Output is the
entity (or `Page<Entity>`).

### Presentation (`controller/`)

`@RestController`. Converts HTTP into a service call and the result into a
response. No rules, no authorization (decided in `SecurityConfig` before
the request gets here).

- Request: `record` with Bean Validation, `toCommand()` method.
- Response: `record` with a `from(entity)` factory. Reads entity getters,
  never calls a business method.
- Listing: one argument, `@Listing(defaultSort = "...") ListingRequest`,
  resolved by `shared/controller/ListingRequestResolver` from the five
  query parameters every listing shares; the controller only names the
  feature's default sort field and gets back a `SearchFilter` and a
  `Pageable`. Response as `PageResponse<T>`.

  ```java
  @GetMapping
  public PageResponse<PlanResponse> search(@Listing(defaultSort = "name.keyword") ListingRequest listing) {
      return PageResponse.from(service.search(listing.filter(), listing.pageable()), PlanResponse::from);
  }
  ```

## Authorization

Spring Security, HTTP Basic, and **one rule per route family** in
`SecurityConfig`:

```java
.requestMatchers(HttpMethod.POST, "/api/users/reindex").hasAuthority("MANAGE_SYSTEM")
.requestMatchers(HttpMethod.GET, "/api/users/**").hasAuthority("VIEW_USERS")
.requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
.anyRequest().authenticated()
```

- The `User`'s `Permission`s become `GrantedAuthority`s at login.
- `MANAGE_*` implies `VIEW_*`: resolved when building the principal's
  authorities, not in the rule. A rule always names **one** permission.
- Most specific route first; Spring Security takes the first match.
- An inactive operator (BR-09) is blocked in `UserDetailsService`:
  `enabled=false`.

Authorization is a question about the **HTTP boundary**: may the operator
on the other side do this? So it is answered there, and nowhere else.
The services carry no check, and every other caller — the startup
bootstraps, the scheduler, one feature calling another — is a plain
method call. The server acting on its own behalf has no operator, and
giving it a pretend one (a system `SecurityContext`, an unannotated
`*Internal()` twin of each use case) would only be working around a check
that was never about it.

Deciding in the filter also fixes the order of refusals. It runs before
any binding, so an operator without the permission gets a 403 before a
malformed body or an out-of-range `size` could earn a 400 — they learn
nothing about what a valid request looks like. A denial is handed to the
same `HandlerExceptionResolver` the controllers use, so the 403 carries
the `ApiError` body every other error does.

What this gives up: a use case reached through a second entry point (a
future GraphQL layer, a CLI) is not checked unless that entry point has
its own rules. That is the right place for them anyway — the question is
still "may this caller", and only the boundary knows who the caller is.

## Dependency rule

Within a feature:

```
controller ──> service ──> repository
     │              │             │
     └──────────────┴──> domain <┘
```

- `controller` imports `service` and `domain`. Never `repository`.
- `service` imports `repository` and `domain`. Never `controller`.
- `repository` imports only `domain`.
- `domain` imports nothing from the feature.

Across features:

- Feature A imports from B only `service` and `domain`. Never `repository`
  or `controller`. If two features call each other, the boundary is wrong.
- An entity references another feature's entity by id
  (`planPriceId: UUID`), not via `@ManyToOne`. The service loads what the
  rule needs and passes it as a parameter. Entity business rules never
  call a service or repository.

Subpackages require `public` classes, so the boundary is a convention. As
the project grows, an ArchUnit test in `src/test` enforces these arrows.

## External services

Payment gateway, email sending and the like: interface and implementation
in the `gateway/` subpackage of the feature that uses them
(`payment/gateway/PaymentGateway.java`,
`payment/gateway/StripePaymentGateway.java`). The service depends on the
interface.

The implementation catches the SDK exception and translates it to the
exception declared by the interface:

```java
@Override
public String charge(Subscriber subscriber, BigDecimal amount) {
    try {
        return stripe.charges().create(...).getId();
    } catch (StripeException e) {
        throw new PaymentGatewayException("Stripe charge failed", e);
    }
}
```

The service knows `PaymentGatewayException`, never `StripeException`.

## Failure

Fail-fast: the system prefers to stop and make noise over continuing
inconsistently in silence. Indexing runs inside the write transaction, so
a search node that is down fails the write with a rollback. A search index
that cannot be created at startup prevents the application from starting.

Exceptions per layer — each layer throws only its own and never swallows
the layer below:

| Layer | Throws | Catches |
|---|---|---|
| Domain | business exceptions extending the bases in `shared/domain/exception` | nothing |
| Persistence | what Spring Data translates (`DataAccessException` and subclasses); no class of its own | nothing |
| Application | nothing of its own; propagates domain and persistence | nothing — a `catch` in a service is a rule out of place |
| Presentation | `InvalidRequestException` for the shape of the input | everything, in one place: `GlobalExceptionHandler` |

## Exceptions

Abstract bases in `shared/domain/exception/`; concrete ones in
`<feature>/domain/exception/`.

| Base | Meaning | HTTP | Example |
|---|---|---|---|
| `NotFoundException` | resource does not exist | 404 | `PlanNotFoundException` |
| `BusinessRuleException` | business rule violated | 422 | `EmailAlreadyInUseException`, `PaymentNotRefundableException` |
| `ExternalServiceException` | external service failed | 502 | `PaymentGatewayException` |
| `MethodArgumentNotValidException` | Bean Validation | 400 | — |
| `InvalidRequestException` | request shape Bean Validation cannot express | 400 | sort field outside the allowed list |
| `AuthenticationException` | no, wrong or refused credentials; no `WWW-Authenticate` challenge, so a browser never pops its own dialog over a client | 401 | — |
| `AccessDeniedException` | missing permission | 403 | — |
| `DataAccessResourceFailureException` | a store cannot be reached | 503 | search node down; fixed message, never the host |

`GlobalExceptionHandler` maps by base type and responds with `ApiError`.
An unauthenticated request never reaches a controller, so it is Spring
Security — not the handler — that answers 401. Anything unmapped is a
500 with no detail.

## Request flow

`POST /api/subscribers`

1. Spring Security authenticates the operator, builds authorities and
   checks the route's rule: `POST /api/subscribers` needs
   `MANAGE_SUBSCRIBERS`, or it is a 403 here.
2. `SubscriberController` receives JSON. Bean Validation validates
   `CreateSubscriberRequest`.
3. `request.toCommand()` produces `CreateSubscriberCommand`.
4. `SubscriberService.create(command)`: validates email uniqueness, fetches
   the `PlanPrice` via `PlanService`, calls `Subscriber.start(...)`, saves.
5. `SubscriberResponse.from(subscriber)`. `201`.

Errors propagate as exceptions and `GlobalExceptionHandler` translates
them.

## Listing contract

Every listing takes the same query parameters and answers with
`PageResponse<T>`, so a client learns the shape once (FR-06, FR-07).

| Parameter | Default | Meaning |
|---|---|---|
| `q` | — | free text, case-insensitive, matching the start of any word of the feature's searchable fields; every word typed must match |
| `filter` | — | repeatable, `field:value` or `field:value1,value2` |
| `page` | `0` | zero-based page number |
| `size` | `20` | page size, maximum 100 |
| `sort` | the feature's default, ascending | `field[:asc\|desc]`, comma-separated, each field named as the index names it; no direction means `asc` |

```
?q=ada&filter=active:true&sort=email.keyword:desc&page=0&size=20
?sort=active:desc,name.keyword:asc
?filter=status:ACTIVE,PAST_DUE&filter=plan=<id>
```

Filtering is **one repeatable parameter, not one parameter per field**.
Otherwise every requirement that adds a filter adds a query parameter and
a controller argument, and the listing signature grows without bound —
FR-06.2 alone wants billing cycle, FR-06.3 wants status and plan.

The semantics are exactly FR-06 and no more: values of one field combine
with OR, separate filters with AND. It is deliberately **not** an
expression language (RSQL and the like). A grammar with parentheses,
negation and OR across fields is a public surface nobody specified, it
ties the API to the persistence model, and it makes a good error message
hard to produce.

`filter` and `sort` name **the index's own fields**, and the **index
mapping is the allow-list**. Nothing in Java vets a field name or a
value: the controller parses the syntax, the query builder passes field
and value through, and Elasticsearch answers. The mapping in
`search/<feature>-mapping.json` is where the decision is made, per field:

| Field | Mapping | Can be |
|---|---|---|
| `name`, `email` | `text` with a prefix analyzer, plus a `.keyword` copy | searched by `q`; sorted on `name.keyword` / `email.keyword` |
| `active`, `createdAt`, `id` | `index: false`, doc values on | filtered and sorted (doc values serve both; the two cannot be split) |
| `permissions` | `index: false, doc_values: false` | nothing — returned in the document, never queried |
| anything else | not mapped | nothing |

What is not in the read model (`UserSummary`) is not in the index at all,
so `passwordHash` cannot be asked about. What is in the index but closed
by the mapping is refused by Elasticsearch with a 400 whose reason names
the field; `GlobalExceptionHandler` passes that status and reason on, so
the refusal *is* the validation. A value the field cannot hold
(`active:maybe`) is refused the same way. Sorting a `text` field itself is
refused too — the contract names `name.keyword` because that is the field
that sorts, and hiding it behind `name` would tie the API to a translation
table the mapping already holds.

One consequence is accepted: a filter on a field the index does not know
matches nothing, and comes back as an empty page rather than a 400. No
value of an unmapped field can match a document, so it is not an oracle;
it is "filtered by nothing, found nothing".

Adding a filterable or sortable field is a line in the mapping. The
endpoint signature does not change. `page` and `size` outside their bounds
are still a 400 from the controller: the limit on `size` is the API's
rule, not the index's.

`sort` is spelled the way Elasticsearch's own URL spells it
(`GET /index/_search?sort=createdAt:desc,name.keyword:asc`): the field,
`:` and a direction, several keys separated by commas, and a key with no
direction ascending. Since the field names are already the index's own,
a caller who knows Elasticsearch already knows the whole parameter, and
there is no translation table to keep. A direction other than `asc` or
`desc`, or a direction with no field, is a 400 from the controller; a
field the mapping cannot sort on is a 400 from Elasticsearch, as with
`filter`.

The direction rides with the field rather than travelling in a parameter
of its own. A separate direction cannot say what it means once more than
one field is sorted on — `sort=name&sort=createdAt&direction=desc` names
no answer.

Sorting always appends `id` as a secondary key so paging stays stable
(FR-07.4). That key is ascending whichever way the caller asked: it is
there to keep pages from overlapping, not to follow the request, so two
directions over a fully tied field return the same order.

The contract is implemented once, in `shared/`, and a feature adds a
listing without restating any of it:

| Piece | Where | The feature supplies |
|---|---|---|
| the controller argument | `shared/controller/Listing`, `ListingRequestResolver` | `@Listing(defaultSort = ...)` on a `ListingRequest` parameter |
| parsing `q`, `filter`, `page`, `size`, `sort`; the page bounds; the `id` key | `shared/controller/ListingRequests` | — |
| the OpenAPI parameters | `components/parameters/{q,filter,page,size,sort}` in `docs/openapi.yaml` | five `$ref`s |
| what a listing asks for | `shared/service/SearchFilter` | — |
| the Elasticsearch query | `shared/repository/SearchQueries` | the fields `q` matches against |
| what can be filtered and sorted on | `src/main/resources/search/<feature>-mapping.json` | the mapping |

The page limit is `recurve.listing.max-page-size` (default 100), one
value for every listing: it is the API's rule about how much a response
may carry, not a property of any index.

## API contract

The API's promise to a client is `src/main/resources/docs/openapi.yaml`,
written by hand — **contract-first**. Nothing is generated from the
code, and nothing in the code is generated from it: the request and
response records stay ordinary records. A change to what an endpoint
takes or returns is a change to the contract first.

The document is only displayed. `shared/config/DocsConfig` serves it and
a Swagger UI over it under `/docs`, to anyone: the contract is a shape,
not data, and the UI has to fetch it before any credential exists. A call
made through the UI still needs Basic Auth. `recurve.docs.enabled` is one
switch for all of it — off, `DocsConfig` does not exist, `/docs` is not
mapped and falls under the default `authenticated()` rule, so a stranger
gets a 401 rather than a 404. Production turns it off.

A hand-written contract can drift, so two tests hold it to the code:

| Test | Root | Proves |
|---|---|---|
| `ApiContractTest` | test | the document is valid OpenAPI, and the routes it names are exactly the routes the controllers map — no more, no fewer |
| `<Feature>ContractIT` | testIntegration | every real exchange, request and response, is one the contract allows (`openapi-request-validator`) |

The contract states what the server actually enforces, not what would be
nice: an email is `^[^@\s]+@[^@\s]+$` because that is `UserValidator`'s
rule, not `format: email`, which promises RFC 5321 and would fail the
conformance test on a `.local` address the server accepts.

The document carries no prose beyond the API's own description: the
schema and its limits are the documentation, and each operation shows one
example of its success response. A response's `description` is the
status's reason phrase, there because the format requires one. A
paragraph on an operation would be a sign that a rule is missing from
the schema.

## Tests

Same structure as the code: package per feature, subpackage per layer.
The test for `subscriber/service/SubscriberService` lives at
`subscriber/service/SubscriberServiceTest`, inside the matching test
source set.

```
src/<test source set>/java/com/navesdev/recurve/
└── subscriber/
    ├── domain/
    │   └── SubscriberTest.java
    ├── repository/
    │   └── SubscriberRepositoryTest.java
    ├── service/
    │   └── SubscriberServiceTest.java
    └── controller/
        └── SubscriberControllerTest.java
```

Two source roots, so that the unit suite never needs a server:

| Root | Names | Needs | Command |
|---|---|---|---|
| `src/test` | `*Test` | nothing | `./mvnw test` |
| `src/testIntegration` | `*IT` | `docker compose up -d` (PostgreSQL, Elasticsearch) | `./mvnw verify` |

`./mvnw verify -Pintegration` runs only the integration tests.

| Layer | Root | Needs | Covers |
|---|---|---|---|
| Domain | test | nothing — plain JUnit | transition rules, invariants, the read model's projection |
| Application | test | Mockito; repositories and other services mocked | orchestration, uniqueness, indexing on write, exceptions |
| Presentation | test | `@WebMvcTest` with mocked service | request validation, serialization, HTTP status |
| Query builder | test | nothing — the query is inspected as data | what a filter and page turn into |
| JPA persistence | testIntegration | real database (`@DataJpaTest`) | lookups, custom queries |
| Search persistence | testIntegration | real node (`@DataElasticsearchTest`) | the listing rules FR-06, FR-07 on the mapped index |
| Authorization | testIntegration | `@SpringBootTest` + HTTP Basic | the route rules in `SecurityConfig` |
| Contract | test + testIntegration | parser; `@SpringBootTest` + the request validator | the document is valid and names the mapped routes; real exchanges match it |
| Docs | testIntegration | `@SpringBootTest` with the property on and off | `/docs` is open when enabled and absent when not |
| Fail-fast | testIntegration | `@SpringBootTest`, search repository mocked to fail | the rollback the transaction promises |

Integration tests use their own database (`recurve-test`) and their own
index prefix (`test-`), configured in `src/testIntegration/resources`.

A test that crosses layers (end-to-end endpoint) lives at the root of the
feature package.

## Configuration

Datasource and other settings come from environment variables with a
placeholder and a local default in `application.yaml`:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:54330/recurve-database}
```

A `.env` file (git-ignored) is loaded via `spring.config.import`.
`.env.example` documents the keys. Real credentials never enter the
repository, only the local database default from `docker-compose.yaml`.

`Clock` is an injectable bean; entities receive `Instant` as a parameter.
Tests control time.

### Schema

Flyway owns the schema. Migrations are `V<n>__<description>.sql` under
`src/main/resources/db/migration` and are immutable once merged: a
correction is a new migration, never an edit of an applied one. Hibernate
runs with `ddl-auto: validate`, so a mapping that drifts from the schema
fails at startup instead of quietly altering a table.

**One migration, one scope.** A migration carries a single change, and its
description says which one. There is no migration that creates the `user`
tables and the `plan` tables; those are two migrations, because they are
two changes that are reviewed, reasoned about and — if it comes to it —
reverted independently. The same holds for a change that is not a table:
adding a column, adding an index and backfilling data are three scopes,
not one.

The unit of scope is the **aggregate**, not the table. A collection table
belongs to the aggregate that owns it and has no meaning without it, so
`users` and `user_permissions` are created by one migration, and
`plans` and `plan_prices` will be created by another. Splitting an
aggregate across migrations would leave a version of the schema in which
the aggregate cannot be persisted at all.

| Scope | Migration |
|---|---|
| operators and their permissions | `V1__create_users.sql` |
| plans and their prices | a separate one |
| subscribers | a separate one |
| payments | a separate one |
| an index added to an existing table | a separate one |

Boot 4 autoconfigures per technology, so the integration comes from
`spring-boot-flyway`; `flyway-core` on its own would sit on the classpath
unwired.

Tests point at their own database (`recurve-test`, created by
`docker-compose` beside the development one) and never at the development
database: a repository test clears tables, and rows left over from a
manual run would collide with its fixtures. Each test still rolls back;
the separate database is what makes that rollback enough.

### Search index

The operator index's settings (analyzers) and mapping live in
`src/main/resources/search/users-settings.json` and `users-mapping.json`.
`UserIndexBootstrap` creates a missing index from them at startup and
fills it from the database. There is no versioning as with Flyway: a
mapping change is a rebuild (`POST /api/users/reindex`), which recreates
the index from the files and reindexes every operator.

`spring.elasticsearch.uris` follows the same placeholder pattern as the
datasource (`${ES_URL:http://localhost:9230}`). Integration tests share
the node and keep apart through `recurve.search.index-prefix: test-`.

### Lombok

`@Getter` and `@RequiredArgsConstructor` only. No `@Setter` and no
`@Data`: an entity changes state through a business method, and a
generated setter would open a second door into the domain.

## Naming conventions

| Type | Pattern | Example |
|---|---|---|
| Entity | noun | `Plan` |
| Exception | `<Thing><Problem>Exception` | `PlanNotFoundException` |
| Service | `<Thing>Service` | `PlanService` |
| Service method | verb | `create`, `cancel`, `search` |
| Command | `<Verb><Thing>Command` | `CreatePlanCommand` |
| Repository | `<Thing>Repository` | `PlanRepository` |
| External interface | `<Thing>Gateway`, `<Thing>Sender` | `PaymentGateway` |
| External impl | `<Vendor><Interface>` | `StripePaymentGateway` |
| Request/Response | `<Verb><Thing>Request`, `<Thing>Response` | `CreatePlanRequest` |
| Controller | `<Thing>Controller` | `PlanController` |
| Route | `/api/<things>` plural | `/api/plans` |
| Table | snake_case plural | `plan_prices` |
