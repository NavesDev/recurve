# Plans and Prices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `plan` feature — the `Plan` aggregate with its `PlanPrice`s, their use cases, the search-index listing, the HTTP API and its contract — as designed in `docs/superpowers/specs/2026-09-30-plan-feature-design.md`.

**Architecture:** Layered, by feature, exactly as `user` (`server/docs/architecture.md`). `Plan` is the aggregate root and holds its prices by object; every price change goes through it so BR-03 holds. PostgreSQL is the write model; Elasticsearch holds `PlanSummary`, the read model every listing is served from. Writes go to both in one transaction (fail-fast).

**Tech Stack:** Java 25, Spring Boot 4 (Web MVC, Data JPA, Data Elasticsearch, Security, Validation), PostgreSQL 18 + Flyway, Elasticsearch 9, Lombok (`@Getter`, `@RequiredArgsConstructor` only), JUnit 5 + AssertJ + Mockito, `openapi-request-validator-mockmvc`.

## Global Constraints

- Every command below runs from `server/`.
- Unit tests: `./mvnw test` (no services). Integration tests: `docker compose up -d` at the repo root, then `./mvnw verify -Pintegration -Dit.test=<Name>IT`.
- PMD gate (`./mvnw pmd:check`, `pmd-ruleset.xml`): no literal in an `if` condition except -1, 0, 1; a string literal of 3+ chars at most 4 times per file; methods ≤ 40 NCSS and cyclomatic ≤ 10; ≤ 15 methods per class; no unused private member; `@Override` always; `isEmpty()` over `size() == 0`; no fully qualified name where an import works.
- No public setter, no `@Setter`, no `@Data`. State changes only through business methods. Time enters as an `Instant now` parameter; entities never read the clock.
- An entity references another aggregate by id; `Plan` → `PlanPrice` is the one by-object relation, because it is one aggregate.
- Migrations are immutable once merged. `ddl-auto: validate`.
- Services carry no authorization; `SecurityConfig` does, one permission per rule.
- No `catch` in a service. Business exceptions extend `NotFoundException` (404) or `BusinessRuleException` (422).
- Amounts: `BigDecimal`, scale 2, `> 0`, at most 10 integer digits (`numeric(12,2)`). Currency: ISO 4217, upper case, 3 letters. Timestamps: `Instant`, UTC.
- Every test class and nested class states a rule (`@DisplayName("FR-02.3 ...")`), test methods read as sentences — as the `user` tests do.
- Commit messages: `type(server-plan): ...`, ending with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Structure

```
server/src/main/java/com/navesdev/recurve/
├── plan/
│   ├── domain/
│   │   ├── BillingInterval.java          enum + advance(Instant)
│   │   ├── PlanValidator.java            attribute rules and their limits
│   │   ├── Plan.java                     aggregate root, BR-03/BR-04 transitions
│   │   ├── PlanPrice.java                price entity, mutated only by Plan
│   │   ├── PlanSummary.java              read model (@Document)
│   │   └── exception/                    8 business exceptions
│   ├── repository/
│   │   ├── PlanRepository.java           JPA: findByPriceId, streamAll
│   │   └── PlanSearchRepository.java     Elasticsearch
│   ├── service/
│   │   ├── PlanService.java              use cases
│   │   ├── PlanIndexBootstrap.java       index at startup
│   │   └── *Command.java                 4 commands
│   └── controller/
│       ├── PlanController.java           /api/plans
│       ├── PriceController.java          /api/prices
│       └── *Request.java, PlanResponse.java, PriceResponse.java
├── shared/
│   ├── config/SecurityConfig.java        + plan rules              (modify)
│   └── controller/
│       ├── GlobalExceptionHandler.java   + 409                      (modify)
│       └── ReindexResponse.java          moved from user/controller
└── user/controller/UserController.java   import ReindexResponse    (modify)

server/src/main/resources/
├── db/migration/V2__create_plans.sql
├── search/plans-settings.json, plans-mapping.json
└── docs/openapi.yaml                                                (modify)
```

Tests mirror it under `src/test` (unit) and `src/testIntegration` (`*IT`).

---

### Task 1: Billing interval and attribute rules

**Files:**
- Create: `server/src/main/java/com/navesdev/recurve/plan/domain/BillingInterval.java`
- Create: `server/src/main/java/com/navesdev/recurve/plan/domain/PlanValidator.java`
- Create: `server/src/main/java/com/navesdev/recurve/plan/domain/exception/InvalidPlanException.java`
- Delete: `server/src/main/java/com/navesdev/recurve/plan/domain/exception/.gitkeep`
- Test: `server/src/test/java/com/navesdev/recurve/plan/domain/BillingIntervalTest.java`
- Test: `server/src/test/java/com/navesdev/recurve/plan/domain/PlanValidatorTest.java`

**Interfaces:**
- Produces: `enum BillingInterval { MONTHLY, YEARLY; Instant advance(Instant from) }`
- Produces: `PlanValidator` constants `NAME_MAX_LENGTH = 120`, `DESCRIPTION_MAX_LENGTH = 500`, `PRICE_MIN = "0.01"`, `PRICE_INTEGER_DIGITS = 10`, `PRICE_FRACTION_DIGITS = 2`, `CURRENCY_PATTERN = "^[A-Za-z]{3}$"`; static `String name(String)`, `String description(String)`, `BigDecimal price(BigDecimal)`, `String currency(String)`, `BillingInterval interval(BillingInterval)`.
- Produces: `InvalidPlanException(String message) extends BusinessRuleException`.

- [ ] **Step 1: Write the failing tests**

`server/src/test/java/com/navesdev/recurve/plan/domain/BillingIntervalTest.java`:

```java
package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** How far one billing cycle reaches (FR-03.2, FR-04.2). */
class BillingIntervalTest {

    @Nested
    @DisplayName("One cycle ahead, on the UTC calendar")
    class Advancing {

        @Test
        void aMonthlyCycleEndsOnTheSameDayOfTheNextMonth() {
            assertThat(BillingInterval.MONTHLY.advance(Instant.parse("2026-01-15T10:00:00Z")))
                    .isEqualTo(Instant.parse("2026-02-15T10:00:00Z"));
        }

        @Test
        void aYearlyCycleEndsOnTheSameDayOfTheNextYear() {
            assertThat(BillingInterval.YEARLY.advance(Instant.parse("2026-01-15T10:00:00Z")))
                    .isEqualTo(Instant.parse("2027-01-15T10:00:00Z"));
        }

        @Test
        void aMonthThatIsTooShortEndsTheCycleOnItsLastDay() {
            assertThat(BillingInterval.MONTHLY.advance(Instant.parse("2026-01-31T10:00:00Z")))
                    .isEqualTo(Instant.parse("2026-02-28T10:00:00Z"));
            assertThat(BillingInterval.YEARLY.advance(Instant.parse("2028-02-29T10:00:00Z")))
                    .isEqualTo(Instant.parse("2029-02-28T10:00:00Z"));
        }
    }
}
```

`server/src/test/java/com/navesdev/recurve/plan/domain/PlanValidatorTest.java`:

```java
package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.navesdev.recurve.plan.domain.exception.InvalidPlanException;

/** What may enter a {@link Plan} or a {@link PlanPrice}, stated once, for every caller. */
class PlanValidatorTest {

    @Nested
    @DisplayName("FR-02.1 a plan has a name and may have a description")
    class Text {

        @Test
        void aBlankNameIsRefusedAndNamesTheField() {
            assertThatThrownBy(() -> PlanValidator.name("  "))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("name");
            assertThatThrownBy(() -> PlanValidator.name(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("name");
        }

        @Test
        void aNameIsStoredTrimmed() {
            assertThat(PlanValidator.name("  Pro ")).isEqualTo("Pro");
        }

        @Test
        void aNameLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(PlanValidator.NAME_MAX_LENGTH + 1);

            assertThatThrownBy(() -> PlanValidator.name(tooLong))
                    .isInstanceOf(InvalidPlanException.class)
                    .hasMessageContaining(String.valueOf(PlanValidator.NAME_MAX_LENGTH));
        }

        @Test
        void anAbsentOrBlankDescriptionIsNoDescription() {
            assertThat(PlanValidator.description(null)).isNull();
            assertThat(PlanValidator.description("   ")).isNull();
        }

        @Test
        void aDescriptionIsStoredTrimmed() {
            assertThat(PlanValidator.description("  For teams ")).isEqualTo("For teams");
        }

        @Test
        void aDescriptionLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(PlanValidator.DESCRIPTION_MAX_LENGTH + 1);

            assertThatThrownBy(() -> PlanValidator.description(tooLong))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("description");
        }
    }

    @Nested
    @DisplayName("NFR-06 an amount is positive, with two decimal places")
    class Amount {

        @Test
        void anAbsentAmountIsRefused() {
            assertThatThrownBy(() -> PlanValidator.price(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("price");
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "0.00", "-1.00" })
        void anAmountThatIsNotPositiveIsRefused(String amount) {
            assertThatThrownBy(() -> PlanValidator.price(new BigDecimal(amount)))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("greater than zero");
        }

        @Test
        void anAmountWithMoreThanTwoDecimalPlacesIsRefused() {
            assertThatThrownBy(() -> PlanValidator.price(new BigDecimal("12.345")))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("decimal");
        }

        @Test
        void trailingZerosBeyondTheSecondPlaceAreNotPrecision() {
            assertThat(PlanValidator.price(new BigDecimal("12.300"))).isEqualByComparingTo("12.30");
        }

        @Test
        void anAmountTooLargeForItsColumnIsRefused() {
            assertThatThrownBy(() -> PlanValidator.price(new BigDecimal("12345678901.00")))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("digits");
            assertThat(PlanValidator.price(new BigDecimal("9999999999.99"))).isEqualByComparingTo("9999999999.99");
        }

        @Test
        void everyAmountIsStoredWithExactlyTwoDecimalPlaces() {
            assertThat(PlanValidator.price(new BigDecimal("49.9")).toPlainString()).isEqualTo("49.90");
            assertThat(PlanValidator.price(new BigDecimal("1E+2")).toPlainString()).isEqualTo("100.00");
        }
    }

    @Nested
    @DisplayName("NFR-06 a currency is an ISO 4217 code")
    class CurrencyCode {

        @Test
        void aCodeIsStoredUpperCased() {
            assertThat(PlanValidator.currency(" brl ")).isEqualTo("BRL");
        }

        @ParameterizedTest
        @ValueSource(strings = { "XYZ", "BR", "REAL", "" })
        void aCodeThatIsNotACurrencyIsRefused(String code) {
            assertThatThrownBy(() -> PlanValidator.currency(code))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("currency");
        }

        @Test
        void anAbsentCodeIsRefused() {
            assertThatThrownBy(() -> PlanValidator.currency(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("currency");
        }
    }

    @Nested
    @DisplayName("FR-02.2 a price has a billing cycle")
    class Cycle {

        @Test
        void anAbsentCycleIsRefused() {
            assertThatThrownBy(() -> PlanValidator.interval(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("interval");
        }

        @Test
        void aCycleIsTakenAsGiven() {
            assertThat(PlanValidator.interval(BillingInterval.YEARLY)).isEqualTo(BillingInterval.YEARLY);
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='BillingIntervalTest,PlanValidatorTest'`
Expected: compilation failure — `BillingInterval`, `PlanValidator`, `InvalidPlanException` do not exist.

- [ ] **Step 3: Write the implementation**

`server/src/main/java/com/navesdev/recurve/plan/domain/exception/InvalidPlanException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/**
 * A plan or price attribute that cannot be: blank where text is required,
 * longer than its column, an amount that is not a positive two-place
 * value, a code that is not a currency. Thrown by {@code PlanValidator} on
 * the way into the entity, so a {@code Plan} never holds it.
 */
public class InvalidPlanException extends BusinessRuleException {

    public InvalidPlanException(String message) {
        super(message);
    }
}
```

`server/src/main/java/com/navesdev/recurve/plan/domain/BillingInterval.java`:

```java
package com.navesdev.recurve.plan.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * How often a price is charged (FR-02.2). Knows how far one cycle
 * reaches, which is what moves a subscriber's next billing date
 * (FR-03.2, FR-04.2). Counted on the UTC calendar (NFR-05); a month too
 * short for the starting day ends on its last day.
 */
public enum BillingInterval {

    MONTHLY,
    YEARLY;

    public Instant advance(Instant from) {
        OffsetDateTime utc = from.atOffset(ZoneOffset.UTC);
        return switch (this) {
            case MONTHLY -> utc.plusMonths(1).toInstant();
            case YEARLY -> utc.plusYears(1).toInstant();
        };
    }
}
```

`server/src/main/java/com/navesdev/recurve/plan/domain/PlanValidator.java`:

```java
package com.navesdev.recurve.plan.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

import com.navesdev.recurve.plan.domain.exception.InvalidPlanException;

/**
 * The invariants of a plan's and a price's attributes, kept apart from
 * the entities so they read as behaviour and this as rules. Every
 * attribute enters through here, on creation and on every change, so a
 * {@code Plan} that exists is a valid one whoever built it.
 *
 * <p>Bean Validation on the request records repeats these limits, taking
 * them from the constants below: the edge answers "what did the caller
 * get wrong", field by field; this answers "can this exist".
 */
public final class PlanValidator {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    /** NFR-06 and {@code numeric(12,2)}: ten digits before the point, two after. */
    public static final String PRICE_MIN = "0.01";
    public static final int PRICE_INTEGER_DIGITS = 10;
    public static final int PRICE_FRACTION_DIGITS = 2;

    /** The shape the edge checks; whether the code is a currency is decided here. */
    public static final String CURRENCY_PATTERN = "^[A-Za-z]{3}$";

    private PlanValidator() {
    }

    public static String name(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidPlanException("name is required");
        }
        return bounded(name.trim(), "name", NAME_MAX_LENGTH);
    }

    /** Optional: absent and blank both mean the plan has none. */
    public static String description(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return bounded(description.trim(), "description", DESCRIPTION_MAX_LENGTH);
    }

    /**
     * BR-05 copies this amount into every charge, so it is stored in one
     * form: exactly two decimal places. Trailing zeros are not precision —
     * {@code 12.300} is {@code 12.30} — but a third significant place is
     * refused rather than rounded away.
     */
    public static BigDecimal price(BigDecimal price) {
        if (price == null) {
            throw new InvalidPlanException("price is required");
        }
        if (price.signum() <= 0) {
            throw new InvalidPlanException("price must be greater than zero, got %s".formatted(price.toPlainString()));
        }
        BigDecimal stripped = price.stripTrailingZeros();
        if (stripped.scale() > PRICE_FRACTION_DIGITS) {
            throw new InvalidPlanException("price must have at most %d decimal places, got %s"
                    .formatted(PRICE_FRACTION_DIGITS, price.toPlainString()));
        }
        BigDecimal normalized = stripped.setScale(PRICE_FRACTION_DIGITS, RoundingMode.UNNECESSARY);
        if (normalized.precision() - normalized.scale() > PRICE_INTEGER_DIGITS) {
            throw new InvalidPlanException("price must have at most %d integer digits, got %s"
                    .formatted(PRICE_INTEGER_DIGITS, price.toPlainString()));
        }
        return normalized;
    }

    /** An ISO 4217 code the JDK knows, upper-cased: {@code brl} is {@code BRL}. */
    public static String currency(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        boolean known = Currency.getAvailableCurrencies().stream()
                .anyMatch(candidate -> candidate.getCurrencyCode().equals(code));
        if (!known) {
            throw new InvalidPlanException("currency must be an ISO 4217 code, got '%s'".formatted(code));
        }
        return code;
    }

    public static BillingInterval interval(BillingInterval interval) {
        if (interval == null) {
            throw new InvalidPlanException("interval is required");
        }
        return interval;
    }

    private static String bounded(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new InvalidPlanException(
                    "%s must be at most %d characters, got %d".formatted(field, maxLength, value.length()));
        }
        return value;
    }
}
```

Then: `git rm -q server/src/main/java/com/navesdev/recurve/plan/domain/exception/.gitkeep`

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='BillingIntervalTest,PlanValidatorTest'`
Expected: `Tests run: ..., Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/navesdev/recurve/plan/domain server/src/test/java/com/navesdev/recurve/plan/domain
git commit -m "feat(server-plan): add the billing cycle and the plan attribute rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The plan aggregate

**Files:**
- Create: `server/src/main/java/com/navesdev/recurve/plan/domain/PlanPrice.java`
- Create: `server/src/main/java/com/navesdev/recurve/plan/domain/Plan.java`
- Create (all in `server/src/main/java/com/navesdev/recurve/plan/domain/exception/`): `PlanNotFoundException.java`, `PriceNotFoundException.java`, `PlanInactiveException.java`, `PlanAlreadyInactiveException.java`, `PriceAlreadyActiveException.java`, `PriceInactiveException.java`, `PriceAlreadyInactiveException.java`
- Test: `server/src/test/java/com/navesdev/recurve/plan/domain/PlanTest.java`

**Interfaces:**
- Consumes: `PlanValidator`, `BillingInterval`, `InvalidPlanException` (Task 1).
- Produces: `Plan` — `static Plan create(String name, String description, Instant now)`, `void update(String name, String description)`, `void deactivate()`, `PlanPrice addPrice(BigDecimal price, String currency, BillingInterval interval, Instant now)`, `PlanPrice replacePrice(UUID priceId, BigDecimal price, Instant now)`, `PlanPrice deactivatePrice(UUID priceId)`, `PlanPrice price(UUID priceId)`, getters `getId()`, `getName()`, `getDescription()`, `isActive()`, `getVersion()` (`Long`), `getCreatedAt()`, `List<PlanPrice> getPrices()` (unmodifiable copy, creation order).
- Produces: `PlanPrice` getters `getId()`, `getPrice()` (`BigDecimal`), `getCurrency()`, `getInterval()`, `isActive()`, `getCreatedAt()`. No public mutator.
- Produces: exceptions `PlanNotFoundException(UUID)`, `PriceNotFoundException(UUID)` (404); `PlanInactiveException(UUID)`, `PlanAlreadyInactiveException(UUID)`, `PriceAlreadyActiveException(BillingInterval, String)`, `PriceInactiveException(UUID)`, `PriceAlreadyInactiveException(UUID)` (422).

- [ ] **Step 1: Write the failing test**

`server/src/test/java/com/navesdev/recurve/plan/domain/PlanTest.java`:

```java
package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.navesdev.recurve.plan.domain.exception.InvalidPlanException;
import com.navesdev.recurve.plan.domain.exception.PlanAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PlanInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;

/** The rules a plan and its prices obey, stated as FR-02 and BR-03/BR-04 state them. */
class PlanTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T10:00:00Z");
    private static final BigDecimal MONTHLY_AMOUNT = new BigDecimal("49.90");

    @Nested
    @DisplayName("FR-02.1 registering a plan")
    class Registering {

        @Test
        void aPlanAcceptsSubscribersAsSoonAsItIsRegistered() {
            Plan plan = Plan.create("Pro", "For teams", NOW);

            assertThat(plan.isActive()).isTrue();
            assertThat(plan.getId()).isNotNull();
            assertThat(plan.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void aPlanStartsWithNoPrice() {
            assertThat(Plan.create("Pro", null, NOW).getPrices()).isEmpty();
        }

        @Test
        void aPlanCannotBeBuiltAroundItsRules() {
            // The rules are PlanValidator's; this shows the entity goes through them.
            assertThatThrownBy(() -> Plan.create(" ", null, NOW)).isInstanceOf(InvalidPlanException.class);
        }

        @Test
        void aBlankDescriptionIsNoDescription() {
            assertThat(Plan.create("Pro", "  ", NOW).getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("FR-02.6 editing a plan")
    class Editing {

        @Test
        void theNameAndDescriptionAreReplaced() {
            Plan plan = Plan.create("Pro", "For teams", NOW);

            plan.update("Pro Plus", null);

            assertThat(plan.getName()).isEqualTo("Pro Plus");
            assertThat(plan.getDescription()).isNull();
        }

        @Test
        void anEditGoesThroughTheSameRules() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.update("", null)).isInstanceOf(InvalidPlanException.class);
            assertThat(plan.getName()).isEqualTo("Pro");
        }
    }

    @Nested
    @DisplayName("FR-02.2 pricing a plan")
    class Pricing {

        @Test
        void aPriceIsActiveAndCarriesItsAmountCurrencyAndCycle() {
            Plan plan = Plan.create("Pro", null, NOW);

            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "brl", BillingInterval.MONTHLY, NOW);

            assertThat(price.isActive()).isTrue();
            assertThat(price.getPrice()).isEqualByComparingTo("49.90");
            assertThat(price.getCurrency()).isEqualTo("BRL");
            assertThat(price.getInterval()).isEqualTo(BillingInterval.MONTHLY);
            assertThat(plan.getPrices()).containsExactly(price);
        }

        @Test
        void aPlanMayHaveSeveralActivePricesInDifferentCyclesOrCurrencies() {
            Plan plan = Plan.create("Pro", null, NOW);

            plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, NOW);
            plan.addPrice(new BigDecimal("9.90"), "USD", BillingInterval.MONTHLY, NOW);

            assertThat(plan.getPrices()).hasSize(3).allMatch(PlanPrice::isActive);
        }

        @Test
        void aSecondActivePriceForTheSameCycleAndCurrencyIsRefused() {
            // BR-03: a new subscriber choosing "monthly in reais" must find one price, not two.
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            assertThatThrownBy(() -> plan.addPrice(new BigDecimal("59.90"), "brl", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(PriceAlreadyActiveException.class);
            assertThat(plan.getPrices()).hasSize(1);
        }

        @Test
        void anInactivePriceDoesNotBlockANewOneOnTheSamePair() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(old.getId());

            plan.addPrice(new BigDecimal("59.90"), "BRL", BillingInterval.MONTHLY, LATER);

            assertThat(plan.getPrices()).hasSize(2);
        }

        @Test
        void anInactivePlanTakesNoNewPrice() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.deactivate();

            assertThatThrownBy(() -> plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(PlanInactiveException.class);
        }

        @Test
        void aPriceCannotBeBuiltAroundItsRules() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.addPrice(BigDecimal.ZERO, "BRL", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(InvalidPlanException.class);
            assertThatThrownBy(() -> plan.addPrice(MONTHLY_AMOUNT, "XYZ", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(InvalidPlanException.class);
            assertThat(plan.getPrices()).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-02.7 / BR-04 replacing a price never edits it")
    class Replacing {

        @Test
        void theSuccessorKeepsTheCycleAndCurrencyAndTakesTheNewAmount() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            PlanPrice successor = plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);

            assertThat(successor.getId()).isNotEqualTo(old.getId());
            assertThat(successor.getPrice()).isEqualByComparingTo("59.90");
            assertThat(successor.getCurrency()).isEqualTo("BRL");
            assertThat(successor.getInterval()).isEqualTo(BillingInterval.MONTHLY);
            assertThat(successor.isActive()).isTrue();
            assertThat(successor.getCreatedAt()).isEqualTo(LATER);
        }

        @Test
        void theOldPriceStaysOnRecordUnchangedButInactive() {
            // Subscribers on the old price keep paying it (BR-04).
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);

            assertThat(old.isActive()).isFalse();
            assertThat(old.getPrice()).isEqualByComparingTo("49.90");
            assertThat(plan.getPrices()).hasSize(2).filteredOn(PlanPrice::isActive).hasSize(1);
        }

        @Test
        void anInactivePriceHasNothingLeftToReplace() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(old.getId());

            assertThatThrownBy(() -> plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER))
                    .isInstanceOf(PriceInactiveException.class);
        }

        @Test
        void anInactivePlanTakesNoReplacement() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivate();

            assertThatThrownBy(() -> plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER))
                    .isInstanceOf(PlanInactiveException.class);
            assertThat(old.isActive()).isTrue();
        }

        @Test
        void anInvalidAmountLeavesTheOldPriceActive() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            assertThatThrownBy(() -> plan.replacePrice(old.getId(), new BigDecimal("-1"), LATER))
                    .isInstanceOf(InvalidPlanException.class);
            assertThat(old.isActive()).isTrue();
            assertThat(plan.getPrices()).hasSize(1);
        }

        @Test
        void aPriceThePlanDoesNotHaveIsNotFound() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.replacePrice(UUID.randomUUID(), MONTHLY_AMOUNT, LATER))
                    .isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("FR-02.3 / FR-02.4 deactivating, never deleting")
    class Deactivating {

        @Test
        void aDeactivatedPriceStaysOnRecord() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            plan.deactivatePrice(price.getId());

            assertThat(plan.getPrices()).containsExactly(price);
            assertThat(price.isActive()).isFalse();
        }

        @Test
        void aPriceIsDeactivatedOnlyOnce() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(price.getId());

            assertThatThrownBy(() -> plan.deactivatePrice(price.getId()))
                    .isInstanceOf(PriceAlreadyInactiveException.class);
        }

        @Test
        void deactivatingAPlanLeavesItsPricesAsTheyWere() {
            // An inactive plan refuses subscribers by itself; its prices
            // keep saying which ones were in force.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            plan.deactivate();

            assertThat(plan.isActive()).isFalse();
            assertThat(price.isActive()).isTrue();
        }

        @Test
        void aPlanIsDeactivatedOnlyOnce() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.deactivate();

            assertThatThrownBy(plan::deactivate).isInstanceOf(PlanAlreadyInactiveException.class);
        }

        @Test
        void aPriceThePlanDoesNotHaveIsNotFound() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.deactivatePrice(UUID.randomUUID()))
                    .isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("The prices change only through the plan")
    class Encapsulation {

        @Test
        void theListOfPricesCannotBeChangedFromOutside() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.getPrices().add(null)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void pricesAreListedInTheOrderTheyWereCreated() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice first = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            PlanPrice second = plan.replacePrice(first.getId(), new BigDecimal("59.90"), LATER);

            assertThat(plan.getPrices()).containsExactly(first, second);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=PlanTest`
Expected: compilation failure — `Plan`, `PlanPrice` and the exceptions do not exist.

- [ ] **Step 3: Write the exceptions**

`PlanNotFoundException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

public class PlanNotFoundException extends NotFoundException {

    public PlanNotFoundException(UUID id) {
        super("Plan %s not found".formatted(id));
    }
}
```

`PriceNotFoundException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

public class PriceNotFoundException extends NotFoundException {

    public PriceNotFoundException(UUID id) {
        super("Price %s not found".formatted(id));
    }
}
```

`PlanInactiveException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** FR-02.4: an inactive plan accepts no new price and no replacement. */
public class PlanInactiveException extends BusinessRuleException {

    public PlanInactiveException(UUID id) {
        super("Plan %s is inactive and accepts no new price".formatted(id));
    }
}
```

`PlanAlreadyInactiveException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

public class PlanAlreadyInactiveException extends BusinessRuleException {

    public PlanAlreadyInactiveException(UUID id) {
        super("Plan %s is already inactive".formatted(id));
    }
}
```

`PriceAlreadyActiveException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-03: at most one active price per cycle and currency. Changing it is a replace (BR-04). */
public class PriceAlreadyActiveException extends BusinessRuleException {

    public PriceAlreadyActiveException(BillingInterval interval, String currency) {
        super("The plan already has an active %s price in %s; replace it instead".formatted(interval, currency));
    }
}
```

`PriceInactiveException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-04: only a price in force can be replaced. */
public class PriceInactiveException extends BusinessRuleException {

    public PriceInactiveException(UUID id) {
        super("Price %s is inactive and cannot be replaced".formatted(id));
    }
}
```

`PriceAlreadyInactiveException.java`:

```java
package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

public class PriceAlreadyInactiveException extends BusinessRuleException {

    public PriceAlreadyInactiveException(UUID id) {
        super("Price %s is already inactive".formatted(id));
    }
}
```

- [ ] **Step 4: Write the entities**

`server/src/main/java/com/navesdev/recurve/plan/domain/PlanPrice.java`:

```java
package com.navesdev.recurve.plan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * How much and how often (FR-02.2). Part of the {@link Plan} aggregate and
 * changed only through it: creation and deactivation are package-private,
 * so BR-03 — one active price per cycle and currency — is the plan's to
 * guard. A price is never edited (BR-04); a new amount is a new price.
 */
@Entity
@Table(name = "plan_prices")
@Getter
public class PlanPrice {

    @Id
    private UUID id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency;

    /** {@code billing_interval}: {@code INTERVAL} is an SQL keyword. */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 10)
    private BillingInterval interval;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected PlanPrice() {
    }

    static PlanPrice create(BigDecimal price, String currency, BillingInterval interval, Instant now) {
        PlanPrice created = new PlanPrice();
        created.id = UUID.randomUUID();
        created.price = PlanValidator.price(price);
        created.currency = PlanValidator.currency(currency);
        created.interval = PlanValidator.interval(interval);
        created.active = true;
        created.createdAt = Objects.requireNonNull(now, "now is required");
        return created;
    }

    /** BR-03: the pair this price occupies while it is in force. */
    boolean holds(BillingInterval interval, String currency) {
        return active && this.interval == interval && this.currency.equals(currency);
    }

    void deactivate() {
        active = false;
    }
}
```

`server/src/main/java/com/navesdev/recurve/plan/domain/Plan.java`:

```java
package com.navesdev.recurve.plan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import com.navesdev.recurve.plan.domain.exception.PlanAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PlanInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * The product (FR-02): a name, and the prices it is sold at. The aggregate
 * root — every change to a price goes through here, because BR-03 is a
 * rule over the set of a plan's prices. Mutable entity, but state changes
 * only through a business method; time comes in as a parameter. Every
 * attribute goes through {@link PlanValidator} on the way in.
 */
@Entity
@Table(name = "plans")
@Getter
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false, length = PlanValidator.NAME_MAX_LENGTH)
    private String name;

    @Column(length = PlanValidator.DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(nullable = false)
    private boolean active;

    /**
     * Optimistic lock. A wrapper, so that Spring Data reads {@code null} as
     * "new" and persists rather than merges. Adding a price changes this
     * entity's own collection, so it increments the version too: two
     * requests pricing the same plan at once cannot both win.
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Held by object, unlike a reference to another aggregate: a price is
     * part of this one. Eager, by a separate batched select rather than a
     * join — open-in-view is off and a response reads the prices after the
     * transaction. Nothing is deleted, so no orphan removal.
     */
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    @Fetch(FetchMode.SELECT)
    @BatchSize(size = 100)
    @OrderBy("createdAt ASC")
    private List<PlanPrice> prices = new ArrayList<>();

    /** For JPA only. */
    protected Plan() {
    }

    public static Plan create(String name, String description, Instant now) {
        Plan plan = new Plan();
        plan.id = UUID.randomUUID();
        plan.name = PlanValidator.name(name);
        plan.description = PlanValidator.description(description);
        plan.active = true;
        plan.createdAt = Objects.requireNonNull(now, "now is required");
        return plan;
    }

    /** FR-02.6. A plan has no amount, so renaming it charges nobody differently. */
    public void update(String name, String description) {
        String validName = PlanValidator.name(name);
        String validDescription = PlanValidator.description(description);
        this.name = validName;
        this.description = validDescription;
    }

    /** FR-02.4: no new subscriber, no new price. The prices keep their own state. */
    public void deactivate() {
        if (!active) {
            throw new PlanAlreadyInactiveException(id);
        }
        active = false;
    }

    /** FR-02.2, guarded by BR-03. */
    public PlanPrice addPrice(BigDecimal price, String currency, BillingInterval interval, Instant now) {
        requireActive();
        PlanPrice created = PlanPrice.create(price, currency, interval, now);
        boolean taken = prices.stream().anyMatch(existing -> existing.holds(created.getInterval(), created.getCurrency()));
        if (taken) {
            throw new PriceAlreadyActiveException(created.getInterval(), created.getCurrency());
        }
        prices.add(created);
        return created;
    }

    /**
     * FR-02.7, BR-04: a new amount is a new price. The successor takes the
     * old one's cycle and currency; the old one stays on record, inactive,
     * and whoever subscribed to it keeps paying it.
     */
    public PlanPrice replacePrice(UUID priceId, BigDecimal price, Instant now) {
        requireActive();
        PlanPrice old = price(priceId);
        if (!old.isActive()) {
            throw new PriceInactiveException(priceId);
        }
        PlanPrice successor = PlanPrice.create(price, old.getCurrency(), old.getInterval(), now);
        old.deactivate();
        prices.add(successor);
        return successor;
    }

    /** FR-02.3: no new subscriber on it; existing ones stay. */
    public PlanPrice deactivatePrice(UUID priceId) {
        PlanPrice target = price(priceId);
        if (!target.isActive()) {
            throw new PriceAlreadyInactiveException(priceId);
        }
        target.deactivate();
        return target;
    }

    public PlanPrice price(UUID priceId) {
        return prices.stream()
                .filter(candidate -> candidate.getId().equals(priceId))
                .findFirst()
                .orElseThrow(() -> new PriceNotFoundException(priceId));
    }

    /** Unmodifiable, in creation order: the collection changes only through a business method. */
    public List<PlanPrice> getPrices() {
        return List.copyOf(prices);
    }

    private void requireActive() {
        if (!active) {
            throw new PlanInactiveException(id);
        }
    }
}
```

Note: `update` validates both values before assigning either, so a refused edit changes nothing.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=PlanTest`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/navesdev/recurve/plan/domain server/src/test/java/com/navesdev/recurve/plan/domain
git commit -m "feat(server-plan): add the plan aggregate and its price rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Schema and JPA repository

**Files:**
- Create: `server/src/main/resources/db/migration/V2__create_plans.sql`
- Create: `server/src/main/java/com/navesdev/recurve/plan/repository/PlanRepository.java`
- Delete: `server/src/main/java/com/navesdev/recurve/plan/repository/.gitkeep`
- Test: `server/src/testIntegration/java/com/navesdev/recurve/plan/repository/PlanRepositoryIT.java`

**Interfaces:**
- Consumes: `Plan`, `PlanPrice`, `BillingInterval` (Task 2).
- Produces: `PlanRepository extends BaseRepository<Plan, UUID>` with `Optional<Plan> findByPriceId(UUID priceId)` and `Stream<Plan> streamAll()`.
- Produces: tables `plans`, `plan_prices`; constraint `ex_plan_prices_one_active`.

- [ ] **Step 1: Write the failing test**

`server/src/testIntegration/java/com/navesdev/recurve/plan/repository/PlanRepositoryIT.java`:

```java
package com.navesdev.recurve.plan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * The plan aggregate against a real PostgreSQL, on the schema the
 * migrations produced: that it round-trips with its prices, that a price
 * leads to its plan, and that BR-03 holds in the schema too. Each test
 * rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PlanRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    private PlanRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();
    }

    @Nested
    @DisplayName("A plan is stored with its prices")
    class RoundTrip {

        @Test
        void thePricesComeBackWithThePlanInTheOrderTheyWereCreated() {
            Plan plan = Plan.create("Pro", "For teams", NOW);
            PlanPrice monthly = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            PlanPrice yearly = plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, LATER);
            store(plan);

            Plan found = repository.findById(plan.getId()).orElseThrow();

            assertThat(found.getPrices()).extracting(PlanPrice::getId).containsExactly(monthly.getId(), yearly.getId());
            assertThat(found.getPrices().getFirst().getPrice()).isEqualByComparingTo("49.90");
            assertThat(found.getPrices().getFirst().getInterval()).isEqualTo(BillingInterval.MONTHLY);
        }

        @Test
        void aPriceAddedToAStoredPlanIsStoredWithIt() {
            Plan plan = store(Plan.create("Pro", null, NOW));

            Plan loaded = repository.findById(plan.getId()).orElseThrow();
            loaded.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            repository.save(loaded);
            entityManager.flush();
            entityManager.clear();

            assertThat(repository.findById(plan.getId()).orElseThrow().getPrices()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("A price is reached through its plan")
    class ByPrice {

        @Test
        void thePlanHoldingAPriceIsFoundByThatPrice() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            store(plan);
            store(Plan.create("Basic", null, NOW));

            assertThat(repository.findByPriceId(price.getId())).get()
                    .extracting(Plan::getId).isEqualTo(plan.getId());
        }

        @Test
        void aPriceNobodyHoldsFindsNoPlan() {
            assertThat(repository.findByPriceId(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-02.5 every plan, for rebuilding the index")
    class Streaming {

        @Test
        void everyPlanIsStreamed() {
            store(Plan.create("Pro", null, NOW));
            store(Plan.create("Basic", null, NOW));

            try (Stream<Plan> plans = repository.streamAll()) {
                assertThat(plans).extracting(Plan::getName).containsExactlyInAnyOrder("Pro", "Basic");
            }
        }
    }

    @Nested
    @DisplayName("BR-03 one active price per cycle and currency, in the schema too")
    class OneActivePrice {

        @Test
        void twoActivePricesOnOnePairCannotBeCommitted() {
            // The domain refuses this first; the constraint stands behind
            // it for two requests that race past the domain check. It is
            // deferred to commit, so the test asks for the check at once.
            Plan plan = store(Plan.create("Pro", null, NOW));
            insertActivePrice(plan.getId());
            entityManager.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate();

            assertThatThrownBy(() -> insertActivePrice(plan.getId())).isInstanceOf(PersistenceException.class);
        }

        @Test
        void aReplacementIsNotTwoActivePrices() {
            // Hibernate flushes the successor's insert before the old
            // price's update; deferring the check to commit is what lets
            // a replace (BR-04) through.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            store(plan);

            Plan loaded = repository.findById(plan.getId()).orElseThrow();
            loaded.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);
            repository.save(loaded);
            entityManager.flush();

            assertThatCode(() -> entityManager.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate())
                    .doesNotThrowAnyException();
        }

        @Test
        void anAmountThatIsNotPositiveCannotBeStored() {
            Plan plan = store(Plan.create("Pro", null, NOW));

            assertThatThrownBy(() -> entityManager.createNativeQuery("""
                    INSERT INTO plan_prices (id, plan_id, price, currency, billing_interval, active, created_at)
                    VALUES (gen_random_uuid(), :plan, 0, 'BRL', 'YEARLY', false, now())
                    """).setParameter("plan", plan.getId()).executeUpdate())
                    .isInstanceOf(PersistenceException.class);
        }

        private void insertActivePrice(UUID planId) {
            entityManager.createNativeQuery("""
                    INSERT INTO plan_prices (id, plan_id, price, currency, billing_interval, active, created_at)
                    VALUES (gen_random_uuid(), :plan, 49.90, 'BRL', 'MONTHLY', true, now())
                    """).setParameter("plan", planId).executeUpdate();
        }
    }

    private Plan store(Plan plan) {
        Plan saved = repository.save(plan);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q verify -Pintegration -Dit.test=PlanRepositoryIT`
Expected: compilation failure — `PlanRepository` does not exist.

- [ ] **Step 3: Write the migration**

`server/src/main/resources/db/migration/V2__create_plans.sql`:

```sql
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
```

- [ ] **Step 4: Write the repository**

`server/src/main/java/com/navesdev/recurve/plan/repository/PlanRepository.java`:

```java
package com.navesdev.recurve.plan.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.shared.repository.BaseRepository;

public interface PlanRepository extends BaseRepository<Plan, UUID> {

    /**
     * The plan a price belongs to. A price is changed only through its
     * plan (BR-03), and the API addresses it by its own id alone.
     */
    @Query("select p from Plan p join p.prices price where price.id = :priceId")
    Optional<Plan> findByPriceId(@Param("priceId") UUID priceId);

    /**
     * Every plan, for rebuilding the search index (FR-02.5) — not a
     * listing, which is always paginated (FR-07). A stream, so the rebuild
     * walks the table without holding it in memory; the caller closes it.
     */
    @Query("select p from Plan p")
    Stream<Plan> streamAll();
}
```

Then: `git rm -q server/src/main/java/com/navesdev/recurve/plan/repository/.gitkeep`

- [ ] **Step 5: Run the test to verify it passes**

Run: `docker compose up -d` (repo root), then `./mvnw -q verify -Pintegration -Dit.test=PlanRepositoryIT`
Expected: `Tests run: 8, Failures: 0, Errors: 0`. Also check `RecurveApplicationIT` still starts: `./mvnw -q verify -Pintegration -Dit.test=RecurveApplicationIT` — Hibernate's `validate` passes against `V2`.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/resources/db/migration/V2__create_plans.sql server/src/main/java/com/navesdev/recurve/plan/repository server/src/testIntegration/java/com/navesdev/recurve/plan
git commit -m "feat(server-plan): create the plan tables and the plan repository

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Read model and search repository

**Files:**
- Create: `server/src/main/java/com/navesdev/recurve/plan/domain/PlanSummary.java`
- Create: `server/src/main/resources/search/plans-settings.json`
- Create: `server/src/main/resources/search/plans-mapping.json`
- Create: `server/src/main/java/com/navesdev/recurve/plan/repository/PlanSearchRepository.java`
- Test: `server/src/test/java/com/navesdev/recurve/plan/domain/PlanSummaryTest.java`
- Test: `server/src/testIntegration/java/com/navesdev/recurve/plan/repository/PlanSearchRepositoryIT.java`

**Interfaces:**
- Consumes: `Plan`, `PlanPrice` (Task 2); `SearchFilter`, `SearchQueries` (existing, `shared`).
- Produces: `record PlanSummary(UUID id, String name, String description, boolean active, Instant createdAt, Set<BillingInterval> activeIntervals, List<PlanSummary.Price> prices)` with `static PlanSummary of(Plan)`; nested `record Price(UUID id, String price, String currency, BillingInterval interval, boolean active, Instant createdAt)`.
- Produces: `PlanSearchRepository` — `PlanSummary save(PlanSummary)`, `void saveAll(Collection<PlanSummary>)`, `Page<PlanSummary> search(SearchFilter, Pageable)`, `boolean indexExists()`, `void recreateIndex()`, `void refresh()`.

- [ ] **Step 1: Write the failing unit test**

`server/src/test/java/com/navesdev/recurve/plan/domain/PlanSummaryTest.java`:

```java
package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the listing shows of a plan (FR-02.5), and what it filters on (FR-06.2). */
class PlanSummaryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Nested
    @DisplayName("FR-02.5 the listing shows a plan with its prices")
    class Projection {

        @Test
        void everyFieldTheListingShowsComesFromThePlan() {
            Plan plan = Plan.create("Pro", "For teams", NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);

            PlanSummary summary = PlanSummary.of(plan);

            assertThat(summary.id()).isEqualTo(plan.getId());
            assertThat(summary.name()).isEqualTo("Pro");
            assertThat(summary.description()).isEqualTo("For teams");
            assertThat(summary.active()).isTrue();
            assertThat(summary.createdAt()).isEqualTo(NOW);
            assertThat(summary.prices()).singleElement().satisfies(shown -> {
                assertThat(shown.id()).isEqualTo(price.getId());
                assertThat(shown.currency()).isEqualTo("BRL");
                assertThat(shown.interval()).isEqualTo(BillingInterval.MONTHLY);
                assertThat(shown.active()).isTrue();
                assertThat(shown.createdAt()).isEqualTo(NOW);
            });
        }

        @Test
        void anAmountTravelsAsTextSoItNeverPassesThroughADouble() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(new BigDecimal("49.9"), "BRL", BillingInterval.MONTHLY, NOW);

            assertThat(PlanSummary.of(plan).prices().getFirst().price()).isEqualTo("49.90");
        }

        @Test
        void inactivePricesAreShownToo() {
            // BR-04: the history of a plan's prices is the point of never editing one.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.replacePrice(old.getId(), new BigDecimal("59.90"), NOW);

            assertThat(PlanSummary.of(plan).prices()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("FR-06.2 the cycles a plan is on sale in")
    class ActiveIntervals {

        @Test
        void onlyActivePricesCount() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice monthly = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, NOW);
            plan.deactivatePrice(monthly.getId());

            assertThat(PlanSummary.of(plan).activeIntervals()).containsExactly(BillingInterval.YEARLY);
        }

        @Test
        void aCycleSoldInSeveralCurrenciesIsListedOnce() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.addPrice(new BigDecimal("9.90"), "USD", BillingInterval.MONTHLY, NOW);

            assertThat(PlanSummary.of(plan).activeIntervals()).containsExactly(BillingInterval.MONTHLY);
        }

        @Test
        void aPlanWithNoActivePriceIsOnSaleInNoCycle() {
            assertThat(PlanSummary.of(Plan.create("Pro", null, NOW)).activeIntervals()).isEmpty();
        }

        @Test
        void thePlansOwnStateIsAFilterOfItsOwn() {
            // activeIntervals answers "which cycles have a price in force";
            // whether the plan itself is active is the active filter's job.
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivate();

            PlanSummary summary = PlanSummary.of(plan);

            assertThat(summary.active()).isFalse();
            assertThat(summary.activeIntervals()).containsExactly(BillingInterval.MONTHLY);
        }
    }
}
```

- [ ] **Step 2: Write the failing integration test**

`server/src/testIntegration/java/com/navesdev/recurve/plan/repository/PlanSearchRepositoryIT.java`:

```java
package com.navesdev.recurve.plan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * The plan listing rules (FR-06.2, FR-07) against a real Elasticsearch, on
 * the index the mapping in {@code search/} produces. The index is
 * recreated before each test: there is no transaction to roll back.
 */
@DataElasticsearchTest
@Import(PlanSearchRepository.class)
class PlanSearchRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Sort BY_NAME = Sort.by(Sort.Order.asc("name.keyword"), Sort.Order.asc("id"));

    @Autowired
    private PlanSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository.recreateIndex();

        Plan pro = Plan.create("Pro Teams", "For teams", NOW);
        pro.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        pro.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, NOW);

        Plan basic = Plan.create("Basic", "Pro features, fewer seats", NOW);
        PlanPrice retired = basic.addPrice(new BigDecimal("19.90"), "BRL", BillingInterval.MONTHLY, NOW);
        basic.deactivatePrice(retired.getId());
        basic.addPrice(new BigDecimal("199.00"), "BRL", BillingInterval.YEARLY, NOW);

        Plan legacy = Plan.create("Legacy", null, NOW);
        legacy.addPrice(new BigDecimal("9.90"), "USD", BillingInterval.MONTHLY, NOW);
        legacy.deactivate();

        List.of(pro, basic, legacy).forEach(plan -> repository.save(PlanSummary.of(plan)));
    }

    @Nested
    @DisplayName("FR-06.2 searching by name")
    class Searching {

        @Test
        void theSearchMatchesTheStartOfAnyWordOfTheName() {
            assertThat(names(search(SearchFilter.of("tea")))).containsExactly("Pro Teams");
        }

        @Test
        void theDescriptionIsNotSearched() {
            // "Basic"'s description says "Pro"; only names are searched.
            assertThat(names(search(SearchFilter.of("pro")))).containsExactly("Pro Teams");
        }

        @Test
        void anAbsentSearchMatchesEveryPlan() {
            assertThat(search(SearchFilter.of(null))).hasSize(3);
        }
    }

    @Nested
    @DisplayName("FR-06.2 filtering by a cycle with an active price")
    class ByCycle {

        @Test
        void aPlanMatchesWhenItHasAnActivePriceInThatCycle() {
            // Basic's monthly price is inactive, so Basic is not on sale monthly.
            assertThat(names(search(filteredBy("activeIntervals", "MONTHLY")))).containsExactly("Legacy", "Pro Teams");
        }

        @Test
        void severalCyclesMatchAnyOfThem() {
            assertThat(search(filteredBy("activeIntervals", "MONTHLY", "YEARLY"))).hasSize(3);
        }

        @Test
        void theCycleAndThePlansOwnStateCombine() {
            SearchFilter both = new SearchFilter(null,
                    Map.of("activeIntervals", List.of("MONTHLY"), "active", List.of("true")));

            assertThat(names(search(both))).containsExactly("Pro Teams");
        }
    }

    @Nested
    @DisplayName("FR-06.2 sorting and FR-07 paging")
    class SortingAndPaging {

        @Test
        void byNameAscendingAndDescending() {
            assertThat(names(search(SearchFilter.of(null)))).containsExactly("Basic", "Legacy", "Pro Teams");

            Page<PlanSummary> descending = repository.search(SearchFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Order.desc("name.keyword"), Sort.Order.asc("id"))));
            assertThat(names(descending)).containsExactly("Pro Teams", "Legacy", "Basic");
        }

        @Test
        void aPageCarriesTheTotalBeforePaging() {
            Page<PlanSummary> page = repository.search(SearchFilter.of(null), PageRequest.of(1, 2, BY_NAME));

            assertThat(names(page)).containsExactly("Pro Teams");
            assertThat(page.getTotalElements()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("FR-02.5 a plan comes back with its prices")
    class Prices {

        @Test
        void thePricesComeBackAsIndexed() {
            PlanSummary basic = search(SearchFilter.of("basic")).getContent().getFirst();

            assertThat(basic.prices()).hasSize(2);
            assertThat(basic.prices()).extracting(PlanSummary.Price::price).containsExactly("19.90", "199.00");
            assertThat(basic.prices()).extracting(PlanSummary.Price::active).containsExactly(false, true);
            assertThat(basic.prices().getFirst().createdAt()).isEqualTo(NOW);
            assertThat(basic.activeIntervals()).containsExactly(BillingInterval.YEARLY);
        }
    }

    private Page<PlanSummary> search(SearchFilter filter) {
        return repository.search(filter, PageRequest.of(0, 20, BY_NAME));
    }

    private static SearchFilter filteredBy(String field, String... values) {
        return new SearchFilter(null, Map.of(field, List.of(values)));
    }

    private static List<String> names(Page<PlanSummary> page) {
        return page.getContent().stream().map(PlanSummary::name).toList();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=PlanSummaryTest`
Expected: compilation failure — `PlanSummary` does not exist.

- [ ] **Step 4: Write the read model**

`server/src/main/java/com/navesdev/recurve/plan/domain/PlanSummary.java`:

```java
package com.navesdev.recurve.plan.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * What a listing shows of a plan (FR-02.5): the read model, indexed in
 * Elasticsearch and served from there (FR-06.2, FR-07). {@link Plan} is
 * the write model; this is a projection of it, rebuilt from it at any
 * time, and holds no rule.
 *
 * <p>{@code activeIntervals} is derived here so that "a plan with an
 * active price in this cycle" is a plain filter on one field. Amounts
 * travel as text, so they never pass through a {@code double}.
 *
 * <p>Mapping annotations only. The index is created by
 * {@code PlanIndexBootstrap}, never on demand, so that it always carries
 * the analyzers from {@code search/plans-settings.json}.
 */
@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}plans", createIndex = false)
@Setting(settingPath = "search/plans-settings.json")
@Mapping(mappingPath = "search/plans-mapping.json")
public record PlanSummary(
        @Id UUID id,
        String name,
        String description,
        boolean active,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt,
        Set<BillingInterval> activeIntervals,
        List<Price> prices) {

    public static PlanSummary of(Plan plan) {
        List<PlanPrice> prices = plan.getPrices();
        return new PlanSummary(
                plan.getId(),
                plan.getName(),
                plan.getDescription(),
                plan.isActive(),
                plan.getCreatedAt(),
                prices.stream()
                        .filter(PlanPrice::isActive)
                        .map(PlanPrice::getInterval)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(BillingInterval.class))),
                prices.stream().map(Price::of).toList());
    }

    /** One price as the listing shows it, inactive ones included (BR-04). */
    public record Price(
            UUID id,
            String price,
            String currency,
            BillingInterval interval,
            boolean active,
            @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt) {

        static Price of(PlanPrice price) {
            return new Price(
                    price.getId(),
                    price.getPrice().toPlainString(),
                    price.getCurrency(),
                    price.getInterval(),
                    price.isActive(),
                    price.getCreatedAt());
        }
    }
}
```

- [ ] **Step 5: Run the unit test to verify it passes**

Run: `./mvnw -q test -Dtest=PlanSummaryTest`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 6: Write the index files and the search repository**

`server/src/main/resources/search/plans-settings.json` — the same analyzers as `users-settings.json`:

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

`server/src/main/resources/search/plans-mapping.json`:

```json
{
  "properties": {
    "id": { "type": "keyword", "index": false },
    "name": {
      "type": "text",
      "analyzer": "prefix",
      "search_analyzer": "prefix_search",
      "fields": {
        "keyword": { "type": "keyword", "normalizer": "lowercase" }
      }
    },
    "description": { "type": "text", "index": false },
    "active": { "type": "boolean", "index": false },
    "createdAt": { "type": "date", "format": "date_time", "index": false },
    "activeIntervals": { "type": "keyword", "index": false },
    "prices": { "type": "object", "enabled": false }
  }
}
```

`server/src/main/java/com/navesdev/recurve/plan/repository/PlanSearchRepository.java`:

```java
package com.navesdev.recurve.plan.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitSupport;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;

import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.shared.repository.SearchQueries;
import com.navesdev.recurve.shared.service.SearchFilter;

import lombok.RequiredArgsConstructor;

/**
 * The plan read model's store (FR-06.2, FR-07), with the same narrow
 * surface as the operator's: it indexes, searches and rebuilds; nothing
 * deletes a single document. A single {@link #save} is visible to the next
 * search at once; a rebuild indexes in bulk and refreshes once at the end.
 */
@Repository
@RequiredArgsConstructor
public class PlanSearchRepository {

    /** FR-06.2: what {@code q} matches against. */
    private static final List<String> SEARCHED_FIELDS = List.of("name");

    private final ElasticsearchOperations operations;

    public PlanSummary save(PlanSummary summary) {
        return operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(summary);
    }

    /** Bulk, not refreshed: for a rebuild, which calls {@link #refresh()} once at the end. */
    public void saveAll(Collection<PlanSummary> summaries) {
        operations.withRefreshPolicy(RefreshPolicy.NONE).save(summaries);
    }

    public Page<PlanSummary> search(SearchFilter filter, Pageable pageable) {
        SearchHits<PlanSummary> hits = operations.search(
                SearchQueries.from(filter, pageable, SEARCHED_FIELDS), PlanSummary.class);

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
        return operations.indexOps(PlanSummary.class);
    }
}
```

- [ ] **Step 7: Run the integration test to verify it passes**

Run: `./mvnw -q verify -Pintegration -Dit.test=PlanSearchRepositoryIT`
Expected: `Failures: 0, Errors: 0`.

If `thePricesComeBackAsIndexed` fails on reading `UUID` or `Instant` inside `Price`, the converter is not reaching the nested record: check the stored document (`curl localhost:9230/test-plans/_search?pretty`) and, for a UUID stored as an object, change `Price.id` to `String` holding `price.getId().toString()` and convert back in `PriceResponse` (Task 6).

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/com/navesdev/recurve/plan server/src/main/resources/search/plans-*.json server/src/test/java/com/navesdev/recurve/plan server/src/testIntegration/java/com/navesdev/recurve/plan
git commit -m "feat(server-plan): index plans in Elasticsearch for the listing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Use cases and the index bootstrap

**Files:**
- Create (all in `server/src/main/java/com/navesdev/recurve/plan/service/`): `CreatePlanCommand.java`, `UpdatePlanCommand.java`, `AddPriceCommand.java`, `ReplacePriceCommand.java`, `PlanService.java`, `PlanIndexBootstrap.java`
- Delete: `server/src/main/java/com/navesdev/recurve/plan/service/.gitkeep`
- Test: `server/src/test/java/com/navesdev/recurve/plan/service/PlanServiceTest.java`
- Test: `server/src/testIntegration/java/com/navesdev/recurve/plan/service/PlanServiceIT.java`
- Test: `server/src/testIntegration/java/com/navesdev/recurve/plan/service/PlanIndexBootstrapIT.java`

**Interfaces:**
- Consumes: `Plan`, `PlanSummary`, exceptions (Tasks 2, 4); `PlanRepository` (Task 3); `PlanSearchRepository` (Task 4); `Clock` bean (existing).
- Produces: `record CreatePlanCommand(String name, String description)`, `record UpdatePlanCommand(UUID id, String name, String description)`, `record AddPriceCommand(UUID planId, BigDecimal price, String currency, BillingInterval interval)`, `record ReplacePriceCommand(UUID priceId, BigDecimal price)`.
- Produces: `PlanService` — `Plan create(CreatePlanCommand)`, `Plan update(UpdatePlanCommand)`, `Plan deactivate(UUID planId)`, `Plan findById(UUID planId)`, `Plan addPrice(AddPriceCommand)`, `Plan replacePrice(ReplacePriceCommand)`, `Plan deactivatePrice(UUID priceId)`, `Page<PlanSummary> search(SearchFilter, Pageable)`, `long reindex()`.

- [ ] **Step 1: Write the failing unit test**

`server/src/test/java/com/navesdev/recurve/plan/service/PlanServiceTest.java`:

```java
package com.navesdev.recurve.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.domain.exception.PlanNotFoundException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * The orchestration around the plan aggregate: what is loaded, that the
 * clock is the service's, and that every write reaches the index.
 */
@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("49.90");

    @Mock
    private PlanRepository repository;

    @Mock
    private PlanSearchRepository searchRepository;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(repository, searchRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("FR-02.1 / FR-02.6 a plan is registered and edited")
    class Plans {

        @Test
        void aRegisteredPlanIsStampedWithTheServicesClock() {
            savesWhatItIsGiven();

            Plan created = service.create(new CreatePlanCommand("Pro", "For teams"));

            assertThat(created.getCreatedAt()).isEqualTo(NOW);
            verify(repository).save(created);
        }

        @Test
        void anEditedPlanIsSaved() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            Plan updated = service.update(new UpdatePlanCommand(plan.getId(), "Pro Plus", "More seats"));

            assertThat(updated.getName()).isEqualTo("Pro Plus");
            verify(repository).save(plan);
        }

        @Test
        void aDeactivatedPlanRemainsOnRecord() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            assertThat(service.deactivate(plan.getId()).isActive()).isFalse();
            verify(repository).save(plan);
        }
    }

    @Nested
    @DisplayName("FR-02.2 / FR-02.3 / FR-02.7 prices change through their plan")
    class Prices {

        @Test
        void aPriceIsAddedToThePlanNamedInTheCommand() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            Plan priced = service.addPrice(new AddPriceCommand(plan.getId(), AMOUNT, "BRL", BillingInterval.MONTHLY));

            assertThat(priced.getPrices()).singleElement()
                    .satisfies(price -> assertThat(price.getCreatedAt()).isEqualTo(NOW));
        }

        @Test
        void aRefusedPriceSavesNothing() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.addPrice(
                    new AddPriceCommand(plan.getId(), new BigDecimal("59.90"), "BRL", BillingInterval.MONTHLY)))
                    .isInstanceOf(PriceAlreadyActiveException.class);

            verify(repository, never()).save(any());
            verifyNoInteractions(searchRepository);
        }

        @Test
        void aPriceIsReplacedThroughThePlanThatHoldsIt() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            PlanPrice old = plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findByPriceId(old.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            Plan replaced = service.replacePrice(new ReplacePriceCommand(old.getId(), new BigDecimal("59.90")));

            assertThat(replaced.getPrices()).hasSize(2);
            assertThat(replaced.getPrices().getLast().getCreatedAt()).isEqualTo(NOW);
            assertThat(old.isActive()).isFalse();
        }

        @Test
        void aPriceIsDeactivatedThroughThePlanThatHoldsIt() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            PlanPrice price = plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findByPriceId(price.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            service.deactivatePrice(price.getId());

            assertThat(price.isActive()).isFalse();
            verify(repository).save(plan);
        }
    }

    @Nested
    @DisplayName("What does not exist")
    class Missing {

        @Test
        void aMissingPlanCannotBeReadEditedDeactivatedOrPriced() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(id)).isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> service.update(new UpdatePlanCommand(id, "Pro", null)))
                    .isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> service.deactivate(id)).isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> service.addPrice(new AddPriceCommand(id, AMOUNT, "BRL", BillingInterval.MONTHLY)))
                    .isInstanceOf(PlanNotFoundException.class);
        }

        @Test
        void aPriceNoPlanHoldsCannotBeReplacedOrDeactivated() {
            UUID id = UUID.randomUUID();
            when(repository.findByPriceId(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replacePrice(new ReplacePriceCommand(id, AMOUNT)))
                    .isInstanceOf(PriceNotFoundException.class);
            assertThatThrownBy(() -> service.deactivatePrice(id)).isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("FR-06 the listing is served from the search index")
    class Indexing {

        @Test
        void everyWriteIsIndexedAsItWasSaved() {
            savesWhatItIsGiven();

            Plan created = service.create(new CreatePlanCommand("Pro", null));

            verify(searchRepository).save(argThat(summary -> summary.id().equals(created.getId())));
        }

        @Test
        void aPriceChangeReindexesItsPlan() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            service.addPrice(new AddPriceCommand(plan.getId(), AMOUNT, "BRL", BillingInterval.YEARLY));

            verify(searchRepository).save(argThat(summary ->
                    summary.activeIntervals().contains(BillingInterval.YEARLY)));
        }

        @Test
        void anIndexingFailureFailsTheWrite() {
            // Fail-fast: the container rolls the database write back (PlanServiceIT).
            savesWhatItIsGiven();
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(PlanSummary.class));

            assertThatThrownBy(() -> service.create(new CreatePlanCommand("Pro", null)))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        void theListingAsksTheIndexAndNeverTheDatabase() {
            SearchFilter filter = SearchFilter.of("pro");
            Pageable page = PageRequest.of(0, 20);
            Page<PlanSummary> expected = new PageImpl<>(List.of());
            when(searchRepository.search(filter, page)).thenReturn(expected);

            assertThat(service.search(filter, page)).isSameAs(expected);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("FR-02.5 the index is rebuilt from the database")
    class Reindexing {

        @Test
        void theIndexIsRecreatedAndEveryPlanIndexed() {
            when(repository.streamAll()).thenReturn(Stream.of(
                    Plan.create("Pro", null, EARLIER), Plan.create("Basic", null, EARLIER)));

            assertThat(service.reindex()).isEqualTo(2);
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

    private void savesWhatItIsGiven() {
        when(repository.save(any(Plan.class))).thenAnswer(call -> call.getArgument(0));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=PlanServiceTest`
Expected: compilation failure — `PlanService` and the commands do not exist.

- [ ] **Step 3: Write the commands and the service**

`CreatePlanCommand.java`:

```java
package com.navesdev.recurve.plan.service;

/** FR-02.1. */
public record CreatePlanCommand(String name, String description) {
}
```

`UpdatePlanCommand.java`:

```java
package com.navesdev.recurve.plan.service;

import java.util.UUID;

/** FR-02.6. Both fields are replaced; an absent description removes it. */
public record UpdatePlanCommand(UUID id, String name, String description) {
}
```

`AddPriceCommand.java`:

```java
package com.navesdev.recurve.plan.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;

/** FR-02.2. */
public record AddPriceCommand(UUID planId, BigDecimal price, String currency, BillingInterval interval) {
}
```

`ReplacePriceCommand.java`:

```java
package com.navesdev.recurve.plan.service;

import java.math.BigDecimal;
import java.util.UUID;

/** FR-02.7, BR-04: the cycle and currency are the replaced price's. */
public record ReplacePriceCommand(UUID priceId, BigDecimal price) {
}
```

`PlanService.java`:

```java
package com.navesdev.recurve.plan.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.domain.exception.PlanNotFoundException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case: it loads the
 * plan, calls the domain and persists. Every rule is the aggregate's; a
 * price is reached through the plan that holds it.
 *
 * <p>Every write goes to PostgreSQL and to the index inside the same
 * transaction, so a failure to index rolls the write back — fail-fast.
 * Each use case answers with the whole plan: after a replace, the caller
 * sees the old price inactive and its successor in force.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PlanService {

    private static final int REINDEX_BATCH = 500;

    private final PlanRepository repository;
    private final PlanSearchRepository searchRepository;
    private final Clock clock;

    public Plan create(CreatePlanCommand command) {
        return persist(Plan.create(command.name(), command.description(), clock.instant()));
    }

    public Plan update(UpdatePlanCommand command) {
        Plan plan = findOrThrow(command.id());
        plan.update(command.name(), command.description());
        return persist(plan);
    }

    /** FR-02.4: deactivate without deleting. */
    public Plan deactivate(UUID planId) {
        Plan plan = findOrThrow(planId);
        plan.deactivate();
        return persist(plan);
    }

    @Transactional(readOnly = true)
    public Plan findById(UUID planId) {
        return findOrThrow(planId);
    }

    public Plan addPrice(AddPriceCommand command) {
        Plan plan = findOrThrow(command.planId());
        plan.addPrice(command.price(), command.currency(), command.interval(), clock.instant());
        return persist(plan);
    }

    public Plan replacePrice(ReplacePriceCommand command) {
        Plan plan = findByPriceOrThrow(command.priceId());
        plan.replacePrice(command.priceId(), command.price(), clock.instant());
        return persist(plan);
    }

    public Plan deactivatePrice(UUID priceId) {
        Plan plan = findByPriceOrThrow(priceId);
        plan.deactivatePrice(priceId);
        return persist(plan);
    }

    /** FR-06.2 and FR-07: search and filter, then sort, then paginate — all in the index. */
    @Transactional(readOnly = true)
    public Page<PlanSummary> search(SearchFilter filter, Pageable pageable) {
        return searchRepository.search(filter, pageable);
    }

    /**
     * Rebuilds the index from the database and returns how many plans it
     * holds. {@link PlanIndexBootstrap} runs it at startup, the reindex
     * endpoint on request.
     */
    public long reindex() {
        searchRepository.recreateIndex();

        long indexed = 0;
        List<PlanSummary> batch = new ArrayList<>(REINDEX_BATCH);
        try (Stream<Plan> plans = repository.streamAll()) {
            for (Plan plan : (Iterable<Plan>) plans::iterator) {
                batch.add(PlanSummary.of(plan));
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
    private Plan persist(Plan plan) {
        Plan saved = repository.save(plan);
        searchRepository.save(PlanSummary.of(saved));
        return saved;
    }

    private Plan findOrThrow(UUID planId) {
        return repository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    }

    private Plan findByPriceOrThrow(UUID priceId) {
        return repository.findByPriceId(priceId).orElseThrow(() -> new PriceNotFoundException(priceId));
    }
}
```

`PlanIndexBootstrap.java`:

```java
package com.navesdev.recurve.plan.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.plan.repository.PlanSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the plan search index at startup, with the analyzers and
 * mapping in {@code search/}, and fills it from the database when it did
 * not exist. An existing index is left alone: a mapping change is a
 * deliberate rebuild through {@code POST /api/plans/reindex}.
 *
 * <p>Fail-fast: if the node cannot be reached, the application does not
 * start. Nothing at startup writes a plan, so the order only has to put it
 * before the first request.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class PlanIndexBootstrap implements ApplicationRunner {

    private final PlanSearchRepository searchRepository;
    private final PlanService service;

    @Override
    public void run(ApplicationArguments args) {
        if (searchRepository.indexExists()) {
            return;
        }

        long indexed = service.reindex();
        log.info("Plan search index created and populated with {} plans", indexed);
    }
}
```

Then: `git rm -q server/src/main/java/com/navesdev/recurve/plan/service/.gitkeep`

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `./mvnw -q test -Dtest=PlanServiceTest`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the integration tests**

`server/src/testIntegration/java/com/navesdev/recurve/plan/service/PlanServiceIT.java`:

```java
package com.navesdev.recurve.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;

/**
 * What only a real commit shows: that the container rolls a write back
 * when the index refuses it, that a replace survives the deferred BR-03
 * check, and that a stale copy of a plan cannot overwrite a newer one.
 * Deliberately not {@code @Transactional}: a test transaction would never
 * commit. The tables are truncated before each test instead; the index is
 * mocked, so nothing is left in it.
 */
@SpringBootTest
class PlanServiceIT {

    @Autowired
    private PlanService service;

    @Autowired
    private PlanRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @MockitoBean
    private PlanSearchRepository searchRepository;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE plans CASCADE");
    }

    @Nested
    @DisplayName("Fail-fast: the database and the index never diverge")
    class NeverDiverge {

        @Test
        void aWriteTheIndexRefusesLeavesNothingInTheDatabase() {
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(PlanSummary.class));

            assertThatThrownBy(() -> service.create(new CreatePlanCommand("Pro", null)))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("BR-04 a replace commits")
    class Replacing {

        @Test
        void theOldPriceIsInactiveAndItsSuccessorInForceAfterCommit() {
            UUID planId = service.create(new CreatePlanCommand("Pro", null)).getId();
            Plan priced = service.addPrice(new AddPriceCommand(planId, new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY));
            UUID oldId = priced.getPrices().getFirst().getId();

            service.replacePrice(new ReplacePriceCommand(oldId, new BigDecimal("59.90")));

            Plan stored = service.findById(planId);
            assertThat(stored.getPrices()).hasSize(2);
            assertThat(stored.price(oldId).isActive()).isFalse();
            assertThat(stored.getPrices()).filteredOn(PlanPrice::isActive).singleElement()
                    .satisfies(successor -> assertThat(successor.getPrice()).isEqualByComparingTo("59.90"));
        }
    }

    @Nested
    @DisplayName("Concurrent writes on one plan")
    class Concurrency {

        @Test
        void aWriteOverAStaleCopyOfThePlanIsRefused() {
            // Adding a price changes the plan's own collection, which
            // increments its version: a copy read before that is stale.
            UUID planId = service.create(new CreatePlanCommand("Pro", null)).getId();
            Plan stale = service.findById(planId);
            service.addPrice(new AddPriceCommand(planId, new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY));

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                stale.update("Pro Plus", null);
                repository.save(stale);
            })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
    }
}
```

`server/src/testIntegration/java/com/navesdev/recurve/plan/service/PlanIndexBootstrapIT.java`:

```java
package com.navesdev.recurve.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The index's schema migration: what startup does when the plan index is
 * missing, and what it leaves alone when it is not.
 */
@SpringBootTest
@Transactional
class PlanIndexBootstrapIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private PlanIndexBootstrap bootstrap;

    @Autowired
    private PlanRepository repository;

    @Autowired
    private PlanSearchRepository searchRepository;

    @Autowired
    private ElasticsearchOperations operations;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();
        Plan pro = Plan.create("Pro Teams", null, NOW);
        pro.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        repository.save(pro);
        repository.save(Plan.create("Basic", null, NOW));
        entityManager.flush();
    }

    @Nested
    @DisplayName("A missing index is created and filled from the database")
    class MissingIndex {

        @Test
        void everyPlanInTheDatabaseIsInTheNewIndex() {
            operations.indexOps(PlanSummary.class).delete();

            bootstrap.run(null);

            assertThat(searchRepository.indexExists()).isTrue();
            assertThat(total(SearchFilter.of(null))).isEqualTo(2);
        }

        @Test
        void theNewIndexCarriesTheMappingNotAGuessedOne() {
            operations.indexOps(PlanSummary.class).delete();

            bootstrap.run(null);

            // Word-prefix matching only exists with the analyzer from search/plans-settings.json.
            assertThat(total(SearchFilter.of("tea"))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("An existing index is left alone")
    class ExistingIndex {

        @Test
        void startupDoesNotRebuildAnIndexThatIsAlreadyThere() {
            searchRepository.recreateIndex();
            searchRepository.save(PlanSummary.of(Plan.create("Only", null, NOW)));

            bootstrap.run(null);

            assertThat(total(SearchFilter.of(null))).isEqualTo(1);
        }
    }

    private long total(SearchFilter filter) {
        return searchRepository.search(filter, PageRequest.of(0, 10)).getTotalElements();
    }
}
```

- [ ] **Step 6: Run the integration tests to verify they pass**

Run: `./mvnw -q verify -Pintegration -Dit.test='PlanServiceIT,PlanIndexBootstrapIT'`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/com/navesdev/recurve/plan/service server/src/test/java/com/navesdev/recurve/plan/service server/src/testIntegration/java/com/navesdev/recurve/plan/service
git commit -m "feat(server-plan): add the plan use cases and build the index at startup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: HTTP endpoints and their contract

**Files:**
- Move: `server/src/main/java/com/navesdev/recurve/user/controller/ReindexResponse.java` → `server/src/main/java/com/navesdev/recurve/shared/controller/ReindexResponse.java`
- Modify: `server/src/main/java/com/navesdev/recurve/user/controller/UserController.java` (import)
- Modify: `server/src/main/java/com/navesdev/recurve/shared/controller/GlobalExceptionHandler.java` (409)
- Create (all in `server/src/main/java/com/navesdev/recurve/plan/controller/`): `CreatePlanRequest.java`, `UpdatePlanRequest.java`, `AddPriceRequest.java`, `ReplacePriceRequest.java`, `PriceResponse.java`, `PlanResponse.java`, `PlanController.java`, `PriceController.java`
- Delete: `server/src/main/java/com/navesdev/recurve/plan/controller/.gitkeep`
- Modify: `server/src/main/resources/docs/openapi.yaml`
- Modify: `server/src/test/java/com/navesdev/recurve/ApiContractTest.java`
- Test: `server/src/test/java/com/navesdev/recurve/plan/controller/PlanControllerTest.java`
- Test: `server/src/test/java/com/navesdev/recurve/plan/controller/PriceControllerTest.java`

**Interfaces:**
- Consumes: `PlanService` and the commands (Task 5); `Plan`, `PlanPrice`, `PlanSummary`, `BillingInterval`, `PlanValidator` (Tasks 1–4); `Listing`, `ListingRequest`, `PageResponse` (existing).
- Produces: routes `POST/GET /api/plans`, `GET/PUT/DELETE /api/plans/{id}`, `POST /api/plans/reindex`, `POST /api/plans/{id}/prices`, `POST /api/prices/{id}/replace`, `DELETE /api/prices/{id}`.
- Produces: `record PlanResponse(UUID id, String name, String description, boolean active, Instant createdAt, List<PriceResponse> prices)` with `from(Plan)`, `from(PlanSummary)`; `record PriceResponse(UUID id, BigDecimal price, String currency, BillingInterval interval, boolean active, Instant createdAt)` with `from(PlanPrice)`, `from(PlanSummary.Price)`; `shared.controller.ReindexResponse(long indexed)`.

- [ ] **Step 1: Move `ReindexResponse` to `shared`**

```bash
git mv server/src/main/java/com/navesdev/recurve/user/controller/ReindexResponse.java server/src/main/java/com/navesdev/recurve/shared/controller/ReindexResponse.java
```

Replace its content with:

```java
package com.navesdev.recurve.shared.controller;

/** How many documents a rebuilt search index holds (FR-01.5, FR-02.5). */
public record ReindexResponse(long indexed) {
}
```

In `UserController.java`, add to the `com.navesdev.recurve.shared.controller` imports:

```java
import com.navesdev.recurve.shared.controller.ReindexResponse;
```

Run: `./mvnw -q test -Dtest=UserControllerTest`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 2: Write the failing controller tests**

`server/src/test/java/com/navesdev/recurve/plan/controller/PlanControllerTest.java`:

```java
package com.navesdev.recurve.plan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.domain.exception.PlanNotFoundException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.service.AddPriceCommand;
import com.navesdev.recurve.plan.service.CreatePlanCommand;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.plan.service.UpdatePlanCommand;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * What the plan API promises a client: what counts as a bad request, how
 * an amount travels, and the shape of a page. Authorization needs the
 * real chain, so it lives in {@code PlanEndpointAuthorizationIT}.
 */
@WebMvcTest(PlanController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, PlanControllerTest.FixedClock.class })
class PlanControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlanService service;

    @Nested
    @DisplayName("FR-02.1 / FR-02.6 a plan has a name and may have a description")
    class Plans {

        @Test
        void aRegisteredPlanIsCreatedAtItsOwnAddress() throws Exception {
            Plan plan = Plan.create("Pro", "For teams", NOW);
            when(service.create(any(CreatePlanCommand.class))).thenReturn(plan);

            mvc.perform(json(post("/api/plans"), """
                    {"name":"Pro","description":"For teams"}
                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/plans/" + plan.getId()))
                    .andExpect(jsonPath("$.name").value("Pro"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.prices.length()").value(0));
        }

        @Test
        void aPlanWithoutANameIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans"), """
                    {"name":""}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("name"));

            verify(service, never()).create(any());
        }

        @Test
        void aDescriptionLongerThanItsColumnIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans"), """
                    {"name":"Pro","description":"%s"}
                    """.formatted("a".repeat(501))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("description"));
        }

        @Test
        void anEditNamesThePlanByItsAddress() throws Exception {
            UUID id = UUID.randomUUID();
            ArgumentCaptor<UpdatePlanCommand> sent = ArgumentCaptor.forClass(UpdatePlanCommand.class);
            when(service.update(sent.capture())).thenReturn(Plan.create("Pro Plus", null, NOW));

            mvc.perform(json(put("/api/plans/{id}", id), """
                    {"name":"Pro Plus"}
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").doesNotExist());

            assertThat(sent.getValue().id()).isEqualTo(id);
        }

        @Test
        void aMissingPlanIsNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.findById(id)).thenThrow(new PlanNotFoundException(id));

            mvc.perform(get("/api/plans/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("FR-02.2 pricing a plan")
    class Pricing {

        @Test
        void aPriceIsCreatedUnderItsPlanAndThePlanComesBack() throws Exception {
            UUID planId = UUID.randomUUID();
            ArgumentCaptor<AddPriceCommand> sent = ArgumentCaptor.forClass(AddPriceCommand.class);
            when(service.addPrice(sent.capture())).thenReturn(pricedPlan());

            mvc.perform(json(post("/api/plans/{id}/prices", planId), """
                    {"price":49.90,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.prices[0].currency").value("BRL"))
                    .andExpect(jsonPath("$.prices[0].interval").value("MONTHLY"));

            assertThat(sent.getValue().planId()).isEqualTo(planId);
            assertThat(sent.getValue().price()).isEqualByComparingTo("49.90");
        }

        @Test
        void anAmountTravelsWithItsTwoDecimalPlaces() throws Exception {
            // NFR-06: 49.90 goes out as 49.90, not 49.9.
            when(service.addPrice(any())).thenReturn(pricedPlan());

            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":49.90,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(content().string(containsString("\"price\":49.90")));
        }

        @Test
        void anAmountThatIsNotPositiveIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":0,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("price"));

            verify(service, never()).addPrice(any());
        }

        @Test
        void anAmountWithMoreThanTwoDecimalPlacesIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":12.345,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("price"));
        }

        @Test
        void everyProblemWithThePriceIsReportedAtOnce() throws Exception {
            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":-1,"currency":"BR"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(3));
        }

        @Test
        void aSecondActivePriceOnOnePairIsABusinessRuleViolation() throws Exception {
            when(service.addPrice(any()))
                    .thenThrow(new PriceAlreadyActiveException(BillingInterval.MONTHLY, "BRL"));

            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":59.90,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    @DisplayName("A plan changed by someone else first")
    class Conflict {

        @Test
        void aLostRaceIsAConflictTheCallerCanRetry() throws Exception {
            when(service.update(any())).thenThrow(new ObjectOptimisticLockingFailureException(Plan.class, UUID.randomUUID()));

            mvc.perform(json(put("/api/plans/{id}", UUID.randomUUID()), """
                    {"name":"Pro"}
                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    @Nested
    @DisplayName("FR-06.2 / FR-07 the listing")
    class Listing {

        @Test
        void thePageShowsEachPlanWithItsPrices() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(PlanSummary.of(pricedPlan())), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].prices[0].currency").value("BRL"))
                    .andExpect(content().string(containsString("\"price\":49.90")));
        }

        @Test
        void anAbsentSortIsByNameAscending() throws Exception {
            ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
            when(service.search(any(SearchFilter.class), sent.capture()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/plans")).andExpect(status().isOk());

            assertThat(sent.getValue().getSort().getOrderFor("name.keyword")).isNotNull();
        }

        @Test
        void theCycleFilterIsPassedThroughForTheIndexToJudge() throws Exception {
            ArgumentCaptor<SearchFilter> sent = ArgumentCaptor.forClass(SearchFilter.class);
            when(service.search(sent.capture(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/plans").param("filter", "activeIntervals:MONTHLY,YEARLY"))
                    .andExpect(status().isOk());

            assertThat(sent.getValue().valuesOf("activeIntervals")).containsExactly("MONTHLY", "YEARLY");
        }
    }

    @Test
    void theRebuildReportsHowManyPlansTheIndexHolds() throws Exception {
        when(service.reindex()).thenReturn(3L);

        mvc.perform(post("/api/plans/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(3));
    }

    private static Plan pricedPlan() {
        Plan plan = Plan.create("Pro", null, NOW);
        plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        return plan;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
```

`server/src/test/java/com/navesdev/recurve/plan/controller/PriceControllerTest.java`:

```java
package com.navesdev.recurve.plan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.plan.service.ReplacePriceCommand;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;

/**
 * A price that exists is addressed by its own id alone. ListingRequests is
 * imported only because the slice builds the shared listing resolver.
 */
@WebMvcTest(PriceController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, PriceControllerTest.FixedClock.class })
class PriceControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlanService service;

    @Nested
    @DisplayName("FR-02.7 / BR-04 replacing a price")
    class Replacing {

        @Test
        void theReplacementIsCreatedAndThePlanComesBackWithBothPrices() throws Exception {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.replacePrice(old.getId(), new BigDecimal("59.90"), NOW);
            ArgumentCaptor<ReplacePriceCommand> sent = ArgumentCaptor.forClass(ReplacePriceCommand.class);
            when(service.replacePrice(sent.capture())).thenReturn(plan);

            mvc.perform(post("/api/prices/{id}/replace", old.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"price":59.90}
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.prices.length()").value(2))
                    .andExpect(jsonPath("$.prices[0].active").value(false))
                    .andExpect(jsonPath("$.prices[1].active").value(true));

            assertThat(sent.getValue().priceId()).isEqualTo(old.getId());
            assertThat(sent.getValue().price()).isEqualByComparingTo("59.90");
        }

        @Test
        void aReplacementWithoutAnAmountIsRefused() throws Exception {
            mvc.perform(post("/api/prices/{id}/replace", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("price"));

            verify(service, never()).replacePrice(any());
        }

        @Test
        void aMissingPriceIsNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.replacePrice(any())).thenThrow(new PriceNotFoundException(id));

            mvc.perform(post("/api/prices/{id}/replace", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"price":59.90}
                            """))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("FR-02.3 deactivating a price")
    class Deactivating {

        @Test
        void aDeactivatedPriceComesBackInactiveInItsPlan() throws Exception {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(price.getId());
            when(service.deactivatePrice(price.getId())).thenReturn(plan);

            mvc.perform(delete("/api/prices/{id}", price.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.prices[0].active").value(false));
        }

        @Test
        void aPriceIsDeactivatedOnlyOnce() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.deactivatePrice(id)).thenThrow(new PriceAlreadyInactiveException(id));

            mvc.perform(delete("/api/prices/{id}", id))
                    .andExpect(status().isUnprocessableEntity());
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='PlanControllerTest,PriceControllerTest'`
Expected: compilation failure — the controllers and records do not exist.

- [ ] **Step 4: Write the requests and responses**

`CreatePlanRequest.java`:

```java
package com.navesdev.recurve.plan.controller;

import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.CreatePlanCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePlanRequest(

        @NotBlank @Size(max = PlanValidator.NAME_MAX_LENGTH) String name,

        @Size(max = PlanValidator.DESCRIPTION_MAX_LENGTH) String description) {

    public CreatePlanCommand toCommand() {
        return new CreatePlanCommand(name, description);
    }
}
```

`UpdatePlanRequest.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.util.UUID;

import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.UpdatePlanCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePlanRequest(

        @NotBlank @Size(max = PlanValidator.NAME_MAX_LENGTH) String name,

        @Size(max = PlanValidator.DESCRIPTION_MAX_LENGTH) String description) {

    public UpdatePlanCommand toCommand(UUID id) {
        return new UpdatePlanCommand(id, name, description);
    }
}
```

`AddPriceRequest.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.math.BigDecimal;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.AddPriceCommand;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AddPriceRequest(

        @NotNull
        @DecimalMin(PlanValidator.PRICE_MIN)
        @Digits(integer = PlanValidator.PRICE_INTEGER_DIGITS, fraction = PlanValidator.PRICE_FRACTION_DIGITS)
        BigDecimal price,

        @NotBlank @Pattern(regexp = PlanValidator.CURRENCY_PATTERN) String currency,

        @NotNull BillingInterval interval) {

    public AddPriceCommand toCommand(UUID planId) {
        return new AddPriceCommand(planId, price, currency, interval);
    }
}
```

`ReplacePriceRequest.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.math.BigDecimal;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.ReplacePriceCommand;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Only the amount: the cycle and currency are the replaced price's (BR-04). */
public record ReplacePriceRequest(

        @NotNull
        @DecimalMin(PlanValidator.PRICE_MIN)
        @Digits(integer = PlanValidator.PRICE_INTEGER_DIGITS, fraction = PlanValidator.PRICE_FRACTION_DIGITS)
        BigDecimal price) {

    public ReplacePriceCommand toCommand(UUID priceId) {
        return new ReplacePriceCommand(priceId, price);
    }
}
```

`PriceResponse.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;

/** An amount goes out as a JSON number with its two decimal places (NFR-06). */
public record PriceResponse(
        UUID id,
        BigDecimal price,
        String currency,
        BillingInterval interval,
        boolean active,
        Instant createdAt) {

    public static PriceResponse from(PlanPrice price) {
        return new PriceResponse(
                price.getId(),
                price.getPrice(),
                price.getCurrency(),
                price.getInterval(),
                price.isActive(),
                price.getCreatedAt());
    }

    /** The index keeps the amount as text; it comes back exactly as stored. */
    public static PriceResponse from(PlanSummary.Price price) {
        return new PriceResponse(
                price.id(),
                new BigDecimal(price.price()),
                price.currency(),
                price.interval(),
                price.active(),
                price.createdAt());
    }
}
```

`PlanResponse.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;

/**
 * A plan with every price it has had, inactive ones included: the history
 * is the point of never editing a price (BR-04). Reads getters only.
 */
public record PlanResponse(
        UUID id,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        List<PriceResponse> prices) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.getId(),
                plan.getName(),
                plan.getDescription(),
                plan.isActive(),
                plan.getCreatedAt(),
                plan.getPrices().stream().map(PriceResponse::from).toList());
    }

    /** The listing (FR-06.2) answers from the read model, not the entity. */
    public static PlanResponse from(PlanSummary summary) {
        return new PlanResponse(
                summary.id(),
                summary.name(),
                summary.description(),
                summary.active(),
                summary.createdAt(),
                summary.prices().stream().map(PriceResponse::from).toList());
    }
}
```

- [ ] **Step 5: Write the controllers**

`PlanController.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.shared.controller.Listing;
import com.navesdev.recurve.shared.controller.ListingRequest;
import com.navesdev.recurve.shared.controller.PageResponse;
import com.navesdev.recurve.shared.controller.ReindexResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Converts HTTP into a service call and the result into a response. Holds
 * no rule. A price is created here, under its plan, because it needs one;
 * once it exists it is addressed alone, in {@link PriceController}.
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    /** Text fields sort on their keyword copy; the contract names it as the index does. */
    private static final String DEFAULT_SORT = "name.keyword";

    private final PlanService service;

    @PostMapping
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody CreatePlanRequest request) {
        Plan created = service.create(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/plans/" + created.getId()))
                .body(PlanResponse.from(created));
    }

    @PutMapping("/{id}")
    public PlanResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest request) {
        return PlanResponse.from(service.update(request.toCommand(id)));
    }

    /** FR-02.4: deactivates the plan; the record and its prices are kept. */
    @DeleteMapping("/{id}")
    public PlanResponse deactivate(@PathVariable UUID id) {
        return PlanResponse.from(service.deactivate(id));
    }

    @GetMapping("/{id}")
    public PlanResponse findById(@PathVariable UUID id) {
        return PlanResponse.from(service.findById(id));
    }

    /**
     * FR-02.2. Answers with the whole plan and no {@code Location}: a
     * price has no address of its own to read.
     */
    @PostMapping("/{id}/prices")
    public ResponseEntity<PlanResponse> addPrice(@PathVariable UUID id, @Valid @RequestBody AddPriceRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PlanResponse.from(service.addPrice(request.toCommand(id))));
    }

    /** Rebuilds the plan search index from the database. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(service.reindex());
    }

    /**
     * FR-06.2 and FR-07. {@code q} searches the name by word prefix;
     * {@code filter=activeIntervals:MONTHLY} keeps plans with an active
     * monthly price; {@code filter=active:true} keeps active plans. The
     * index mapping decides what can be filtered or sorted on.
     */
    @GetMapping
    public PageResponse<PlanResponse> search(@Listing(defaultSort = DEFAULT_SORT) ListingRequest listing) {
        return PageResponse.from(service.search(listing.filter(), listing.pageable()), PlanResponse::from);
    }
}
```

`PriceController.java`:

```java
package com.navesdev.recurve.plan.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.plan.service.PlanService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A price that exists, addressed by its own id: unique across plans, so a
 * plan id beside it would only be a second thing to get wrong. Both routes
 * answer with the plan the price belongs to.
 */
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PlanService service;

    /**
     * FR-02.7, BR-04: a {@code POST}, not a {@code PUT}. The price is not
     * edited — a successor is created and this one deactivated, so the
     * result is a new resource.
     */
    @PostMapping("/{id}/replace")
    public ResponseEntity<PlanResponse> replace(@PathVariable UUID id, @Valid @RequestBody ReplacePriceRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PlanResponse.from(service.replacePrice(request.toCommand(id))));
    }

    /** FR-02.3: deactivates the price; subscribers on it stay. */
    @DeleteMapping("/{id}")
    public PlanResponse deactivate(@PathVariable UUID id) {
        return PlanResponse.from(service.deactivatePrice(id));
    }
}
```

Then: `git rm -q server/src/main/java/com/navesdev/recurve/plan/controller/.gitkeep`

- [ ] **Step 6: Map a lost race to 409**

In `server/src/main/java/com/navesdev/recurve/shared/controller/GlobalExceptionHandler.java`, add the import `org.springframework.dao.OptimisticLockingFailureException` and, after `handleInvalidRequest`, the handler:

```java
    /**
     * Two requests changed the same record and this one lost. Nothing was
     * written; the caller reloads and decides again.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConflict(OptimisticLockingFailureException e) {
        return respond(HttpStatus.CONFLICT, "The resource was changed by another request; reload it and try again");
    }
```

- [ ] **Step 7: Run the controller tests to verify they pass**

Run: `./mvnw -q test -Dtest='PlanControllerTest,PriceControllerTest,UserControllerTest'`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 8: Write the contract**

In `server/src/test/java/com/navesdev/recurve/ApiContractTest.java`, change the slice and add the mock:

```java
@WebMvcTest(controllers = { UserController.class, PlanController.class, PriceController.class })
class ApiContractTest {
```

```java
    @MockitoBean
    private PlanService planService;
```

with imports `com.navesdev.recurve.plan.controller.PlanController`, `com.navesdev.recurve.plan.controller.PriceController`, `com.navesdev.recurve.plan.service.PlanService`.

Run: `./mvnw -q test -Dtest=ApiContractTest`
Expected: FAIL in `routesMatch` — the nine plan routes are mapped but not documented.

In `server/src/main/resources/docs/openapi.yaml`:

1. Under `tags:`, after `- name: Users`, add:

```yaml
  - name: Plans
  - name: Prices
```

2. At the end of `paths:` (after `/api/users/reindex`), add:

```yaml
  /api/plans:

    post:
      tags: [Plans]
      operationId: createPlan
      summary: Register a plan
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreatePlanRequest'
      responses:
        '201':
          description: Created
          headers:
            Location:
              schema:
                type: string
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              example:
                id: 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
                name: Pro
                description: For teams
                active: true
                createdAt: '2026-01-15T10:00:00Z'
                prices: []
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

    get:
      tags: [Plans]
      operationId: searchPlans
      summary: List plans
      parameters:
        - $ref: '#/components/parameters/q'
        - $ref: '#/components/parameters/filter'
        - $ref: '#/components/parameters/page'
        - $ref: '#/components/parameters/size'
        - $ref: '#/components/parameters/sort'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanPage'
              example:
                items:
                  - id: 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
                    name: Pro
                    description: For teams
                    active: true
                    createdAt: '2026-01-15T10:00:00Z'
                    prices:
                      - id: 7c6b5a49-3827-4615-9e0d-1c2b3a4f5e6d
                        price: 49.90
                        currency: BRL
                        interval: MONTHLY
                        active: true
                        createdAt: '2026-01-15T10:00:00Z'
                page: 0
                size: 20
                total: 1
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '502':
          $ref: '#/components/responses/BadGateway'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/plans/{id}:

    parameters:
      - $ref: '#/components/parameters/planId'

    get:
      tags: [Plans]
      operationId: findPlan
      summary: Get a plan
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              examples:
                plan:
                  $ref: '#/components/examples/plan'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

    put:
      tags: [Plans]
      operationId: updatePlan
      summary: Update a plan
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UpdatePlanRequest'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              examples:
                plan:
                  $ref: '#/components/examples/plan'
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

    delete:
      tags: [Plans]
      operationId: deactivatePlan
      summary: Deactivate a plan
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              example:
                id: 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
                name: Pro
                description: For teams
                active: false
                createdAt: '2026-01-15T10:00:00Z'
                prices:
                  - id: 7c6b5a49-3827-4615-9e0d-1c2b3a4f5e6d
                    price: 49.90
                    currency: BRL
                    interval: MONTHLY
                    active: true
                    createdAt: '2026-01-15T10:00:00Z'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/plans/{id}/prices:

    parameters:
      - $ref: '#/components/parameters/planId'

    post:
      tags: [Plans]
      operationId: addPrice
      summary: Add a price to a plan
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AddPriceRequest'
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              examples:
                plan:
                  $ref: '#/components/examples/plan'
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/plans/reindex:

    post:
      tags: [Plans]
      operationId: reindexPlans
      summary: Rebuild the plan search index
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ReindexResponse'
              example:
                indexed: 12
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/prices/{id}/replace:

    parameters:
      - $ref: '#/components/parameters/priceId'

    post:
      tags: [Prices]
      operationId: replacePrice
      summary: Replace a price with a new amount
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ReplacePriceRequest'
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              example:
                id: 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
                name: Pro
                description: For teams
                active: true
                createdAt: '2026-01-15T10:00:00Z'
                prices:
                  - id: 7c6b5a49-3827-4615-9e0d-1c2b3a4f5e6d
                    price: 49.90
                    currency: BRL
                    interval: MONTHLY
                    active: false
                    createdAt: '2026-01-15T10:00:00Z'
                  - id: 0e1d2c3b-4a59-4687-b7c6-d5e4f3a2b1c0
                    price: 59.90
                    currency: BRL
                    interval: MONTHLY
                    active: true
                    createdAt: '2026-03-01T10:00:00Z'
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/prices/{id}:

    parameters:
      - $ref: '#/components/parameters/priceId'

    delete:
      tags: [Prices]
      operationId: deactivatePrice
      summary: Deactivate a price
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PlanResponse'
              example:
                id: 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
                name: Pro
                description: For teams
                active: true
                createdAt: '2026-01-15T10:00:00Z'
                prices:
                  - id: 7c6b5a49-3827-4615-9e0d-1c2b3a4f5e6d
                    price: 49.90
                    currency: BRL
                    interval: MONTHLY
                    active: false
                    createdAt: '2026-01-15T10:00:00Z'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'
```

3. Under `components.parameters`, after `userId`, add:

```yaml
    planId:
      name: id
      in: path
      required: true
      schema:
        type: string
        format: uuid

    priceId:
      name: id
      in: path
      required: true
      schema:
        type: string
        format: uuid
```

4. Under `components.responses`, after `UnprocessableEntity`, add:

```yaml
    Conflict:
      description: Conflict
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ApiError'
```

5. Under `components.schemas`, before `ReindexResponse`, add:

```yaml
    BillingInterval:
      type: string
      enum:
        - MONTHLY
        - YEARLY

    CreatePlanRequest:
      type: object
      required: [name]
      properties:
        name:
          type: string
          minLength: 1
          maxLength: 120
        description:
          type: string
          nullable: true
          maxLength: 500

    UpdatePlanRequest:
      type: object
      required: [name]
      properties:
        name:
          type: string
          minLength: 1
          maxLength: 120
        description:
          type: string
          nullable: true
          maxLength: 500

    AddPriceRequest:
      type: object
      required: [price, currency, interval]
      properties:
        price:
          type: number
          minimum: 0.01
          maximum: 9999999999.99
        currency:
          type: string
          pattern: '^[A-Za-z]{3}$'
        interval:
          $ref: '#/components/schemas/BillingInterval'

    ReplacePriceRequest:
      type: object
      required: [price]
      properties:
        price:
          type: number
          minimum: 0.01
          maximum: 9999999999.99

    PriceResponse:
      type: object
      required: [id, price, currency, interval, active, createdAt]
      properties:
        id:
          type: string
          format: uuid
        price:
          type: number
          minimum: 0.01
          maximum: 9999999999.99
        currency:
          type: string
          pattern: '^[A-Z]{3}$'
        interval:
          $ref: '#/components/schemas/BillingInterval'
        active:
          type: boolean
        createdAt:
          type: string
          format: date-time

    PlanResponse:
      type: object
      required: [id, name, active, createdAt, prices]
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        description:
          type: string
          nullable: true
        active:
          type: boolean
        createdAt:
          type: string
          format: date-time
        prices:
          type: array
          items:
            $ref: '#/components/schemas/PriceResponse'

    PlanPage:
      type: object
      required: [items, page, size, total]
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/PlanResponse'
        page:
          type: integer
          minimum: 0
        size:
          type: integer
          minimum: 1
        total:
          type: integer
          format: int64
          minimum: 0
```

6. Under `components.examples`, after `user`, add:

```yaml
    plan:
      value:
        id: 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
        name: Pro
        description: For teams
        active: true
        createdAt: '2026-01-15T10:00:00Z'
        prices:
          - id: 7c6b5a49-3827-4615-9e0d-1c2b3a4f5e6d
            price: 49.90
            currency: BRL
            interval: MONTHLY
            active: true
            createdAt: '2026-01-15T10:00:00Z'
```

- [ ] **Step 9: Run the unit suite and the gate**

Run: `./mvnw -q test`
Expected: all unit tests pass, `ApiContractTest.routesMatch` included.

Run: `./mvnw -q pmd:check`
Expected: no violation.

- [ ] **Step 10: Commit**

```bash
git add server/src/main/java server/src/main/resources/docs/openapi.yaml server/src/test/java
git commit -m "feat(server-plan): serve plans and prices over HTTP and state the contract

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Route permissions, end to end

**Files:**
- Modify: `server/src/main/java/com/navesdev/recurve/shared/config/SecurityConfig.java`
- Test: `server/src/testIntegration/java/com/navesdev/recurve/plan/PlanEndpointAuthorizationIT.java`
- Test: `server/src/testIntegration/java/com/navesdev/recurve/plan/PlanContractIT.java`

**Interfaces:**
- Consumes: every route of Task 6; `User`, `Permission`, `UserRepository` (existing); `PlanRepository`, `PlanSearchRepository`, `PlanSummary` (Tasks 3, 4).
- Produces: route rules — `POST /api/plans/reindex` → `MANAGE_SYSTEM`; `GET /api/plans/**` → `VIEW_PLANS`; any other method on `/api/plans/**` and every `/api/prices/**` → `MANAGE_PLANS`.

- [ ] **Step 1: Write the failing authorization test**

`server/src/testIntegration/java/com/navesdev/recurve/plan/PlanEndpointAuthorizationIT.java`:

```java
package com.navesdev.recurve.plan;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The plan route rules in {@code SecurityConfig}, over real HTTP Basic and
 * the real service: VIEW_PLANS reads, MANAGE_PLANS writes and reads too
 * (BR-01), rebuilding the index is a system operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlanEndpointAuthorizationIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanSearchRepository planSearchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID planId;
    private UUID priceId;

    @BeforeEach
    void setUp() {
        planSearchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();

        register("viewer@recurve.local", Permission.VIEW_PLANS);
        register("manager@recurve.local", Permission.MANAGE_PLANS);
        register("outsider@recurve.local", Permission.VIEW_SUBSCRIBERS);
        register("sysadmin@recurve.local", Permission.MANAGE_SYSTEM);

        Plan plan = Plan.create("Pro", null, NOW);
        priceId = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW).getId();
        planId = planSearchRepository.save(PlanSummary.of(planRepository.save(plan))).id();
        entityManager.flush();
    }

    @Nested
    @DisplayName("VIEW_PLANS reads and nothing else")
    class Viewer {

        @Test
        void listsAndReadsPlans() throws Exception {
            mvc.perform(get("/api/plans").with(as("viewer"))).andExpect(status().isOk());
            mvc.perform(get("/api/plans/{id}", planId).with(as("viewer"))).andExpect(status().isOk());
        }

        @Test
        void changesNothing() throws Exception {
            mvc.perform(post("/api/plans").with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Basic\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/plans/{id}", planId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Basic\"}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/plans/{id}", planId).with(as("viewer"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/plans/{id}/prices", planId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content(priceBody("YEARLY"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/prices/{id}/replace", priceId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"price\":59.90}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/prices/{id}", priceId).with(as("viewer"))).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("MANAGE_PLANS writes, and reads because manage implies view (BR-01)")
    class Manager {

        @Test
        void listsPlans() throws Exception {
            mvc.perform(get("/api/plans").with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void registersAPlan() throws Exception {
            mvc.perform(post("/api/plans").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Basic\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        void pricesReplacesAndDeactivates() throws Exception {
            mvc.perform(post("/api/plans/{id}/prices", planId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(priceBody("YEARLY")))
                    .andExpect(status().isCreated());
            mvc.perform(post("/api/prices/{id}/replace", priceId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"price\":59.90}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.prices.length()").value(3));
            mvc.perform(delete("/api/prices/{id}", priceId).with(as("manager")))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void aSecondActivePriceOnOnePairIsABusinessRuleViolation() throws Exception {
            mvc.perform(post("/api/plans/{id}/prices", planId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(priceBody("MONTHLY")))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void filtersByCycleEndToEnd() throws Exception {
            mvc.perform(get("/api/plans").with(as("manager")).param("filter", "activeIntervals:YEARLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(get("/api/plans").with(as("manager")).param("filter", "activeIntervals:MONTHLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
    }

    @Test
    void anOperatorWithNoPlanPermissionIsRefusedEvenAListing() throws Exception {
        mvc.perform(get("/api/plans").with(as("outsider"))).andExpect(status().isForbidden());
    }

    @Nested
    @DisplayName("Rebuilding the plan index is a system operation")
    class Reindexing {

        @Test
        void managingPlansIsNotEnough() throws Exception {
            mvc.perform(post("/api/plans/reindex").with(as("manager"))).andExpect(status().isForbidden());
        }

        @Test
        void managingTheSystemRebuildsTheIndexFromTheDatabase() throws Exception {
            mvc.perform(post("/api/plans/reindex").with(as("sysadmin")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(1));
        }
    }

    private static String priceBody(String interval) {
        return """
                {"price":49.90,"currency":"BRL","interval":"%s"}
                """.formatted(interval);
    }

    private static RequestPostProcessor as(String who) {
        return httpBasic(who + "@recurve.local", PASSWORD);
    }

    private void register(String email, Permission permission) {
        userRepository.save(User.create(email, email, passwordEncoder.encode(PASSWORD), Set.of(permission), NOW));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q verify -Pintegration -Dit.test=PlanEndpointAuthorizationIT`
Expected: FAIL — `changesNothing`, `anOperatorWithNoPlanPermissionIsRefusedEvenAListing` and `managingPlansIsNotEnough` get 2xx/4xx other than 403, because every authenticated operator passes `anyRequest().authenticated()`.

- [ ] **Step 3: Add the route rules**

In `SecurityConfig.securityFilterChain`, replace:

```java
                    requests
                        // FR-01.5: rebuilding an index is a system operation.
                        .requestMatchers(HttpMethod.POST, "/api/users/reindex").hasAuthority("MANAGE_SYSTEM")
                        // BR-01: operators have no view permission; reading them is managing them.
                        .requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
                        .anyRequest().authenticated();
```

with:

```java
                    requests
                        // FR-01.5: rebuilding an index is a system operation.
                        .requestMatchers(HttpMethod.POST, "/api/users/reindex", "/api/plans/reindex")
                            .hasAuthority("MANAGE_SYSTEM")
                        // BR-01: operators have no view permission; reading them is managing them.
                        .requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
                        // BR-01: plans are read with VIEW_PLANS, which MANAGE_PLANS implies.
                        .requestMatchers(HttpMethod.GET, "/api/plans/**").hasAuthority("VIEW_PLANS")
                        // A price is part of its plan: changing either is managing plans.
                        .requestMatchers("/api/plans/**", "/api/prices/**").hasAuthority("MANAGE_PLANS")
                        .anyRequest().authenticated();
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw -q verify -Pintegration -Dit.test='PlanEndpointAuthorizationIT,UserEndpointAuthorizationIT'`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the contract conformance test**

`server/src/testIntegration/java/com/navesdev/recurve/plan/PlanContractIT.java`:

```java
package com.navesdev.recurve.plan;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Every real plan and price exchange, request and response, must be one
 * {@code docs/openapi.yaml} allows. Behaviour is covered elsewhere; this
 * asks only whether what went over the wire matches what was promised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlanContractIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String SPEC = "/docs/openapi.yaml";

    /** Request and response both as promised. */
    private static final ResultMatcher CONTRACT = openApi()
            .isValid(OpenApiInteractionValidator.createForSpecificationUrl(SPEC).build());

    /** For a request that is wrong on purpose: only the refusal is checked. */
    private static final ResultMatcher CONTRACT_RESPONSE = openApi()
            .isValid(OpenApiInteractionValidator.createForSpecificationUrl(SPEC)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .build())
                    .build());

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanSearchRepository planSearchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID planId;
    private UUID priceId;

    @BeforeEach
    void setUp() {
        planSearchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();

        userRepository.save(User.create("Maya Manager", "manager@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.MANAGE_PLANS, Permission.MANAGE_SYSTEM), NOW));
        userRepository.save(User.create("Vera Viewer", "viewer@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.VIEW_PLANS), NOW));

        Plan plan = Plan.create("Pro", "For teams", NOW);
        priceId = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW).getId();
        planId = planSearchRepository.save(PlanSummary.of(planRepository.save(plan))).id();
        entityManager.flush();
    }

    @Test
    void createsAPlan() throws Exception {
        mvc.perform(post("/api/plans").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basic\",\"description\":null}"))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAnInvalidBody() throws Exception {
        mvc.perform(post("/api/plans").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void findsAPlan() throws Exception {
        mvc.perform(get("/api/plans/{id}", planId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingPlan() throws Exception {
        mvc.perform(get("/api/plans/{id}", UUID.randomUUID()).with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void updatesAPlan() throws Exception {
        mvc.perform(put("/api/plans/{id}", planId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Pro Plus\"}"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void deactivatesAPlanOnce() throws Exception {
        mvc.perform(delete("/api/plans/{id}", planId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);

        mvc.perform(delete("/api/plans/{id}", planId).with(manager()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void listsPlans() throws Exception {
        mvc.perform(get("/api/plans").with(manager())
                .param("q", "pro")
                .param("filter", "activeIntervals:MONTHLY")
                .param("sort", "createdAt:desc")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void addsAPrice() throws Exception {
        mvc.perform(post("/api/plans/{id}/prices", planId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":499.00,\"currency\":\"BRL\",\"interval\":\"YEARLY\"}"))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesASecondActivePriceOnOnePair() throws Exception {
        mvc.perform(post("/api/plans/{id}/prices", planId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":59.90,\"currency\":\"BRL\",\"interval\":\"MONTHLY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void replacesAPrice() throws Exception {
        mvc.perform(post("/api/prices/{id}/replace", priceId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":59.90}"))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void deactivatesAPrice() throws Exception {
        mvc.perform(delete("/api/prices/{id}", priceId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingPrice() throws Exception {
        mvc.perform(delete("/api/prices/{id}", UUID.randomUUID()).with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAMissingPermission() throws Exception {
        mvc.perform(post("/api/plans").with(httpBasic("viewer@recurve.local", PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basic\"}"))
                .andExpect(status().isForbidden())
                .andExpect(CONTRACT);
    }

    @Test
    void rebuildsTheIndex() throws Exception {
        mvc.perform(post("/api/plans/reindex").with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    private static RequestPostProcessor manager() {
        return httpBasic("manager@recurve.local", PASSWORD);
    }
}
```

- [ ] **Step 6: Run the integration suite**

Run: `./mvnw -q verify -Pintegration`
Expected: every `*IT` passes, the user ones included.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/com/navesdev/recurve/shared/config/SecurityConfig.java server/src/testIntegration/java/com/navesdev/recurve/plan
git commit -m "feat(server-plan): require plan permissions on the plan and price routes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Record the decisions in the project docs

**Files:**
- Modify: `docs/requirements.md`
- Modify: `server/docs/data-model.md`
- Modify: `server/docs/architecture.md`

No code; the check is a full build at the end.

- [ ] **Step 1: `docs/requirements.md`**

Replace the FR-02 block:

```markdown
### FR-02 Plans

- FR-02.1 Register a plan with name and optional description.
- FR-02.2 Register one or more prices for a plan, each with amount,
  currency and billing cycle (monthly, yearly).
- FR-02.3 Deactivate a price. Inactive price accepts no new subscribers;
  existing subscribers stay on it.
- FR-02.4 Deactivate a plan. Inactive plan accepts no new subscribers.
- FR-02.5 List plans with their prices (see FR-06.2).
```

with:

```markdown
### FR-02 Plans

- FR-02.1 Register a plan with name and optional description.
- FR-02.2 Register one or more prices for a plan, each with amount,
  currency and billing cycle (monthly, yearly). The amount is greater
  than zero. A price whose cycle and currency already have an active
  price on the plan is refused (BR-03); changing it is FR-02.7.
- FR-02.3 Deactivate a price. Inactive price accepts no new subscribers;
  existing subscribers stay on it.
- FR-02.4 Deactivate a plan. Inactive plan accepts no new subscribers and
  no new or replacement price. Its prices keep their own state.
- FR-02.5 List plans with their prices, inactive ones included (see
  FR-06.2). Rebuild the plan search index on request; requires
  `MANAGE_SYSTEM`.
- FR-02.6 Edit a plan's name and description.
- FR-02.7 Replace a price with a new amount: a new price with the same
  cycle and currency is created and the old one deactivated, in one
  operation (BR-04). Only an active price can be replaced.
```

- [ ] **Step 2: `server/docs/data-model.md`**

1. In the `Plan` table, replace the `description` and `active` rows and add `version`:

```markdown
| description | String | `description` | nullable, at most 500 |
| active | boolean | `active` | not null. An inactive plan accepts no new subscribers and no new price |
| version | Long | `version` | not null. Optimistic lock; a lost race is a 409 |
```

2. Replace the `PlanPrice` intro paragraph and table rows for `currency` and `interval`, and the index sentence:

```markdown
How much and how often. Same aggregate as `Plan`, lives in `plan`, and is
held by the plan by object (`@OneToMany`) — the one exception to "by id",
because it is the same aggregate. Changing a price means creating a new
`PlanPrice` and deactivating the old one (`POST /api/prices/{id}/replace`);
existing subscribers stay on the old one.
```

```markdown
| price | BigDecimal | `price numeric(12,2)` | not null, `> 0` |
| currency | String | `currency varchar(3)` | not null, ISO 4217, upper case, e.g. `BRL` |
| interval | BillingInterval | `billing_interval` | not null, enum as string. `INTERVAL` is an SQL keyword |
```

```markdown
At most one active price per `(plan_id, billing_interval, currency)`: a
deferred exclusion constraint (see Indexes).
```

3. In the Indexes table, replace the `plan_prices` BR-03 row with:

```markdown
| `plan_prices` | `ex_plan_prices_one_active` `(plan_id, billing_interval, currency) WHERE active` | exclusion constraint, deferred | BR-03, checked at commit so a replace can insert the successor before deactivating the old price |
```

4. In "Mapping conventions", after "A relationship between aggregates is by id ...", add:

```markdown
- Inside one aggregate the relation is by object: `Plan` holds its
  `PlanPrice`s, because BR-03 is a rule over that set and only the root
  can guard it.
```

5. Replace the last sentence of the file, "Only `users` and `user_permissions` exist so far; the tables for `plan`, `subscriber` and `payment` come with their features.", with:

```markdown
`V1` creates the operator tables and `V2` the plan tables; the tables for
`subscriber` and `payment` come with their features.
```

- [ ] **Step 3: `server/docs/architecture.md`**

1. In "Authorization", replace the code block with:

```java
.requestMatchers(HttpMethod.POST, "/api/users/reindex", "/api/plans/reindex").hasAuthority("MANAGE_SYSTEM")
.requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
.requestMatchers(HttpMethod.GET, "/api/plans/**").hasAuthority("VIEW_PLANS")
.requestMatchers("/api/plans/**", "/api/prices/**").hasAuthority("MANAGE_PLANS")
.anyRequest().authenticated()
```

2. In "Exceptions", add a row after `ExternalServiceException`:

```markdown
| `OptimisticLockingFailureException` | another request changed the record first | 409 | two replaces of one price at once |
```

3. In "Schema", replace the migration table rows for plans:

```markdown
| plans and their prices | `V2__create_plans.sql` |
```

4. In "Search index", after the paragraph on the operator index, add:

```markdown
The plan index follows the same pattern: `search/plans-settings.json`,
`plans-mapping.json`, `PlanIndexBootstrap`, `POST /api/plans/reindex`.
Its `activeIntervals` field is derived in `PlanSummary` so that "a plan
with an active price in this cycle" is a plain `terms` filter.
```

5. In "Naming conventions", add a row after `Route`:

```markdown
| Sub-resource route | created under its parent, then addressed alone by its own id | `POST /api/plans/{id}/prices`, `DELETE /api/prices/{id}` |
```

- [ ] **Step 4: Full build**

Run: `./mvnw -q verify` (with `docker compose up -d`)
Expected: PMD, unit and integration suites all pass.

- [ ] **Step 5: Commit**

```bash
git add docs/requirements.md server/docs/data-model.md server/docs/architecture.md
git commit -m "docs(server-plan): record the plan feature in requirements, model and architecture

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
