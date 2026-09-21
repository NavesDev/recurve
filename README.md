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
./mvnw spring-boot:run
```

The operator search index is created at first start and rebuilt on
demand with `POST /api/users/reindex` (requires `MANAGE_SYSTEM`).

The API contract (`server/src/main/resources/docs/openapi.yaml`) and a
Swagger UI over it are served at http://localhost:8080/docs. Set
`DOCS_ENABLED=false` to turn them off.
