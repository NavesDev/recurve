# Web frontend — design

Date: 2026-10-03. Branch: `feat/web`, cut from `main` after PR #7.

## Goal

Build the operators' panel (NFR-09) in `web/`, using the visual language
of the prototype (`Protótipo Dashboard Recurve.zip`: `recurve-tokens.css`,
`Recurve Dashboard.dc.html`), over the API the server already exposes.
Add to the server what a browser client needs: a token, the signed-in
operator with their permissions, and CORS.

## Decisions

| Question | Decision |
|---|---|
| Prototype vs. domain | The domain wins. The prototype gives the look (tokens, sidebar, tables, forms, badges, dialogs); screens follow the API. A plan has a list of prices (monthly/yearly, BRL); no trial, no quarterly cycle, no "trial" status, no "last access", no password reset e-mail. |
| Overview | A placeholder with shortcuts to the areas the operator may open. No aggregate endpoint in this work. |
| Global search in the top bar | Left out: no unified search endpoint. |
| Stack | SPA: React + Vite + TypeScript (`strict`), React Router, TanStack Query, react-hook-form + zod, CSS Modules over the prototype's tokens. No UI kit; inline SVG icons from the prototype. |
| Authentication | Token. `POST /api/auth/token` exchanges e-mail and password for a signed JWT. FR-05.3 deferred this "until there is a client that needs one"; this is that client. |
| What the token carries | Only `sub` = operator id. Each request reloads the operator from the database, so deactivation (BR-09) and a revoked permission take effect at once, as with Basic today, and cheaper (no BCrypt per request). |
| Basic Auth | Still accepted, for the Swagger UI and scripts. |
| Token storage | In memory plus `sessionStorage`: survives a reload, dies with the tab. No refresh token: on expiry, sign in again. |
| Exposing permissions | `GET /api/me` returns the operator and their **expanded** permissions (MANAGE implies VIEW already applied). The client decides menus, routes and buttons from it. The server stays the authority. |
| State-dependent actions | Derived in the client from `status` (`canEdit`, `canRefund`…). No per-resource "allowed actions" in responses: the rules are few and stable. |
| Plan name on a subscriber | Resolved in the client from the plan list (it loads it anyway for the plan filter). No server change. |
| Subscriber name on a payment | One extra request per page: `GET /api/subscribers?filter=id:a,b,c`. If the index cannot filter by `id`, stop and decide before denormalizing on the server. |
| Cache | TanStack Query, in memory only. Cached: `me` alone (`staleTime` 5 min). Everything else `staleTime: 0`, no refetch on window focus, invalidated after mutations. |
| Deploy | Out of scope. Dev: Vite proxies `/api` to `:8080` (same origin). Production gets `CORS_ALLOWED_ORIGINS` on the server. |
| Language | UI in pt-BR. Code, comments and commits in English, as in the server. |

## Server changes

### `POST /api/auth/token` (public)

Request `{ email, password }`. Response `{ token, expiresAt }`.

- Authenticates through the existing `OperatorDetailsService` and
  `PasswordEncoder`. Wrong credentials or an inactive operator: 401 with
  the usual `ApiError`, same message for both (no account probing).
- JWT HS256 via `spring-boot-starter-oauth2-resource-server`
  (`NimbusJwtEncoder`/`NimbusJwtDecoder`). Secret in `JWT_SECRET`
  (NFR-03), at least 32 bytes; the application does not start without it,
  like the Asaas settings. Lifetime `JWT_TTL`, default `8h`. Claims: `sub`,
  `iat`, `exp`.

### Bearer on every route

- The resource server validates signature and expiry. A converter turns
  `sub` into the operator loaded from the database: missing or inactive →
  401; authorities = `User.authorities()` (already expanded).
- `httpBasic` stays. Both entry points answer through the same
  `HandlerExceptionResolver`, so 401/403 keep the `ApiError` body and no
  `WWW-Authenticate: Basic` challenge.

### `GET /api/me` (any authenticated operator)

`{ id, name, email, permissions }`, permissions expanded and sorted. New
`auth` feature package on the server (`auth/controller`, `auth/service`),
following the layered layout.

### CORS

`recurve.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:}`, comma-separated.
Empty: no CORS configuration at all. Set: those origins, methods `GET,
POST, PUT, DELETE`, header `Authorization, Content-Type`, no credentials
(the token goes in a header, not a cookie).

### Documentation

`openapi.yaml` (the two endpoints, `bearerAuth` scheme alongside
`basicAuth`), `requirements.md` (FR-05.3 amended, NFR-09 filled),
`server/docs/architecture.md` (auth package, token flow), `README.md`
(running the web app, `JWT_SECRET`).

## Frontend architecture

Mirrors the server: features at the root of `src/`, shared code in
`shared/`. Feature folders use the server's package names.

```
web/
  src/
    main.tsx, App.tsx      bootstrap, router (aggregates feature routes), providers
    shared/
      api/                 http client, ApiError, listing (q/filter/sort/page ↔ URL)
      constants/           permissions, routes, listing, api, query, errors
      design/              tokens.css, global.css, one folder per component
      format/              BRL, dates (UTC → local, pt-BR), document masks
      layout/              AppShell, Sidebar, Topbar
    auth/                  login page, session, me, useCan, <Can>, <RequireAuth>
    plan/
    subscriber/
    payment/
    user/                  operators
    system/                reindex
    overview/              placeholder
  e2e/                     Playwright, against the real server
```

### Inside a feature

```
plan/
  domain.ts        types + pure rules (activePrices, canReplace…). No React, no fetch.
  constants.ts     sorts, filters, default sort, status/interval labels
  api.ts           query keys, HTTP calls, query/mutation hooks
  routes.tsx       the feature's routes and the permission each needs
  pages/           route-bound screens: read URL, call hooks, check permission
  components/      the feature's pieces; receive data and callbacks
  index.ts         the only file other features may import
```

- Dependencies inside a feature flow `pages → components → api → domain`
  (`constants` usable by all).
- A page that grows too much logic extracts a hook next to it
  (`usePlanListState`). No mandatory controller layer: React already joins
  view and controller, and TanStack Query owns server state.
- A file that grows becomes a folder with the same `index`. Not before.
- A feature omits what it does not need (`system/` has no `domain.ts`,
  `overview/` only `pages/`).

### Dependency rules (enforced by lint)

- `shared/` imports no feature.
- A feature imports `shared/*`, `auth/` and another feature's `index.ts`
  only (e.g. `subscriber` uses `usePlanLookup` from `plan`; `payment`
  invalidates `subscriberKeys`).
- `VIEW_*`/`MANAGE_*` string literals only in
  `shared/constants/permissions.ts`.
- Route path literals (`'/plans…'`) only in `shared/constants/routes.ts`;
  `'/api/…'` literals only in a feature's `api.ts` or `shared/api`.

### Constants

`shared/constants/` holds what two or more features use, one file per
subject: `permissions.ts` (mirror of the Java enum), `routes.ts`
(`ROUTES.plans`, `ROUTES.planDetail(id)`), `listing.ts` (page sizes,
default 20, max 100), `api.ts` (base path, storage key), `query.ts`
(`ME_STALE_TIME`), `errors.ts` (pt-BR messages by status/error). A
feature's own constants go in its `constants.ts`. The import rule above
is what forces a shared constant up into `shared/`.

### Styles

CSS Modules next to their component (`Button/Button.tsx` +
`Button.module.css`). `tokens.css` (the prototype's, verbatim names) and
`global.css` imported once in `main.tsx`. Values only through tokens.
Features style layout, not look; a look repeated across features becomes
a `design/` component. Variants by prop (`<Button variant="danger">`).
Dark mode out of scope.

### Design system (`shared/design`)

From the prototype: Button (primary, secondary, danger, quiet), Badge
(success, warn, danger, action, neutral), TextField, TextArea, Select,
SegmentedControl, Switch, Checkbox, DataTable (sortable headers, column
filter popover, selection-free, pagination footer with page size),
Dialog, ConfirmDialog, Menu (row actions), Toast, EmptyState, Skeleton,
Card, PageHeader. Accessible: labels, focus ring, Escape closes, focus
trapped in dialogs.

### Permissions in the UI

`useMe()` loads `/api/me` once (cached 5 min). `useCan(p)` reads it.
The sidebar lists only areas the operator may view; a route without
permission renders a 403 page; write buttons are wrapped in `<Can>`. A
button appears only when **permission** (from `auth`) and **state**
(from `domain`) both allow.

### Cache

- Global defaults: `staleTime: 0`, `refetchOnWindowFocus: false`,
  `retry` once on network/5xx, never on 4xx.
- `me`: `staleTime` 5 min.
- Hierarchical keys per feature (`subscriberKeys.all → list(listing) /
  detail(id)`); the whole listing is part of the key.
- Lists use `placeholderData: keepPreviousData` so paging does not flash
  an empty table.
- A mutation writes its response into the detail (`setQueryData`), then
  invalidates:

| Mutation | Invalidates |
|---|---|
| plan / price | `plans.all` |
| subscriber create/edit/cancel | `subscribers.all`, `plans.all` (BR-10 count) |
| payment request/send/sync/confirm/refund | `payments.all`, `subscribers.all` |
| operator | `users.all`; `me` if it is the signed-in operator |
| reindex | everything |

- No optimistic updates: the server may refuse on a business rule.
- Sign-out or 401 → `queryClient.clear()`. 403 → invalidate `me`.
- Nothing persisted to disk.

## Screens

| Route | Permission | Content | Endpoints |
|---|---|---|---|
| `/login` | public | e-mail, password | `POST /api/auth/token`, `GET /api/me` |
| `/` | signed in | overview placeholder, shortcuts | — |
| `/plans` | VIEW_PLANS | name + description, active prices ("R$ 49,90/mês · R$ 499,00/ano"), status. Search name; filter cycle; sort name | `GET /api/plans` |
| `/plans/new`, `/plans/:id/edit` | MANAGE_PLANS | name, description; on create, optional first price (amount + cycle) | `POST /api/plans`, `PUT /api/plans/:id`, `POST /api/plans/:id/prices` |
| `/plans/:id` | VIEW_PLANS | details, active prices and history. MANAGE: add price, replace amount, deactivate price, deactivate plan | `GET /api/plans/:id`, `POST /api/plans/:id/prices`, `POST /api/prices/:id/replace`, `DELETE /api/prices/:id`, `DELETE /api/plans/:id` |
| `/subscribers` | VIEW_SUBSCRIBERS | name/e-mail, plan, amount/cycle, status, start, next billing. Search; filter status (multi) and plan; sort start, amount | `GET /api/subscribers`, `GET /api/plans` |
| `/subscribers/new`, `/subscribers/:id/edit` | MANAGE_SUBSCRIBERS | name, e-mail, CPF/CNPJ (masked); plan → active price on create only | `POST /api/subscribers`, `PUT /api/subscribers/:id` |
| `/subscribers/:id` | VIEW_SUBSCRIBERS | details + the subscriber's payments. Edit, cancel (MANAGE_SUBSCRIBERS); generate charge (MANAGE_PAYMENTS) | `GET /api/subscribers/:id`, `DELETE /api/subscribers/:id`, `GET /api/payments?filter=subscriberId:…`, `POST /api/payments` |
| `/payments` | VIEW_PAYMENTS | subscriber, amount, due, status, paid at, invoice link. Filter status; sort due. MANAGE: send, confirm, refund, sync | `GET /api/payments`, `POST /api/payments/:id/{send,confirm,refund,sync}` |
| `/users` | MANAGE_USERS | name/e-mail, access ("N de 5 áreas"), status. Search; filter active; sort name, e-mail, creation | `GET /api/users` |
| `/users/new`, `/users/:id/edit` | MANAGE_USERS | name, e-mail, password (create only), profile shortcut + per-area matrix (none/view/manage), active; deactivate | `POST /api/users`, `PUT /api/users/:id`, `DELETE /api/users/:id` |
| `/system` | MANAGE_SYSTEM | reindex per entity and "reindex all" (sequential), with documents indexed | `POST /api/{users,plans,subscribers,payments}/reindex` |
| `*` | — | 404 | — |

Profiles (Administrador, Financeiro, Suporte, Leitura) are a client-side
shortcut that fills the permission matrix, as in the prototype; the
server knows only permissions. Destructive actions confirm in a
`ConfirmDialog`, never `window.confirm`. "Simular cobrança" becomes a
real "Gerar cobrança" showing the result and the invoice link.
Desktop-first; tables scroll horizontally below ~1024px.

## Errors and states

`shared/api/http.ts` turns every failure into `ApiError { status, error,
message, fieldErrors }`.

| Case | Handling |
|---|---|
| 400 with `fieldErrors` | `setError` on the form fields |
| 401 | clear token and cache, go to `/login?next=<current>` |
| 403 | toast, invalidate `me` |
| 404 on a detail | not-found screen with a way back |
| 409 / 422 | message shown inline or as a toast |
| 502 / 503 | toast with retry |
| network | toast "sem conexão" |
| render error | per-route ErrorBoundary |

Server messages are English; `shared/constants/errors.ts` maps known
cases to pt-BR, with a generic pt-BR fallback. Screens have loading
(skeleton rows), empty (EmptyState, CTA when the operator may manage)
and error (retry) states.

## Testing

- Server: unit tests for the token service and the JWT→operator
  converter; integration tests for `/api/auth/token`, `/api/me`, Bearer
  and Basic on the same route, an inactive operator with a valid token
  (401), an expired/forged token (401), CORS on and off; contract tests
  through `openapi.yaml` as today.
- Web unit (Vitest): `domain.ts`, `shared/format`, `shared/api/listing`,
  `http` (401/403 handling).
- Web components (Vitest + Testing Library + MSW): forms (validation,
  server `fieldErrors`), `useCan`/guards (button absent without
  permission), tables (search/filter/sort land in the URL).
- E2E (Playwright, `web/e2e`) against the real server (`docker compose`,
  `spring-boot:run`, gateway `fake`): sign in; an operator without a
  permission does not see the area; create plan with price; create
  subscriber; generate and confirm a charge; cancel subscriber; create a
  restricted operator and sign in as them.

## Tooling and CI

TypeScript `strict`; ESLint flat config (typescript-eslint, react-hooks,
jsx-a11y, `eslint-plugin-boundaries`, `no-restricted-syntax` for
permission and route literals); Prettier. `ci.yml` gains `web-lint`
(eslint + `tsc --noEmit`), `web-test` (vitest) and `web-build`, with
`working-directory: web`. E2E runs locally for now: it needs the whole
stack.

## Delivery

On `feat/web`, one commit per step:

1. Server: token, `/api/me`, Bearer + Basic, CORS, docs.
2. Web: scaffold, tooling, architecture lint, design system.
3. Auth and layout.
4. Plans → subscribers → payments → operators → system.
5. E2E and CI.

## Out of scope

Overview metrics, global search, trial, quarterly cycle, last access,
password reset, refresh tokens, dark mode, deploy, i18n beyond pt-BR.
