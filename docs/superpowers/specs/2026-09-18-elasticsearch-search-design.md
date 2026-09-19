# Elasticsearch as the search engine — design

Date: 2026-09-18. Branch: `feat/server-user`.

## Goal

Replace the hand-written listing (Specification + LIKE over PostgreSQL) with
Elasticsearch as the single engine for search, filter, sort and pagination
(FR-06, FR-07), starting with operators (FR-06.1). Remove the legacy
search code and its tests. Split unit tests from integration tests so the
unit suite runs without any server.

## Decisions

| Question | Decision |
|---|---|
| Index sync | Synchronous, in the same use case: the service saves to PostgreSQL then indexes. Plus a full reindex. |
| Read source | The listing is served from the ES document. PostgreSQL is only read by `findById`, login and reindex. |
| Test infra | ES in `docker-compose.yaml`, like PostgreSQL. No Testcontainers. |
| Test split | `src/test` = unit (Surefire, `mvn test`, no compose). `src/testIntegration` = integration (Failsafe, `*IT`, `mvn verify`). Profile `integration` runs only the ITs. |
| Client | Spring Data Elasticsearch (`spring-boot-starter-data-elasticsearch`). |
| Search semantics | Word-prefix match, case-insensitive, over name and email (`edge_ngram` at index time). Replaces "substring" in FR-06. No fuzzy, no relevance ranking. |
| Reindex trigger | Index created (settings + mapping) and populated at boot when absent; `POST /api/users/reindex` forces it. |
| Reindex permission | New `Permission.MANAGE_SYSTEM`, no expansion. |
| Write failure | Fail-fast: indexing runs inside the transaction; ES down → exception → rollback → 503. |
| Read model location | `user/domain/UserSummary`, a record with ES mapping annotations only. Mirrors "the JPA entity is the domain model". |

## Architecture

```
PRESENTATION   controller/   UserController → UserFilter + Pageable → PageResponse<UserResponse>
APPLICATION    service/      create/update/deactivate: JPA save + index
                             search: ES only        reindex: PostgreSQL → ES
DOMAIN         domain/       User (@Entity, write model)   UserSummary (@Document, read model)
PERSISTENCE    repository/   UserRepository (JPA)   UserSearchRepository (ES)   UserSearchQueries (DSL)
user/service                 UserIndexBootstrap — creates the index at boot (the ES "Flyway"); in the feature, because shared/ never imports one
```

ES is persistence: a second store beside PostgreSQL. PostgreSQL is the
source of truth (write); ES is a projection (read). Nothing above the
service knows there are two stores.

## Infrastructure and build

`docker-compose.yaml` adds `elasticsearch`: image 9.x (minor pinned to the
client version managed by the Boot 4.1.1 BOM), `discovery.type=single-node`,
`xpack.security.enabled=false`, 512m heap, port `9230:9200`, volume
`elasticsearch_data`.

`pom.xml` adds `spring-boot-starter-data-elasticsearch`,
`build-helper-maven-plugin` (registers `src/testIntegration/java` and
`src/testIntegration/resources` as test sources) and
`maven-failsafe-plugin` (`**/*IT.java`, bound to `integration-test` and
`verify`). A profile `integration` sets `skipTests` on Surefire only.

| Command | Runs |
|---|---|
| `mvn test` | unit only, no compose needed |
| `mvn verify` | unit + integration, needs `docker compose up` |
| `mvn verify -Pintegration` | integration only |

Existing tests that need infrastructure move and are renamed:
`UserRepositoryTest` → `UserRepositoryIT`, `UserEndpointAuthorizationTest`
→ `UserEndpointAuthorizationIT`, `RecurveApplicationTests` →
`RecurveApplicationIT`. `src/test/resources/application.yaml` moves to
`src/testIntegration/resources`. `src/test` has no yaml.

Configuration: `spring.elasticsearch.uris: ${ES_URL:http://localhost:9230}`
in main. `recurve.search.index-prefix` (default empty; `test-` in
testIntegration) so ITs use `test-users` and never touch `users`.

## Domain

`UserSummary`: record, `@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}users")`,
fields `id`, `name`, `email`, `permissions`, `active`, `createdAt`. No
`passwordHash` by construction. Factory `UserSummary.of(User)`. No rules.

`Permission` gains `MANAGE_SYSTEM`. Its javadoc changes: most permissions
come in view/manage pairs per resource; `MANAGE_SYSTEM` is an
administrative operation with no read counterpart.

## Index mapping (`src/main/resources/search/users.json`)

- Analyzer `prefix`: tokenizer `standard` → `lowercase` → `edge_ngram`
  (min 1, max 20). `search_analyzer`: `standard` + `lowercase`, so the
  query is not ngram-split.
- `name`, `email`: `text` with analyzer `prefix`, sub-field `keyword`
  with a `lowercase` normalizer for case-insensitive sorting.
- `permissions`: `keyword`. `active`: `boolean`. `createdAt`: `date`.
  `id`: `keyword` — the `_id` and the fixed secondary sort key (FR-07.4).
- The `standard` tokenizer splits `ada@recurve.local` into `ada` and
  `recurve.local`; `"ada@"` matches as a prefix of `ada`.

## Persistence

`UserSearchRepository`: a `@Repository` class over `ElasticsearchOperations`
with a minimal surface in the spirit of `BaseRepository` — `save`,
`saveAll`, `search(UserFilter, Pageable): Page<UserSummary>`,
`indexExists`, `recreateIndex`, `refresh`. No delete exposed; reindex
recreates the index via `IndexOperations`.

`UserSearchQueries.from(UserFilter, Pageable)` replaces
`UserSpecifications`: `bool` with a `multi_match` on `name`/`email` when
text is present, a `terms` per filter criterion, sort on the requested
field (`name.keyword`, `email.keyword`, `createdAt`) then `id` asc,
`from`/`size` from the `Pageable`.

`BaseRepository` stops extending `JpaSpecificationExecutor`.
`UserRepository` gains `Stream<User> streamAll()` (`@Query`), documented
as "for reindex, not for listing".

`UserIndexBootstrap` (in `user/service`, ordered before
`OperatorBootstrap`, which also indexes the admin it creates): at boot,
if the index is absent → create with settings + mapping from JSON and run that
index's reindex. An existing index is left alone; a mapping change is a
manual reindex through the endpoint, which recreates it. Failure to
create the index stops the application.

## Application

`UserService`:
- `create`/`update`/`deactivate`: step 3 becomes a private `persist(User)`
  that does `repository.save(user)` and
  `searchRepository.save(UserSummary.of(user))`, inside the transaction.
- `search(UserFilter, Pageable): Page<UserSummary>` — ES only,
  `@PreAuthorize('VIEW_USERS')`, read-only.
- `reindex(): long` — `@PreAuthorize('MANAGE_SYSTEM')`. Recreates the
  index and `saveAll`s in batches from `repository.streamAll()`; returns
  the count. Recreating is what removes orphan documents.
- `reindexInternal()` — no `@PreAuthorize`, called by the initializer at
  boot (same documented pattern as `OperatorBootstrap`).

## Presentation

- `GET /api/users`: same signature and parsing (`RequestFilters`,
  `RequestSort`, `UserFilterField`, `UserSortField` unchanged — they are
  API contract). Maps `Page<UserSummary>` with `UserResponse.from(UserSummary)`.
- `UserResponse` gains `from(UserSummary)`; keeps `from(User)`.
- `POST /api/users/reindex` → `200 { "indexed": n }`.

## Failure

Documented in `architecture.md` as a principle:

- **Fail-fast.** The system prefers to stop and make noise over continuing
  inconsistently in silence. Indexing runs inside the transaction; ES down
  fails the write with rollback. An index that cannot be created at boot
  prevents startup.
- **Exceptions per layer.** Each layer throws only its own and never
  swallows the layer below:
  - Domain: business exceptions extending `shared/domain/exception` bases.
  - Persistence: what Spring Data translates (`DataAccessException`); no
    own classes, no try/catch.
  - Application: throws and catches nothing of its own; a `catch` in a
    service signals a rule out of place.
  - Presentation: `InvalidRequestException` for input shape;
    `GlobalExceptionHandler` is the only place that maps exceptions to
    HTTP — business → 404/409, input → 400,
    `DataAccessResourceFailureException` → 503 with a generic `ApiError`,
    anything else → 500 without detail.

## Tests (TDD order)

Unit (`src/test`):
1. `UserSummaryTest` — `of(user)` copies the six fields; no password field.
2. `PermissionTest` — `MANAGE_SYSTEM.expand()` is itself only.
3. `UserSearchQueriesTest` — query has `match` only with text; `terms
   active` with the values; sort on requested field + `id` asc; `from`/`size`.
4. `UserServiceTest` — create/update/deactivate index the summary; an
   indexing failure propagates; `search` uses the index, never JPA;
   `reindex` recreates and indexes everything `streamAll` yields.
5. `UserControllerTest` — `search` builds `PageResponse` from
   `UserSummary`; `POST /reindex` returns the count; parsing tests unchanged.

Integration (`src/testIntegration`):
6. `UserSearchRepositoryIT` — FR-06/FR-07 rules against real ES on
   `test-users`, recreated per test: prefix match on name and email,
   case-insensitive, `"velace"` does not match "Lovelace", `active`
   filter, sort per field and direction, stable secondary key, page past
   the end, total after filter.
7. `UserRepositoryIT` — FR-05.1, BR-02 only.
8. `UserEndpointAuthorizationIT` — `POST /reindex` needs `MANAGE_SYSTEM`;
   `MANAGE_USERS` alone gets 403.
9. `UserServiceIT` — fail-fast end to end: ES on a dead port → `create`
   throws and PostgreSQL has no row. Boot with the index absent creates
   and populates it.

## Removed

`UserSpecifications`; `JpaSpecificationExecutor` from `BaseRepository`;
the FR-06/FR-07 half of `UserRepositoryTest`; `src/test/resources/application.yaml`.

## Unchanged

`User`, `RequestFilters`, `RequestSort`, `PageResponse`, `UserFilter`,
`UserFilterField`, `UserSortField`, `OperatorBootstrap`,
`OperatorDetailsService`, `SecurityConfig`.

## Out of scope

Plan/subscriber indexes; `search_after` beyond 10k results; fuzzy or
relevance ranking; async indexing / CDC; Testcontainers.

## Documentation changes

- `docs/requirements.md`: FR-06 "by substring" → "by word prefix,
  case-insensitive"; FR-01 lists `MANAGE_SYSTEM`; FR-06 notes the index
  is rebuildable through the endpoint.
- `server/docs/architecture.md`: two stores in Persistence, read model in
  Domain, initializer as the ES "Flyway", Failure section, test split and
  commands.

## Commit sequence

`tipo(server-user): ...`: infra/build → test split → `MANAGE_SYSTEM` →
`UserSummary` → index + ES repository → service → controller → legacy
removal → docs.
