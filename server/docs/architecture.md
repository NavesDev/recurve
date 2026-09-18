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
- Authorization via `@PreAuthorize` on the service.
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
├── config/               # SecurityConfig, ClockConfig
├── controller/           # GlobalExceptionHandler, ApiError, PageResponse
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
  `User.create(name, email, passwordHash, now)`.
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
delete; an index is only ever rebuilt whole. A dedicated class
(`UserSearchQueries`) turns the feature's filter into a query, the way a
`Specifications` class would. Never business logic; never calls an
entity's business method.

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

- Checks authorization (`@PreAuthorize`).
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

    @PreAuthorize("hasAuthority('MANAGE_SUBSCRIBERS')")
    public Subscriber create(CreateSubscriberCommand cmd) {
        if (repository.existsByEmail(cmd.email())) {
            throw new EmailAlreadyInUseException(cmd.email());
        }
        PlanPrice price = planService.findActivePrice(cmd.planPriceId());
        Subscriber subscriber = Subscriber.start(cmd.name(), cmd.email(), price, clock.instant());
        return repository.save(subscriber);
    }

    @PreAuthorize("hasAuthority('MANAGE_SUBSCRIBERS')")
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
response. No rules, no `@PreAuthorize` (already on the service).

- Request: `record` with Bean Validation, `toCommand()` method.
- Response: `record` with a `from(entity)` factory. Reads entity getters,
  never calls a business method.
- Listing: query params become a filter `record` + `Pageable`; response
  as `PageResponse<T>`.

## Authorization

Spring Security with method security enabled (`@EnableMethodSecurity`).

- The `User`'s `Permission`s become `GrantedAuthority`s at login.
- `MANAGE_*` implies `VIEW_*`: resolved when building the principal's
  authorities, not in the annotation. `@PreAuthorize` always names
  **one** permission.
- Annotation on the service, never on the controller. That way the
  scheduler and cross-feature calls go through the check too.
- An internal method that must not be checked (called only by another
  service of the same feature) has no annotation and is documented as
  internal.
- An inactive operator (BR-09) is blocked in `UserDetailsService`:
  `enabled=false`.

Because the check lives in a proxy, a call that does not cross the proxy
is not checked. That is what makes an unannotated internal component
possible — and what makes an annotation on a self-invoked method silently
useless. Two components are internal on purpose, both running when there
is no authenticated operator to check, and both talking to the repository
rather than to the service:

- `UserIndexBootstrap`, which creates the search index at startup and
  calls the service's unchecked `reindexInternal()`.
- `OperatorDetailsService`, which runs inside the authentication filter,
  before a principal exists.
- `OperatorBootstrap`, which creates the first operator on an empty
  table. Every write use case demands `MANAGE_USERS`, so without it no
  operator could ever be created through the API. Its credentials come
  from the environment and it does nothing when they are absent.

The scheduled job (billing) runs without an operator. Mechanism to be
decided with FR-04.1: a system `SecurityContext`, or a dedicated
unannotated service method invoked only by the feature's own scheduler.

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
| `AccessDeniedException` | missing permission | 403 | — |
| `DataAccessResourceFailureException` | a store cannot be reached | 503 | search node down; fixed message, never the host |

`GlobalExceptionHandler` maps by base type and responds with `ApiError`.
An unauthenticated request never reaches a controller, so it is Spring
Security — not the handler — that answers 401. Anything unmapped is a
500 with no detail.

## Request flow

`POST /api/subscribers`

1. Spring Security authenticates the operator and builds authorities.
2. `SubscriberController` receives JSON. Bean Validation validates
   `CreateSubscriberRequest`.
3. `request.toCommand()` produces `CreateSubscriberCommand`.
4. `SubscriberService.create(command)`: `@PreAuthorize` checks
   `MANAGE_SUBSCRIBERS`; the service validates email uniqueness, fetches
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
| `sort` | the feature's default, ascending | one field from the feature's allow-list, prefixed with `-` for descending |

```
?q=ada&filter=active:true&sort=-email&page=0&size=20
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

Both `filter` and `sort` name their fields against an **allow-list**, an
enum in the feature's `service/`. The allow-list is part of the use case,
not of the controller, and without it a client could filter or sort over
any mapped column — `passwordHash` included — and turn the listing into
an oracle. A field outside the list is a 400, never an empty page: an
empty page reads as "nobody matches", which is a different and false
answer.

Adding a filter is a constant in that enum plus a case in the feature's
search queries. The endpoint signature does not change. A `page`, `size`,
`sort` or `filter` outside what is allowed is a 400, never a silent
fallback to the default.

A listing sorts on **one** field. Naming more than one — `sort=name,email`
or a repeated `sort` — is a 400, not a silent choice of the first: quietly
dropping the rest would answer a question the caller did not ask, and
nothing in the response would say so. The syntax leaves room for several
fields if a requirement ever asks for them; the allow-list is what would
have to change, not the parameter.

The direction rides with the field, as JSON:API, Spring Data, OData and
Elasticsearch each do in their own spelling, rather than travelling in a
parameter of its own. A separate direction cannot say what it means once
more than one field is sorted on — `sort=name&sort=createdAt&
direction=desc` names no answer — so keeping them together leaves
multi-field sorting open instead of closing it.

Sorting always appends `id` as a secondary key so paging stays stable
(FR-07.4). That key is ascending whichever way the caller asked: it is
there to keep pages from overlapping, not to follow the request, so two
directions over a fully tied field return the same order.

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
| Authorization | testIntegration | `@SpringBootTest` + HTTP Basic | `@PreAuthorize` per use case |
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
