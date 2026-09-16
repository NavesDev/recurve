# Modelo de dados

Quatro features: `user`, `plan`, `subscriber`, `payment`. Tabelas em
snake_case plural. Todo id é UUID gerado pela aplicação. Todo timestamp é
`Instant` (UTC), coluna `timestamptz`.

```
User                    Plan 1──n PlanPrice 1──n Subscriber 1──n Payment
(isolado)
```

## User

Operador do sistema. Acesso controlado por permissões granulares, não por
cargo.

| Campo | Tipo | Coluna | Obs |
|---|---|---|---|
| id | UUID | `id` | PK |
| name | String | `name` | not null |
| email | String | `email` | not null, unique |
| passwordHash | String | `password_hash` | not null |
| permissions | Set\<Permission\> | tabela `user_permissions(user_id, permission)` | `@ElementCollection`, enum como string |
| active | boolean | `active` | not null, default true |
| createdAt | Instant | `created_at` | not null |

### Permission (enum, `user/domain`)

Uma permissão de leitura e uma de escrita por recurso. `MANAGE_*` implica o
`VIEW_*` correspondente; checagem de leitura aceita qualquer um dos dois.

| Valor | Cobre |
|---|---|
| `VIEW_USERS` | listar operadores e suas permissões |
| `MANAGE_USERS` | criar/editar/desativar operadores e permissões |
| `VIEW_PLANS` | listar planos e preços |
| `MANAGE_PLANS` | criar/editar/desativar planos e preços |
| `VIEW_SUBSCRIBERS` | listar assinantes |
| `MANAGE_SUBSCRIBERS` | criar/cancelar assinantes |
| `VIEW_PAYMENTS` | listar pagamentos |
| `MANAGE_PAYMENTS` | registrar/estornar pagamentos |

## Plan

O produto. Não tem preço nem intervalo próprios; isso fica em `PlanPrice`.

| Campo | Tipo | Coluna | Obs |
|---|---|---|---|
| id | UUID | `id` | PK |
| name | String | `name` | not null |
| description | String | `description` | nullable |
| active | boolean | `active` | not null. Inativo não aceita novos assinantes |
| createdAt | Instant | `created_at` | not null |

## PlanPrice

Quanto e com que frequência. Mesmo agregado de `Plan`, vive em `plan/domain`.
Mudar preço = criar novo `PlanPrice` e desativar o antigo; assinantes
existentes continuam no antigo.

| Campo | Tipo | Coluna | Obs |
|---|---|---|---|
| id | UUID | `id` | PK |
| planId | UUID | `plan_id` | FK `plans.id`, not null |
| price | BigDecimal | `price numeric(12,2)` | not null |
| currency | String | `currency char(3)` | not null, ISO 4217, ex. `BRL` |
| interval | BillingInterval | `interval` | not null, enum como string |
| active | boolean | `active` | not null |
| createdAt | Instant | `created_at` | not null |

Índice único parcial: `(plan_id, interval, currency) WHERE active`.

### BillingInterval (enum, `plan/domain`)

`MONTHLY`, `YEARLY`

## Subscriber

Assinante de um preço de plano.

| Campo | Tipo | Coluna | Obs |
|---|---|---|---|
| id | UUID | `id` | PK |
| planPriceId | UUID | `plan_price_id` | FK `plan_prices.id`, not null |
| name | String | `name` | not null |
| email | String | `email` | not null, unique |
| status | SubscriberStatus | `status` | not null, enum como string |
| startedAt | Instant | `started_at` | not null |
| nextBillingAt | Instant | `next_billing_at` | not null. Estado, não derivação: muda só por evento (pagamento confirmado, troca de preço, reativação), sempre via método de domínio |
| canceledAt | Instant | `canceled_at` | nullable |
| createdAt | Instant | `created_at` | not null |

Índice: `next_billing_at` (job de cobrança faz `WHERE next_billing_at <= now()`).

### SubscriberStatus (enum, `subscriber/domain`)

| Valor | Significado |
|---|---|
| `ACTIVE` | em dia |
| `PAST_DUE` | cobrança venceu sem pagamento |
| `CANCELED` | encerrado, `canceledAt` preenchido |

## Payment

Cobrança de um ciclo da assinatura. `amount` e `currency` são snapshot do
`PlanPrice` no momento da criação; preço do plano pode mudar depois sem
alterar histórico.

| Campo | Tipo | Coluna | Obs |
|---|---|---|---|
| id | UUID | `id` | PK |
| subscriberId | UUID | `subscriber_id` | FK `subscribers.id`, not null |
| amount | BigDecimal | `amount numeric(12,2)` | not null |
| currency | String | `currency char(3)` | not null |
| status | PaymentStatus | `status` | not null, enum como string |
| dueAt | Instant | `due_at` | not null |
| paidAt | Instant | `paid_at` | nullable |
| externalId | String | `external_id` | nullable. Id da transação no gateway de pagamento; usado em webhook, estorno e conciliação. Vazio em pagamento manual |
| createdAt | Instant | `created_at` | not null |

Índices: `subscriber_id`, `external_id`.

### PaymentStatus (enum, `payment/domain`)

| Valor | Significado |
|---|---|
| `PENDING` | criado, aguardando pagamento |
| `PAID` | confirmado, `paidAt` preenchido |
| `FAILED` | gateway recusou ou venceu sem pagamento |
| `REFUNDED` | estornado |

## Convenções de mapeamento

- Domain model é `record` imutável. Entity JPA é classe mutável separada em
  `infrastructure/persistence`; conversão dentro do `RepositoryImpl`.
- Relação entre agregados é por id (`planPriceId: UUID`), não por objeto
  (`@ManyToOne`). Evita lazy loading vazando pro domínio e mantém feature
  desacoplada.
- Enum persiste como `@Enumerated(EnumType.STRING)`.
- FK e índices ficam no schema. Enquanto `ddl-auto: update` estiver ativo,
  Hibernate gera colunas mas não índice parcial; migrar pra Flyway quando o
  schema estabilizar.
