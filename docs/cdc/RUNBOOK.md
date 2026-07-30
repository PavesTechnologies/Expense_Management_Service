# Employee CDC pipeline — infra/DBA handoff runbook

This is a **handoff artifact**, not something runnable from this repo. It
gives the infra/DBA team everything needed to stand up the production side of
the Employee CDC pipeline (EP06 Phase 0c). Application-side code
(`EmployeeCdcConsumer` and friends) is already built and tested against a
local Redpanda (see `docs/cdc/README.md`) and needs nothing further from this
list to keep working once the real pipeline exists — it only needs the topic
name and bootstrap servers pointed at it via `CDC_EMPLOYEE_TOPIC` /
`KAFKA_BOOTSTRAP_SERVERS`.

## Why this is needed

The Leave Management System already runs a production Debezium connector
against EOS's employee table (topic `eos_test.eos.employee_details`), proving
the pipeline works end to end. However:

- **Nothing in any repo on this workspace defines the `cdc-network` Docker
  network or the Redpanda/Kafka Connect services themselves** — EOS and LMS
  both only declare `cdc-network` as `external: true`. Ownership of the actual
  infrastructure needs to be established; this runbook assumes whoever owns it
  can add one more connector to the existing Kafka Connect cluster.
- This service should **not** simply subscribe to LMS's existing `eos_test.*`
  topic — a second, independently-owned connector keeps the two services'
  consumer groups and failure domains isolated (see `docker-compose.cdc.yml`
  and `application.properties` for the local-dev topic name
  `eos_dev.eos.employee_details`, which this connector should produce to in a
  dev environment).

## 1. Confirm/enable binlog on the source RDS instance

EOS's database is AWS RDS MySQL. There is no `my.cnf` in the EOS repo — this
is entirely an RDS parameter-group concern. Binlog is very likely **already
enabled**, since LMS's existing connector depends on it — this step is
confirmation, not new enablement, unless the dev/prod instances differ.

Required parameter group settings:
```
binlog_format = ROW
binlog_row_image = FULL
```
Plus, via the RDS-specific procedure (binlog retention isn't a standard MySQL
variable on RDS):
```sql
CALL mysql.rds_set_configuration('binlog retention hours', 168);
```
Automated backups must be enabled - RDS requires this before binlog can be
turned on. A parameter group change to `binlog_format` requires a **reboot** if
it wasn't already set - confirm current value before assuming this is a no-op.

## 2. Create a dedicated replication user

Do not reuse the application's own DB user (`PavesIntraProd` in prod, per
`EOS/Backend/create_tables.py` - flagging separately that this is hardcoded
in plaintext in that file and should be rotated/removed regardless of CDC
work). Debezium needs its own credential:

```sql
CREATE USER 'debezium_eos'@'%' IDENTIFIED BY '<generate - do not commit this>';
GRANT REPLICATION SLAVE, REPLICATION CLIENT, SELECT, RELOAD ON *.* TO 'debezium_eos'@'%';
FLUSH PRIVILEGES;
```
Store the password via whatever secrets mechanism Kafka Connect on this
cluster uses (do not inline it in the connector config - see the
`${file:...}` placeholder in `debezium-connector-eos-dev.json`).

## 3. Deploy the connector

`debezium-connector-eos-dev.json` in this same folder is the full config,
annotated inline with `_comment_*` fields explaining each non-obvious choice
(server ID uniqueness, why `employee_exit` is mandatory, why the topic prefix
differs from LMS's, why the SMT config must match LMS's exactly). Replace the
placeholder values, then submit to Kafka Connect's REST API:

```
POST http://<connect-host>:8083/connectors
Content-Type: application/json

<contents of debezium-connector-eos-dev.json>
```

Verify it's running:
```
GET http://<connect-host>:8083/connectors/eos-employee-connector-dev/status
```

## 4. Assign a unique `database.server.id`

MySQL replication requires every connected replica (including every Debezium
connector) reading a given instance's binlog to have a distinct numeric
`server-id`. If LMS's `eos_test` connector already reads the same RDS
instance, this connector's ID must not collide with it, or with any other
replica. Check the current registry of assigned server IDs before picking one
— none exists in any repo on this workspace, so one must be established (a
shared spreadsheet or config-management value, not guessed).

## 5. Confirm `cdc-network` / Redpanda ownership

This is the one item this runbook cannot resolve on its own: identify who
actually owns the `cdc-network` Docker network and the broker it's meant to
carry (Redpanda, per LMS's config referencing `redpanda:29092`). Once that's
established, this service's `KAFKA_BOOTSTRAP_SERVERS` env var points at it and
`CDC_EMPLOYEE_TOPIC` is set to `eos_dev.eos.employee_details` (or the agreed
prefix) instead of the local-dev default.

## 6. Verify end-to-end once deployed

1. Update an `eos.employee_details` row directly (or via the EOS API) in the
   target environment.
2. Confirm a message appears on the connector's topic (`rpk topic consume
   eos_dev.eos.employee_details` or the Kafka Connect cluster's equivalent).
3. Confirm `EmployeeCache` in this service picks it up within a few seconds
   and `synced_at` moves forward.
4. Trigger a termination via the EOS exit flow and confirm the corresponding
   `employee_exit` row change also reaches this service (proves the
   termination gap is actually closed, not just theoretically included in
   `table.include.list`).
