# recurve
Recurve — a recurring billing and subscription management engine built with Spring Boot, organized in layers by feature.

## Running

```bash
docker compose up -d          # PostgreSQL (54330) and Elasticsearch (9230)
cd server
./mvnw test                   # unit tests, no server needed
./mvnw pmd:check              # lint (server/pmd-ruleset.xml)
./mvnw verify                 # unit + integration tests + lint
./mvnw verify -Pintegration   # integration tests only
./mvnw spring-boot:run       # needs JWT_SECRET in server/.env (see .env.example)
```

The operators' panel lives in `web/` (see `web/README.md`):

```bash
cd web && npm install && npm run dev   # http://localhost:5173
```

The operator search index is created at first start and rebuilt on
demand with `POST /api/users/reindex` (requires `MANAGE_SYSTEM`).

The API contract (`server/src/main/resources/docs/openapi.yaml`) and a
Swagger UI over it are served at http://localhost:8080/docs. Sign in with
`POST /api/auth/token` and send the token as `Authorization: Bearer`, or
use HTTP Basic. Set
`DOCS_ENABLED=false` to turn them off.
