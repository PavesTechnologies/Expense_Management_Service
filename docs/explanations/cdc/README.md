# Employee CDC pipeline — local development guide

This document is Phase 0a/0b of the EP06 plan
(`~/.claude/plans/abundant-wandering-blossom.md`). It lets you develop and test
`EmployeeCdcConsumer` against a real (throwaway, local) Redpanda broker,
without any dependency on RDS, Debezium, or another team's infrastructure.

## Why this exists

Production topology, proven by the Leave Management System's already-merged
CDC consumer:

```
AWS RDS MySQL (eos db) --binlog--> Debezium --> Redpanda topic --> consumer
```

Nothing in this repo, the EOS repo, or the LMS repo actually *defines* the
Redpanda broker or the `cdc-network` it lives on — both EOS and LMS only
declare it `external: true`. So there is currently no way to run the real
pipeline locally. `docker-compose.cdc.yml` fills that gap for development only.

## 1. Start the stack

```powershell
docker compose -f docker-compose.cdc.yml up -d
```

This starts:
- **Redpanda** — Kafka-API-compatible broker, reachable at `localhost:19092` from the host (e.g. from `./mvnw spring-boot:run`), or `redpanda:29092` from another container on the same `cdc-network`.
- **Redpanda Console** — a web UI at http://localhost:8090 for browsing topics and producing/consuming messages by hand.

Wait for the healthcheck to pass (`docker compose -f docker-compose.cdc.yml ps` should show `redpanda` as `healthy`) before producing messages.

## 2. Create the topic

The consumer subscribes to a topic named after the **proposed dev connector**
(see Phase 0c in the plan): `eos_dev.eos.employee_details`
(`<debezium.server.name>.<schema>.<table>` — the same convention LMS's real
connector uses with server name `eos_test`).

```powershell
docker exec xms-cdc-redpanda rpk topic create eos_dev.eos.employee_details --brokers localhost:29092
```

## 3. Publish sample events

Every event mirrors the **flattened** Debezium contract (`ExtractNewRecordState`
SMT, `add.fields=op,ts_ms`, `delete.handling.mode=rewrite`) — metadata fields
(`__op`, `__ts_ms`, `__deleted`) sit flat alongside the column names, matching
exactly what `EmployeeCdcConsumer` / `EmployeeCdcEvent` expect.

Easiest path: open http://localhost:8090 → **Topics** → `eos_dev.eos.employee_details`
→ **Produce Message**, paste one of the JSON bodies below as the value, and
use the `employee_uuid` as the key.

Or via `rpk` from the command line:

```powershell
$body = @'
{"employee_uuid":"3f1b2c4d-0000-0000-0000-000000000001","employee_id":"5100101","first_name":"Asha","last_name":"Verma","work_email":"asha.verma@paves.com","gender":"Female","contact_number":"9876543210","joining_date":"2026-07-01","designation_uuid":"d1111111-0000-0000-0000-000000000001","employment_status":"Active","employment_type":"Full-Time","reporting_manager_uuid":"5100001","created_by":"5100001","__op":"c","__ts_ms":1753600000000,"__deleted":"false"}
'@
$body | docker exec -i xms-cdc-redpanda rpk topic produce eos_dev.eos.employee_details --brokers localhost:29092 --key 3f1b2c4d-0000-0000-0000-000000000001
```

### Scenario A — create (`__op: "c"`)
```json
{
  "employee_uuid": "3f1b2c4d-0000-0000-0000-000000000001",
  "employee_id": "5100101",
  "first_name": "Asha",
  "last_name": "Verma",
  "work_email": "asha.verma@paves.com",
  "gender": "Female",
  "contact_number": "9876543210",
  "joining_date": "2026-07-01",
  "designation_uuid": "d1111111-0000-0000-0000-000000000001",
  "employment_status": "Active",
  "employment_type": "Full-Time",
  "reporting_manager_uuid": "5100001",
  "created_by": "5100001",
  "__op": "c",
  "__ts_ms": 1753600000000,
  "__deleted": "false"
}
```
Expected: a new `EmployeeCache` row keyed on `employee_uuid`, `syncedAt` set.

### Scenario B — update (`__op: "u"`)
Same key, changed field:
```json
{
  "employee_uuid": "3f1b2c4d-0000-0000-0000-000000000001",
  "employee_id": "5100101",
  "first_name": "Asha",
  "last_name": "Verma",
  "work_email": "asha.verma@paves.com",
  "gender": "Female",
  "contact_number": "9876543210",
  "joining_date": "2026-07-01",
  "designation_uuid": "d1111111-0000-0000-0000-000000000001",
  "employment_status": "On-Notice",
  "employment_type": "Full-Time",
  "reporting_manager_uuid": "5100001",
  "created_by": "5100001",
  "__op": "u",
  "__ts_ms": 1753600100000,
  "__deleted": "false"
}
```
Expected: the existing row updates in place, `syncedAt` moves forward.
**Note:** `On-Notice` is one of the two EOS status values LMS's consumer
crashes on (`EmployeeStatus.valueOf(...)` with no `ON_NOTICE`/`EXITED`
mapping) — this repo's consumer must handle it defensively (log + accept, or
map to a known status), never throw.

### Scenario C — delete, rewrite mode (`__op: "d"`, `__deleted: "true"`)
```json
{
  "employee_uuid": "3f1b2c4d-0000-0000-0000-000000000001",
  "employee_id": "5100101",
  "first_name": "Asha",
  "last_name": "Verma",
  "work_email": "asha.verma@paves.com",
  "gender": "Female",
  "contact_number": "9876543210",
  "joining_date": "2026-07-01",
  "designation_uuid": "d1111111-0000-0000-0000-000000000001",
  "employment_status": "Exited",
  "employment_type": "Full-Time",
  "reporting_manager_uuid": "5100001",
  "created_by": "5100001",
  "__op": "d",
  "__ts_ms": 1753600200000,
  "__deleted": "true"
}
```
Expected: the row is removed (or soft-deleted, depending on the implementation) — handled via the `__deleted`/`op=d` branch, not the status enum (this is also why `employee_exit`/`Exited` must never reach the enum-mapping code path untested).

### Scenario D — true tombstone (null value)
Debezium can also emit a plain Kafka tombstone (null message value) after a
rewrite-mode delete. `rpk topic produce` cannot send a null value directly;
use the Console UI's produce dialog and leave the value field empty, or (if
`kcat`/`kafkacat` is installed):
```powershell
kcat -b localhost:19092 -t eos_dev.eos.employee_details -k 3f1b2c4d-0000-0000-0000-000000000001 -Z -P nul
```
Expected: consumer must treat a null value as a no-op-but-safe delete
confirmation, never a null-pointer exception.

### Scenario E — malformed payload
```
not-valid-json{{{
```
Expected: **a `CdcFailureLog` row is written** (`failureType=PARSE_FAILED`)
before the message is acknowledged. This is the exact gap found in LMS's
implementation — its consumer only logs and acks, so its retry cron always
finds an empty table. Verify this repo's `cdc_failure_log` table actually
gets a row via:
```sql
SELECT * FROM cdc_failure_log ORDER BY created_at DESC;
```
or `GET /xms/admin/cdc/failures` once the admin endpoint exists.

## 4. Tear down

```powershell
docker compose -f docker-compose.cdc.yml down -v
```

`-v` also drops the `redpanda-data` volume, so the next `up` starts from a
clean broker (no leftover topics/offsets).

## Known deliberate differences from the LMS reference implementation

| Behaviour | LMS (existing) | This service |
|---|---|---|
| Unknown `employment_status` value | Throws inside `handleUpsert`, silently swallowed, employee never synced | Logged + quarantined via `CdcFailureLog`, never thrown |
| Null `gender` | NPEs on `.toUpperCase()` | Null-safe |
| Processing failure | Logged only, `ack.acknowledge()` called regardless — `cdc_failure_log` never receives a row despite existing | Written to `CdcFailureLog` **before** acking |
| Back-fill of dependents | Passes a UUID into a method that matches on `employee_id` — never fires | N/A in this service for Phase 0 (no back-fill dependency chain needed yet) |
