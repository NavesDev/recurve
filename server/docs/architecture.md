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
| Persistence | model, `Specification`, `Pageable` | model | Domain | Application, Presentation |

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

Spring Data interface. Every repository extends
`shared/repository/BaseRepository`, which fixes the minimum surface —
`save`, `findById`, `existsById`, `count`, plus specification-based
listing — and stops there.

`BaseRepository` deliberately does not extend `JpaRepository`. Nothing in
Recurve deletes a record: an operator is deactivated, a plan and a price
are deactivated, a subscriber is canceled. A repository that offers no
`deleteAll` makes that a property of the type rather than of everyone's
discipline. It also withholds the unbounded `findAll()`, since every
listing is paginated (FR-07).

A feature's repository adds only what its use cases need — a lookup by a
natural key, an existence check. Custom queries via `@Query` or a
`Specification` in a dedicated class (`SubscriberSpecifications`). Never
business logic; never calls an entity's business method.

### Application (`service/`)

`@Service`, `@Transactional`. One method per use case.

Three-step pattern for every state-changing use case:

```
1. repository.findById(id)      load      (skipped on creation)
2. entity.businessMethod()      domain changes state, or throws
3. repository.save(entity)      persist
```

`save()` is always explicit, even on updates. For a managed entity it is a
no-op, but it keeps the intent visible without relying on dirty checking.
If step 2 throws, step 3 does not run and the transaction rolls back.

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

`GlobalExceptionHandler` maps by base type and responds with `ApiError`.
An unauthenticated request never reaches a controller, so it is Spring
Security — not the handler — that answers 401.

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
| `q` | — | free text, case-insensitive substring, over the feature's searchable fields |
| `page` | `0` | zero-based page number |
| `size` | `20` | page size, maximum 100 |
| `sort` | the feature's default | one field, from the feature's allow-list |
| `direction` | `asc` | `asc` or `desc` |

Each feature adds its own filters (`active` for operators, `status` for
subscribers, and so on). A `page`, `size`, `sort` or `direction` outside
what is allowed is a 400, never a silent fallback to the default.

The allowed sort fields are an enum in the feature's `service/`, not a
free string: the allow-list is part of the use case, and the controller
only maps the incoming text onto it. Sorting always appends `id` as a
secondary key so paging stays stable (FR-07.4).

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

Holds for any source set (`test`, integration, etc.). How source sets are
split and what runs in each is a build decision, outside this document.

| Layer | Needs | Covers |
|---|---|---|
| Domain | nothing — plain JUnit | transition rules, invariants |
| Application | Mockito; repository and other services mocked | orchestration, uniqueness, exceptions |
| Presentation | `@WebMvcTest` with mocked service | request validation, serialization, HTTP status |
| Persistence | real database | `Specification`, custom queries |
| Authorization | Spring context + `@WithMockUser(authorities = ...)` | `@PreAuthorize` per use case |

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
