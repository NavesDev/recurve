# Elasticsearch Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the operator listing (`GET /api/users`: search, filter, sort, pagination) from Elasticsearch, indexed synchronously on every write, with a boot-time index bootstrap and an admin reindex endpoint; remove the PostgreSQL Specification-based listing; split unit tests from integration tests.

**Architecture:** PostgreSQL stays the write model and source of truth (`User` `@Entity`). Elasticsearch holds a read projection (`UserSummary` `@Document`, in `user/domain`). `UserService` coordinates both stores inside one transaction (fail-fast: an indexing error rolls the write back). `UserSearchRepository` (persistence layer) turns a `UserFilter` + `Pageable` into an ES query. `UserIndexBootstrap` creates the index with its mapping at boot and populates it when it did not exist.

**Tech Stack:** Spring Boot 4.1.1, Java 25, Maven, Spring Data JPA (PostgreSQL 18), Spring Data Elasticsearch (client 9.4.5, server image 9.4.7), Lombok, JUnit 5, Mockito, AssertJ, Surefire + Failsafe.

Spec: `docs/superpowers/specs/2026-09-18-elasticsearch-search-design.md`.

## Global Constraints

- All paths below are relative to `server/` unless they start with `docs/` or are `docker-compose.yaml` (repo root).
- Layer rule (`server/docs/architecture.md`): Presentation imports Application + Domain only; Application imports Domain + Persistence; Persistence imports Domain; `shared/` never imports a feature.
- Commit messages follow the branch convention `type(server-user): message` and end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Commit with `GIT_AUTHOR_NAME=Claude GIT_AUTHOR_EMAIL=noreply@anthropic.com GIT_COMMITTER_NAME=Claude GIT_COMMITTER_EMAIL=noreply@anthropic.com` exported (the repo has no configured identity; earlier commits use this author).
- Unit tests live in `src/test` and never need a server. Integration tests live in `src/testIntegration`, are named `*IT`, and need `docker compose up -d` (PostgreSQL on 54330, Elasticsearch on 9230).
- Maven: `./mvnw test` = unit only. `./mvnw verify` = unit + integration. `./mvnw verify -Pintegration` = integration only.
- Test names read as rules (existing style): `@Nested` classes with a `@DisplayName` naming the requirement (`FR-06 ...`), methods in the form `theSearchMatchesPartOfAName`.
- Javadoc explains *why* (requirement ids, the rule being protected), in the density of the surrounding code.
- Fail-fast: no `try/catch` in service or repository; exceptions propagate. Every listing is paginated; no unbounded `findAll`.
- Elasticsearch index name: `users`, prefixed by `recurve.search.index-prefix` (empty in main, `test-` in integration tests, so ITs use `test-users`).
- Search semantics (FR-06 after this change): case-insensitive **word-prefix** match over `name` and `email`; every word typed must match (AND across words, across the two fields).

---

## File map

| Path | Responsibility |
|---|---|
| `docker-compose.yaml` | adds the `elasticsearch` service |
| `pom.xml` | ES starter, build-helper (extra test source root), failsafe, `integration` profile |
| `src/main/resources/application.yaml` | `spring.elasticsearch.uris`, `recurve.search.index-prefix` |
| `src/testIntegration/resources/application.yaml` | test DB + test ES + `test-` prefix (moved from `src/test/resources`) |
| `src/main/resources/search/users-settings.json` | analyzers (`prefix`, `prefix_search`), `lowercase` normalizer |
| `src/main/resources/search/users-mapping.json` | field mapping of the `users` index |
| `src/main/java/.../user/domain/Permission.java` | + `MANAGE_SYSTEM` |
| `src/main/java/.../user/domain/UserSummary.java` | the read model (`@Document`), `of(User)` |
| `src/main/java/.../user/repository/UserSearchQueries.java` | `UserFilter` + `Pageable` → `NativeQuery` (replaces `UserSpecifications`) |
| `src/main/java/.../user/repository/UserSearchRepository.java` | ES access: `save`, `saveAll`, `search`, `indexExists`, `recreateIndex`, `refresh` |
| `src/main/java/.../user/repository/UserRepository.java` | + `streamAll()` for reindex |
| `src/main/java/.../shared/repository/BaseRepository.java` | − `JpaSpecificationExecutor` |
| `src/main/java/.../user/service/UserService.java` | index on write, `search` via ES, `reindex`, `reindexInternal` |
| `src/main/java/.../user/service/UserIndexBootstrap.java` | boot: create index + populate when absent |
| `src/main/java/.../user/service/OperatorBootstrap.java` | also indexes the bootstrap admin |
| `src/main/java/.../user/controller/UserController.java` | listing from `UserSummary`; `POST /reindex` |
| `src/main/java/.../user/controller/UserResponse.java` | + `from(UserSummary)` |
| `src/main/java/.../user/controller/ReindexResponse.java` | `{ "indexed": n }` |
| `src/main/java/.../shared/controller/GlobalExceptionHandler.java` | `DataAccessResourceFailureException` → 503 |
| `src/test/java/.../user/domain/PermissionTest.java` | new |
| `src/test/java/.../user/domain/UserSummaryTest.java` | new |
| `src/test/java/.../user/repository/UserSearchQueriesTest.java` | new |
| `src/test/java/.../user/service/UserServiceTest.java` | + indexing, search, reindex |
| `src/test/java/.../user/service/OperatorBootstrapTest.java` | new |
| `src/test/java/.../user/controller/UserControllerTest.java` | `UserSummary` pages, reindex, 503 |
| `src/testIntegration/java/.../RecurveApplicationIT.java` | moved |
| `src/testIntegration/java/.../user/repository/UserRepositoryIT.java` | moved, FR-06/07 half removed |
| `src/testIntegration/java/.../user/repository/UserSearchRepositoryIT.java` | new: FR-06/07 against ES |
| `src/testIntegration/java/.../user/UserEndpointAuthorizationIT.java` | moved, indexes fixtures, reindex authorization |
| `src/testIntegration/java/.../user/service/UserServiceIT.java` | new: fail-fast rollback |
| `src/testIntegration/java/.../user/service/UserIndexBootstrapIT.java` | new: boot creates + populates |
| `docs/requirements.md`, `server/docs/architecture.md`, `README.md` | docs |

`...` = `com/navesdev/recurve`.

---

### Task 1: Elasticsearch in docker-compose and on the classpath

**Files:**
- Modify: `docker-compose.yaml` (repo root)
- Modify: `pom.xml` (dependencies block)
- Modify: `src/main/resources/application.yaml`

**Interfaces:**
- Produces: ES reachable at `http://localhost:9230`; property `spring.elasticsearch.uris`; property `recurve.search.index-prefix` (default empty).

- [ ] **Step 1: Add the service to docker-compose**

Replace `docker-compose.yaml` with:

```yaml
services:
  postgres:
    image: postgres:18
    container_name: recurve-database
    environment:
      POSTGRES_DB: recurve-database
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "54330:5432"
    volumes:
      - postgres_data:/var/lib/postgresql
      # Runs only when the volume is first created. On an existing volume:
      # docker exec -i recurve-database psql -U postgres -c 'CREATE DATABASE "recurve-test"'
      - ./db/init:/docker-entrypoint-initdb.d:ro

  # The listing engine (FR-06, FR-07). Same major.minor as the client the
  # Spring Boot BOM manages (elasticsearch-java 9.4.x). Single node,
  # security off: development only. Integration tests share this node
  # under a "test-" index prefix, the way they share the database server
  # under a separate database name.
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:9.4.7
    container_name: recurve-search
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: -Xms512m -Xmx512m
    ports:
      - "9230:9200"
    volumes:
      - elasticsearch_data:/usr/share/elasticsearch/data

volumes:
  postgres_data:
  elasticsearch_data:
```

- [ ] **Step 2: Start it and check it answers**

Run: `docker compose -f /home/naves/Projetos/Recurve/docker-compose.yaml up -d && sleep 20 && curl -s localhost:9230 | grep number`
Expected: a line like `"number" : "9.4.7",`

- [ ] **Step 3: Add the starter to pom.xml**

In `pom.xml`, right after the `spring-boot-starter-security` dependency, add:

```xml
		<!-- The listing engine (FR-06, FR-07): search, filter, sort and
		     pagination run here, not in PostgreSQL. -->
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-elasticsearch</artifactId>
		</dependency>
```

- [ ] **Step 4: Configure the client**

In `src/main/resources/application.yaml`, under `spring:` add (after `datasource:`):

```yaml
  elasticsearch:
    uris: ${ES_URL:http://localhost:9230}
```

and under `recurve:` add:

```yaml
  search:
    # Prepended to every index name. Empty in development; the integration
    # tests set "test-" so they never touch a development index.
    index-prefix: ""
```

- [ ] **Step 5: Compile**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q compile`
Expected: BUILD SUCCESS (no output with `-q`).

- [ ] **Step 6: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add docker-compose.yaml server/pom.xml server/src/main/resources/application.yaml && git commit -m "build(server-user): add Elasticsearch to compose and the classpath

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Split unit tests from integration tests

**Files:**
- Modify: `pom.xml` (build plugins, profiles)
- Move: `src/test/resources/application.yaml` → `src/testIntegration/resources/application.yaml`
- Move: `src/test/java/com/navesdev/recurve/RecurveApplicationTests.java` → `src/testIntegration/java/com/navesdev/recurve/RecurveApplicationIT.java`
- Move: `src/test/java/com/navesdev/recurve/user/repository/UserRepositoryTest.java` → `src/testIntegration/java/com/navesdev/recurve/user/repository/UserRepositoryIT.java`
- Move: `src/test/java/com/navesdev/recurve/user/UserEndpointAuthorizationTest.java` → `src/testIntegration/java/com/navesdev/recurve/user/UserEndpointAuthorizationIT.java`

**Interfaces:**
- Produces: `./mvnw test` runs only `src/test`; `./mvnw verify` also runs `src/testIntegration/**/*IT.java`; `-Pintegration` skips Surefire.

- [ ] **Step 1: Register the extra source root and Failsafe**

In `pom.xml`, inside `<build><plugins>`, after the `spring-boot-maven-plugin` plugin, add:

```xml
			<!-- Integration tests live in their own source root so that
			     src/test stays runnable with no server at all. Surefire
			     (mvn test) only picks *Test classes; Failsafe (mvn verify)
			     only picks *IT classes. -->
			<plugin>
				<groupId>org.codehaus.mojo</groupId>
				<artifactId>build-helper-maven-plugin</artifactId>
				<executions>
					<execution>
						<id>add-integration-test-sources</id>
						<phase>generate-test-sources</phase>
						<goals>
							<goal>add-test-source</goal>
						</goals>
						<configuration>
							<sources>
								<source>src/testIntegration/java</source>
							</sources>
						</configuration>
					</execution>
					<execution>
						<id>add-integration-test-resources</id>
						<phase>generate-test-resources</phase>
						<goals>
							<goal>add-test-resource</goal>
						</goals>
						<configuration>
							<resources>
								<resource>
									<directory>src/testIntegration/resources</directory>
								</resource>
							</resources>
						</configuration>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-failsafe-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>integration-test</goal>
							<goal>verify</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
```

After `</build>` and before `</project>`, add:

```xml
	<profiles>
		<!-- mvn verify -Pintegration: only the integration tests. -->
		<profile>
			<id>integration</id>
			<build>
				<plugins>
					<plugin>
						<groupId>org.apache.maven.plugins</groupId>
						<artifactId>maven-surefire-plugin</artifactId>
						<configuration>
							<skipTests>true</skipTests>
						</configuration>
					</plugin>
				</plugins>
			</build>
		</profile>
	</profiles>
```

- [ ] **Step 2: Move the files**

```bash
cd /home/naves/Projetos/Recurve/server
mkdir -p src/testIntegration/java/com/navesdev/recurve/user/repository src/testIntegration/resources
git mv src/test/resources/application.yaml src/testIntegration/resources/application.yaml
git mv src/test/java/com/navesdev/recurve/RecurveApplicationTests.java src/testIntegration/java/com/navesdev/recurve/RecurveApplicationIT.java
git mv src/test/java/com/navesdev/recurve/user/repository/UserRepositoryTest.java src/testIntegration/java/com/navesdev/recurve/user/repository/UserRepositoryIT.java
git mv src/test/java/com/navesdev/recurve/user/UserEndpointAuthorizationTest.java src/testIntegration/java/com/navesdev/recurve/user/UserEndpointAuthorizationIT.java
sed -i 's/class RecurveApplicationTests/class RecurveApplicationIT/' src/testIntegration/java/com/navesdev/recurve/RecurveApplicationIT.java
sed -i 's/UserRepositoryTest/UserRepositoryIT/g' src/testIntegration/java/com/navesdev/recurve/user/repository/UserRepositoryIT.java
sed -i 's/UserEndpointAuthorizationTest/UserEndpointAuthorizationIT/g' src/testIntegration/java/com/navesdev/recurve/user/UserEndpointAuthorizationIT.java
grep -rn "UserEndpointAuthorizationTest" src/ | cat
```

The last `grep` prints the javadoc reference in `UserControllerTest.java`; change that text to `UserEndpointAuthorizationIT`.

- [ ] **Step 3: Point the integration yaml at the test index prefix and the ES node**

Replace `src/testIntegration/resources/application.yaml` with:

```yaml
# Integration tests run against their own database and their own search
# indexes, never the development ones: a repository test truncates tables
# and recreates indexes, and pre-existing data would collide with its
# fixtures. docker-compose creates the database alongside the development
# one; the index prefix keeps the search indexes apart on the same node.
spring:
  datasource:
    url: ${TEST_DB_URL:jdbc:postgresql://localhost:54330/recurve-test}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  elasticsearch:
    uris: ${TEST_ES_URL:http://localhost:9230}
  flyway:
    enabled: true
    clean-disabled: false
  jpa:
    hibernate:
      ddl-auto: validate

recurve:
  search:
    index-prefix: test-
  bootstrap:
    admin:
      email: ""
      password: ""
```

- [ ] **Step 4: Unit suite runs without infrastructure**

Run: `docker compose -f /home/naves/Projetos/Recurve/docker-compose.yaml stop && cd /home/naves/Projetos/Recurve/server && ./mvnw -q test 2>&1 | tail -20; ls target/surefire-reports/*.txt`
Expected: BUILD SUCCESS; the report list contains only `UserTest`, `UserServiceTest`, `UserControllerTest` (no `IT`, no `RecurveApplication`).

- [ ] **Step 5: Integration suite runs under verify**

Run: `docker compose -f /home/naves/Projetos/Recurve/docker-compose.yaml start && sleep 15 && cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify -Pintegration 2>&1 | tail -20; ls target/failsafe-reports/*.txt`
Expected: BUILD SUCCESS; reports for `RecurveApplicationIT`, `UserRepositoryIT`, `UserEndpointAuthorizationIT`; no `target/surefire-reports` refresh (Surefire skipped).

- [ ] **Step 6: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add -A server/pom.xml server/src/test server/src/testIntegration && git commit -m "build(server-user): keep integration tests in their own source root

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: `MANAGE_SYSTEM` permission

**Files:**
- Modify: `src/main/java/com/navesdev/recurve/user/domain/Permission.java`
- Create: `src/test/java/com/navesdev/recurve/user/domain/PermissionTest.java`
- Modify: `docs/requirements.md` (BR-01, FR-01)

**Interfaces:**
- Produces: `Permission.MANAGE_SYSTEM`; `MANAGE_SYSTEM.expand()` returns `Set.of(MANAGE_SYSTEM)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/navesdev/recurve/user/domain/PermissionTest.java`:

```java
package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PermissionTest {

    @Nested
    @DisplayName("BR-01 manage implies view")
    class ManageImpliesView {

        @Test
        void managingAResourceGrantsViewingIt() {
            assertThat(Permission.MANAGE_USERS.expand())
                    .containsExactlyInAnyOrder(Permission.MANAGE_USERS, Permission.VIEW_USERS);
        }

        @Test
        void viewingGrantsNothingElse() {
            assertThat(Permission.VIEW_USERS.expand()).containsExactly(Permission.VIEW_USERS);
        }

        @Test
        void managingTheSystemHasNoViewCounterpartAndGrantsNothingElse() {
            assertThat(Permission.MANAGE_SYSTEM.expand()).containsExactly(Permission.MANAGE_SYSTEM);
        }
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=PermissionTest 2>&1 | tail -15`
Expected: compilation error, `MANAGE_SYSTEM` cannot be found.

- [ ] **Step 3: Add the constant**

In `Permission.java`, replace the class javadoc and the constant list:

```java
/**
 * Granular access. Most permissions come in pairs, one read and one write
 * per resource (BR-01); {@link #MANAGE_SYSTEM} stands alone, an
 * administrative operation with no read counterpart. Access is not
 * modelled as a role: an operator holds exactly the permissions granted
 * to them.
 */
public enum Permission {

    VIEW_USERS,
    MANAGE_USERS,
    VIEW_PLANS,
    MANAGE_PLANS,
    VIEW_SUBSCRIBERS,
    MANAGE_SUBSCRIBERS,
    VIEW_PAYMENTS,
    MANAGE_PAYMENTS,
    /** Operational routines with no resource of their own: rebuilding a search index. */
    MANAGE_SYSTEM;
```

The `expand()` method is unchanged (`default -> Set.of(this)` already covers it).

- [ ] **Step 4: Run the test**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=PermissionTest 2>&1 | tail -15`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Record it in the requirements**

In `docs/requirements.md`:

Under `### FR-01 Operators` after the `FR-01.4` line add:

```markdown
- FR-01.5 Rebuild the operator search index (see FR-06). Requires the
  `MANAGE_SYSTEM` permission.
```

Replace the BR-01 bullet with:

```markdown
- BR-01 Permissions per resource: a view permission and a manage
  permission for each of operators, plans, subscribers and payments.
  Manage implies view. One more permission, `MANAGE_SYSTEM`, covers
  operational routines (rebuilding a search index) and implies nothing.
```

- [ ] **Step 6: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src/main/java/com/navesdev/recurve/user/domain/Permission.java server/src/test/java/com/navesdev/recurve/user/domain/PermissionTest.java docs/requirements.md && git commit -m "feat(server-user): add the MANAGE_SYSTEM permission

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: `UserSummary`, the read model, and its index definition

**Files:**
- Create: `src/main/java/com/navesdev/recurve/user/domain/UserSummary.java`
- Create: `src/main/resources/search/users-settings.json`
- Create: `src/main/resources/search/users-mapping.json`
- Create: `src/test/java/com/navesdev/recurve/user/domain/UserSummaryTest.java`

**Interfaces:**
- Produces: `record UserSummary(UUID id, String name, String email, Set<Permission> permissions, boolean active, Instant createdAt)`; `static UserSummary of(User user)`. Index `users` (prefixed), fields `name`, `name.keyword`, `email`, `email.keyword`, `permissions`, `active`, `createdAt`, `id`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/navesdev/recurve/user/domain/UserSummaryTest.java`:

```java
package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the listing shows of an operator (FR-01.4), and what it never shows. */
class UserSummaryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Nested
    @DisplayName("FR-01.4 the listing shows an operator with their permissions")
    class Projection {

        @Test
        void everyFieldTheListingShowsComesFromTheOperator() {
            User user = User.create("Ada Lovelace", "ada@recurve.local", "$2a$10$hash",
                    Set.of(Permission.MANAGE_USERS), NOW);

            UserSummary summary = UserSummary.of(user);

            assertThat(summary.id()).isEqualTo(user.getId());
            assertThat(summary.name()).isEqualTo("Ada Lovelace");
            assertThat(summary.email()).isEqualTo("ada@recurve.local");
            assertThat(summary.permissions()).containsExactly(Permission.MANAGE_USERS);
            assertThat(summary.active()).isTrue();
            assertThat(summary.createdAt()).isEqualTo(NOW);
        }

        @Test
        void aDeactivatedOperatorIsSummarizedAsInactive() {
            User user = User.create("Ada Lovelace", "ada@recurve.local", "$2a$10$hash", Set.of(), NOW);
            user.deactivate();

            assertThat(UserSummary.of(user).active()).isFalse();
        }
    }

    @Nested
    @DisplayName("NFR-04 a password never leaves the system")
    class NoPassword {

        @Test
        void theSummaryHasNoPlaceForAPasswordHash() {
            assertThat(Arrays.stream(UserSummary.class.getRecordComponents()).map(c -> c.getName()))
                    .doesNotContain("passwordHash", "password");
        }
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserSummaryTest 2>&1 | tail -15`
Expected: compilation error, `UserSummary` cannot be found.

- [ ] **Step 3: Write the record**

Create `src/main/java/com/navesdev/recurve/user/domain/UserSummary.java`:

```java
package com.navesdev.recurve.user.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * What a listing shows of an operator (FR-01.4): the read model, indexed
 * in Elasticsearch and served from there (FR-06, FR-07). {@link User} is
 * the write model; this is a projection of it, rebuilt from it at any
 * time, and holds no rule.
 *
 * <p>The password hash has no field here, so it cannot reach the index
 * or a response by omission.
 *
 * <p>Mapping annotations only, the same discipline as the JPA annotations
 * on {@link User}. The index name takes the configurable prefix so that
 * integration tests never share an index with development. The index is
 * created by {@code UserIndexBootstrap}, never on demand, so that it
 * always carries the analyzers from {@code search/users-settings.json}.
 */
@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}users", createIndex = false)
@Setting(settingPath = "search/users-settings.json")
@Mapping(mappingPath = "search/users-mapping.json")
public record UserSummary(
        @Id UUID id,
        String name,
        String email,
        Set<Permission> permissions,
        boolean active,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt) {

    public static UserSummary of(User user) {
        return new UserSummary(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPermissions(),
                user.isActive(),
                user.getCreatedAt());
    }
}
```

- [ ] **Step 4: Write the index settings**

Create `src/main/resources/search/users-settings.json`:

```json
{
  "index": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "analysis": {
    "filter": {
      "word_prefix": {
        "type": "edge_ngram",
        "min_gram": 1,
        "max_gram": 20
      }
    },
    "normalizer": {
      "lowercase": {
        "type": "custom",
        "filter": ["lowercase"]
      }
    },
    "analyzer": {
      "prefix": {
        "type": "custom",
        "tokenizer": "standard",
        "filter": ["lowercase", "word_prefix"]
      },
      "prefix_search": {
        "type": "custom",
        "tokenizer": "standard",
        "filter": ["lowercase"]
      }
    }
  }
}
```

FR-06 semantics live here: at index time every word is expanded into its prefixes (`lovelace` → `l`, `lo`, … `lovelace`); at search time the typed text is only lower-cased and split into words, so `"love"` matches and `"velace"` does not.

- [ ] **Step 5: Write the index mapping**

Create `src/main/resources/search/users-mapping.json`:

```json
{
  "properties": {
    "id": { "type": "keyword" },
    "name": {
      "type": "text",
      "analyzer": "prefix",
      "search_analyzer": "prefix_search",
      "fields": {
        "keyword": { "type": "keyword", "normalizer": "lowercase" }
      }
    },
    "email": {
      "type": "text",
      "analyzer": "prefix",
      "search_analyzer": "prefix_search",
      "fields": {
        "keyword": { "type": "keyword", "normalizer": "lowercase" }
      }
    },
    "permissions": { "type": "keyword" },
    "active": { "type": "boolean" },
    "createdAt": { "type": "date", "format": "date_time" }
  }
}
```

`name.keyword` / `email.keyword` exist for sorting (FR-06.1), lower-cased so the order is case-insensitive. `id` is a keyword for the fixed secondary sort key (FR-07.4).

- [ ] **Step 6: Run the test**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserSummaryTest 2>&1 | tail -15`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 7: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src/main/java/com/navesdev/recurve/user/domain/UserSummary.java server/src/main/resources/search server/src/test/java/com/navesdev/recurve/user/domain/UserSummaryTest.java && git commit -m "feat(server-user): add the operator read model and its index definition

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: `UserSearchQueries` — filter and page become a query

**Files:**
- Create: `src/main/java/com/navesdev/recurve/user/repository/UserSearchQueries.java`
- Create: `src/test/java/com/navesdev/recurve/user/repository/UserSearchQueriesTest.java`

**Interfaces:**
- Consumes: `UserFilter(String text, Map<UserFilterField, List<String>> criteria)`, `UserFilterField.ACTIVE` with `field()` = `"active"`.
- Produces: `static NativeQuery UserSearchQueries.from(UserFilter filter, Pageable pageable)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/navesdev/recurve/user/repository/UserSearchQueriesTest.java`:

```java
package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

/**
 * The query is inspected as data: what a filter turns into, without a
 * server. Whether Elasticsearch answers it as FR-06 expects is the
 * integration test's job.
 */
class UserSearchQueriesTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by("name"));

    @Nested
    @DisplayName("FR-06.1 search by name and email")
    class Searching {

        @Test
        void anAbsentSearchAsksForNothingInParticular() {
            assertThat(bool(UserFilter.of(null)).must()).isEmpty();
            assertThat(bool(UserFilter.of("   ")).must()).isEmpty();
        }

        @Test
        void aSearchMustMatchOverNameOrEmailWithEveryWord() {
            var match = bool(UserFilter.of("  Ada  ")).must().getFirst().multiMatch();

            assertThat(match.query()).isEqualTo("Ada");
            assertThat(match.fields()).containsExactly("name", "email");
            assertThat(match.operator()).isEqualTo(Operator.And);
        }
    }

    @Nested
    @DisplayName("FR-06 filters combine with AND, values of one filter with OR")
    class Filtering {

        @Test
        void anAbsentFilterRestrictsNothing() {
            assertThat(bool(UserFilter.of(null)).filter()).isEmpty();
        }

        @Test
        void aFilterBecomesATermsClauseOverItsField() {
            var terms = bool(activeIn("true")).filter().getFirst().terms();

            assertThat(terms.field()).isEqualTo("active");
            assertThat(terms.terms().value()).singleElement()
                    .satisfies(value -> assertThat(value.booleanValue()).isTrue());
        }

        @Test
        void severalValuesOfOneFilterTravelInTheSameTermsClause() {
            var terms = bool(activeIn("true", "false")).filter().getFirst().terms();

            assertThat(terms.terms().value()).hasSize(2);
        }

        @Test
        void aSearchAndAFilterAreBothRequired() {
            BoolQuery both = bool(new UserFilter("ada",
                    Map.of(UserFilterField.ACTIVE, List.of("true"))));

            assertThat(both.must()).hasSize(1);
            assertThat(both.filter()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("FR-06.1 and FR-07 sort, then paginate")
    class SortingAndPaging {

        @Test
        void textFieldsAreSortedOnTheirKeywordCopy() {
            var sorts = UserSearchQueries.from(UserFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "email"))).getSortOptions();

            assertThat(sorts.getFirst().field().field()).isEqualTo("email.keyword");
            assertThat(sorts.getFirst().field().order()).isEqualTo(SortOrder.Desc);
        }

        @Test
        void theCreationDateIsSortedAsItself() {
            var sorts = UserSearchQueries.from(UserFilter.of(null),
                    PageRequest.of(0, 20, Sort.by("createdAt"))).getSortOptions();

            assertThat(sorts.getFirst().field().field()).isEqualTo("createdAt");
        }

        @Test
        void everySortKeyRequestedIsKeptInOrder() {
            // FR-07.4: the controller appends id ascending; the query must
            // keep it after the field the caller chose.
            var sorts = UserSearchQueries.from(UserFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "name").and(Sort.by("id"))))
                    .getSortOptions();

            assertThat(sorts).hasSize(2);
            assertThat(sorts.get(1).field().field()).isEqualTo("id");
            assertThat(sorts.get(1).field().order()).isEqualTo(SortOrder.Asc);
        }

        @Test
        void thePageIsCarriedWithoutItsSortSoItIsNotAppliedTwice() {
            NativeQuery query = UserSearchQueries.from(UserFilter.of(null), PageRequest.of(3, 10, Sort.by("name")));

            assertThat(query.getPageable().getPageNumber()).isEqualTo(3);
            assertThat(query.getPageable().getPageSize()).isEqualTo(10);
            assertThat(query.getPageable().getSort().isUnsorted()).isTrue();
        }
    }

    private static BoolQuery bool(UserFilter filter) {
        return UserSearchQueries.from(filter, FIRST_PAGE).getQuery().bool();
    }

    private static UserFilter activeIn(String... values) {
        return new UserFilter(null, Map.of(UserFilterField.ACTIVE, List.of(values)));
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserSearchQueriesTest 2>&1 | tail -15`
Expected: compilation error, `UserSearchQueries` cannot be found.

- [ ] **Step 3: Write the query builder**

Create `src/main/java/com/navesdev/recurve/user/repository/UserSearchQueries.java`:

```java
package com.navesdev.recurve.user.repository;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;

/**
 * Turns a listing filter and page into an Elasticsearch query (FR-06,
 * FR-07). Knows no business rule: which fields may be filtered or sorted
 * is decided by the allow-lists in the service, and the values arrive
 * already validated.
 *
 * <p>The search is a {@code multi_match} over the prefix-analyzed
 * {@code name} and {@code email} (see {@code search/users-settings.json}):
 * every word typed must be the start of a word in either field. Filters
 * go in the {@code filter} context, so they neither score nor need to.
 */
public final class UserSearchQueries {

    private static final List<String> SEARCHED_FIELDS = List.of("name", "email");

    private UserSearchQueries() {
    }

    public static NativeQuery from(UserFilter filter, Pageable pageable) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (filter.text() != null && !filter.text().isBlank()) {
            bool.must(matchesText(filter.text().trim()));
        }

        for (UserFilterField field : filter.criteria().keySet()) {
            List<String> values = filter.valuesOf(field);
            if (!values.isEmpty()) {
                bool.filter(matches(field, values));
            }
        }

        return NativeQuery.builder()
                .withQuery(Query.of(query -> query.bool(bool.build())))
                .withSort(sortOf(pageable.getSort()))
                // Page number and size only: the sort travels as sort options
                // above, and a sorted Pageable would apply it a second time.
                .withPageable(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .build();
    }

    private static Query matchesText(String text) {
        return Query.of(query -> query.multiMatch(match -> match
                .query(text)
                .fields(SEARCHED_FIELDS)
                .type(TextQueryType.CrossFields)
                .operator(Operator.And)));
    }

    /** FR-06: several values of one field combine with OR — one terms clause. */
    private static Query matches(UserFilterField field, List<String> values) {
        List<FieldValue> terms = switch (field) {
            case ACTIVE -> values.stream()
                    .map(Boolean::parseBoolean)
                    .distinct()
                    .map(FieldValue::of)
                    .toList();
        };

        return Query.of(query -> query.terms(t -> t
                .field(field.field())
                .terms(v -> v.value(terms))));
    }

    /**
     * A text field is sorted on its {@code .keyword} copy; every other
     * field on itself. The order of keys is kept: the last one is the
     * fixed secondary key that makes paging stable (FR-07.4).
     */
    private static List<SortOptions> sortOf(Sort sort) {
        return sort.stream()
                .map(order -> SortOptions.of(options -> options.field(field -> field
                        .field(sortField(order.getProperty()))
                        .order(order.isAscending() ? SortOrder.Asc : SortOrder.Desc))))
                .toList();
    }

    private static String sortField(String property) {
        return SEARCHED_FIELDS.contains(property) ? property + ".keyword" : property;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserSearchQueriesTest 2>&1 | tail -15`
Expected: BUILD SUCCESS, 10 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src/main/java/com/navesdev/recurve/user/repository/UserSearchQueries.java server/src/test/java/com/navesdev/recurve/user/repository/UserSearchQueriesTest.java && git commit -m "feat(server-user): turn a listing filter into a search query

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: `UserSearchRepository` against a real node

**Files:**
- Create: `src/main/java/com/navesdev/recurve/user/repository/UserSearchRepository.java`
- Create: `src/testIntegration/java/com/navesdev/recurve/user/repository/UserSearchRepositoryIT.java`

**Interfaces:**
- Consumes: `UserSearchQueries.from(UserFilter, Pageable)`, `UserSummary`.
- Produces (all public, on a `@Repository` class):
  - `UserSummary save(UserSummary summary)` — visible to search immediately.
  - `void saveAll(Collection<UserSummary> summaries)` — bulk, no refresh (for reindex).
  - `Page<UserSummary> search(UserFilter filter, Pageable pageable)`.
  - `boolean indexExists()`.
  - `void recreateIndex()` — drop if present, create with settings + mapping.
  - `void refresh()`.

- [ ] **Step 1: Write the failing integration test**

Create `src/testIntegration/java/com/navesdev/recurve/user/repository/UserSearchRepositoryIT.java`:

```java
package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.elasticsearch.test.autoconfigure.DataElasticsearchTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

/**
 * The listing rules (FR-06, FR-07) against a real Elasticsearch, on the
 * index the mapping in {@code search/} produces. The index is recreated
 * before each test: there is no transaction to roll back.
 */
@DataElasticsearchTest
@Import(UserSearchRepository.class)
class UserSearchRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private UserSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository.recreateIndex();
    }

    @Nested
    @DisplayName("FR-06 searching and filtering")
    class SearchingAndFiltering {

        @BeforeEach
        void index() {
            UserSearchRepositoryIT.this.index("Ada Lovelace", "ada@recurve.local");
            UserSearchRepositoryIT.this.index("Grace Hopper", "grace@recurve.local");
            UserSearchRepositoryIT.this.index("Alan Turing", "alan@example.com");
        }

        @Test
        void theSearchMatchesAWholeWordOfAName() {
            assertThat(names(search(UserFilter.of("Lovelace")))).containsExactly("Ada Lovelace");
        }

        @Test
        void theSearchMatchesTheStartOfAnyWordOfAName() {
            assertThat(names(search(UserFilter.of("Love")))).containsExactly("Ada Lovelace");
            assertThat(names(search(UserFilter.of("hopp")))).containsExactly("Grace Hopper");
        }

        @Test
        void theSearchMatchesTheStartOfAnEmail() {
            assertThat(names(search(UserFilter.of("ala")))).containsExactly("Alan Turing");
        }

        @Test
        void theSearchMatchesTheDomainOfAnEmail() {
            assertThat(names(search(UserFilter.of("example.com")))).containsExactly("Alan Turing");
        }

        @Test
        void theSearchIgnoresCase() {
            assertThat(names(search(UserFilter.of("lovelace"))))
                    .isEqualTo(names(search(UserFilter.of("LOVELACE"))));
        }

        @Test
        void theSearchDoesNotMatchFromTheMiddleOfAWord() {
            assertThat(search(UserFilter.of("urin"))).isEmpty();
            assertThat(search(UserFilter.of("velace"))).isEmpty();
        }

        @Test
        void everyWordTypedMustMatch() {
            assertThat(names(search(UserFilter.of("ada love")))).containsExactly("Ada Lovelace");
            assertThat(search(UserFilter.of("ada hopper"))).isEmpty();
        }

        @Test
        void anAbsentSearchMatchesEveryone() {
            assertThat(search(UserFilter.of(null))).hasSize(3);
            assertThat(search(UserFilter.of("  "))).hasSize(3);
        }

        @Test
        void filteringSeparatesActiveFromInactiveOperators() {
            deactivate("alan@example.com");

            assertThat(search(filteredBy("true"))).hasSize(2);
            assertThat(search(filteredBy("false"))).hasSize(1);
        }

        @Test
        void anAbsentFilterMatchesBoth() {
            deactivate("alan@example.com");

            assertThat(search(UserFilter.of(null))).hasSize(3);
        }

        @Test
        void severalValuesOfOneFilterMatchAnyOfThem() {
            deactivate("alan@example.com");

            assertThat(search(filteredBy("true", "false"))).hasSize(3);
        }

        @Test
        void aSearchAndAFilterMustBothMatch() {
            deactivate("ada@recurve.local");

            UserFilter both = new UserFilter("recurve.local",
                    Map.of(UserFilterField.ACTIVE, List.of("true")));

            assertThat(names(search(both))).containsExactly("Grace Hopper");
        }
    }

    @Nested
    @DisplayName("FR-06.1 sorting")
    class Sorting {

        @BeforeEach
        void index() {
            UserSearchRepositoryIT.this.index("bob Builder", "zed@recurve.local");
            UserSearchRepositoryIT.this.index("Ada Lovelace", "ada@recurve.local");
            UserSearchRepositoryIT.this.index("Carol Danvers", "mid@recurve.local");
        }

        @Test
        void byNameIgnoresCase() {
            assertThat(names(page(0, 10, Sort.by("name"))))
                    .containsExactly("Ada Lovelace", "bob Builder", "Carol Danvers");
        }

        @Test
        void byEmailDescending() {
            assertThat(page(0, 10, Sort.by(Sort.Direction.DESC, "email")).map(UserSummary::email))
                    .containsExactly("zed@recurve.local", "mid@recurve.local", "ada@recurve.local");
        }

        @Test
        void byCreationDate() {
            repository.recreateIndex();
            repository.save(summary("Second", "second@recurve.local", NOW.plusSeconds(60)));
            repository.save(summary("First", "first@recurve.local", NOW));

            assertThat(names(page(0, 10, Sort.by("createdAt")))).containsExactly("First", "Second");
        }
    }

    @Nested
    @DisplayName("FR-07 pagination")
    class Pagination {

        @Test
        void theTotalCountsEveryMatchNotJustThePage() {
            indexMany(5, "Operator");

            Page<UserSummary> firstPage = page(0, 2, Sort.by("name"));

            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(firstPage.getTotalElements()).isEqualTo(5);
        }

        @Test
        void theTotalCountsWhatMatchedTheSearchNotTheWholeIndex() {
            indexMany(5, "Operator");
            index("Ada Lovelace", "ada@recurve.local");

            Page<UserSummary> found = repository.search(UserFilter.of("Lovelace"),
                    PageRequest.of(0, 2, Sort.by("name")));

            assertThat(found.getTotalElements()).isEqualTo(1);
        }

        @Test
        void aPageBeyondTheEndIsEmptyButStillReportsTheTotal() {
            indexMany(3, "Operator");

            Page<UserSummary> beyond = page(50, 20, Sort.by("name"));

            assertThat(beyond.getContent()).isEmpty();
            assertThat(beyond.getTotalElements()).isEqualTo(3);
        }

        @Test
        void everyOperatorAppearsExactlyOnceWhenPagingThroughTiedNames() {
            // FR-07.4: every operator here sorts identically by name, so
            // only the secondary key keeps the pages from overlapping.
            indexMany(5, "Same Name");

            List<UUID> seen = new ArrayList<>();
            Sort byTiedField = Sort.by("name").ascending().and(Sort.by("id").ascending());
            for (int number = 0; number < 3; number++) {
                page(number, 2, byTiedField).forEach(operator -> seen.add(operator.id()));
            }

            assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        }
    }

    private Page<UserSummary> page(int number, int size, Sort sort) {
        return repository.search(UserFilter.of(null), PageRequest.of(number, size, sort));
    }

    private Page<UserSummary> search(UserFilter filter) {
        return repository.search(filter, PageRequest.of(0, 20, Sort.by("name")));
    }

    private static UserFilter filteredBy(String... active) {
        return new UserFilter(null, Map.of(UserFilterField.ACTIVE, List.of(active)));
    }

    private static List<String> names(Page<UserSummary> page) {
        return page.getContent().stream().map(UserSummary::name).toList();
    }

    private void indexMany(int howMany, String sharedName) {
        for (int index = 0; index < howMany; index++) {
            index(sharedName, "operator%d@recurve.local".formatted(index));
        }
    }

    private UserSummary index(String name, String email) {
        return repository.save(summary(name, email, NOW));
    }

    private static UserSummary summary(String name, String email, Instant createdAt) {
        return new UserSummary(UUID.randomUUID(), name, email, Set.of(Permission.VIEW_USERS), true, createdAt);
    }

    private void deactivate(String email) {
        UserSummary current = search(UserFilter.of(email)).getContent().getFirst();
        User user = User.create(current.name(), current.email(), "$2a$10$hash", current.permissions(), NOW);
        user.deactivate();
        // Same id, so the document is replaced rather than added.
        repository.save(new UserSummary(current.id(), user.getName(), user.getEmail(),
                user.getPermissions(), user.isActive(), current.createdAt()));
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify -Pintegration -Dit.test=UserSearchRepositoryIT 2>&1 | tail -15`
Expected: compilation error, `UserSearchRepository` cannot be found.

- [ ] **Step 3: Write the repository**

Create `src/main/java/com/navesdev/recurve/user/repository/UserSearchRepository.java`:

```java
package com.navesdev.recurve.user.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitSupport;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;

import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.service.UserFilter;

import lombok.RequiredArgsConstructor;

/**
 * The operator read model's store (FR-06, FR-07). A narrow surface on
 * purpose, in the spirit of {@code BaseRepository}: it indexes, searches
 * and rebuilds — nothing deletes a single document, because nothing in
 * Recurve deletes a record; an index is only ever rebuilt whole.
 *
 * <p>A single {@link #save} is visible to the next search at once
 * (refresh on write): an operator who deactivates a colleague expects the
 * listing to say so on the next request, and the write volume here is
 * small. A rebuild indexes in bulk without refreshing and refreshes once
 * at the end.
 */
@Repository
@RequiredArgsConstructor
public class UserSearchRepository {

    private final ElasticsearchOperations operations;

    public UserSummary save(UserSummary summary) {
        return operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(summary);
    }

    /** Bulk, not refreshed: for a rebuild, which calls {@link #refresh()} once at the end. */
    public void saveAll(Collection<UserSummary> summaries) {
        operations.withRefreshPolicy(RefreshPolicy.NONE).save(summaries);
    }

    public Page<UserSummary> search(UserFilter filter, Pageable pageable) {
        SearchHits<UserSummary> hits = operations.search(
                UserSearchQueries.from(filter, pageable), UserSummary.class);

        return SearchHitSupport.searchPageFor(hits, pageable).map(SearchHit::getContent);
    }

    public boolean indexExists() {
        return indexOps().exists();
    }

    /** Drops what is there and creates the index with the settings and mapping in {@code search/}. */
    public void recreateIndex() {
        IndexOperations index = indexOps();
        if (index.exists()) {
            index.delete();
        }
        index.createWithMapping();
    }

    public void refresh() {
        indexOps().refresh();
    }

    private IndexOperations indexOps() {
        return operations.indexOps(UserSummary.class);
    }
}
```

- [ ] **Step 4: Run the integration test**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify -Pintegration -Dit.test=UserSearchRepositoryIT 2>&1 | tail -30`
Expected: BUILD SUCCESS, 19 tests pass.

If `@Id` on a record component is not picked up (an error mentioning "no id property"), change `UserSummary` from a `record` into a final class with private fields, a private no-arg constructor, an all-args constructor and accessor methods named like the record components (`id()`, `name()`, …); keep `UserSummaryTest` as is except the reflection test, which then checks `getDeclaredFields()` names.

- [ ] **Step 5: Confirm the test index name**

Run: `curl -s 'localhost:9230/_cat/indices?v' | grep users`
Expected: a `test-users` index; no `users` index yet.

- [ ] **Step 6: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src/main/java/com/navesdev/recurve/user/repository/UserSearchRepository.java server/src/testIntegration/java/com/navesdev/recurve/user/repository/UserSearchRepositoryIT.java && git commit -m "feat(server-user): index and search operators in Elasticsearch

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: The service coordinates both stores; the listing is served from the index

**Files:**
- Modify: `src/main/java/com/navesdev/recurve/user/service/UserService.java`
- Modify: `src/main/java/com/navesdev/recurve/user/repository/UserRepository.java`
- Modify: `src/main/java/com/navesdev/recurve/user/controller/UserResponse.java`
- Modify: `src/main/java/com/navesdev/recurve/user/controller/UserController.java` (the `search` method only)
- Modify: `src/test/java/com/navesdev/recurve/user/service/UserServiceTest.java`
- Modify: `src/test/java/com/navesdev/recurve/user/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: `UserSearchRepository` (Task 6), `UserSummary.of(User)`.
- Produces:
  - `Page<UserSummary> UserService.search(UserFilter, Pageable)`
  - `long UserService.reindex()` — `@PreAuthorize("hasAuthority('MANAGE_SYSTEM')")`
  - `long UserService.reindexInternal()` — package-private, no authorization
  - `Stream<User> UserRepository.streamAll()`
  - `UserResponse.from(UserSummary)`

- [ ] **Step 1: Write the failing service tests**

In `src/test/java/com/navesdev/recurve/user/service/UserServiceTest.java`:

Add imports:

```java
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserSearchRepository;
```

Add a mock next to the existing ones:

```java
    @Mock
    private UserSearchRepository searchRepository;
```

Update the `@BeforeEach` that builds the service so it passes `searchRepository` as the second constructor argument: `service = new UserService(repository, searchRepository, passwordEncoder, clock);` (read the existing line and adjust; the constructor is Lombok-generated in field order).

Add these nested classes at the end of the test class (before the private helpers):

```java
    @Nested
    @DisplayName("FR-06 the listing is served from the search index")
    class Indexing {

        @Test
        void aRegisteredOperatorIsIndexedAsItWasSaved() {
            when(repository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            User created = service.create(command("ada@recurve.local"));

            verify(searchRepository).save(argThat(summary ->
                    summary.id().equals(created.getId()) && summary.email().equals("ada@recurve.local")));
        }

        @Test
        void anUpdatedOperatorIsReindexed() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.existsByEmailAndIdNot(anyString(), any())).thenReturn(false);
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            service.update(new UpdateUserCommand(id, "Ada Lovelace", "ada@recurve.local", Set.of()));

            verify(searchRepository).save(argThat(summary -> summary.name().equals("Ada Lovelace")));
        }

        @Test
        void aDeactivatedOperatorIsReindexedAsInactive() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            service.deactivate(id);

            verify(searchRepository).save(argThat(summary -> !summary.active()));
        }

        @Test
        void anIndexingFailureFailsTheWrite() {
            // Fail-fast: the caller learns that nothing is consistent, and
            // the container rolls the database write back (UserServiceIT).
            when(repository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(UserSummary.class));

            assertThatThrownBy(() -> service.create(command("ada@recurve.local")))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        void theListingAsksTheIndexAndNeverTheDatabase() {
            UserFilter filter = UserFilter.of("ada");
            Pageable page = PageRequest.of(0, 20);
            Page<UserSummary> expected = new PageImpl<>(List.of());
            when(searchRepository.search(filter, page)).thenReturn(expected);

            assertThat(service.search(filter, page)).isSameAs(expected);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("FR-01.5 the index is rebuilt from the database")
    class Reindexing {

        @Test
        void theIndexIsRecreatedAndEveryOperatorIndexed() {
            User ada = User.create("Ada", "ada@recurve.local", "$2a$10$hash", Set.of(), NOW);
            User grace = User.create("Grace", "grace@recurve.local", "$2a$10$hash", Set.of(), NOW);
            when(repository.streamAll()).thenReturn(Stream.of(ada, grace));

            long indexed = service.reindex();

            assertThat(indexed).isEqualTo(2);
            verify(searchRepository).recreateIndex();
            verify(searchRepository).saveAll(argThat(batch -> batch.size() == 2));
            verify(searchRepository).refresh();
        }

        @Test
        void anEmptyDatabaseStillLeavesAFreshIndexBehind() {
            when(repository.streamAll()).thenReturn(Stream.empty());

            assertThat(service.reindex()).isZero();
            verify(searchRepository).recreateIndex();
            verify(searchRepository, never()).saveAll(any());
        }
    }
```

`NOW` and `command(...)`/`existingOperator()` already exist in this test class; reuse them. If `NOW` is not a constant, add `private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");`.

- [ ] **Step 2: Run them and see them fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserServiceTest 2>&1 | tail -15`
Expected: compilation errors (`UserSearchRepository` constructor, `streamAll`, `reindex`).

- [ ] **Step 3: Add `streamAll` to the JPA repository**

Replace `src/main/java/com/navesdev/recurve/user/repository/UserRepository.java` with:

```java
package com.navesdev.recurve.user.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.Query;

import com.navesdev.recurve.shared.repository.BaseRepository;
import com.navesdev.recurve.user.domain.User;

public interface UserRepository extends BaseRepository<User, UUID> {

    /** The login lookup (FR-05.1). */
    Optional<User> findByEmail(String email);

    /** BR-02, on creation. */
    boolean existsByEmail(String email);

    /** BR-02, on update: the operator keeping its own email is not a clash. */
    boolean existsByEmailAndIdNot(String email, UUID id);

    /**
     * Every operator, for rebuilding the search index (FR-01.5) — not a
     * listing, which is always paginated (FR-07). A stream, so the rebuild
     * walks the table without holding it in memory; the caller closes it.
     */
    @Query("select u from User u")
    Stream<User> streamAll();
}
```

- [ ] **Step 4: Rewrite the service**

Replace `src/main/java/com/navesdev/recurve/user/service/UserService.java` with:

```java
package com.navesdev.recurve.user.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.domain.exception.EmailAlreadyInUseException;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case: it loads,
 * calls the domain and persists, holding no business rule of its own —
 * only the rules that need the database, such as email uniqueness.
 *
 * <p>Two stores: PostgreSQL holds the operator and is the source of truth;
 * Elasticsearch holds the read model the listing is served from (FR-06,
 * FR-07). Every write goes to both inside the same transaction, so that a
 * failure to index rolls the write back — fail-fast, never a silent
 * divergence. The index can always be rebuilt from the database.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private static final int REINDEX_BATCH = 500;

    private final UserRepository repository;
    private final UserSearchRepository searchRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public User create(CreateUserCommand command) {
        String email = normalize(command.email());

        if (repository.existsByEmail(email)) {
            throw new EmailAlreadyInUseException(email);
        }

        User user = User.create(
                command.name(),
                email,
                passwordEncoder.encode(command.rawPassword()),
                command.permissions(),
                clock.instant());

        return persist(user);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public User update(UpdateUserCommand command) {
        User user = findOrThrow(command.id());
        String email = normalize(command.email());

        if (repository.existsByEmailAndIdNot(email, command.id())) {
            throw new EmailAlreadyInUseException(email);
        }

        user.rename(command.name());
        user.changeEmail(email);
        user.replacePermissions(command.permissions());

        return persist(user);
    }

    /** FR-01.3: deactivate without deleting. */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public User deactivate(UUID id) {
        User user = findOrThrow(id);
        user.deactivate();
        return persist(user);
    }

    @PreAuthorize("hasAuthority('VIEW_USERS')")
    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return findOrThrow(id);
    }

    /** FR-06.1 and FR-07: search and filter, then sort, then paginate — all in the index. */
    @PreAuthorize("hasAuthority('VIEW_USERS')")
    @Transactional(readOnly = true)
    public Page<UserSummary> search(UserFilter filter, Pageable pageable) {
        return searchRepository.search(filter, pageable);
    }

    /** FR-01.5: rebuild the index from the database. Returns how many operators were indexed. */
    @PreAuthorize("hasAuthority('MANAGE_SYSTEM')")
    public long reindex() {
        return reindexInternal();
    }

    /**
     * Internal, unchecked: {@link UserIndexBootstrap} runs it at startup,
     * when there is no authenticated operator. Recreating the index rather
     * than overwriting documents is what drops a document whose operator
     * no longer exists.
     */
    long reindexInternal() {
        searchRepository.recreateIndex();

        long indexed = 0;
        List<UserSummary> batch = new ArrayList<>(REINDEX_BATCH);
        try (Stream<User> users = repository.streamAll()) {
            for (User user : (Iterable<User>) users::iterator) {
                batch.add(UserSummary.of(user));
                if (batch.size() == REINDEX_BATCH) {
                    searchRepository.saveAll(batch);
                    indexed += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            searchRepository.saveAll(batch);
            indexed += batch.size();
        }

        searchRepository.refresh();
        return indexed;
    }

    /** Step 3 of every write: the database first, then the index that mirrors it. */
    private User persist(User user) {
        User saved = repository.save(user);
        searchRepository.save(UserSummary.of(saved));
        return saved;
    }

    private User findOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
```

- [ ] **Step 5: Let the response read a summary**

In `src/main/java/com/navesdev/recurve/user/controller/UserResponse.java`, add the import `com.navesdev.recurve.user.domain.UserSummary` and the factory:

```java
    /** The listing (FR-06) answers from the read model, not the entity. */
    public static UserResponse from(UserSummary summary) {
        return new UserResponse(
                summary.id(),
                summary.name(),
                summary.email(),
                summary.permissions(),
                summary.active(),
                summary.createdAt());
    }
```

- [ ] **Step 6: Point the controller's listing at the summary page**

In `UserController.java`, add the import `com.navesdev.recurve.user.domain.UserSummary` and change the line inside `search`:

```java
        Page<UserSummary> found = service.search(filterOf(q, request.getParameterValues("filter")), pageable);
```

(`PageResponse.from(found, UserResponse::from)` now resolves to `from(UserSummary)`.)

- [ ] **Step 7: Fix the controller test's listing fixtures**

In `src/test/java/com/navesdev/recurve/user/controller/UserControllerTest.java`:

Add the import `com.navesdev.recurve.user.domain.UserSummary`.

Every `when(service.search(...)).thenReturn(new PageImpl<>(List.of(operator()), ...))` becomes `new PageImpl<>(List.of(UserSummary.of(operator())), ...)` (the empty-list stubs need no change). Run `grep -n "List.of(operator())" src/test/java/com/navesdev/recurve/user/controller/UserControllerTest.java` — only the occurrences inside `search` stubs change; the `create` stubs keep returning `operator()`.

- [ ] **Step 8: Run the unit suite**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test 2>&1 | tail -20`
Expected: BUILD SUCCESS. `UserServiceTest` and `UserControllerTest` pass, including the 7 new service tests.

- [ ] **Step 9: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src/main/java/com/navesdev/recurve/user server/src/test/java/com/navesdev/recurve/user && git commit -m "feat(server-user): serve the listing from the index and keep it on every write

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Remove the legacy Specification listing

**Files:**
- Delete: `src/main/java/com/navesdev/recurve/user/repository/UserSpecifications.java`
- Modify: `src/main/java/com/navesdev/recurve/shared/repository/BaseRepository.java`
- Modify: `src/testIntegration/java/com/navesdev/recurve/user/repository/UserRepositoryIT.java`
- Modify: `src/testIntegration/java/com/navesdev/recurve/user/UserEndpointAuthorizationIT.java`

**Interfaces:**
- Consumes: `UserSearchRepository.recreateIndex()`, `.save(UserSummary)`.
- Produces: `BaseRepository<T, I> extends Repository<T, I>` only.

- [ ] **Step 1: Delete the specifications and shrink the base repository**

```bash
cd /home/naves/Projetos/Recurve/server && git rm -q src/main/java/com/navesdev/recurve/user/repository/UserSpecifications.java
```

Replace `src/main/java/com/navesdev/recurve/shared/repository/BaseRepository.java` with:

```java
package com.navesdev.recurve.shared.repository;

import java.util.Optional;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The contract every JPA repository in the project follows.
 *
 * <p>It deliberately does not extend {@code JpaRepository}. That interface
 * carries a wide surface — {@code deleteAll}, {@code saveAll}, an
 * unbounded {@code findAll} — that the domain has no use for and that
 * invites a caller to go around a business rule. Nothing in Recurve
 * deletes a record: an operator is deactivated (FR-01.3), a plan and a
 * price are deactivated (FR-02.3, FR-02.4), a subscriber is canceled
 * (FR-03.3). A repository that cannot delete makes that structural rather
 * than a matter of discipline.
 *
 * <p>No listing either: search, filter, sort and pagination (FR-06,
 * FR-07) are served from the search index, by the feature's search
 * repository, never from a JPA query.
 *
 * <p>A feature's repository adds only what its use cases need — a lookup
 * by a natural key, an existence check — and nothing that circumvents the
 * domain.
 *
 * @param <T> the aggregate this repository stores
 * @param <I> its identifier type
 */
@NoRepositoryBean
public interface BaseRepository<T, I> extends Repository<T, I> {

    <S extends T> S save(S entity);

    Optional<T> findById(I id);

    boolean existsById(I id);

    long count();
}
```

- [ ] **Step 2: Trim the JPA repository IT to what the JPA repository still does**

In `UserRepositoryIT.java`:
- Delete the two `@Nested` classes `SearchingAndFiltering` and `Pagination` entirely.
- Delete the private helpers `page`, `search`, `filteredBy`, `names`, `registerMany`, `deactivate`.
- Delete the now-unused imports: `java.util.ArrayList`, `java.util.List`, `java.util.Map`, `java.util.UUID`, `org.springframework.data.domain.Page`, `org.springframework.data.domain.PageRequest`, `org.springframework.data.domain.Sort`, `com.navesdev.recurve.user.service.UserFilter`, `com.navesdev.recurve.user.service.UserFilterField`.
- Change the class javadoc to:

```java
/**
 * What the JPA repository still answers for on its own — the login
 * lookup (FR-05.1) and the uniqueness checks (BR-02) — against a real
 * PostgreSQL, on the schema the migrations produced. Each test rolls
 * back. The listing rules live in {@code UserSearchRepositoryIT}.
 */
```

What remains: `SigningIn`, `UniqueEmail`, `register`.

- [ ] **Step 3: Make the authorization IT index its fixtures**

The listing now reads the index, and this test seeds through the repository, so it must seed the index too. In `UserEndpointAuthorizationIT.java`:

Add imports:

```java
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserSearchRepository;
```

Add a field:

```java
    @Autowired
    private UserSearchRepository searchRepository;
```

At the top of `setUp()`, before the `TRUNCATE`, add:

```java
        // The database write rolls back with the test; the index does not,
        // so it is rebuilt from scratch instead.
        searchRepository.recreateIndex();
```

Change `register(...)` to index as well:

```java
    private void register(String name, String email, Set<Permission> permissions, boolean active) {
        User operator = User.create(name, email, passwordEncoder.encode(PASSWORD), permissions, NOW);
        if (!active) {
            operator.deactivate();
        }
        searchRepository.save(UserSummary.of(repository.save(operator)));
    }
```

- [ ] **Step 4: Run both suites**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify 2>&1 | tail -30`
Expected: BUILD SUCCESS. Unit: `PermissionTest`, `UserSummaryTest`, `UserSearchQueriesTest`, `UserTest`, `UserServiceTest`, `UserControllerTest`. Integration: `RecurveApplicationIT`, `UserRepositoryIT` (6 tests), `UserSearchRepositoryIT`, `UserEndpointAuthorizationIT`.

- [ ] **Step 5: Confirm nothing references the old listing**

Run: `cd /home/naves/Projetos/Recurve/server && grep -rn "UserSpecifications\|JpaSpecificationExecutor\|Specification<" src/ | cat`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add -A server/src && git commit -m "refactor(server-user): drop the Specification listing

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Reindex endpoint and the 503 for an unavailable store

**Files:**
- Create: `src/main/java/com/navesdev/recurve/user/controller/ReindexResponse.java`
- Modify: `src/main/java/com/navesdev/recurve/user/controller/UserController.java`
- Modify: `src/main/java/com/navesdev/recurve/shared/controller/GlobalExceptionHandler.java`
- Modify: `src/test/java/com/navesdev/recurve/user/controller/UserControllerTest.java`
- Modify: `src/testIntegration/java/com/navesdev/recurve/user/UserEndpointAuthorizationIT.java`

**Interfaces:**
- Consumes: `UserService.reindex(): long`.
- Produces: `POST /api/users/reindex` → `200 {"indexed": n}`; `DataAccessResourceFailureException` → `503`.

- [ ] **Step 1: Write the failing controller tests**

In `UserControllerTest.java`, add the import `org.springframework.dao.DataAccessResourceFailureException` and the nested classes:

```java
    @Nested
    @DisplayName("FR-01.5 the search index is rebuilt on request")
    class Reindexing {

        @Test
        void theResponseSaysHowManyOperatorsWereIndexed() throws Exception {
            when(service.reindex()).thenReturn(42L);

            mvc.perform(post("/api/users/reindex"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(42));
        }
    }

    @Nested
    @DisplayName("Fail-fast: a store that cannot be reached is reported, not hidden")
    class StoreUnavailable {

        @Test
        void anUnreachableStoreIsAServiceUnavailableWithoutItsDetails() throws Exception {
            when(service.search(any(UserFilter.class), any()))
                    .thenThrow(new DataAccessResourceFailureException("connect to localhost:9230 refused"));

            mvc.perform(get("/api/users"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.message").value("A backing service is unavailable"));
        }
    }
```

- [ ] **Step 2: Run them and see them fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserControllerTest 2>&1 | tail -15`
Expected: the reindex test fails with 404 (or 405); the 503 test fails with 500.

- [ ] **Step 3: Add the response and the endpoint**

Create `src/main/java/com/navesdev/recurve/user/controller/ReindexResponse.java`:

```java
package com.navesdev.recurve.user.controller;

/** How many operators the rebuilt index holds (FR-01.5). */
public record ReindexResponse(long indexed) {
}
```

In `UserController.java`, after the `findById` endpoint and before `search`, add:

```java
    /** FR-01.5: rebuilds the search index from the database. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(service.reindex());
    }
```

- [ ] **Step 4: Map the unavailable store**

In `GlobalExceptionHandler.java`, add the import `org.springframework.dao.DataAccessResourceFailureException` and, after `handleAccessDenied`, add:

```java
    /**
     * Fail-fast: a store that cannot be reached fails the request loudly.
     * The message is fixed — the exception names hosts and ports.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiError> handleStoreUnavailable(DataAccessResourceFailureException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "A backing service is unavailable");
    }
```

- [ ] **Step 5: Run the controller tests**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=UserControllerTest 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Write the failing authorization tests**

In `UserEndpointAuthorizationIT.java`, in `setUp()` after the `Rita Retired` line, add:

```java
        register("Sam Sysadmin", "sysadmin@recurve.local", Set.of(Permission.MANAGE_SYSTEM), true);
```

Every existing count in this class grows by one active `recurve.local` operator: in `manageImpliesViewSoAManagerMayList` change `$.total` from 4 to 5; in `searchesFiltersAndPaginatesInOneRequest` change `$.total` from 3 to 4 (the first item, sorted by `-email`, is still `viewer@recurve.local`).

Add the nested class:

```java
    @Nested
    @DisplayName("FR-01.5 rebuilding the index is a system operation")
    class Reindexing {

        @Test
        void managingOperatorsIsNotEnough() throws Exception {
            mvc.perform(post("/api/users/reindex").with(basic("manager@recurve.local")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void managingTheSystemRebuildsTheIndexFromTheDatabase() throws Exception {
            mvc.perform(post("/api/users/reindex").with(basic("sysadmin@recurve.local")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(5));
        }
    }
```

- [ ] **Step 7: Run the integration test**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify -Pintegration -Dit.test=UserEndpointAuthorizationIT 2>&1 | tail -20`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src && git commit -m "feat(server-user): rebuild the search index on request

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: Boot creates the index; the bootstrap admin is indexed; fail-fast is verified end to end

**Files:**
- Create: `src/main/java/com/navesdev/recurve/user/service/UserIndexBootstrap.java`
- Modify: `src/main/java/com/navesdev/recurve/user/service/OperatorBootstrap.java`
- Create: `src/test/java/com/navesdev/recurve/user/service/OperatorBootstrapTest.java`
- Create: `src/testIntegration/java/com/navesdev/recurve/user/service/UserIndexBootstrapIT.java`
- Create: `src/testIntegration/java/com/navesdev/recurve/user/service/UserServiceIT.java`

**Interfaces:**
- Consumes: `UserService.reindexInternal()`, `UserSearchRepository.indexExists()`, `OperatorBootstrap`.
- Produces: `UserIndexBootstrap implements ApplicationRunner`, `@Order(1)`; `OperatorBootstrap` `@Order(2)` and indexing the admin.

- [ ] **Step 1: Write the failing bootstrap unit test**

Create `src/test/java/com/navesdev/recurve/user/service/OperatorBootstrapTest.java`:

```java
package com.navesdev.recurve.user.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

@ExtendWith(MockitoExtension.class)
class OperatorBootstrapTest {

    @Mock
    private UserRepository repository;

    @Mock
    private UserSearchRepository searchRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private OperatorBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        bootstrap = new OperatorBootstrap(repository, searchRepository, passwordEncoder, clock);
    }

    @Nested
    @DisplayName("The first operator is created from the environment")
    class FirstOperator {

        @Test
        void theBootstrapOperatorIsIndexedLikeAnyOther() {
            configure("admin@recurve.local", "s3cret-password");
            when(repository.count()).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            bootstrap.run(null);

            verify(searchRepository).save(argThat(summary ->
                    summary.email().equals("admin@recurve.local")
                            && summary.permissions().contains(Permission.MANAGE_SYSTEM)));
        }

        @Test
        void nothingIsCreatedOrIndexedWhenAnOperatorAlreadyExists() {
            configure("admin@recurve.local", "s3cret-password");
            when(repository.count()).thenReturn(1L);

            bootstrap.run(null);

            verifyNoInteractions(searchRepository);
        }

        @Test
        void nothingIsCreatedOrIndexedWithoutCredentials() {
            configure("", "");

            bootstrap.run(null);

            verifyNoInteractions(repository, searchRepository);
        }
    }

    private void configure(String email, String password) {
        ReflectionTestUtils.setField(bootstrap, "email", email);
        ReflectionTestUtils.setField(bootstrap, "password", password);
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test -Dtest=OperatorBootstrapTest 2>&1 | tail -15`
Expected: compilation error (constructor arity).

- [ ] **Step 3: Index the bootstrap admin and order the runners**

In `OperatorBootstrap.java`:

Add imports:

```java
import org.springframework.core.annotation.Order;

import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserSearchRepository;
```

Add `@Order(2)` under `@Component`, with this comment above the class annotations:

```java
/*
 * After UserIndexBootstrap (1): the index must exist with its mapping
 * before anything is written to it, or Elasticsearch would create it on
 * the fly with a mapping of its own guessing.
 */
```

Add the field after `repository`:

```java
    private final UserSearchRepository searchRepository;
```

Replace the two lines `repository.save(admin);` / `log.info(...)` with:

```java
        searchRepository.save(UserSummary.of(repository.save(admin)));
        log.info("Bootstrap operator created with email {}", admin.getEmail());
```

- [ ] **Step 4: Write the index bootstrap**

Create `src/main/java/com/navesdev/recurve/user/service/UserIndexBootstrap.java`:

```java
package com.navesdev.recurve.user.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.user.repository.UserSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the operator search index at startup, with the analyzers and
 * mapping in {@code search/}, and fills it from the database when it did
 * not exist — the index's equivalent of a schema migration. An existing
 * index is left alone: a mapping change is a deliberate rebuild through
 * {@code POST /api/users/reindex}.
 *
 * <p>Fail-fast: if the node cannot be reached, the exception stops the
 * application from starting. A listing that silently answered from a
 * missing index would be worse than no application.
 *
 * <p>Internal by design, like {@link OperatorBootstrap}: it runs with no
 * authenticated operator, so it calls the service's unchecked method.
 * Ordered first so that the bootstrap operator finds the index in place.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class UserIndexBootstrap implements ApplicationRunner {

    private final UserSearchRepository searchRepository;
    private final UserService service;

    @Override
    public void run(ApplicationArguments args) {
        if (searchRepository.indexExists()) {
            return;
        }

        long indexed = service.reindexInternal();
        log.info("Operator search index created and populated with {} operators", indexed);
    }
}
```

- [ ] **Step 5: Run the unit suite**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q test 2>&1 | tail -15`
Expected: BUILD SUCCESS, `OperatorBootstrapTest` passes.

- [ ] **Step 6: Write the failing integration tests**

Create `src/testIntegration/java/com/navesdev/recurve/user/service/UserIndexBootstrapIT.java`:

```java
package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The index's schema migration: what startup does when the index is
 * missing, and what it leaves alone when it is not.
 */
@SpringBootTest
@Transactional
class UserIndexBootstrapIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private UserIndexBootstrap bootstrap;

    @Autowired
    private UserRepository repository;

    @Autowired
    private UserSearchRepository searchRepository;

    @Autowired
    private ElasticsearchOperations operations;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
        repository.save(User.create("Ada Lovelace", "ada@recurve.local", "$2a$10$hash", Set.of(), NOW));
        repository.save(User.create("Grace Hopper", "grace@recurve.local", "$2a$10$hash", Set.of(), NOW));
        entityManager.flush();
    }

    @Nested
    @DisplayName("A missing index is created and filled from the database")
    class MissingIndex {

        @Test
        void everyOperatorInTheDatabaseIsInTheNewIndex() {
            operations.indexOps(UserSummary.class).delete();

            bootstrap.run(null);

            assertThat(searchRepository.indexExists()).isTrue();
            assertThat(searchRepository.search(UserFilter.of(null), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(2);
        }

        @Test
        void theNewIndexCarriesTheMappingNotAGuessedOne() {
            operations.indexOps(UserSummary.class).delete();

            bootstrap.run(null);

            // Word-prefix matching only exists with the analyzer from search/users-settings.json.
            assertThat(searchRepository.search(UserFilter.of("hopp"), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("An existing index is left alone")
    class ExistingIndex {

        @Test
        void startupDoesNotRebuildAnIndexThatIsAlreadyThere() {
            searchRepository.recreateIndex();
            searchRepository.save(UserSummary.of(repository.findByEmail("ada@recurve.local").orElseThrow()));

            bootstrap.run(null);

            assertThat(searchRepository.search(UserFilter.of(null), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(1);
        }
    }
}
```

Create `src/testIntegration/java/com/navesdev/recurve/user/service/UserServiceIT.java`:

```java
package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

/**
 * Fail-fast across the transaction boundary: what a unit test cannot
 * show is that the container rolls the database write back when the
 * index refuses it. Deliberately not {@code @Transactional}: a test
 * transaction would hide the rollback it is here to observe. No cleanup
 * either — when the rule holds, nothing was written; every other IT
 * truncates the table before it starts.
 */
@SpringBootTest
class UserServiceIT {

    @Autowired
    private UserService service;

    @Autowired
    private UserRepository repository;

    @MockitoBean
    private UserSearchRepository searchRepository;

    @Nested
    @DisplayName("Fail-fast: the database and the index never diverge")
    class NeverDiverge {

        @Test
        @WithMockUser(authorities = "MANAGE_USERS")
        void aWriteTheIndexRefusesLeavesNothingInTheDatabase() {
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(UserSummary.class));

            assertThatThrownBy(() -> service.create(new CreateUserCommand(
                    "Ada Lovelace", "ada@recurve.local", "s3cret-password", Set.of(Permission.VIEW_USERS))))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.existsByEmail("ada@recurve.local")).isFalse();
        }
    }
}
```

Check `CreateUserCommand`'s component order with `cat src/main/java/com/navesdev/recurve/user/service/CreateUserCommand.java` and adjust the constructor call if the order differs from `(name, email, rawPassword, permissions)`.

- [ ] **Step 7: Run the integration suite**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify -Pintegration 2>&1 | tail -30`
Expected: BUILD SUCCESS. `UserIndexBootstrapIT` (3) and `UserServiceIT` (1) pass alongside the earlier ITs.

Note: `UserServiceIT` boots a context with the search repository mocked, so `UserIndexBootstrap.run` sees `indexExists()` return `false` (Mockito default) and calls `reindexInternal`, which uses the mock — harmless.

- [ ] **Step 8: Boot the real application once**

Run: `cd /home/naves/Projetos/Recurve/server && curl -s -X DELETE localhost:9230/users >/dev/null; timeout 60 ./mvnw -q spring-boot:run 2>&1 | grep -m1 "Operator search index created"; curl -s 'localhost:9230/_cat/indices?v' | grep -w users`
Expected: the log line `Operator search index created and populated with N operators`, and a `users` index listed.

- [ ] **Step 9: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add server/src && git commit -m "feat(server-user): create and populate the search index at startup

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: Documentation

**Files:**
- Modify: `docs/requirements.md` (FR-06)
- Modify: `server/docs/architecture.md`
- Modify: `README.md`

- [ ] **Step 1: Requirements — the new search semantics**

In `docs/requirements.md`, replace the first paragraph of `### FR-06 Search, filter and sort`:

```markdown
Applies to listings. Search is free text, case-insensitive, matching the
start of any word of the searched fields; every word typed must match.
Filters combine with AND; multiple values of the same filter combine with
OR. Sort accepts one field, ascending or descending; the direction is part
of naming the field rather than a separate choice, so that sorting on more
than one field stays expressible. A field outside the allowed list is a
validation error, and so is a filter over a field the listing does not
offer — neither is an empty result. Every listing is paginated (FR-07).

Listings are served from a search index, kept in step with the database
on every write and rebuildable from it on request (FR-01.5).
```

- [ ] **Step 2: Architecture — two stores, the read model, failure, tests**

In `server/docs/architecture.md`:

(a) In the Principles list at the top, after "Authorization via `@PreAuthorize` on the service.", add:

```markdown
- Two stores. PostgreSQL holds the write model and is the source of
  truth; Elasticsearch holds a read model per listing and serves every
  search, filter, sort and page (FR-06, FR-07). Nothing above the
  service knows there are two.
- Fail-fast. The system prefers to stop and make noise over continuing
  inconsistently in silence.
```

(b) In the table of layers, change the Persistence row to:

```markdown
| Persistence | model, filter, `Pageable` | model, read model | Domain | Application, Presentation |
```

(c) In the `### Domain (`domain/`)` section, after the paragraph starting "JPA in the domain: **mapping annotations only**", add:

```markdown
A listing's read model lives here too — `UserSummary`, a record of what
the listing shows, with `@Document` and its mapping annotations and a
factory from the entity. It is a projection: it holds no rule and can be
rebuilt from the entity at any time. The same discipline applies as for
JPA: mapping annotations only, and the password hash has no field.
```

(d) Replace the `### Persistence (`repository/`)` section body with:

```markdown
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
`Specifications` class would. Never business logic.

The index's settings and mapping live outside Java, in
`src/main/resources/search/`, next to the schema in `db/migration`. They
are applied by the feature's `UserIndexBootstrap` at startup, which
creates a missing index and fills it from the database; an existing index
is left alone, and a mapping change is a deliberate rebuild through the
feature's reindex endpoint (`MANAGE_SYSTEM`).
```

(e) In the `### Application (`service/`)` section, replace the three-step block with:

```
1. repository.findById(id)             load      (skipped on creation)
2. entity.businessMethod()             domain changes state, or throws
3. repository.save(entity)             persist
   searchRepository.save(Summary.of(entity))   index, same transaction
```

and after the sentence "If step 2 throws, step 3 does not run and the transaction rolls back." add:

```markdown
If indexing throws, the transaction rolls back too: the database and the
index never diverge, and the caller gets a 503 rather than a listing that
quietly stopped matching the data.
```

(f) In the Authorization section, add `UserIndexBootstrap` to the list of internal components:

```markdown
- `UserIndexBootstrap`, which creates the search index at startup and
  calls the service's unchecked `reindexInternal()`.
```

(g) Add a new top-level section before `## Authorization`:

```markdown
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
| Presentation | `InvalidRequestException` for the shape of the input | everything, in one place |

`GlobalExceptionHandler` is the only place that maps an exception to a
status: not found → 404, business rule → 422, external service → 502,
invalid request → 400, access denied → 403, a store that cannot be
reached (`DataAccessResourceFailureException`) → 503 with a fixed
message, anything else → 500 with no detail.
```

(h) Add a new top-level section at the end:

```markdown
## Tests

Two source roots, so that the unit suite never needs a server:

| Root | Names | Needs | Command |
|---|---|---|---|
| `src/test` | `*Test` | nothing | `./mvnw test` |
| `src/testIntegration` | `*IT` | `docker compose up -d` (PostgreSQL, Elasticsearch) | `./mvnw verify` |

`./mvnw verify -Pintegration` runs only the integration tests.

Unit tests cover a layer in isolation: the domain with plain objects, the
service with mocked repositories, the controller with `@WebMvcTest` and a
mocked service, a query builder by inspecting the query it builds.
Integration tests cover what only a real store can answer: the JPA
repository on the migrated schema (`@DataJpaTest`), the search repository
on the mapped index (`@DataElasticsearchTest`), authorization across every
layer (`@SpringBootTest`), and the rollback that fail-fast promises.

Integration tests use their own database (`recurve-test`) and their own
index prefix (`test-`), configured in `src/testIntegration/resources`.
```

- [ ] **Step 3: README — how to run it**

Append to `README.md`:

````markdown

## Running

```bash
docker compose up -d          # PostgreSQL (54330) and Elasticsearch (9230)
cd server
./mvnw test                   # unit tests, no server needed
./mvnw verify                 # unit + integration tests
./mvnw verify -Pintegration   # integration tests only
./mvnw spring-boot:run
```

The operator search index is created at first start and rebuilt on
demand with `POST /api/users/reindex` (requires `MANAGE_SYSTEM`).
````

- [ ] **Step 4: Update the spec for the two deviations**

In `docs/superpowers/specs/2026-09-18-elasticsearch-search-design.md`, in the Architecture block change the `shared/config` line to `user/service     UserIndexBootstrap — creates the index at boot (the ES "Flyway"); lives in the feature because shared/ never imports one`, and in the Persistence section replace the `SearchIndexInitializer` paragraph's first words with `UserIndexBootstrap` (in `user/service`). In the Domain section replace `@Document(indexName = "#{@indexNames.users}")` with `@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}users")`. In the Persistence section, replace "interface Spring Data ES with ... fragment `UserSearchRepositoryImpl`" with "a `@Repository` class over `ElasticsearchOperations`".

- [ ] **Step 5: Full build**

Run: `cd /home/naves/Projetos/Recurve/server && ./mvnw -q verify 2>&1 | tail -20`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
cd /home/naves/Projetos/Recurve && git add docs README.md server/docs && git commit -m "docs(server-user): record the search index, fail-fast and the test split

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
