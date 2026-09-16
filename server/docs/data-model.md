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

### Permission (enum, `user`)

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

Quanto e com que frequência. Mesmo agregado de `Plan`, vive em `plan`.
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

Índice único parcial: `(plan_id, interval, currency) WHERE active`
(ver Índices).

### BillingInterval (enum, `plan`)

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


### SubscriberStatus (enum, `subscriber`)

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


### PaymentStatus (enum, `payment`)

| Valor | Significado |
|---|---|
| `PENDING` | criado, aguardando pagamento |
| `PAID` | confirmado, `paidAt` preenchido |
| `FAILED` | gateway recusou ou venceu sem pagamento |
| `REFUNDED` | estornado |

## Convenções de mapeamento

- Entity JPA é o modelo de domínio. Mutação só por método de negócio, sem
  setter público (ver `architecture.md`).
- Relação entre agregados é por id (`planPriceId: UUID`), não por objeto
  (`@ManyToOne`). Evita lazy loading vazando pro domínio e mantém feature
  desacoplada.
- Enum persiste como `@Enumerated(EnumType.STRING)`.
- FK e índices ficam no schema (ver Índices).

## Índices

Regra: indexa filtro de igualdade/range, ordenação padrão, FK e unicidade.
Não indexa busca `ILIKE '%q%'` (B-tree não serve; `pg_trgm` só se volume
justificar) nem ordenação de campo já filtrado (sort em memória de uma
página é irrelevante).

| Tabela | Índice | Tipo | Serve |
|---|---|---|---|
| `users` | `email` | unique | login, RN-02 |
| `user_permissions` | `(user_id, permission)` | PK | carregar permissões do operador |
| `plans` | `active` | btree | RF-02.4, listar só ativos |
| `plan_prices` | `plan_id` | btree | FK; listar preços do plano |
| `plan_prices` | `(plan_id, interval, currency) WHERE active` | unique parcial | RN-03; filtro por ciclo (RF-06.2) |
| `subscribers` | `email` | unique | RN-02 |
| `subscribers` | `plan_price_id` | btree | FK; filtro por plano (RF-06.3); contagem de assinaturas (RF-06.2) |
| `subscribers` | `(status, next_billing_at)` | btree | job de cobrança `WHERE status <> 'CANCELED' AND next_billing_at <= now()` (RF-04.1); filtro por status (RF-06.3) |
| `subscribers` | `started_at` | btree | ordenação padrão (RF-06.3) |
| `payments` | `subscriber_id` | btree | FK; listar por assinante (RF-04.6) |
| `payments` | `(status, due_at)` | btree | listar por status (RF-04.6); pendentes vencidos |
| `payments` | `external_id` | btree | webhook e conciliação (RF-04.7) |

Não indexado, de propósito:

- `users.name`, `users.email`, `subscribers.name`, `subscribers.email`,
  `plans.name` para busca `q`. Seq scan até volume justificar `pg_trgm`.
- Ordenação por valor cobrado do assinante (RF-06.3): join em
  `plan_prices` pela PK, sort do resultado filtrado.
- `users.active`: tabela pequena, baixa cardinalidade.

Declaração: `@Index` em `@Table` na entity enquanto `ddl-auto: update`.
Índice parcial Hibernate não gera; entra via Flyway (RNF-08). Quando
Flyway existir, migration é a fonte da verdade e `@Index` sai.
