# Arquitetura

Recurve usa **arquitetura em camadas, organizada por feature**. Cada feature
é um pacote autocontido com quatro camadas: apresentação, aplicação,
domínio e persistência.

Princípios:

- Entity JPA é o modelo de domínio. Regras de negócio vivem em métodos da
  entity, não na service.
- Service é a única porta de entrada da feature. Controller, scheduler e
  outras features só falam com a service.
- Interface só onde troca de implementação é plausível: serviço externo
  (gateway de pagamento, email). Repositório é Spring Data direto.
- Autorização por `@PreAuthorize` na service.

## Camadas

```
┌────────────────────────────────────────────────────────────┐
│  APRESENTAÇÃO                                 controller/  │
│  Recebe entrada externa, valida forma, converte pra        │
│  comando. Converte resultado em saída externa.             │
└──────────────────────────┬─────────────────────────────────┘
                           ▼
┌────────────────────────────────────────────────────────────┐
│  APLICAÇÃO                                       service/  │
│  Um método por caso de uso. Transação e autorização.       │
│  Busca, chama o domínio, persiste. Coordena; não contém    │
│  regra de negócio.                                         │
└──────────────┬────────────────────────────────┬────────────┘
               ▼                                ▼
┌────────────────────────────┐   ┌──────────────────────────────┐
│  DOMÍNIO           domain/ │◄──│  PERSISTÊNCIA    repository/ │
│  Modelo + regras. Estado   │   │  Grava e busca o modelo.     │
│  só muda por método de     │   │  Traduz filtro em query.     │
│  negócio. Não depende de   │   │  Não conhece regra.          │
│  nada.                     │   │                              │
└────────────────────────────┘   └──────────────────────────────┘
```

Setas só apontam pra baixo.

| Camada | Entrada | Saída | Pode importar | Proibido |
|---|---|---|---|---|
| Apresentação | protocolo externo (HTTP, JSON) | protocolo externo | Aplicação, Domínio | Persistência |
| Aplicação | comando, id, filtro | modelo de domínio | Domínio, Persistência, Aplicação de outra feature | Apresentação |
| Domínio | valores primitivos, outro modelo | modelo, exception de negócio | nada da feature | qualquer camada |
| Persistência | modelo, `Specification`, `Pageable` | modelo | Domínio | Aplicação, Apresentação |

## Layout

```
src/main/java/com/navesdev/recurve/
├── RecurveApplication.java
├── user/
├── plan/
├── payment/
│   └── gateway/          # interface de serviço externo + impl
└── subscriber/
    ├── domain/           # @Entity com regras, enums
    │   └── exception/    # exceptions de negócio da feature
    ├── repository/       # Spring Data, Specifications
    ├── service/          # Service, Commands, Filters
    └── controller/            # Controller, Request, Response
```

Toda feature segue as quatro subpastas. Subpasta extra só para adapter de
serviço externo (`payment/gateway/`).

Código transversal (config de segurança, handler global de exception,
paginação) **[aberto]**: local a definir quando a primeira feature existir.

## Detalhe de cada camada

### Domínio (`domain/`)

`@Entity` mutável, mas mutação só por método de negócio. Sem setter público.

- Construtor ou factory estática valida invariantes: `User.create(name, email, passwordHash, now)`.
- Transição de estado é método com nome de negócio: `subscriber.cancel(now)`,
  `subscriber.confirmPayment(interval)`, `plan.deactivate()`.
- Método lança exception de negócio quando regra é violada:
  `payment.refund()` em status diferente de `PAID` lança
  `PaymentNotRefundableException`.
- Regra que precisa de dado externo (email único) fica na service, porque
  entity não consulta banco.
- Tempo entra por parâmetro (`Instant now`). Entity nunca chama
  `Instant.now()`.

JPA no domínio: **só anotação de mapeamento** (`@Entity`, `@Table`,
`@Column`, `@Id`, `@Enumerated`). Nada de `EntityManager`, `@Query`,
`@Transactional`. Domínio sabe que é persistido; não sabe como.

```java
@Entity
@Table(name = "subscribers")
public class Subscriber {

    // ... campos, construtor protegido para JPA

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

### Persistência (`repository/`)

Interface Spring Data. `JpaSpecificationExecutor` quando a listagem tem
filtro dinâmico. Query customizada com `@Query` ou `Specification` em
classe própria (`SubscriberSpecifications`). Nunca lógica de negócio;
nunca chama método de negócio da entity.

### Aplicação (`service/`)

`@Service`, `@Transactional`. Um método por caso de uso.

Padrão de três passos para todo caso de uso que muda estado:

```
1. repository.findById(id)      carrega  (pula na criação)
2. entity.metodoDeNegocio()     domínio muda estado, ou lança
3. repository.save(entity)      persiste
```

`save()` sempre explícito, mesmo em alteração. Para entity gerenciada é
no-op, mas deixa a intenção visível sem depender de dirty checking. Se o
passo 2 lançar, passo 3 não roda e a transação faz rollback.

Antes do passo 2 a service também:

- Checa autorização (`@PreAuthorize`).
- Valida regra que depende do banco ou de outra entity (unicidade de
  email, preço ativo).
- Busca em outra feature o que a regra do domínio precisa
  (`planService.findActivePrice`).

Não faz: validação de formato (Bean Validation no request), serialização
HTTP, `if` de regra que cabe na entity.

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

Entrada da service é `record` de comando (`CreateSubscriberCommand`), ids
ou `record` de filtro. Request HTTP nunca chega na service. Saída é a
entity (ou `Page<Entity>`).

### Apresentação (`controller/`)

`@RestController`. Converte HTTP em chamada de service e resultado em
response. Sem regra, sem `@PreAuthorize` (já está na service).

- Request: `record` com Bean Validation, método `toCommand()`.
- Response: `record` com factory `from(entity)`. Web lê getters da
  entity, nunca chama método de negócio.
- Listagem: query params viram `record` de filtro + `Pageable`; resposta
  em `PageResponse<T>`.

## Autorização

Spring Security com method security habilitada
(`@EnableMethodSecurity`).

- `Permission` do `User` vira `GrantedAuthority` no login.
- `MANAGE_*` implica `VIEW_*`: resolvido ao montar as authorities do
  principal, não na anotação. `@PreAuthorize` sempre cita **uma** permissão.
- Anotação na service, nunca no controller. Assim scheduler e chamada entre
  features também passam pela checagem.
- Método interno que não deve ser checado (chamado só por outra service da
  mesma feature) fica sem anotação e documentado como interno.
- Operador inativo (BR-09) é barrado no `UserDetailsService`: `enabled=false`.

Job agendado (cobrança) roda sem operador. Mecanismo a definir junto com
FR-04.1: `SecurityContext` de sistema ou método de service dedicado sem
anotação, invocado só pelo scheduler da própria feature.

## Regra de dependência

Dentro da feature:

```
controller ──> service ──> repository
     │              │             │
     └──────────────┴──> domain <┘
```

- `controller` importa `service` e `domain`. Nunca `repository`.
- `service` importa `repository` e `domain`. Nunca `controller`.
- `repository` importa só `domain`.
- `domain` não importa nada da feature.

Entre features:

- Feature A importa de B só `service` e `domain`. Nunca `repository` nem
  `controller`. Se duas features se chamam mutuamente, fronteira está errada.
- Entity referencia entity de outra feature por id (`planPriceId: UUID`),
  não por `@ManyToOne`. Service carrega o que a regra precisa e passa por
  parâmetro. Regra de negócio da entity não chama service nem repository.

Subpacotes exigem classes `public`, então a fronteira é convenção. Quando
o projeto crescer, teste ArchUnit em `src/test` valida essas setas.

## Serviços externos

Gateway de pagamento, envio de email e similares: interface e
implementação no subpacote `gateway/` da feature que os usa
(`payment/gateway/PaymentGateway.java`,
`payment/gateway/StripePaymentGateway.java`). Service depende da interface.

Implementação captura a exception do SDK e traduz para exception declarada
na interface:

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

Service conhece `PaymentGatewayException`, nunca `StripeException`.

## Exceptions

Bases abstratas transversais **[aberto]** (ver Layout); concretas em
`<feature>/domain/exception/`.

| Base | Significado | HTTP | Exemplo |
|---|---|---|---|
| `NotFoundException` | recurso não existe | 404 | `PlanNotFoundException` |
| `BusinessRuleException` | regra de negócio violada | 422 | `EmailAlreadyInUseException`, `PaymentNotRefundableException` |
| `ExternalServiceException` | serviço externo falhou | 502 | `PaymentGatewayException` |
| `MethodArgumentNotValidException` | Bean Validation | 400 | — |
| `AccessDeniedException` | sem permissão | 403 | — |

`GlobalExceptionHandler` mapeia por tipo base e responde `ApiError`.

## Fluxo de uma requisição

`POST /api/subscribers`

1. Spring Security autentica o operador e monta authorities.
2. `SubscriberController` recebe JSON. Bean Validation valida
   `CreateSubscriberRequest`.
3. `request.toCommand()` gera `CreateSubscriberCommand`.
4. `SubscriberService.create(command)`: `@PreAuthorize` checa
   `MANAGE_SUBSCRIBERS`; service valida email único, busca `PlanPrice` via
   `PlanService`, chama `Subscriber.start(...)`, salva.
5. `SubscriberResponse.from(subscriber)`. `201`.

Erro sobe como exception e `GlobalExceptionHandler` traduz.

## Testes

Mesma estrutura do código: pacote por feature, subpasta por camada. Teste
de `subscriber/service/SubscriberService` vive em
`subscriber/service/SubscriberServiceTest`, dentro do source set de teste
correspondente.

```
src/<source set de teste>/java/com/navesdev/recurve/
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

Vale pra qualquer source set (`test`, integração, etc.). Como os source
sets se dividem e o que roda em cada um é decisão de build, fora deste
documento.

| Camada | Precisa | Cobre |
|---|---|---|
| Domínio | nada — JUnit puro | regras de transição, invariantes |
| Aplicação | Mockito; repository e outras services mockados | orquestração, unicidade, exceptions |
| Apresentação | `@WebMvcTest` com service mockada | validação de request, serialização, status HTTP |
| Persistência | banco real | `Specification`, queries customizadas |
| Autorização | contexto Spring + `@WithMockUser(authorities = ...)` | `@PreAuthorize` por caso de uso |

Teste que atravessa camadas (endpoint ponta a ponta) fica na raiz do
pacote da feature.

## Configuração

Datasource e demais configs vêm de variáveis de ambiente com placeholder e
default local em `application.yaml`:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:54330/recurve-database}
```

Arquivo `.env` (ignorado pelo git) é carregado via `spring.config.import`.
`.env.example` documenta as chaves. Credenciais reais nunca entram no
repositório, só o default do banco local do `docker-compose.yaml`.

`Clock` é bean injetável; entity recebe `Instant` por parâmetro. Teste
controla o tempo.

## Convenções de nome

| Tipo | Padrão | Exemplo |
|---|---|---|
| Entity | substantivo | `Plan` |
| Exception | `<Coisa><Problema>Exception` | `PlanNotFoundException` |
| Service | `<Coisa>Service` | `PlanService` |
| Método de service | verbo | `create`, `cancel`, `search` |
| Comando | `<Verbo><Coisa>Command` | `CreatePlanCommand` |
| Repository | `<Coisa>Repository` | `PlanRepository` |
| Interface externa | `<Coisa>Gateway`, `<Coisa>Sender` | `PaymentGateway` |
| Impl externa | `<Fornecedor><Interface>` | `StripePaymentGateway` |
| Request/Response | `<Verbo><Coisa>Request`, `<Coisa>Response` | `CreatePlanRequest` |
| Controller | `<Coisa>Controller` | `PlanController` |
| Rota | `/api/<coisas>` plural | `/api/plans` |
| Tabela | snake_case plural | `plan_prices` |
