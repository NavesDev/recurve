# Payments — design

Date: 2026-10-03. Branch: `feat/server-payment`, cut from
`feat/server-subscriber` (PR #6), whose subscribers it charges.

## Goal

Implement the `payment` feature (FR-04): the `Payment` entity, charges an
operator requests for a subscriber's current cycle, their delivery to the
Asaas payment gateway, the gateway's webhook, manual confirmation and
refund, and the search-index listing. Follows the patterns `user`,
`plan` and `subscriber` set (`server/docs/architecture.md`).

## Decisions

| Question | Decision |
|---|---|
| Gateway (FR-04.7) | Asaas, through its sandbox (`https://api-sandbox.asaas.com/v3`) until production. Behind a `PaymentGateway` interface; a `FakePaymentGateway` is the default implementation, so the application and the tests run with no Asaas account. |
| How a charge is born (FR-04.1) | An operator requests it: `POST /api/payments` with a subscriber. **No scheduler** in this work — jobs need an architecture of their own first. The automatic billing run stays open. |
| Tax document | Asaas requires `cpfCnpj` for a customer. `Subscriber` gains `document`, required on registering and editing, validated by check digits. Added by `V4`; rows from `V3` have none until someone fills it, and cannot be charged until then. |
| Currency | Asaas charges in BRL only, so prices are restricted to `BRL` (NFR-06 amended). The `currency` column stays. |
| How the subscriber pays | `billingType: UNDEFINED`: Asaas issues an invoice on which the customer chooses Pix, boleto or card. The payment keeps its `invoiceUrl` for the operator to send. Recurve never touches card data. |
| Manual payment (FR-04.5) | The operator confirms the cycle's pending charge. If it was sent to Asaas, Asaas is told first (`receiveInCash`), so it neither keeps charging nor marks it overdue. One payment per cycle, always. |
| A gateway call and a database transaction | Two transactions around the call: the payment is recorded first, the gateway called outside any transaction, its answer recorded second. `externalReference` is our payment id, so a resend finds a charge that reached Asaas before a crash instead of creating a second one. No HTTP call holds a pooled connection. An outbox was considered and is more than the volume needs. |
| A late payment | Asaas accepts payment of an overdue charge. `FAILED → PAID` is allowed: the subscriber becomes active and its next billing date advances. |
| A refund | Changes the payment only. Ending the subscription is a separate cancel. |
| A canceled subscriber that pays | The payment is recorded; the subscriber stays canceled and its next billing date does not move. |
| Unknown payment in a webhook | Answered 200 and logged, so Asaas does not pause the queue over a charge Recurve does not hold. |
| Concurrent writes on one payment | `@Version`: a webhook and an operator can race. A lost race is a 409. |
| Listing (FR-04.6) | A `payments` index. The subscriber's name is not copied (it changes, FR-03.7); filter by `subscriberId`. |

## Asaas, as used here

Checked against the official documentation on 2026-10-03.

| Use | Call |
|---|---|
| Authentication | header `access_token: <API key>` |
| Customer | `POST /customers` — `name`, `cpfCnpj` (required), `email`, `externalReference` = subscriber id |
| Charge | `POST /payments` — `customer`, `billingType: UNDEFINED`, `value`, `dueDate`, `externalReference` = payment id; answers `id`, `invoiceUrl` |
| Find a charge | `GET /payments?externalReference=<payment id>` |
| Manual receipt | `POST /payments/{id}/receiveInCash` — `paymentDate`, `value` |
| Refund | `POST /payments/{id}/refund` |
| Sandbox: pay a charge | `POST /sandbox/payment/{id}/confirm` (smoke test only, never called by the application) |
| Webhook | body `{id, event, dateCreated, account, payment}`; header `asaas-access-token` carries the token configured on the webhook (32–255 characters) |

## Domain

### `Payment` (`payment/domain`)

| Field | Column | Notes |
|---|---|---|
| id | `id` | PK |
| subscriberId | `subscriber_id` | FK, by id |
| amount | `amount numeric(12,2)` | snapshot of the price (BR-05), `> 0` |
| currency | `currency varchar(3)` | snapshot |
| status | `status` | `PENDING`, `PAID`, `FAILED`, `REFUNDED` |
| dueAt | `due_at` | the subscriber's `nextBillingAt` when the charge was requested |
| paidAt | `paid_at` | nullable |
| refundedAt | `refunded_at` | nullable |
| externalId | `external_id` | nullable until sent; unique |
| invoiceUrl | `invoice_url` | nullable until sent |
| version | `version` | optimistic lock |
| createdAt | `created_at` | |

```java
Payment.charge(Subscriber subscriber, PlanPrice price, Instant now)
    // PENDING, amount and currency from the price, dueAt = subscriber.getNextBillingAt()
    // SubscriberNotBillableException when canceled (BR-07) or without a document
payment.registerAtGateway(String externalId, String invoiceUrl)
    // once; PaymentAlreadySentException after that
payment.confirm(Instant paidAt)    // PENDING or FAILED → PAID; PaymentAlreadyPaidException, PaymentNotPendingException (REFUNDED)
payment.fail()                     // PENDING → FAILED; PaymentNotPendingException otherwise
payment.refund(Instant now)        // PAID → REFUNDED (BR-08); PaymentNotRefundableException otherwise
payment.isSent()                   // externalId present
```

Exceptions, under `payment/domain/exception`:

| Exception | Base | HTTP |
|---|---|---|
| `PaymentNotFoundException` | `NotFoundException` | 404 |
| `SubscriberNotBillableException` | `BusinessRuleException` | 422 |
| `PaymentAlreadyRequestedException` | `BusinessRuleException` | 422 |
| `PaymentAlreadySentException` | `BusinessRuleException` | 422 |
| `PaymentAlreadyPaidException` | `BusinessRuleException` | 422 |
| `PaymentNotPendingException` | `BusinessRuleException` | 422 |
| `PaymentNotRefundableException` | `BusinessRuleException` | 422 |
| `PaymentGatewayException` | `ExternalServiceException` | 502 |

### `Subscriber` gains

| Field | Column | Notes |
|---|---|---|
| document | `document varchar(14)` | CPF (11 digits) or CNPJ (14), stored as digits only, check digits verified by `SubscriberValidator.document`. Required by the domain on `start` and `update`; nullable in the schema for rows from `V3`. Not unique. |
| gatewayCustomerId | `gateway_customer_id varchar(64)` | nullable; the Asaas customer, created on the first charge sent |

```java
Subscriber.start(name, email, document, price, now)
subscriber.update(name, email, document)
subscriber.attachGatewayCustomer(String customerId)   // once
subscriber.confirmPayment(BillingInterval interval)   // FR-04.2: ACTIVE, nextBillingAt advances one cycle; canceled: nothing changes
subscriber.markPastDue()                              // FR-04.3: ACTIVE → PAST_DUE; canceled: nothing changes
subscriber.isBillable()                               // not canceled and has a document
```

A CNPJ with letters (the format announced for 2026) is out of scope;
the validator accepts digits only.

### `Plan`

`PlanValidator.currency` accepts `BRL` only. Every other ISO code is a
422, with a message naming BRL.

## Gateway (`payment/gateway`)

```java
public interface PaymentGateway {
    String ensureCustomer(Subscriber subscriber);
    Charge createCharge(Payment payment, String customerId);
    Optional<Charge> findCharge(UUID paymentId);
    void receiveInCash(String externalId, BigDecimal amount, LocalDate paidOn);
    void refund(String externalId);

    record Charge(String externalId, String invoiceUrl) {}
}
```

- `AsaasPaymentGateway`: a `RestClient` built from `recurve.payment.asaas.api-url`
  and `api-key`. `ensureCustomer` returns the subscriber's
  `gatewayCustomerId` when there is one, otherwise creates the customer.
  Any non-2xx answer or I/O failure becomes `PaymentGatewayException`,
  whose message never carries the key or the response body.
- `FakePaymentGateway`: in-memory, accepts everything, invents ids and an
  `invoiceUrl`.
- Chosen by `recurve.payment.gateway` (`fake` by default, `asaas`). With
  `asaas` and an empty key or webhook token, the application does not
  start (fail-fast).

## Application

`PaymentService`:

```java
PaymentSummary request(RequestPaymentCommand)   // subscriberId
PaymentSummary send(UUID paymentId)
PaymentSummary confirm(UUID paymentId)           // manual, FR-04.5
PaymentSummary refund(UUID paymentId)            // FR-04.4
PaymentSummary findById(UUID paymentId)
Page<PaymentSummary> search(SearchFilter, Pageable)
long reindex()
void handle(GatewayEvent event)                 // from the webhook
```

**Request** (`POST /api/payments`):

1. Transaction 1: load the subscriber (`subscriberService.findForBilling`),
   refuse a duplicate cycle (`existsBySubscriberIdAndDueAt` →
   `PaymentAlreadyRequestedException`), get its price from the plan
   feature, `Payment.charge`, persist and index.
2. No transaction: `send` (below).
3. The answer is the payment with its `invoiceUrl`. If step 2 failed, the
   payment stays `PENDING` with no `externalId`, and the caller gets the
   502; `POST /api/payments/{id}/send` retries.

The cycle's unique constraint stands behind step 1 for two requests that
race past the check: the second is a 409.

**Send** (also `POST /api/payments/{id}/send`): a sent payment is
returned as it is. Otherwise `gateway.findCharge(paymentId)`; when absent,
`ensureCustomer` (and, in its own transaction, the subscriber's
`attachGatewayCustomer`) and `createCharge`. Then, in a transaction,
`registerAtGateway` and index.

**Confirm (manual)**: load; if sent, `gateway.receiveInCash` first — a
failure there is a 502 and nothing changes here. Then, in one
transaction, `payment.confirm(now)`, `subscriberService.confirmPayment`,
persist and index both.

**Refund**: load; must be `PAID`. If sent, `gateway.refund` first. Then
`payment.refund(now)`, persist and index.

**Webhook** (`handle`): find the payment by `payment.externalReference`,
else by `payment.id` as `externalId`. None: log, return. Then:

| Event | Effect | Already in the target state |
|---|---|---|
| `PAYMENT_CONFIRMED`, `PAYMENT_RECEIVED` | `confirm` + `subscriberService.confirmPayment` | no-op |
| `PAYMENT_OVERDUE`, `PAYMENT_CREDIT_CARD_CAPTURE_REFUSED` | `fail` + `subscriberService.markPastDue` | no-op (also when `PAID`: a late event never undoes a payment) |
| `PAYMENT_REFUNDED` | `refund` | no-op |
| anything else | ignored | — |

One transaction per event; the gateway is not called.

`SubscriberService` gains `findForBilling(id)` (the entity, for charging),
`attachGatewayCustomer(id, customerId)`, `confirmPayment(id)` and
`markPastDue(id)`; the last two resolve the interval through the plan
feature and reindex the subscriber. Dependencies run payment →
subscriber → plan; payment also calls `planService.findByPriceIds` for
the price to copy.

`PaymentIndexBootstrap` as for the other features.

## Persistence

`V4__add_subscriber_billing_data.sql`:

```sql
ALTER TABLE subscribers ADD COLUMN document varchar(14);
ALTER TABLE subscribers ADD COLUMN gateway_customer_id varchar(64);
```

`V5__create_payments.sql`:

```sql
CREATE TABLE payments (
    id            uuid          PRIMARY KEY,
    subscriber_id uuid          NOT NULL REFERENCES subscribers (id),
    amount        numeric(12,2) NOT NULL CHECK (amount > 0),
    currency      varchar(3)    NOT NULL,
    status        varchar(10)   NOT NULL,
    due_at        timestamptz   NOT NULL,
    paid_at       timestamptz,
    refunded_at   timestamptz,
    external_id   varchar(64),
    invoice_url   varchar(500),
    version       bigint        NOT NULL,
    created_at    timestamptz   NOT NULL,
    CONSTRAINT uq_payments_cycle UNIQUE (subscriber_id, due_at),
    CONSTRAINT uq_payments_external_id UNIQUE (external_id),
    CONSTRAINT ck_payments_paid CHECK ((status IN ('PAID', 'REFUNDED')) = (paid_at IS NOT NULL)),
    CONSTRAINT ck_payments_refunded CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);
CREATE INDEX ix_payments_status ON payments (status);
```

`uq_payments_cycle` serves lookups by subscriber (its prefix) and
`uq_payments_external_id` the webhook, so neither needs an index of its
own.

`PaymentRepository extends BaseRepository<Payment, UUID>`:
`existsBySubscriberIdAndDueAt`, `findByExternalId`, `streamAll`.

`PaymentSearchRepository`, index `payments`:

| Field | Mapping | Can be |
|---|---|---|
| `id` | keyword, `index: false` | tie-breaker |
| `subscriberId` | keyword | filtered |
| `amount` | `scaled_float` ×100, written as text | sorted |
| `currency`, `status` | keyword | filtered |
| `dueAt` | date | sorted (default, descending), filtered |
| `paidAt`, `createdAt` | date | sorted, filtered |
| `refundedAt` | date, no doc values | returned |
| `externalId` | keyword | searched by `q` (exact), filtered |
| `invoiceUrl` | keyword, `index: false`, no doc values | returned |

`SubscriberSummary` gains `document` (returned only, never searched).

## Presentation

`PaymentController` (`/api/payments`):

| Route | Status | Permission |
|---|---|---|
| `POST /api/payments` | 201 + `Location` | `MANAGE_PAYMENTS` |
| `POST /api/payments/{id}/send` | 200 | `MANAGE_PAYMENTS` |
| `POST /api/payments/{id}/confirm` | 200 | `MANAGE_PAYMENTS` |
| `POST /api/payments/{id}/refund` | 200 | `MANAGE_PAYMENTS` |
| `GET /api/payments/{id}` | 200 | `VIEW_PAYMENTS` |
| `GET /api/payments` | 200, default sort `dueAt:desc` | `VIEW_PAYMENTS` |
| `POST /api/payments/reindex` | 200 | `MANAGE_SYSTEM` |

`AsaasWebhookController`: `POST /api/webhooks/asaas`, `permitAll` in
`SecurityConfig` and authenticated by the controller itself: the
`asaas-access-token` header compared with `ASAAS_WEBHOOK_TOKEN` in
constant time (`MessageDigest.isEqual`); absent or different is a 401
with the `ApiError` body. Only registered when the gateway is `asaas`.

`PaymentResponse { id, subscriberId, amount, currency, status, dueAt,
paidAt, refundedAt, externalId, invoiceUrl, createdAt }`.

Subscriber requests gain `document` (required, digits with or without
mask); `SubscriberResponse` gains `document` (digits).

## Configuration

`.env.example` and `application.yaml`, never a real key:

```
PAYMENT_GATEWAY=fake
ASAAS_API_URL=https://api-sandbox.asaas.com/v3
ASAAS_API_KEY=
ASAAS_WEBHOOK_TOKEN=
```

## Tests

| Test | Root | Covers |
|---|---|---|
| `PaymentTest` | test | `charge` (snapshot, `dueAt`, not billable), `registerAtGateway` once, `confirm` from `PENDING` and `FAILED`, `fail`, `refund` only from `PAID` |
| `SubscriberTest`, `SubscriberValidatorTest` | test | `document` valid and invalid (CPF, CNPJ, mask stripped), `confirmPayment`, `markPastDue`, canceled unchanged, `isBillable` |
| `PlanValidatorTest`, `PlanTest` | test | BRL only |
| `PaymentSummaryTest` | test | fields, amount as text |
| `PaymentServiceTest` | test | request (both transactions, gateway failure leaves it unsent), duplicate cycle, send reuses a found charge, confirm with and without `receiveInCash`, refund, each webhook event and its idempotence, unknown payment |
| `AsaasPaymentGatewayTest` | test | `MockRestServiceServer`: `access_token` header, request bodies, reuse of a known customer, errors to `PaymentGatewayException` without the key |
| `PaymentControllerTest`, `AsaasWebhookControllerTest` | test | validation, status, serialization; missing or wrong token 401 |
| `SubscriberControllerTest`, `SubscriberServiceTest` (existing) | test | `document` in requests and responses |
| `PaymentRepositoryIT` | testIntegration | the uniques, the CHECKs, `existsBySubscriberIdAndDueAt`, `findByExternalId` |
| `PaymentSearchRepositoryIT` | testIntegration | FR-04.6 filters, default sort, amount sort |
| `PaymentIndexBootstrapIT`, `PaymentServiceIT` | testIntegration | index at boot; rollback; a confirmation commits payment and subscriber together |
| `PaymentEndpointAuthorizationIT` | testIntegration | `VIEW_PAYMENTS` reads only; `MANAGE_PAYMENTS` writes; reindex needs `MANAGE_SYSTEM`; the webhook needs no operator but the token |
| `PaymentContractIT`, `ApiContractTest` | both | the contract |

No test calls Asaas. The sandbox is exercised in a manual smoke test with
a real sandbox key.

## Documentation

- `openapi.yaml` first: payment schemas, the seven routes and the
  webhook; `document` on the subscriber schemas.
- `docs/requirements.md`: FR-04.1 is an operator request for now and the
  automatic run is open; FR-04.7 settled on Asaas; NFR-06 BRL only;
  subscriber document.
- `server/docs/data-model.md`: `Payment` as built, `Subscriber.document`
  and `gatewayCustomerId`, `V4` and `V5`.
- `server/docs/architecture.md`: the Asaas gateway in "External
  services", the two transactions around a gateway call, the webhook as
  the one public route.

## Out of scope

- The automatic billing run (a scheduler), until jobs have an
  architecture.
- Retry policy and grace period (FR-04.8).
- Partial refunds; Asaas's own subscriptions (Recurve is the recurrence
  engine); tokenized cards.
- BR-10.
- CNPJ with letters.
