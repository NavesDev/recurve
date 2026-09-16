# Requisitos

Recurve: motor de cobrança recorrente e gestão de assinaturas.

Documento consolida o que foi decidido até agora. Itens marcados **[aberto]**
ainda não foram definidos.

## Atores

| Ator | Descrição |
|---|---|
| Operador (`User`) | Pessoa que usa o sistema. Acesso limitado por permissões granulares |
| Assinante (`Subscriber`) | Cliente que assina um plano. Não acessa o sistema |
| Gateway de pagamento | Serviço externo que processa cobranças. **[aberto]** qual |

## Requisitos funcionais

### RF-01 Operadores

- RF-01.1 Cadastrar operador com nome, email único e senha.
- RF-01.2 Atribuir e remover permissões de um operador.
- RF-01.3 Desativar operador sem excluir. Operador inativo não autentica.
- RF-01.4 Listar operadores e suas permissões.

### RF-02 Planos

- RF-02.1 Cadastrar plano com nome e descrição opcional.
- RF-02.2 Cadastrar um ou mais preços para o plano, cada um com valor,
  moeda e intervalo (`MONTHLY`, `YEARLY`).
- RF-02.3 Desativar preço. Preço inativo não aceita novos assinantes;
  assinantes existentes permanecem nele.
- RF-02.4 Desativar plano. Plano inativo não aceita novos assinantes.
- RF-02.5 Listar planos com seus preços.

### RF-03 Assinantes

- RF-03.1 Cadastrar assinante com nome, email único e preço de plano.
- RF-03.2 Assinante nasce `ACTIVE` com `startedAt` = agora e
  `nextBillingAt` calculado a partir do intervalo do preço.
- RF-03.3 Cancelar assinante. Status vira `CANCELED`, `canceledAt`
  preenchido. Não gera novas cobranças.
- RF-03.4 Listar assinantes, filtrando por status e plano.
- RF-03.5 **[aberto]** Trocar assinante de preço (upgrade/downgrade).
- RF-03.6 **[aberto]** Reativar assinante cancelado.

### RF-04 Pagamentos

- RF-04.1 Gerar cobrança (`Payment` `PENDING`) para assinante quando
  `nextBillingAt` vence. Valor e moeda copiados do preço no momento.
- RF-04.2 Confirmar pagamento: status `PAID`, `paidAt` preenchido,
  assinante volta/permanece `ACTIVE`, `nextBillingAt` avança um intervalo.
- RF-04.3 Registrar falha: status `FAILED`. Assinante vira `PAST_DUE`.
- RF-04.4 Estornar pagamento pago: status `REFUNDED`.
- RF-04.5 Registrar pagamento manual (sem gateway), com `externalId` vazio.
- RF-04.6 Listar pagamentos por assinante e por status.
- RF-04.7 **[aberto]** Integração com gateway: criar cobrança, receber
  webhook de confirmação/falha, localizar `Payment` por `externalId`.
- RF-04.8 **[aberto]** Política de retentativa e prazo até cancelar
  assinante `PAST_DUE`.

### RF-05 Autenticação e autorização

- RF-05.1 Operador autentica com email e senha.
- RF-05.2 Toda operação exige permissão correspondente (ver RN-01).
- RF-05.3 **[aberto]** Mecanismo de sessão (JWT, cookie de sessão).

## Regras de negócio

- RN-01 Permissões por recurso: `VIEW_*` e `MANAGE_*` para `USERS`,
  `PLANS`, `SUBSCRIBERS`, `PAYMENTS`. `MANAGE_*` implica `VIEW_*`.
- RN-02 Email de operador é único. Email de assinante é único.
- RN-03 Plano não tem preço próprio; preço e intervalo vivem em `PlanPrice`.
  Um plano pode ter vários preços ativos, no máximo um por
  `(intervalo, moeda)`.
- RN-04 Alterar preço nunca edita `PlanPrice` existente: cria novo e
  desativa o antigo. Histórico preservado.
- RN-05 `Payment` guarda snapshot de valor e moeda. Mudança de preço não
  altera cobranças já geradas.
- RN-06 `nextBillingAt` é estado persistido, não derivado de `startedAt`.
  Muda somente por evento de domínio (pagamento confirmado, troca de preço,
  reativação).
- RN-07 Assinante `CANCELED` não gera cobrança.
- RN-08 Estorno só de pagamento `PAID`.
- RN-09 Operador inativo não autentica nem executa operações.

## Requisitos não funcionais

- RNF-01 Backend Spring Boot, Java 25, PostgreSQL, Hibernate/JPA.
- RNF-02 Arquitetura em camadas, package by feature, entity com regra de
  negócio, autorização na service (ver
  `server/docs/architecture.md`).
- RNF-03 Configuração via variável de ambiente; nenhuma credencial real no
  repositório.
- RNF-04 Senha de operador armazenada como hash. **[aberto]** algoritmo
  (BCrypt/Argon2).
- RNF-05 Timestamps em UTC.
- RNF-06 Valores monetários em `BigDecimal`, escala 2, moeda ISO 4217.
- RNF-07 Casos de uso testáveis sem banco e sem HTTP (ports mockados).
- RNF-08 **[aberto]** Migrações de schema com Flyway quando modelo
  estabilizar; `ddl-auto: update` só em desenvolvimento.
- RNF-09 **[aberto]** Frontend (`web/`): stack e escopo.

## Fora de escopo por enquanto

- Múltiplos tenants / empresas.
- Cupons, descontos, trial.
- Notificação ao assinante (email, WhatsApp).
- Nota fiscal.
