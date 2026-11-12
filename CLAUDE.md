# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

**XMS — Expense Management Service.** Spring Boot backend for the Paves intranet ERP: expense reports and line items, receipts + OCR, policy rules/bundles, multi-level approval workflow, cash advances, finance verification, tax (GST/ITC), cost centers/budgets, AP payment routing, invoice handoff to PMS/RMS, notifications, dashboards.

- Java 21, Spring Boot 4.x, Maven wrapper (`./mvnw`)
- MySQL via Spring Data JPA, Lombok, MapStruct, springdoc-openapi
- Auth: OAuth2 resource server — validates JWTs issued by **UMS**; XMS never issues tokens
- Messaging: Kafka (employee CDC from EOS via Debezium), Spring events (approval workflow, OCR). No RabbitMQ.
- Storage: AWS S3 for receipts

## Commands

```bash
./mvnw -DskipTests compile          # fast check
./mvnw test                         # all tests
./mvnw test -Dtest=CostCenterControllerTest   # single test class
./mvnw spring-boot:run              # needs .env (loaded by spring-dotenv)
```

Swagger (local): http://localhost:8080/xms/swagger-ui.html

## API paths — ALWAYS prefix with `/xms`

**Every controller `@RequestMapping` must start with `/xms`.** This is not optional.

- In k8s, the nginx ingress routes only `/xms/**` to this service, and **no servlet context path is set** (`SERVER_SERVLET_CONTEXT_PATH` was removed from the deployment). The `/xms` prefix lives in the code.
- A controller without `/xms` is unreachable in dev/prod (404 at the ingress).
- Never add `server.servlet.context-path=/xms` (or the env var) — it doubles every route to `/xms/xms/...`.

Convention: `/xms/{audience}/{resource}`, e.g.

```java
@RestController
@RequestMapping("/xms/admin/cost-centers")      // admin masters
// "/xms/employee/expense-reports"              // employee self-service
// "/xms/manager/approval-delegations"          // manager
// "/xms/finance/reimbursements"                // finance
// "/xms/approvals", "/xms/dashboard", "/xms/notifications"
```

Same applies to anything else served over HTTP: WebSocket endpoint is `/xms/ws`, Swagger is `/xms/swagger-ui.html` + `/xms/v3/api-docs`. Public paths in `SecurityConfig.PUBLIC_ENDPOINTS` must use the `/xms` form too. Controller tests call the full path (`mockMvc.perform(post("/xms/admin/cost-centers"))`).

The frontend (`Intranet_Frontend`) calls XMS as `${EXPENSE_MANAGEMENT_URL}/xms/...` — if you add or rename an endpoint, the frontend path must match.

## Code layout (`src/main/java/com/expense_management_service`)

| Package | Contents |
|---|---|
| `controller` | REST controllers — thin; delegate to services |
| `service` / `service/impl` | Interfaces + implementations (business logic, `@Transactional`) |
| `repository` | Spring Data JPA repositories |
| `entity`, `enums` | JPA entities and enums |
| `dto/request`, `dto/response`, `dto/external` | API and integration DTOs (never expose entities) |
| `mapper` | MapStruct mappers |
| `common` | `ApiResponse`, `GlobalExceptionHandler`, `common/exception/*` |
| `security` | `CurrentUserService`, `JwtAuthConverter`, `RoleConstants`, `PermissionConstants` |
| `integration/*` | Clients for UMS, PMS, RMS, Employee Onboarding |
| `consumer`, `event`, `scheduler` | Kafka CDC consumer, Spring events, scheduled jobs |
| `config` | Security, CORS, Kafka, S3, OpenAPI, WebSocket |

## Conventions

- **New endpoint** = request/response DTOs + MapStruct mapper + service interface & impl + controller + `@WebMvcTest` controller test. Follow `CostCenterController` as the reference.
- Return `ApiResponse<T>` (`ApiResponse.success(data)` / `ApiResponse.success("message", data)`); creates use `@ResponseStatus(HttpStatus.CREATED)`, deletes `NO_CONTENT`.
- Validate request bodies with `@Valid` + Jakarta constraints on the DTO.
- Authorize every endpoint with `@PreAuthorize` using roles from `RoleConstants`: `GENERAL`, `ADMIN`, `FINANCE`, `FINANCE_EXECUTIVE`, `MANAGER` (e.g. `hasAnyRole('ADMIN','FINANCE')`).
- Get the caller from `CurrentUserService`, never from request params.
- Throw the exceptions in `common/exception` (`ResourceNotFoundException`, `BusinessRuleViolationException`, `DuplicateResourceException`, …); `GlobalExceptionHandler` maps them to responses.
- IDs are `UUID`.

## Database

- Schema is currently managed by `spring.jpa.hibernate.ddl-auto=update`; **Flyway is disabled** (`spring.flyway.enabled=false`).
- Still add a migration in `src/main/resources/db/migration` for schema/data changes, named `V<next>__description.sql`. Check the highest existing version first and keep versions unique — `V6` is already duplicated, which Flyway will reject if it is re-enabled.
- One shared MySQL (RDS) instance is used by dev — never run destructive SQL against it.

## Config & secrets

- All environment-specific values come from env vars (`${DB_URL}`, `${UMS_BASE_URL}`, …) — locally from `.env`, in k8s from the `xms-secrets` Secret.
- Never commit `.env`, credentials, or real hostnames/keys. Add new settings to `application.properties` as `${ENV_VAR}` / `${ENV_VAR:default}` and tell the user the Secret needs the new key.
- Do not log SQL statements/bind parameters or tokens (SQL logging is deliberately off).

## Deployment

1. Merge to `dev` → Jenkins (`Jenkinsfile.cd`, shared lib `deployToDev`) builds an **arm64** image → ECR `paves/xms-dev:dev-<sha>`. (`Jenkinsfile` is the CI build/quality pipeline; `Jenkinsfile.dev` is fully commented out.)
2. Jenkins commits the new tag to `intranet-devops` (`dev` branch) at `k8s/backend/xms/deployment.yaml`.
3. ArgoCD (`paves-xms`) syncs it into namespace `backend`; ingress path `/xms` → `xms-service:8080`.

Kafka bootstrap and CDC topic are set as env vars in that deployment manifest.
