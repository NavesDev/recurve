# Recurve web

The operators' panel (NFR-09): a single-page application over the
server's API. React, Vite, TypeScript (`strict`), React Router, TanStack
Query, react-hook-form + zod, CSS Modules over the design tokens. UI in
Portuguese (Brazil); code, comments and commits in English.

Design: `docs/superpowers/specs/2026-10-03-web-frontend-design.md`.

## Running

```bash
docker compose up -d                       # from the repository root
cd server && ./mvnw spring-boot:run        # needs JWT_SECRET in server/.env
cd web && npm install && npm run dev       # http://localhost:5173
```

Vite proxies `/api` to `http://localhost:8080`, so the browser sees one
origin and the server needs no CORS in development. Sign in with an
operator (the bootstrap admin from `server/.env` on a fresh database).

| Command | Does |
|---|---|
| `npm run dev` | dev server with the API proxy |
| `npm run lint` | ESLint (including the architecture rules) + `tsc --noEmit` |
| `npm test` | Vitest: unit and component tests |
| `npm run build` | type-check and production build into `dist/` |
| `npm run e2e` | Playwright against the real server (must be running) |

## Architecture

Mirrors the server: one folder per feature at the root of `src/`, named
as the server's package; shared code in `shared/`.

```
src/
  main.tsx, App.tsx    bootstrap, router (aggregates feature routes), providers
  shared/
    api/               http client, ApiError, listing (q/filter/sort/page ↔ URL)
    constants/         what two or more features use, one file per subject
    design/            tokens.css, global.css, Component.tsx + Component.module.css
    format/            money, dates, documents
    layout/            AppShell, Sidebar, Topbar
  auth/                sign-in, session, me, useCan, <Can>, <RequireAuth>
  plan/  subscriber/  payment/  user/  system/  overview/
```

### Inside a feature

| File | Role | Server analogue |
|---|---|---|
| `domain.ts` | types and pure rules (`canRefund`, `activePrices`); no React, no fetch | `domain/` |
| `constants.ts` | sorts, filters, labels the feature alone uses | — |
| `api.ts` | query keys, HTTP calls, query and mutation hooks | `repository/` |
| `routes.tsx` | the feature's routes and the permission each needs | the controller's mappings |
| `pages/` | route-bound screens: read the URL, call hooks, check permission | `controller/` |
| `components/` | the feature's pieces; receive data and callbacks | — |
| `index.ts` | the only file another feature may import | the public `service`/`domain` |

Inside a feature, dependencies flow `pages → components → api → domain`.
A page that grows too much logic extracts a hook next to it. A file that
grows becomes a folder with the same `index`. A feature omits what it
does not need.

### Dependency rules (ESLint enforces them: `eslint/architecture.js`)

- `shared/` imports no feature.
- A feature imports `shared/*` and another feature's `index.ts` only —
  `auth/` included — never its `constants.ts`, `api.ts` or a component.
  `App.tsx` is held to the same rule. A constant two
  features need therefore moves to `shared/constants/`.
- `VIEW_*` / `MANAGE_*` string literals only in
  `shared/constants/permissions.ts`.
- Route paths only in `shared/constants/routes.ts`; `/api/…` paths only
  in `api.ts` files and `shared/api/`.

### Errors, in layers

1. `shared/api/http.ts` turns every failure into an `ApiError` (status,
   message, field errors) — the transport layer's only error type.
2. A feature's `api.ts` lets it through; forms map `fieldErrors` onto
   their fields, and `shared/constants/errors.ts` turns the rest into a
   pt-BR message.
3. Components only display. A render error stops at the route's
   ErrorBoundary.

### Fail-closed

- Permissions come from `GET /api/me`. While it loads, fails or lacks a
  permission, the answer is **no**: nothing protected renders.
- A 401 signs the operator out and clears every cached response.
- A 403 refetches `me`: a revoked permission hides its buttons.
- A route declares its permission; a route without one is not reachable.
- The server is still the authority; the client only avoids offering
  what would be refused.

### Session and cache

The token lives in memory and `sessionStorage` (survives a reload, dies
with the tab). Server state lives in TanStack Query, in memory only:
`me` is cached for 5 minutes; everything else is fetched when a screen
opens (`staleTime: 0`) and invalidated after a mutation. Nothing is
written to disk beyond the token.

### Styles

CSS Modules next to their component. Values only through the tokens in
`shared/design/tokens.css` (the prototype's). Features style layout;
look belongs to `shared/design`. Variants by prop, not by class.

## Tests

| Kind | Tool | Where | Covers |
|---|---|---|---|
| Unit | Vitest | next to the file (`x.test.ts`) | `domain.ts`, `shared/format`, `shared/api` |
| Component | Vitest + Testing Library + MSW | next to the file (`X.test.tsx`) | forms, guards, tables |
| End-to-end | Playwright | `e2e/` | journeys against the real server |
