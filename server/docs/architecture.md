# Arquitetura

Recurve segue **Clean Architecture** com organização **package by feature**. Cada
feature é uma fatia vertical autocontida com suas próprias camadas. Código
transversal vive em `shared/`.

## Layout

```
src/main/java/com/navesdev/recurve/
├── RecurveApplication.java
├── shared/
│   ├── domain/           # DomainException, value objects, utilitários puros
│   ├── application/      # ApplicationException
│   ├── infrastructure/   # InfrastructureException, entity base, config
│   └── web/              # exception handler global, formato de erro, config HTTP
├── user/
├── plan/
└── subscriber/
    ├── domain/           # modelo, regras e exceptions de negócio
    ├── application/      # casos de uso e comandos
    │   └── port/         # interfaces de saída (repositório, gateway)
    └── infrastructure/
        ├── persistence/  # entidades JPA, Spring Data, implementação dos ports
        └── web/          # controllers, request/response DTOs
```

## Camadas

### `domain`

Núcleo. Java puro, sem import de Spring, JPA ou qualquer framework.

- Modelos de negócio (`record` ou POJO): `User`, `Plan`, `Subscriber`
- Value objects: `Email`, `Money`
- Exceptions de negócio: `UserNotFoundException`, `EmailAlreadyInUseException`
- Constantes de regra de negócio, como `static final` na classe que usa

Não depende de nada. Nem de `application`, nem de `infrastructure`.

### `application`

Orquestra o domínio. Contém os casos de uso e o que eles precisam do mundo
externo, declarado como interface.

- Casos de uso: `CreateUserUseCase`, `FindPlanUseCase`. Uma classe por ação,
  método `execute`. Anotados com `@Service` e `@Transactional`.
- Comandos de entrada: `CreateUserCommand`. Record simples, sem validação
  de framework.
- `port/`: interfaces de saída. `UserRepository`, `PaymentGateway`. Definem
  **o que** o caso de uso precisa, não **como** é feito.

Depende só de `domain`.

### `infrastructure`

Adapters. Tudo que fala com framework ou serviço externo.

- `persistence/`: `@Entity` JPA, `JpaRepository` do Spring Data, e a classe
  `<X>RepositoryImpl` que implementa o port de `application/port/`. Mapeamento
  domain ↔ entity fica aqui.
- `web/`: `@RestController`, `<X>Request` (com Bean Validation), `<X>Response`.
  Controller converte HTTP em comando, chama o caso de uso, converte o
  resultado em response. Sem regra de negócio.
- Outros adapters de saída (gateway de pagamento, email, fila) também
  entram aqui, implementando o port correspondente.

Depende de `application` e `domain`.

## Regra de dependência

Setas só apontam para dentro.

```
infrastructure ──> application ──> domain
```

- `domain` não importa nada do projeto além de `shared/domain`.
- `application` não importa `infrastructure`.
- `infrastructure` implementa as interfaces de `application/port/`. Spring
  injeta a implementação onde o caso de uso pede a interface.

Consequência: trocar JPA por JDBC, ou Stripe por outro gateway, mexe só em
`infrastructure/`. Testar caso de uso não exige banco nem HTTP, só mock dos
ports.

## Ports e adapters

| Direção | Port | Adapter |
|---|---|---|
| Entrada (driving) | caso de uso em `application/` | `Controller` em `infrastructure/web/` |
| Saída (driven) | interface em `application/port/` | `RepositoryImpl`, `PaymentGateway` impl em `infrastructure/` |

## Exceptions

Cada camada tem exceptions no seu nível de abstração. Base abstrata em
`shared/`, concretas na feature.

| Camada | Base | Significado | Exemplo |
|---|---|---|---|
| `domain` | `DomainException` | regra de negócio violada | `EmailAlreadyInUseException`, `PlanNotFoundException` |
| `application` | `ApplicationException` | falha de orquestração do caso de uso | `SubscriptionAlreadyActiveException` |
| `infrastructure` | `InfrastructureException` | falha técnica de adapter | `StripeGatewayException` |

Exception de camada externa nunca sobe crua para dentro. Adapter captura a
exception técnica e traduz para a exception declarada no port:

```java
// infrastructure/StripePaymentGateway.java
@Override
public void charge(UUID subscriberId, BigDecimal amount) {
    try {
        stripe.charges().create(...);
    } catch (StripeException e) {
        throw new PaymentGatewayException("Stripe charge failed", e);
    }
}
```

`PaymentGatewayException` mora em `application/port/` junto com a interface
`PaymentGateway`: faz parte do contrato. Caso de uso conhece ela, nunca
`StripeException`.

`GlobalExceptionHandler` em `shared/web/` mapeia por tipo base:
`NotFoundException` → 404, `DomainException` → 422,
`ApplicationException` → 409, `InfrastructureException` → 502,
falha de validação → 400.

## Fluxo de uma requisição

`POST /api/users`

1. `UserController` recebe o JSON. Bean Validation valida `CreateUserRequest`.
2. `request.toCommand()` gera `CreateUserCommand`. DTO web morre aqui.
3. `CreateUserUseCase.execute(command)` aplica regras usando `UserRepository`
   (interface) e cria o `User` de domínio.
4. Spring injeta `UserRepositoryImpl`, que converte `User` em `UserEntity` e
   persiste via Hibernate.
5. `User` volta ao controller. `UserResponse.from(user)` serializa. `201`.

Erro sobe como exception da camada onde nasceu e `GlobalExceptionHandler`
traduz em status HTTP (ver seção Exceptions).

## Dependência entre features

Feature A usa feature B só pelo que B expõe em `domain` e `application`
(casos de uso e modelos). Nunca por `infrastructure`.

Exemplo: `CreatePlanUseCase` valida o dono chamando
`user.application.FindUserUseCase`, não `UserJpaRepository`.

Se o acoplamento entre duas features cresce, ou a fronteira está errada ou o
trecho comum é candidato a `shared/`.

## `shared/`

Código usado por duas ou mais features. Segue a mesma regra de camada:
`shared/domain` não importa `shared/web`.

- `shared/domain/`: `DomainException`, `NotFoundException`, value objects
  genéricos, utilitários sem framework.
- `shared/application/`: `ApplicationException`.
- `shared/infrastructure/`: `InfrastructureException`, entity base com
  auditoria, `@ConfigurationProperties`, beans gerais.
- `shared/web/`: `GlobalExceptionHandler`, `ApiError`, config de CORS e
  conversores HTTP.

Sinal de alerta: `shared/` maior que uma feature. Algo ali é feature
disfarçada.

## Visibilidade

- Classes de `infrastructure` são package-private. Nada fora da feature
  precisa enxergar `UserEntity` ou `UserController`.
- `domain`, `application` e `application/port` são `public`: são a API da
  feature para as outras.

## Configuração

Datasource e demais configs vêm de variáveis de ambiente com placeholder e
default local em `application.yaml`:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:54330/recurve-database}
```

Arquivo `.env` (ignorado pelo git) é carregado via `spring.config.import`.
`.env.example` documenta as chaves. Credenciais reais nunca entram no
repositório, só o default do banco local do `docker-compose.yaml`.

## Convenções de nome

| Tipo | Padrão | Exemplo |
|---|---|---|
| Modelo de domínio | substantivo | `Plan` |
| Exception | `<Coisa><Problema>Exception` | `PlanNotFoundException` |
| Caso de uso | `<Verbo><Coisa>UseCase` | `CreatePlanUseCase` |
| Comando | `<Verbo><Coisa>Command` | `CreatePlanCommand` |
| Port de saída | `<Coisa>Repository`, `<Coisa>Gateway` | `PlanRepository` |
| Entity JPA | `<Coisa>Entity` | `PlanEntity` |
| Spring Data | `<Coisa>JpaRepository` | `PlanJpaRepository` |
| Impl do port | `<Coisa>RepositoryImpl` | `PlanRepositoryImpl` |
| Request/Response | `<Verbo><Coisa>Request`, `<Coisa>Response` | `CreatePlanRequest` |
| Controller | `<Coisa>Controller` | `PlanController` |
| Rota | `/api/<coisas>` plural | `/api/plans` |
