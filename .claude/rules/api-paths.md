---
paths:
  - "src/main/java/**/controller/**/*.java"
  - "src/main/java/**/diagnostic/**/*.java"
  - "src/main/java/**/config/SecurityConfig.java"
  - "src/main/java/**/config/*WebSocket*.java"
  - "src/test/java/**/controller/**/*.java"
---

# XMS API paths must start with `/xms`

- Every `@RequestMapping` on a controller class MUST begin with `/xms` — e.g. `@RequestMapping("/xms/admin/cost-centers")`. Never create a controller mapped at `/api/...` or any other root path.
- Method-level mappings (`@GetMapping("/{id}")`, etc.) are relative to that class mapping. If a controller has no class-level mapping, each method mapping must start with `/xms`.
- Reason: the k8s ingress forwards only `/xms/**` to XMS and no servlet context path is configured, so a path without `/xms` returns 404 in every deployed environment.
- Never set `server.servlet.context-path` / `SERVER_SERVLET_CONTEXT_PATH` — combined with the in-code prefix it produces `/xms/xms/...`.
- Use `/xms/{audience}/{resource}`: `admin`, `employee`, `manager`, `finance`, or a feature root such as `/xms/approvals`.
- Any new public (unauthenticated) path added to `SecurityConfig.PUBLIC_ENDPOINTS` must use the `/xms/...` form.
- Controller tests must call the full `/xms/...` path.
- After adding or renaming an endpoint, check: `grep -rnE '@RequestMapping\("/' src/main/java | grep -v '"/xms'` returns nothing.
