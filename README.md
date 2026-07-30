# Change Data Capture & Kafka — A Complete Learning Guide

*(Grounded in this repository's actual Employee CDC pipeline: `EmployeeCdcConsumer`, `KafkaConfig`, `EmployeeCache`, `CdcFailureLog`, `CdcRetryScheduler`, `docker-compose.cdc.yml`, and the Debezium connector handoff in `docs/cdc/`.)*

This document teaches Change Data Capture (CDC) and Apache Kafka from absolute zero, then walks
through exactly how this project uses both to keep employee/manager data in sync from a system we
don't own. By the end, you should be able to explain the whole pipeline — and the reasoning behind
every design decision in it — to another developer.

> **How to read this**: Parts 1–2 are pure fundamentals — read them even if you've heard of Kafka
> before, because later parts assume you know these exact terms. Parts 3 onward are 100% specific to
> this codebase. Every code reference is a real file path you can open and follow along.

---

## Table of Contents

1. [Part 1 — CDC Fundamentals](#part-1--cdc-fundamentals)
2. [Part 2 — Kafka Fundamentals](#part-2--kafka-fundamentals)
3. [Part 3 — Our Project's Architecture](#part-3--our-projects-architecture)
4. [Part 4 — Architecture Diagrams](#part-4--architecture-diagrams)
5. [Part 5 — Local Development](#part-5--local-development)
6. [Part 6 — Cloud / Production Deployment](#part-6--cloud--production-deployment)
7. [Part 7 — Component Deep Dive](#part-7--component-deep-dive)
8. [Part 8 — Execution Flow: One Record, Start to Finish](#part-8--execution-flow-one-record-start-to-finish)
9. [Part 9 — Code Mapping](#part-9--code-mapping)
10. [Part 10 — Running the Project](#part-10--running-the-project)
11. [Part 11 — Debugging & Monitoring](#part-11--debugging--monitoring)
12. [Part 12 — Learning Roadmap](#part-12--learning-roadmap)
13. [Appendix A — Common Mistakes](#appendix-a--common-mistakes)
14. [Appendix B — Interview Questions](#appendix-b--interview-questions)
15. [Appendix C — Glossary](#appendix-c--glossary)

---

## Part 1 — CDC Fundamentals

### 1.1 What is Change Data Capture?

Imagine two people share a shopping list on paper. Person A writes items down. Person B needs to
know *the moment* something changes, without walking over to check the paper every five minutes.

Change Data Capture (CDC) is a technique for detecting and capturing changes (inserts, updates,
deletes) made to data in one system, so that other systems can react to those changes — usually
close to instantly, without the source system having to explicitly announce anything.

Formally: **CDC observes a database's changes as a continuous stream of events, instead of treating
the database as something you only ever query.**

In our project specifically: the **Employee Onboarding System (EOS)** owns a MySQL table
(`eos.employee_details`) with every employee's data — name, email, manager, employment status. Our
service, the **Expense Management Service (EMS)**, needs to know who reports to whom, so its
approval-workflow engine (EP06) can figure out "who is this employee's manager?" when routing an
expense report for approval. EMS doesn't own that data and shouldn't — EOS is the single source of
truth for employee records. CDC is how EMS gets a live, local copy of just the pieces it needs.

### 1.2 Why is CDC needed? What problem does it solve?

Picture the alternatives EMS *could* have used to get employee data, and why each one is worse:

| Approach | How it would work | Why it's a problem |
|---|---|---|
| **Direct API calls, on demand** | Every time EMS needs a manager, call an EOS API endpoint synchronously | EOS becomes a hard dependency for every approval action; if EOS is slow or down, expense approvals break. Also very "chatty" — one HTTP call per lookup. |
| **Polling** | EMS asks EOS "give me everyone updated since time X" every N minutes | Wastes work when nothing changed; misses changes if the polling interval isn't tight enough; requires EOS to build and maintain a "give me recent changes" API it may not have. |
| **Dual writes** | Whenever EOS changes an employee, it *also* writes to EMS's database directly | Two systems now write to the same data — if one write succeeds and the other fails, the two databases silently disagree forever. Also couples EOS's code to EMS's schema. |
| **CDC (what we use)** | A tool watches EOS's database transaction log and turns every row change into an event | EOS's code doesn't change **at all**. EMS gets a live stream of changes with no polling, no synchronous coupling, and (as we'll see) even survives outages of the consuming side. |

**The core problems CDC solves:**
1. **Freshness** — data arrives close to real time, not "whenever the next poll runs."
2. **Decoupling** — the source system (EOS) has zero code, zero awareness that EMS exists. It just
   keeps being a normal MySQL database.
3. **Completeness** — every single change is captured, in order, including ones a naive polling
   query might miss (e.g., a row that was updated twice between two poll intervals).
4. **No load on the source** — reading a transaction log is far cheaper for the source database than
   being hit with repeated `SELECT` polling queries from every consumer that wants updates.

### 1.3 Different CDC approaches

| Approach | How it detects changes | Trade-offs |
|---|---|---|
| **Timestamp/version polling** | Query `WHERE updated_at > last_poll_time` | Simple to build, but misses hard deletes (no `updated_at` on a deleted row!) and adds recurring query load. |
| **Trigger-based CDC** | Database triggers write every change to a separate "audit" table | Captures everything, but adds write overhead to every transaction on the source table, and triggers are notoriously easy to forget to update when the schema changes. |
| **Log-based CDC** *(our project)* | A tool reads the database's internal write-ahead/replication log (MySQL's **binlog**) — the same log MySQL itself uses for replication | Near-zero overhead on the source (it's just tailing a file MySQL already writes), captures every change including deletes, and doesn't require any schema or trigger changes on the source database. This is the approach **Debezium** implements. |

### 1.4 Why our project uses log-based CDC via Debezium

Our project's Debezium connector config
(`docs/cdc/debezium-connector-eos-dev.json`) sets:

```json
"connector.class": "io.debezium.connector.mysql.MySqlConnector"
```

This means: **Debezium connects to MySQL as if it were a MySQL replica**, and reads the **binlog** —
the same append-only log a real MySQL replica would read to stay in sync. That's what
`binlog_format = ROW` and `binlog_row_image = FULL` (in `docs/cdc/RUNBOOK.md`) are about: they tell
MySQL to write the *actual before/after row values* into the binlog (not just the SQL statement that
ran), which is exactly what Debezium needs to reconstruct "this row changed from X to Y."

**Why this approach, specifically, for this project:**
- EOS is somebody else's system. We are not allowed to add triggers or ask them to build a webhook.
- Reading the binlog requires only a MySQL user with `REPLICATION SLAVE` / `REPLICATION CLIENT`
  privileges (see `RUNBOOK.md` step 2) — no schema changes, no application code changes in EOS at
  all.
- It captures deletes and updates with full fidelity, which polling cannot reliably do.
- It was already **proven in production** by a sibling project (the Leave Management System, "LMS")
  using the exact same technique against the exact same EOS database — so this wasn't a novel,
  risky choice; it was replicating a working pattern.

---

## Part 2 — Kafka Fundamentals

CDC captures *that* something changed. Something still has to carry that event from "captured at
the source" to "delivered to everyone who wants it," reliably, in order, even if the consumer is
temporarily down. That's Kafka's job.

### 2.1 What is Kafka? (the post office analogy)

Think of Kafka as a **postal sorting office** for messages:

- Someone (a **producer**) drops off a letter.
- The sorting office doesn't open the letter or care what's inside — it just files it, in order,
  into the correct **mailbox slot** (a **topic**).
- Anyone with a key to that mailbox (a **consumer**) can come by, read the letters in order, and — 
  crucially — **the letters stay in the mailbox even after being read**. Ten different people can
  each read the same letter independently, at their own pace.

That last point is the single most important thing that makes Kafka different from something like a
task queue: **reading a message does not delete it.** Kafka is a durable, ordered, replayable log —
not a mailbox that empties once opened.

### 2.2 Why do we use Kafka (in general, and in this project)?

- **Decoupling in time**: the producer (Debezium, watching EOS's binlog) and the consumer
  (`EmployeeCdcConsumer` in this repo) never talk to each other directly. Kafka sits in between.
  If our consumer is down for maintenance, events simply wait in Kafka — nothing is lost, and
  Debezium doesn't even notice.
- **Ordering guarantees** (explained below) that let us safely process "employee X was updated,
  then updated again" in the correct sequence.
- **Replayability**: because reading doesn't delete, we can restart our consumer from the beginning
  of the topic and rebuild our entire local cache from scratch if we ever need to (this project
  actually relies on this — see §2.7).
- It's the de facto standard that **Debezium** (our CDC tool) publishes into, so choosing Kafka (or
  a Kafka-API-compatible broker — see §2.8) wasn't really an independent choice; it comes bundled
  with the CDC approach we chose in Part 1.

### 2.3 Core concepts, one at a time, with analogies first

#### Broker
> **Analogy**: a single post office building.

A **broker** is one server running Kafka (or a Kafka-compatible engine). It stores messages on disk
and serves producers and consumers. A production deployment usually has several brokers working
together (a **cluster**) for reliability — if one building burns down, the mail isn't gone, because
other buildings have copies (see **Replication** below).

*In this project*: locally, we run exactly **one broker** (`docker-compose.cdc.yml` — a single
Redpanda container named `xms-cdc-redpanda`). This is deliberately minimal for development; it is
**not** how you'd run this in production (see Part 6).

#### Topic
> **Analogy**: a named mailbox slot, e.g. "Invoices" vs. "Complaints." You choose which slot a
> letter goes into based on what it's about.

A **topic** is a named, ordered stream of messages. Producers publish messages *to* a topic;
consumers subscribe *to* a topic. A Kafka cluster can host many topics simultaneously, each
completely independent of the others.

*In this project*: there is one topic, and its name follows Debezium's naming convention
`<server-name>.<schema>.<table>`:

```
eos_dev.eos.employee_details
```

`eos_dev` is the connector's configured `topic.prefix` (see
`docs/cdc/debezium-connector-eos-dev.json`), `eos` is the MySQL schema, `employee_details` is the
table. Configured in `application.properties`:

```properties
cdc.employee.topic=${CDC_EMPLOYEE_TOPIC:eos_dev.eos.employee_details}
```

#### Partition
> **Analogy**: imagine the "Invoices" mailbox slot is actually split into several numbered drawers
> (Drawer 1, Drawer 2, Drawer 3). A letter always goes into the *same* drawer if it has the same
> "addressee name" written on the envelope — that's what makes it possible to promise "all letters
> for Alice arrive in the order they were sent," even though letters for Bob might be filed in a
> completely different drawer and processed independently.

A topic is physically split into one or more **partitions**. Kafka only guarantees strict ordering
**within a single partition**, not across an entire topic. Producers can attach a **key** to each
message; Kafka hashes that key to consistently choose the same partition for every message sharing
that key — which is exactly how per-entity ordering is achieved even with multiple partitions.

*In this project*: Debezium is configured with

```json
"message.key.columns": "eos.employee_details:employee_uuid"
```

This means **every change event for a given employee carries that employee's UUID as the Kafka
message key**. So even if the topic had multiple partitions, all events for one employee always
land in the same partition and are therefore guaranteed to arrive at our consumer **in the order
they happened** — critical, because we must never apply an "update" before the "create" that
preceded it, or apply an old update after a newer one.

#### Producer
> **Analogy**: the person dropping off the letter at the post office.

A **producer** is anything that publishes messages to a topic.

*In this project*: **we do not write a producer.** The producer is the **Debezium MySQL connector**,
running inside a separately-hosted **Kafka Connect** cluster (infra-owned, not part of this repo —
see `docs/cdc/RUNBOOK.md`). Debezium watches the binlog and produces one Kafka message per row
change it observes.

#### Consumer
> **Analogy**: the person who visits the mailbox and reads letters.

A **consumer** subscribes to one or more topics and processes the messages in them.

*In this project*: `EmployeeCdcConsumer`
(`src/main/java/com/expense_management_service/consumer/EmployeeCdcConsumer.java`) is the one and
only consumer. It's a Spring `@KafkaListener` method.

#### Consumer Group
> **Analogy**: a team of mail clerks who split the drawers between themselves so no two clerks
> process the same letter — but if the mailbox has 3 drawers and only 1 clerk shows up, that one
> clerk covers all 3 drawers alone.

A **consumer group** is a named set of consumer instances that cooperatively share the work of
reading a topic's partitions. Kafka guarantees **each partition is read by exactly one consumer
within a given group at a time** — so scaling out (adding more consumer instances to the same group)
spreads the load, without any single message being processed twice by the same group.

*In this project*, the group id is configured as:

```properties
spring.kafka.consumer.group-id=xms-employee-consumer
```

Why this matters concretely: LMS (the sibling Leave Management System) has its **own**, separate
consumer group reading a **different** topic (`eos_test.eos.employee_details`). The two services'
progress through "their" stream is tracked completely independently — one falling behind or
restarting has zero effect on the other. This is explicitly called out in the code's Javadocs and
the runbook as a deliberate design choice (a distinct topic prefix, `eos_dev` vs `eos_test`, so the
two consumer groups can never accidentally collide or reprocess each other's offsets).

#### Offset
> **Analogy**: a bookmark in a book. "I've read up to page 42" — the bookmark, not the book itself,
> is what a reader keeps track of. Two different readers of the same book keep their own separate
> bookmarks.

An **offset** is a monotonically increasing number identifying a message's position within a
partition. A consumer group's "progress" through a partition is just the last offset it has
confirmed processing — its bookmark.

*In this project*, two properties govern offset behaviour:

```properties
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
```

- `auto-offset-reset=earliest` — **if this consumer group has no bookmark yet** (brand new group,
  first ever run), start reading from the very beginning of the topic rather than only from "now
  onward." This matters a lot here: Debezium's connector is configured with `"snapshot.mode":
  "initial"`, meaning on its very first run it publishes a full snapshot of **every existing
  employee row** as synthetic "create" events, before switching to live binlog streaming. Combined
  with `earliest`, this means: **the very first time our consumer group ever runs, it will read that
  entire initial snapshot and fully populate `EmployeeCache` from scratch** — no separate backfill
  job needed.
- `enable-auto-commit=false` — we do **not** let Kafka automatically advance the bookmark just
  because a message was handed to us. We commit ("acknowledge") the offset **manually**, only after
  we've either successfully processed the event or safely recorded its failure (see §7's deep dive
  on `EmployeeCdcConsumer`). This is what makes the pipeline resilient to crashes mid-processing.

#### Replication
> **Analogy**: the post office keeps a **photocopy** of every letter in a different, physically
> separate building. If one building burns down, the letters aren't gone.

**Replication** is Kafka's mechanism for durability: each partition's data is copied across multiple
brokers. One broker is the "leader" for a partition (handles all reads/writes); the others are
"followers" that continuously copy the leader's data. If the leader broker dies, a follower with a
full up-to-date copy is promoted to take over, and no data is lost.

*In this project*: our local `docker-compose.cdc.yml` runs a **single Redpanda node** —
**there is no replication in local development**, by deliberate design, because local dev only needs
"good enough to develop and test against," not durability guarantees. Production replication factor
is an infrastructure decision owned by whoever runs the real broker (see Part 6) — it is **not**
specified anywhere in this repository, since this repo only defines the *application* side of the
pipeline, not the broker's operational configuration.

#### Message Ordering
Already covered under **Partition** above, but worth stating as its own principle because it's the
single most commonly misunderstood Kafka guarantee:

> **Kafka guarantees ordering *within a partition*, never across an entire topic.**

Our project achieves "all changes to one employee arrive in order" specifically **because** Debezium
keys every message by `employee_uuid`, which pins all of one employee's events to one partition.
Nothing here relies on the topic having only one partition — it relies on the **key**.

### 2.4 A quick worked example, tying it all together

Say employee Asha's row changes twice in quick succession in EOS: her `employment_status` goes
`Active → On-Notice`, then a minute later `On-Notice → Exited`.

1. Debezium (the **producer**) reads both binlog events and publishes two messages to the topic
   `eos_dev.eos.employee_details`, **both keyed by Asha's `employee_uuid`**.
2. Kafka (the **broker**) stores both messages, in the order they arrived, in whichever single
   **partition** that key hashes to.
3. Our consumer group `xms-employee-consumer` — right now, a single consumer instance,
   `EmployeeCdcConsumer` — reads them **in that same order**, because that's the partition
   ordering guarantee.
4. After processing the first message, it commits (acknowledges) its **offset**. If the app crashed
   right now, on restart it would resume exactly after that offset — not reprocess it, not skip the
   second message.
5. The second message updates the same `EmployeeCache` row again, to `Exited`.

If instead these two updates had been produced *without* a shared key (hypothetically, hashed to
different partitions), there would be no guarantee which one our consumer sees first — we could end
up with Asha's cached status incorrectly showing `On-Notice` after she's actually `Exited`. The key
is what prevents that.

### 2.5 Why "at least once," and why our consumer must be idempotent

Kafka consumers in this project use manual acknowledgment, which gives an **"at least once"**
delivery guarantee: if the app crashes *after* processing a message but *before* acknowledging it,
Kafka will redeliver that same message after restart — because, from Kafka's point of view, it was
never confirmed. This means **our consumer might process the exact same event twice.**

This is why `EmployeeCdcConsumer.handleUpsert` is written as an **upsert** (update-if-exists,
else-insert) keyed on `employeeUuid`, not a raw `INSERT`:

```java
EmployeeCache employee = employeeCacheRepository.findByEmployeeUuid(event.employeeUuid())
        .orElseGet(EmployeeCache::new);
// ...set every field from the event...
employeeCacheRepository.save(employee);
```

Processing the same "Asha's status is On-Notice" event twice produces the exact same end state both
times — safe. This property (processing a message multiple times has the same effect as processing
it once) is called **idempotency**, and it's the standard way to make an "at least once" system
behave, in practice, like "exactly once" from the data's perspective.

### 2.6 What Kafka is *not*

- Not a database you query with SQL (though tools like Redpanda Console can browse messages).
- Not a request/response system — producers don't wait for a reply from consumers.
- Not a guaranteed-exactly-once system by default (see §2.5 — you design for "at least once" plus
  idempotency instead).
- Not something that deletes a message once someone reads it — multiple independent consumer groups
  can each read the entire topic from the start, completely independently.

### 2.7 Replayability in practice: why this matters for our project

Because Kafka doesn't delete messages on read, and our consumer group can be told to start from
`earliest`, this project could, in principle, **rebuild `EmployeeCache` entirely from scratch** by
resetting the `xms-employee-consumer` group's offset back to zero and letting it replay the whole
topic (snapshot + every change since). This is a powerful operational safety net that a
polling-based design simply cannot offer, because polling never "remembers" the full history — only
Kafka's retained log does.

### 2.8 Redpanda vs. "real" Apache Kafka

You'll notice this project's `docker-compose.cdc.yml` and the production runbook both mention
**Redpanda**, not "Apache Kafka" the original project. Redpanda is a different implementation of the
**same wire protocol** Kafka clients speak — meaning our Java code (`spring-kafka`,
`org.apache.kafka.clients.consumer.ConsumerRecord`, etc.) works against it completely unmodified.
Everything you learn about "Kafka concepts" in this guide (topics, partitions, offsets, consumer
groups) applies identically; Redpanda is simply the specific broker software this project's
infrastructure happens to run, in both local dev and production (confirmed by
`docs/cdc/RUNBOOK.md` referencing `redpanda:29092` as the production broker address). We will use
the word "Kafka" throughout this guide for the general concepts and "Redpanda" when talking
specifically about the broker software we run.

---

## Part 3 — Our Project's Architecture

### 3.1 The business problem, restated precisely

The EMS approval-workflow engine (a separate feature, EP06) needs to answer "who is this employee's
manager?" when routing an expense report for a `MANAGER`-type approval level. That data — employee
names, emails, and their manager's identity — lives in a completely different system's database:
EOS (Employee Onboarding System), specifically the MySQL table `eos.employee_details`.

EMS cannot query EOS's database directly (different service, different database, no direct network
access assumed, and tight coupling would be a bad idea even if it were possible). So EMS keeps its
own **local, read-only mirror** of just the employee fields it actually needs, called
`EmployeeCache`, and uses CDC to keep that mirror continuously up to date.

### 3.2 Every component, and its one job

| Component | Responsibility | Owned by this repo? |
|---|---|---|
| **EOS MySQL database** | The source of truth for employee data | No — external system |
| **MySQL binlog** | Row-level change log MySQL writes for every insert/update/delete | No — MySQL feature, enabled via RDS parameter group |
| **Debezium MySQL connector** (running inside **Kafka Connect**) | Tails the binlog, turns row changes into Kafka messages | No — infra-owned, deployed via a REST call per `RUNBOOK.md` |
| **Redpanda broker** | Stores and orders the change-event messages on topic `eos_dev.eos.employee_details` | No in production (infra-owned) / Yes in local dev (`docker-compose.cdc.yml`) |
| **`KafkaConfig`** | Wires up how our Spring app connects to the broker: deserialization, manual-ack mode, `@EnableKafka` | **Yes** |
| **`EmployeeCdcConsumer`** | The one `@KafkaListener`; parses each message, applies it to `EmployeeCache`, and on any failure records it durably instead of losing it | **Yes** |
| **`EmployeeCdcEvent`** | The DTO shape of a flattened Debezium JSON message | **Yes** |
| **`EmployeeCache`** (entity + repository) | The local mirror table the rest of EMS actually reads from | **Yes** |
| **`CdcFailureLog`** (entity + repository) | A durable "dead-letter" record of any event that couldn't be processed | **Yes** |
| **`CdcFailureLogService`** | Writes/reads failure records; tracks retry counts and status | **Yes** |
| **`CdcRetryService`** | Replays every still-retryable `CdcFailureLog` row through the consumer's own upsert/delete logic | **Yes** |
| **`CdcRetryScheduler`** | A cron job that triggers `CdcRetryService` every 10 minutes by default | **Yes** |
| **`ApproverResolver` (`DefaultApproverResolverImpl`)** | The actual *consumer* of `EmployeeCache`'s data — reads it to resolve a `MANAGER`-type approval level | **Yes** (different feature, EP06) |

### 3.3 How components communicate

```
EOS MySQL  --binlog-->  Debezium (in Kafka Connect)  --Kafka protocol-->  Redpanda topic
                                                                                │
                                                                                │ Kafka protocol
                                                                                ▼
                                                                     EmployeeCdcConsumer
                                                                       (this repo, JVM)
                                                                                │
                                                        success ─────┐         │ failure
                                                                     ▼         ▼
                                                              EmployeeCache   CdcFailureLog
                                                              (MySQL table)   (MySQL table)
                                                                                    │
                                                                     every 10 min   │
                                                                     CdcRetryScheduler
                                                                     replays these ─┘
```

Every arrow above is a **network hop through the Kafka protocol** except the last three, which are
plain JDBC/JPA calls into this service's own MySQL database (the Aiven-hosted EMS database, a
completely different database from EOS's).

### 3.4 The complete data flow, end to end, in one sentence per stage

1. Someone changes an employee's row in EOS's MySQL database (an HR admin edits a record, or an
   employee's status changes via EOS's exit workflow).
2. MySQL writes that change to its binlog, as it always does regardless of who's watching.
3. Debezium, tailing that binlog, turns the row change into a flattened JSON message and publishes
   it to the `eos_dev.eos.employee_details` topic on Redpanda, keyed by the employee's UUID.
4. Our `EmployeeCdcConsumer`, subscribed to that topic under consumer group `xms-employee-consumer`,
   receives the message.
5. It parses the JSON into an `EmployeeCdcEvent`, validates it has an `employee_uuid`, and either
   upserts or deletes the corresponding `EmployeeCache` row — or, if anything goes wrong at any
   step, records a `CdcFailureLog` row instead of losing the event.
6. It acknowledges the message either way, advancing its offset.
7. Later, when the approval-workflow engine needs to know who Asha's manager is, it reads that
   answer straight out of the local `EmployeeCache` table — no network call to EOS, no waiting.

---

## Part 4 — Architecture Diagrams

### 4.1 High-level system architecture

```
┌───────────────────────────┐
│   Employee Onboarding      │
│   System (EOS) — owns      │
│   the employee data        │
│                             │
│   MySQL: eos.employee_     │
│   details, employee_exit   │
└─────────────┬───────────────┘
              │ binlog (row-level change log)
              ▼
┌───────────────────────────┐
│      Kafka Connect          │
│  (infra-owned, NOT in this  │
│   repo)                     │
│                             │
│  ┌───────────────────────┐  │
│  │ Debezium MySQL         │  │
│  │ connector              │  │
│  │ "eos-employee-         │  │
│  │  connector-dev"        │  │
│  └───────────┬───────────┘  │
└──────────────┼───────────────┘
               │ produces Kafka messages
               ▼
┌───────────────────────────────┐
│         Redpanda broker         │
│  topic: eos_dev.eos.            │
│         employee_details        │
│  key: employee_uuid             │
└─────────────┬───────────────────┘
              │ consumes (consumer group:
              │  xms-employee-consumer)
              ▼
┌───────────────────────────────────────┐
│   Expense Management Service (EMS)      │
│   — THIS REPOSITORY                     │
│                                         │
│  EmployeeCdcConsumer                    │
│      │            │                    │
│  success        failure                │
│      ▼            ▼                    │
│  EmployeeCache   CdcFailureLog          │
│  (MySQL, EMS's                         │
│   own database)   ▲                    │
│                    │ every 10 min       │
│              CdcRetryScheduler          │
│                                         │
│  Downstream reader:                    │
│  ApprovalWorkflowService's              │
│  ApproverResolver (EP06)                │
└─────────────────────────────────────────┘
```

### 4.2 Local development architecture

```
Your machine
│
│  docker compose -f docker-compose.cdc.yml up -d
│
├── Container: xms-cdc-redpanda
│     Kafka API:  localhost:19092 (from host) / redpanda:29092 (from other containers)
│     Single node, single broker, NO replication (dev only)
│
├── Container: xms-cdc-console  (Redpanda Console — web UI)
│     http://localhost:8090
│
└── ./mvnw spring-boot:run   (your Spring Boot app, running on the host, NOT in Docker)
      connects to localhost:19092
      consumer group: xms-employee-consumer
      topic: eos_dev.eos.employee_details

      *** There is no Debezium and no real EOS database in this picture ***
      You publish sample JSON events BY HAND (via Redpanda Console or `rpk`)
      that mimic exactly what Debezium would have produced.
```

**Why no Debezium locally?** Nothing in this repo, EOS's repo, or the Leave Management System's repo
actually *defines* the `cdc-network` Docker network or a Kafka Connect service — all of them only
reference it as pre-existing, infra-owned. `docker-compose.cdc.yml` fills that gap purely for local
development by standing up a throwaway broker you fully control, so you can develop and test
`EmployeeCdcConsumer` — including its failure/quarantine paths — without depending on any other
team's infrastructure.

### 4.3 Cloud / production architecture

```
AWS RDS (MySQL) — paves-intranet-db-{dev,prod}
   binlog_format=ROW, binlog_row_image=FULL
   replication user: debezium_eos
          │
          │ binlog replication protocol
          ▼
Kafka Connect cluster (infra-owned, shared with LMS)
   Connector: eos-employee-connector-dev
   (config: docs/cdc/debezium-connector-eos-dev.json)
          │
          │ produces to
          ▼
Redpanda cluster (infra-owned; NOT AWS MSK — a self-hosted
   Redpanda deployment on infra's own "cdc-network")
   Topic: eos_dev.eos.employee_details
          │
          │ KAFKA_BOOTSTRAP_SERVERS env var points here
          ▼
Expense Management Service — deployed instance(s)
   consumer group: xms-employee-consumer
   Same EmployeeCdcConsumer code as local dev — ZERO code
   changes needed to go from local to production, only
   environment variables change.
          │
          ▼
   EMS's own MySQL (Aiven-hosted) — EmployeeCache,
   CdcFailureLog tables
```

**Key point**: the application code in this repo is *identical* between local dev and production.
Only two environment variables differ:

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:19092}
cdc.employee.topic=${CDC_EMPLOYEE_TOPIC:eos_dev.eos.employee_details}
```

Locally these default to the throwaway Redpanda; in a real environment, they're overridden to point
at the infra-owned broker.

### 4.4 End-to-end data flow (sequence view)

```
EOS Admin        MySQL         Debezium      Redpanda      EmployeeCdcConsumer   EmployeeCache   CdcFailureLog
   │               │               │             │                  │                 │              │
   │ edits row      │               │             │                  │                 │              │
   ├──────────────►│               │             │                  │                 │              │
   │               │ writes binlog  │             │                  │                 │              │
   │               ├──────────────►│             │                  │                 │              │
   │               │               │ produce msg  │                  │                 │              │
   │               │               ├────────────►│                  │                 │              │
   │               │               │             │  deliver to       │                 │              │
   │               │               │             │  consumer group   │                 │              │
   │               │               │             ├─────────────────►│                 │              │
   │               │               │             │                  │ parse JSON       │              │
   │               │               │             │                  │──── OK ─────────►│ upsert row   │
   │               │               │             │                  │                 │              │
   │               │               │             │                  │──── FAIL ───────────────────────►│ write failure row
   │               │               │             │                  │ ack (either way) │              │
   │               │               │             │◄─────────────────┤                 │              │
   │               │               │             │  offset advances │                 │              │
   │               │               │             │                  │                 │              │
   │               │               │             │       (every 10 minutes, independently:)            │
   │               │               │             │                  │◄──────── CdcRetryScheduler reads retryable rows
   │               │               │             │                  │           and replays them directly
```

### 4.5 Component interaction — who calls whom, and why one dependency is deliberately split

```
                       ┌────────────────────┐
                       │  CdcRetryScheduler   │   (@Scheduled cron, every 10 min)
                       └──────────┬───────────┘
                                  │ calls
                                  ▼
                       ┌────────────────────┐
                       │  CdcRetryService     │
                       └──────┬───────┬───────┘
                    depends on │       │ depends on
                                ▼       ▼
                ┌───────────────────┐ ┌───────────────────┐
                │ CdcFailureLogService│ │ EmployeeCdcConsumer │
                │  (read retryable,   │ │  (its PUBLIC        │
                │   mark succeeded/   │ │   handleUpsert /    │
                │   failed)           │ │   handleDelete       │
                └─────────────────────┘ │   methods, called    │
                                         │   directly — NOT via  │
                                         │   Kafka at all)        │
                                         └───────────────────────┘
```

**Why `CdcRetryService` exists as its own class, separate from `CdcFailureLogService`**: the
consumer needs `CdcFailureLogService` to record its own failures. If `CdcFailureLogService` also
depended on `EmployeeCdcConsumer` (to replay events), you'd get a **circular dependency** — two beans
each needing the other to exist first, which Spring cannot construct. `CdcRetryService` sits *above*
both, depending on each of them one-directionally, breaking the cycle. This is a real, deliberate
design decision documented directly in the code's Javadoc.

---

## Part 5 — Local Development

### 5.1 Which services need to be started

For local CDC development, you need exactly two things running:

1. **The Redpanda stack** (broker + web console), via Docker Compose.
2. **This Spring Boot application**, via Maven.

You do **not** need EOS, Debezium, or Kafka Connect running locally — you simulate their output by
hand-publishing sample JSON messages that match Debezium's exact flattened format.

### 5.2 How Kafka runs locally

```powershell
docker compose -f docker-compose.cdc.yml up -d
```

This starts (see `docker-compose.cdc.yml`):
- **`xms-cdc-redpanda`** — a single-node Redpanda broker. Two addresses matter:
  - `localhost:19092` — how your Spring Boot app (running directly on your machine, not in Docker)
    reaches it.
  - `redpanda:29092` — how another *container* on the same Docker network (`cdc-network`) would
    reach it.
- **`xms-cdc-console`** — a web UI at **http://localhost:8090** for browsing topics and manually
  producing/consuming messages, so you don't need a command-line Kafka client to test things.

Wait for the healthcheck before doing anything else:

```powershell
docker compose -f docker-compose.cdc.yml ps
# redpanda should show as "healthy"
```

Then create the topic (Redpanda doesn't require this — it can auto-create on first publish — but
doing it explicitly makes intent clear):

```powershell
docker exec xms-cdc-redpanda rpk topic create eos_dev.eos.employee_details --brokers localhost:29092
```

### 5.3 How CDC events are generated (locally, by hand)

Since there's no real Debezium running locally, `docs/cdc/README.md` documents publishing sample
JSON payloads directly — either through the Redpanda Console's **Produce Message** button, or via
`rpk` from the command line. Every payload must match the **flattened Debezium contract** exactly:
metadata fields (`__op`, `__ts_ms`, `__deleted`) sit flat alongside the real column names, not nested
under a `payload.after` wrapper — because that's what `EmployeeCdcEvent`'s `@JsonProperty` mappings
expect.

Example — a "create" event:

```json
{
  "employee_uuid": "3f1b2c4d-0000-0000-0000-000000000001",
  "employee_id": "5100101",
  "first_name": "Asha",
  "last_name": "Verma",
  "work_email": "asha.verma@paves.com",
  "joining_date": "2026-07-01",
  "reporting_manager_uuid": "5100001",
  "employment_status": "Active",
  "employment_type": "Full-Time",
  "__op": "c",
  "__ts_ms": 1753600000000,
  "__deleted": "false"
}
```

Publish it with `rpk`:

```powershell
$body = Get-Content sample-create.json -Raw
$body | docker exec -i xms-cdc-redpanda rpk topic produce eos_dev.eos.employee_details --brokers localhost:29092 --key 3f1b2c4d-0000-0000-0000-000000000001
```

`docs/cdc/README.md` includes five ready-made scenarios worth trying, in order:

| Scenario | What it tests |
|---|---|
| A — create (`__op: "c"`) | A brand-new `EmployeeCache` row appears |
| B — update (`__op: "u"`), status → `"On-Notice"` | The existing row updates in place; also proves an EOS status value LMS's reference implementation crashes on is handled safely here |
| C — delete, rewrite mode (`__op: "d"`, `__deleted: "true"`) | The cached row is removed |
| D — true tombstone (null message value) | Handled as a safe no-op, never a `NullPointerException` |
| E — malformed JSON | A `CdcFailureLog` row is written, **before** acknowledging |

### 5.4 How to verify the pipeline is working

1. **Via the app's logs** — `EmployeeCdcConsumer` logs on every upsert/delete:
   ```
   Upserted employee cache row: employeeId=5100101 employeeUuid=3f1b2c4d-... status=Active
   ```
2. **Via the database** — query `EmployeeCache` directly (whatever DB client you use against the
   configured `DB_URL`):
   ```sql
   SELECT * FROM employee_cache WHERE employee_uuid = '3f1b2c4d-0000-0000-0000-000000000001';
   ```
3. **Via Redpanda Console** (http://localhost:8090) — open the topic, confirm your message is there,
   check its offset, and see the consumer group `xms-employee-consumer`'s current lag (how far
   behind it is — should return to 0 shortly after you publish).
4. **For the failure path** — after publishing Scenario E (malformed JSON), confirm a row appeared:
   ```sql
   SELECT * FROM cdc_failure_log ORDER BY created_at DESC;
   ```

### 5.5 Common issues and troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App logs nothing at all when you publish a message | `@EnableKafka` missing, or app isn't actually connected | Confirm `KafkaConfig` has `@EnableKafka` (it does — this was a deliberate note in the code: without it, `@KafkaListener` methods are silently never wired into a container, no error, no log line) |
| `Unexpected character (code 65279 / 0xfeff)` when parsing | A UTF-8 byte-order-mark (BOM) was prepended by whatever tool produced the message (observed from Windows-originated tooling) | Already handled — `EmployeeCdcConsumer.stripLeadingByteOrderMark(...)` strips it before parsing. If you see this error anyway, you've likely found a new BOM-emitting path; check the raw bytes. |
| Message never seems to arrive / consumer group stuck | Broker not healthy yet, or wrong topic name | `docker compose -f docker-compose.cdc.yml ps` to confirm `healthy`; double-check the topic name matches `cdc.employee.topic` exactly, including the `eos_dev` prefix |
| `employee_cache` row never appears, no error either | Your JSON is missing `employee_uuid`, or it's blank | The consumer explicitly rejects this with a `VALIDATION_FAILED` `CdcFailureLog` entry — check that table |
| Two services fighting over the same broker/network | You have EOS's or LMS's real compose stack running at the same time | **Do not run this alongside real EOS/LMS containers** — both declare `cdc-network` as `external: true`; whichever stack starts second will fail unless this one is brought down first |
| Want a totally clean slate | Leftover topics/offsets from previous testing | `docker compose -f docker-compose.cdc.yml down -v` — the `-v` also drops the `redpanda-data` volume |

---

## Part 6 — Cloud / Production Deployment

### 6.1 What changes compared to local

| Aspect | Local dev | Production |
|---|---|---|
| Broker | Single-node Redpanda in `docker-compose.cdc.yml`, self-owned | Infra-owned Redpanda cluster on the real `cdc-network` |
| Producer | **None** — you hand-publish JSON | Real Debezium MySQL connector, running in a shared Kafka Connect cluster |
| Source database | N/A | AWS RDS MySQL (`paves-intranet-db-dev` / `-prod`) |
| Topic name | `eos_dev.eos.employee_details` (local default) | Same name pattern, pointed at via `CDC_EMPLOYEE_TOPIC` |
| App connection | `localhost:19092` (default) | `KAFKA_BOOTSTRAP_SERVERS` env var, pointed at the real cluster |
| Replication | None (single node) | Whatever the infra team configures — not defined by this repo |
| Ownership | You, entirely | EOS/infra team owns the connector + broker; this repo owns only the consumer |

**The application code does not change between these two environments.** Every difference is an
environment variable:

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:19092}
cdc.employee.topic=${CDC_EMPLOYEE_TOPIC:eos_dev.eos.employee_details}
```

### 6.2 Which services are managed vs. self-hosted

- **Self-hosted by infra, not this repo, not a managed cloud Kafka service (like AWS MSK)**: the
  Redpanda cluster and the Kafka Connect cluster running Debezium. This is explicit in
  `docker-compose.cdc.yml`'s comments and `RUNBOOK.md` — production topology is "a Debezium MySQL
  connector on AWS RDS publishes... to a Redpanda broker on an external Docker network," owned by
  whoever deploys EOS/LMS's infrastructure, not by this project.
- **Managed by AWS**: the source MySQL database itself (RDS), including binlog retention (configured
  via `CALL mysql.rds_set_configuration('binlog retention hours', 168);` — an RDS-specific procedure,
  since binlog retention isn't a standard MySQL server variable on RDS).
- **Owned by this repo/team**: everything from the consumer inward — `EmployeeCdcConsumer`,
  `EmployeeCache`, `CdcFailureLog`, and the scheduler.

### 6.3 Deploying the production side (per `docs/cdc/RUNBOOK.md`)

This is a **handoff runbook** — the application side needs nothing further once these steps are
done; it only needs `KAFKA_BOOTSTRAP_SERVERS` and `CDC_EMPLOYEE_TOPIC` pointed at the real
infrastructure.

1. **Confirm/enable binlog** on the RDS instance: `binlog_format=ROW`, `binlog_row_image=FULL`,
   automated backups on (RDS requires this before binlog can be enabled), retention set via the
   RDS-specific procedure above. A parameter-group change to `binlog_format` requires a **reboot**
   if it wasn't already set that way.
2. **Create a dedicated replication user** — never reuse the application's own DB credentials:
   ```sql
   CREATE USER 'debezium_eos'@'%' IDENTIFIED BY '<generated>';
   GRANT REPLICATION SLAVE, REPLICATION CLIENT, SELECT, RELOAD ON *.* TO 'debezium_eos'@'%';
   ```
3. **Deploy the connector** by `POST`-ing `docs/cdc/debezium-connector-eos-dev.json`'s contents to
   Kafka Connect's REST API (`POST http://<connect-host>:8083/connectors`), then verifying with
   `GET .../connectors/eos-employee-connector-dev/status`.
4. **Assign a unique `database.server.id`** — MySQL replication requires every connected replica
   (including every Debezium connector reading a given instance's binlog) to have a distinct numeric
   ID. This must not collide with LMS's existing connector if it reads the same RDS instance.
5. **Confirm `cdc-network` / Redpanda ownership** — the one item the runbook flags as genuinely
   unresolved at the infra level: identifying who owns the actual broker this connects to.
6. **Verify end to end**: update a real `eos.employee_details` row, confirm a message appears on the
   topic, confirm `EmployeeCache` picks it up within seconds (`synced_at` moves forward), and —
   importantly — trigger a real termination through EOS's exit flow to prove the `employee_exit`
   table change also reaches this service (see §6.5 below for why this specific check matters).

### 6.4 How components communicate in production

Identical protocol-wise to local dev — this is one of the big advantages of building against the
real Kafka wire protocol from day one. The only difference is *which* broker and *which* topic name
the environment variables point to. There is no separate "production client library" or different
code path.

### 6.5 Production considerations

**Scalability**: Kafka's consumer-group model (§2.3) means if event volume ever grows large enough
to need it, you could run multiple instances of this service under the same consumer group id, and
Kafka would automatically split partitions between them — again, no code change, just more running
instances. (Today's topic has enough partitions to keep per-employee ordering; scaling further would
be a partition-count and infra decision, not an application one.)

**Fault tolerance**: this pipeline is fault-tolerant at two independent layers:
1. **Kafka-level**: manual acknowledgment means a crash mid-processing simply re-delivers the
   message on restart (§2.5) — nothing is silently dropped by the broker/consumer relationship.
2. **Application-level**: any event that *can't* be processed (malformed JSON, a database error, an
   unrecognized value) is durably recorded in `CdcFailureLog` **before** being acknowledged, and
   independently retried later by `CdcRetryScheduler`. This is the exact gap the sibling LMS
   implementation has — its consumer never actually calls its own `CdcFailureLogService`, so its
   retry cron always finds an empty table and silently drops every poison message forever. This
   project's whole failure-handling design exists specifically to close that gap.

**Retries**: `CdcRetryScheduler` runs every 10 minutes by default (`cdc.retry.cron`), replaying any
`CdcFailureLog` row still in `FAILED`/`RETRYING` status and under its `maxRetries` (default 3) —
directly through the consumer's `handleUpsert`/`handleDelete` methods, using the row's stored
`rawPayload`, entirely independent of Kafka's own offsets. After 3 failed retries, a row moves to
`EXHAUSTED` status and stops being retried automatically (it would need manual intervention — there
is currently no admin API for this; see §6.6).

**Monitoring** (see Part 11 for the how-to): what you'd want visibility into in production is (a)
consumer group lag on `xms-employee-consumer` (are we falling behind?), (b) the row count and age
distribution of `CdcFailureLog` by status (are failures accumulating faster than retries clear
them?), and (c) whether the connector itself is running (`GET .../connectors/.../status` against
Kafka Connect).

### 6.6 A known, deliberate gap

There is currently **no HTTP admin endpoint** to inspect or manually trigger a retry of
`CdcFailureLog` rows — the only retry path today is the scheduled cron job. `docs/cdc/README.md`
even notes this directly ("`GET /xms/admin/cdc/failures` **once the admin endpoint exists**"). Today,
inspecting failures means querying the `cdc_failure_log` table directly. This is worth knowing if
you're debugging in production and wondering why there's no dashboard for it yet — there genuinely
isn't one.

---

## Part 7 — Component Deep Dive

For each component: purpose, inputs/outputs, configuration, dependencies, how it connects to others,
typical flow, and failure/recovery behaviour.

### 7.1 `KafkaConfig`
`src/main/java/com/expense_management_service/config/KafkaConfig.java`

- **Purpose**: defines *how* this Spring app talks to Kafka — deserialization, offset-commit
  behaviour, and enabling `@KafkaListener` processing at all.
- **Inputs**: `spring.kafka.bootstrap-servers`, `spring.kafka.consumer.group-id` (via `@Value`).
- **Outputs**: two Spring beans — a `ConsumerFactory<String, String>` and a
  `ConcurrentKafkaListenerContainerFactory<String, String>`.
- **Configuration**: both key and value deserializers are `StringDeserializer` — **not** a JSON or
  Avro deserializer — because Debezium here publishes with `schemas.enable=false` (plain JSON text,
  no schema registry involved at all). Parsing that string into `EmployeeCdcEvent` happens later, in
  the consumer itself, via a plain `ObjectMapper`.
- **Dependencies**: none beyond Spring Kafka / the Kafka client library.
- **Connects to**: every `@KafkaListener`-annotated method in the app (currently just
  `EmployeeCdcConsumer.consume`) uses the container factory this class defines.
- **Typical flow**: Spring Boot starts up → sees `@EnableKafka` → wires the listener container
  factory → subscribes `EmployeeCdcConsumer.consume` to its configured topic under its configured
  consumer group.
- **A subtle but important detail**: `@EnableKafka` is written *explicitly* here rather than relying
  on Spring Boot autoconfiguration, specifically because — per the class's own Javadoc — without it,
  `@KafkaListener` methods are **silently** never wired into a container. No exception. No log line.
  The consumer would simply never run, and you'd have no error message telling you why. This is a
  real, documented "gotcha" worth remembering.
- **Failure scenarios**: if `bootstrap-servers` points at an unreachable broker, the consumer factory
  will retry connecting in the background (standard Kafka client retry behaviour) and log connection
  warnings — the app itself still starts successfully; it just won't be receiving messages until the
  broker becomes reachable.

### 7.2 `EmployeeCdcEvent`
`src/main/java/com/expense_management_service/dto/external/EmployeeCdcEvent.java`

- **Purpose**: a Java `record` shaped exactly like Debezium's flattened JSON output for
  `eos.employee_details` — the DTO layer between "raw Kafka message string" and "typed object our
  code works with."
- **Inputs**: the raw JSON string (parsed via Jackson's `@JsonProperty` mappings, one per source
  column plus the three metadata fields `__op`, `__ts_ms`, `__deleted`).
- **Outputs**: nothing further — it's a passive data holder, plus one small piece of logic:
  `isDelete()`, which returns true if **either** `__deleted` is `"true"` **or** `__op` is `"d"` —
  covering both signals Debezium's rewrite-mode delete can carry.
- **Configuration**: `@JsonIgnoreProperties(ignoreUnknown = true)` — any extra field Debezium ever
  adds to its payload (which happens as connector versions evolve) is silently ignored rather than
  causing a parse failure. This is a deliberate forward-compatibility choice.
- **Dependencies**: none.
- **Connects to**: constructed by `EmployeeCdcConsumer` from the raw Kafka record value; also
  reconstructed by `CdcRetryServiceImpl` from a `CdcFailureLog`'s stored `rawPayload` when retrying.
- **A field worth remembering**: `reportingManagerUuid` is a misnomer inherited directly from EOS —
  despite the name, the underlying `eos.employee_details.reporting_manager_uuid` column actually
  stores the manager's **`employee_id`**, not a UUID, and has no foreign key. Anything that joins on
  it must join on `employeeId`, never `employeeUuid`.

### 7.3 `EmployeeCache` (entity) + `EmployeeCacheRepository`
`src/main/java/com/expense_management_service/entity/EmployeeCache.java`,
`src/main/java/com/expense_management_service/repository/EmployeeCacheRepository.java`

- **Purpose**: the actual local mirror table other parts of EMS (specifically the approval-workflow
  engine's `ApproverResolver`) read from. This is the whole point of the pipeline — everything
  upstream exists to keep this table correct.
- **Inputs**: written to exclusively by `EmployeeCdcConsumer.handleUpsert` / `handleDelete`.
- **Outputs**: read by `EmployeeCacheRepository.findByEmployeeId(...)` from
  `DefaultApproverResolverImpl` (a different feature, EP06's approval engine) when resolving a
  `MANAGER`-type approval level.
- **Configuration**: unique constraints on both `employee_id` and `employee_uuid` — two different
  stable keys, both guaranteed unique.
- **Dependencies**: plain JPA/Hibernate, no other service dependencies.
- **A deliberate omission**: this table does **not** cache `gender`, a synthesized password, or a
  hardcoded salary — fields the sibling LMS implementation's equivalent entity does carry, none of
  which the approval workflow has any use for. Not caching `gender` at all also removes an entire
  class of null-pointer bug LMS has (calling `.toUpperCase()` on a null gender) by construction,
  rather than by adding a defensive null check.
- **Failure scenarios**: none of its own — it's a passive table; failures happen upstream in the
  consumer, which is exactly why `CdcFailureLog` exists as a separate safety net rather than letting
  a bad write silently corrupt this table.

### 7.4 `EmployeeCdcConsumer`
`src/main/java/com/expense_management_service/consumer/EmployeeCdcConsumer.java`

This is the heart of the whole pipeline — read this class first if you're new to the codebase.

- **Purpose**: consumes every message on the employee CDC topic, keeps `EmployeeCache` in sync, and
  guarantees no event is ever silently lost.
- **Inputs**: a Kafka `ConsumerRecord<String, String>` (key = employee UUID, value = raw JSON string,
  or `null` for a true tombstone) plus an `Acknowledgment` handle for manually committing the offset.
- **Outputs**: writes to `EmployeeCache` (via `EmployeeCacheRepository`) on success, or to
  `CdcFailureLog` (via `CdcFailureLogService`) on any failure.
- **Configuration**: subscribed via
  `@KafkaListener(topics = "${cdc.employee.topic}", groupId = "${spring.kafka.consumer.group-id}")`.
- **Dependencies**: `EmployeeCacheRepository`, `CdcFailureLogService`, and a field-initialized (not
  constructor-injected) `ObjectMapper` — see the "gotcha" note below.
- **Typical execution flow** (`consume(...)`), in order:
  1. If `record.value()` is `null` → this is a true Kafka **tombstone** (as distinct from a
     rewrite-mode delete, which still carries a value with `__deleted=true`). Nothing to correlate
     without a payload — a preceding rewrite-mode delete already did the real work. Acknowledge and
     return.
  2. **Strip a leading byte-order-mark** if present (`stripLeadingByteOrderMark`) — a real issue
     observed against a live broker: some producers/tooling prepend a UTF-8 BOM, which
     `StringDeserializer` decodes into a literal `U+FEFF` character that Jackson's string-based
     parser does not skip on its own.
  3. **Parse** the (now-clean) string into `EmployeeCdcEvent`. If this throws → record a
     `PARSE_FAILED` failure with `employeeId`/`employeeUuid` both unknown (`null`), acknowledge, and
     return. A malformed message can never block the partition.
  4. **Validate** `employeeUuid` is present and non-blank — this is the correlation key everything
     else depends on. If missing → record a `VALIDATION_FAILED` failure, acknowledge, return.
  5. Dispatch to `handleUpsert` or `handleDelete` based on `event.isDelete()`, inside a try/catch. Any
     exception here (e.g., a database constraint violation, a transient connection failure) is caught,
     logged, and recorded as an `UPSERT_FAILED` or `DELETE_FAILED` `CdcFailureLog` row.
  6. **Always acknowledge**, regardless of which branch above was taken. The comment in the code
     states the reasoning directly: *"a poison message must not block the partition forever. Its
     permanent record lives in `cdc_failure_log`, which `CdcRetryScheduler` replays independently of
     the live topic offset."*
- **`handleUpsert`** (public, `@Transactional`, also called directly by `CdcRetryServiceImpl` when
  replaying a failure): looks up the existing `EmployeeCache` row by `employeeUuid`
  (`orElseGet(EmployeeCache::new)` for a brand-new employee), copies every field across, stamps
  `syncedAt = now()`, and saves. `parseJoiningDate` tries ISO format first (`"2026-07-01"`), then
  falls back to epoch-day numeric format, and if neither works, logs a warning and leaves the field
  `null` rather than failing the whole event over one unparseable date.
- **`handleDelete`** (public, `@Transactional`, also called directly by `CdcRetryServiceImpl`): finds
  the row by `employeeUuid` and deletes it if present; if it's already gone, logs a warning and does
  nothing — deleting something that doesn't exist is not an error condition here.
- **A "gotcha" worth knowing**: the `ObjectMapper` is a plain field initializer
  (`new ObjectMapper().findAndRegisterModules()`), **not** constructor-injected via Spring, with an
  explicit comment explaining why: this Spring Boot version's auto-configuration registers a
  `tools.jackson.databind.ObjectMapper` bean (**Jackson 3**), not the classic
  `com.fasterxml.jackson.databind.ObjectMapper` (**Jackson 2**) type this class and every DTO in the
  codebase actually use — so there is no bean of the right type to inject. `CdcRetryServiceImpl` has
  the identical workaround, for the identical reason.
- **Failure scenarios and recovery**: covered in depth by §6.5/§6.6 above — every failure path is a
  `CdcFailureLog` row plus an acknowledgment, and recovery is `CdcRetryScheduler`'s job, not this
  class's.

### 7.5 `CdcFailureLog` (entity) + `CdcFailureLogRepository`
`src/main/java/com/expense_management_service/entity/CdcFailureLog.java`,
`src/main/java/com/expense_management_service/repository/CdcFailureLogRepository.java`

- **Purpose**: a durable, queryable "dead-letter" record for any CDC event that could not be
  processed — the safety net that makes this pipeline resilient rather than lossy.
- **Fields worth knowing**: `sourceTopic`, `employeeId`/`employeeUuid` (populated when parseable —
  may be `null` for a totally malformed payload), `operation` (`"c"`/`"u"`/`"d"`/`"unknown"`),
  `failureType` (one of `PARSE_FAILED`, `VALIDATION_FAILED`, `UPSERT_FAILED`, `DELETE_FAILED`),
  `errorMessage` and `rawPayload` (both `@Lob` with an **explicit** `columnDefinition = "LONGTEXT"`),
  `status` (`FAILED`/`RETRYING`/`RESOLVED`/`EXHAUSTED`), `retryCount`/`maxRetries` (default 3),
  `kafkaPartition`/`kafkaOffset` (for tracing back to the exact original message).
- **A real bug this fixes, documented in the code**: `@Lob` alone maps to MySQL `TINYTEXT` (a
  255-byte max, inherited from JPA's default `@Column(length=255)`) unless told otherwise — this was
  **discovered by actually running the app against real MySQL**, where a normal-sized real payload
  triggered `Data too long for column 'raw_payload'`. The explicit `columnDefinition` fixes it. This
  is a good concrete lesson: `@Lob` is not automatically "unlimited size" in every dialect.
- **Connects to**: written by `CdcFailureLogServiceImpl.logFailure(...)`; read and updated by the
  same service's `findRetryable()`, `markRetrySucceeded(...)`, `markRetryFailed(...)`.

### 7.6 `CdcFailureLogService` / `CdcFailureLogServiceImpl`
`src/main/java/com/expense_management_service/service/CdcFailureLogService.java`,
`src/main/java/com/expense_management_service/service/impl/CdcFailureLogServiceImpl.java`

- **Purpose**: the only way anything writes to or updates `CdcFailureLog` — encapsulates the status
  state machine.
- **Status state machine**: `FAILED` (just recorded) → `RETRYING` (at least one retry attempted, still
  failing) → either `RESOLVED` (a retry succeeded) or `EXHAUSTED` (hit `maxRetries`, currently 3, with
  no further automatic retries).
- **`findRetryable()`**: returns every row with status in `(FAILED, RETRYING)` and `retryCount <
  maxRetries` — i.e., exactly the rows `CdcRetryService` should attempt next.
- **Dependencies**: `CdcFailureLogRepository` only. Notably **does not** depend on
  `EmployeeCdcConsumer` — see §7.7 for why that separation matters.

### 7.7 `CdcRetryService` / `CdcRetryServiceImpl`
`src/main/java/com/expense_management_service/service/CdcRetryService.java`,
`src/main/java/com/expense_management_service/service/impl/CdcRetryServiceImpl.java`

- **Purpose**: replays every currently-retryable `CdcFailureLog` row, directly through the same
  business logic the original Kafka message would have triggered — **without going back through
  Kafka at all**.
- **Why it's a separate class from `CdcFailureLogService`** (stated directly in its own Javadoc):
  `EmployeeCdcConsumer` depends on `CdcFailureLogService` to record failures. If
  `CdcFailureLogService` also depended on `EmployeeCdcConsumer` to replay them, that would be a
  circular bean dependency — Spring cannot construct two beans that each require the other to exist
  first. `CdcRetryServiceImpl` depends on *both* one-directionally, sitting above them, so the cycle
  never forms.
- **Typical flow** (`retryFailedEvents()`): fetch every retryable row → for each one, re-parse its
  stored `rawPayload` back into an `EmployeeCdcEvent` and call `employeeCdcConsumer.handleUpsert(...)`
  or `.handleDelete(...)` directly (the same public methods the Kafka listener itself calls) → on
  success, `markRetrySucceeded` (status → `RESOLVED`); on failure, `markRetryFailed` (increments
  `retryCount`, status → `RETRYING` or `EXHAUSTED` if the limit is now reached) → returns a
  `CdcRetryResponse` summarizing `attempted`/`succeeded`/`failed` counts and a human-readable note.
- **A subtlety**: if `rawPayload` itself is missing or blank on a given row, retrying throws
  immediately with `"No raw payload stored for this failure, cannot replay"` — meaning it's
  important the original failure was captured *with* its payload (which `EmployeeCdcConsumer` always
  does).
- **Same `ObjectMapper` workaround as §7.4**, for the identical Jackson 2 vs. 3 reason.

### 7.8 `CdcRetryScheduler`
`src/main/java/com/expense_management_service/scheduler/CdcRetryScheduler.java`

- **Purpose**: the trigger. Owns nothing but *when* retries happen; all actual logic lives in
  `CdcRetryService`.
- **Configuration**: `@Scheduled(cron = "${cdc.retry.cron:0 */10 * * * *}")` — every 10 minutes by
  default, matching the cadence the sibling LMS implementation uses.
- **A deliberate constraint, stated in its own Javadoc**: runs as `SYSTEM` — it must **never** depend
  on `CurrentUserService` or `UmsClient`, both of which require a request-bound `SecurityContext`
  that simply does not exist on a scheduler thread (there's no HTTP request, no logged-in user, no
  JWT to read roles from). This is a general pattern worth remembering for any scheduled job in this
  codebase, not just this one.
- **Failure behaviour**: if `retryFailedEvents()` itself throws (which it's designed not to, since
  each individual retry is caught internally), Spring's scheduler would simply log the exception and
  try again at the next scheduled run — it does not crash the application.

---

## Part 8 — Execution Flow: One Record, Start to Finish

Let's trace **one specific, concrete example** through the entire system: an HR admin in EOS marks
employee Asha Verma (`employee_id = "5100101"`) as `On-Notice`.

### Stage 1 — the database record changes

An UPDATE statement runs against EOS's MySQL:
```sql
UPDATE employee_details SET employment_status = 'On-Notice' WHERE employee_uuid = '3f1b2c4d-...-01';
```
Internally, because the RDS parameter group has `binlog_format=ROW` and `binlog_row_image=FULL`,
MySQL writes a binlog event containing the **full before-and-after row image** — not just the SQL
text, the actual old and new column values.

### Stage 2 — CDC detects the change

The Debezium MySQL connector, running inside Kafka Connect and authenticated as the `debezium_eos`
replication user, is continuously tailing this binlog (exactly as a real MySQL replica would). It
sees this new binlog event, applies its configured `ExtractNewRecordState` SMT (single message
transform) to flatten the payload, and determines the message's key using
`message.key.columns: eos.employee_details:employee_uuid` → key = `3f1b2c4d-...-01`.

### Stage 3 — Kafka receives the event

Debezium (the **producer**) publishes one message to topic `eos_dev.eos.employee_details`:

```json
{
  "employee_uuid": "3f1b2c4d-0000-0000-0000-000000000001",
  "employee_id": "5100101",
  "employment_status": "On-Notice",
  "...other unchanged fields...": "...",
  "__op": "u",
  "__ts_ms": 1753600100000,
  "__deleted": "false"
}
```
Redpanda (the broker) hashes the key `3f1b2c4d-...` to a partition and appends this message at the
next available **offset** in that partition. Nothing is "sent" to our consumer yet — it's just
stored, durably, waiting to be read.

### Stage 4 — downstream consumer processes it

Our Spring Boot app's Kafka client, subscribed as consumer group `xms-employee-consumer`, polls that
partition and receives this record. `EmployeeCdcConsumer.consume(record, ack)` runs:

1. `record.value()` is not null → not a tombstone, continue.
2. No BOM present → no stripping needed.
3. Parses successfully into an `EmployeeCdcEvent` (op="u", deleted="false", employmentStatus="On-Notice", ...).
4. `employeeUuid` is present → validation passes.
5. `event.isDelete()` → `false` (neither `__deleted="true"` nor `op="d"`) → dispatches to
   `handleUpsert(event)`.
6. `handleUpsert`: looks up `EmployeeCache` by `employeeUuid` → finds Asha's existing row → updates
   every field, including `employmentStatus = "On-Notice"` → sets `syncedAt = now()` → saves.
7. Logs: `Upserted employee cache row: employeeId=5100101 employeeUuid=3f1b2c4d-... status=On-Notice`.
8. `ack.acknowledge()` is called — the consumer group's offset advances past this message.

### Stage 5 — the final outcome

`EmployeeCache`'s row for Asha now shows `employment_status = "On-Notice"`, `synced_at` updated to
the current timestamp. From this point forward, if Asha's expense report needs manager-level
approval, `DefaultApproverResolverImpl` reading `EmployeeCache.findByEmployeeId("5100101")` will see
this current status — entirely without ever contacting EOS directly, and within seconds of the
original change, not the next time some batch job happens to run.

### What if something had gone wrong at Stage 4?

Suppose the database write in step 6 threw a transient connection error. The `try/catch` in
`consume(...)` catches it, logs it, calls
`cdcFailureLogService.logFailure(topic, "5100101", "3f1b2c4d-...", "u", "UPSERT_FAILED", <error message>, <raw JSON>, partition, offset)`
— which creates a `CdcFailureLog` row with `status=FAILED`, `retryCount=0` — and **then** still
calls `ack.acknowledge()`. The message is not lost: within 10 minutes, `CdcRetryScheduler` fires,
`CdcRetryService.retryFailedEvents()` finds this row (status `FAILED`, `retryCount 0 < maxRetries 3`),
re-parses its stored `rawPayload`, and calls `employeeCdcConsumer.handleUpsert(...)` directly. If
that succeeds this time, the row moves to `RESOLVED`. If it fails again, `retryCount` becomes 1 and
status becomes `RETRYING`, tried again at the next scheduled run, up to `maxRetries` total attempts.

---

## Part 9 — Code Mapping

**Start here if you're new to this code**, in this order:

1. **`docs/cdc/README.md`** — the local-dev walkthrough; run it once, hands-on, before reading any
   Java. Seeing real messages flow through a real (local) broker will make everything below click.
2. **`EmployeeCdcEvent.java`** — the simplest file; shows you the exact shape of a message.
3. **`EmployeeCdcConsumer.java`** — the core logic; read `consume(...)` top to bottom.
4. **`EmployeeCache.java`** — what the consumer is ultimately keeping in sync, and why some EOS
   fields are deliberately *not* mirrored here.
5. **`CdcFailureLog.java`** + **`CdcFailureLogServiceImpl.java`** — the safety net.
6. **`CdcRetryServiceImpl.java`** + **`CdcRetryScheduler.java`** — how the safety net gets emptied
   back out over time.
7. **`KafkaConfig.java`** — the plumbing that makes all of the above actually run; read this last,
   since it's meaningless without the context of what it's wiring up.

### Responsibility → file map

| Responsibility | File |
|---|---|
| Kafka connection/deserialization wiring | `config/KafkaConfig.java` |
| The Kafka message's shape | `dto/external/EmployeeCdcEvent.java` |
| Consuming messages, applying/quarantining changes | `consumer/EmployeeCdcConsumer.java` |
| The local employee/manager mirror table | `entity/EmployeeCache.java`, `repository/EmployeeCacheRepository.java` |
| Dead-letter record of failed events | `entity/CdcFailureLog.java`, `repository/CdcFailureLogRepository.java` |
| Writing/querying/updating failure records | `service/CdcFailureLogService.java` + `impl/CdcFailureLogServiceImpl.java` |
| Replaying failed events | `service/CdcRetryService.java` + `impl/CdcRetryServiceImpl.java`, `dto/response/CdcRetryResponse.java` |
| Scheduling the replay | `scheduler/CdcRetryScheduler.java` |
| Downstream consumer of `EmployeeCache` (a different feature) | `service/impl/DefaultApproverResolverImpl.java` |
| Local broker for development | `docker-compose.cdc.yml` |
| Local-dev walkthrough + sample payloads | `docs/cdc/README.md` |
| Production connector configuration | `docs/cdc/debezium-connector-eos-dev.json` |
| Production deployment handoff | `docs/cdc/RUNBOOK.md` |
| All CDC-related runtime configuration | `src/main/resources/application.properties` (section `Employee CDC Pipeline (EP06 Phase 0)`) |

### Configuration files that matter

`application.properties`, the `Employee CDC Pipeline` section:
```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:19092}
spring.kafka.consumer.group-id=xms-employee-consumer
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.listener.ack-mode=manual

cdc.employee.topic=${CDC_EMPLOYEE_TOPIC:eos_dev.eos.employee_details}
cdc.retry.cron=0 */10 * * * *
```

> **A subtlety worth knowing**: `KafkaConfig.java` actually **programmatically constructs** its own
> `ConsumerFactory` props map (reading only `spring.kafka.bootstrap-servers` and
> `spring.kafka.consumer.group-id` via `@Value`, then hardcoding `auto-offset-reset=earliest` and
> `enable-auto-commit=false` directly in Java) and its own `ConcurrentKafkaListenerContainerFactory`
> with `AckMode.MANUAL` set explicitly in code. This means the `spring.kafka.consumer.auto-offset-
> reset`, `spring.kafka.consumer.enable-auto-commit`, and `spring.kafka.listener.ack-mode` properties
> in `application.properties` are **documentation of intent** — the actual enforced values come from
> `KafkaConfig.java`'s custom beans, which happen to match. If you ever needed to change this
> consumer's offset-reset or ack-mode behaviour, **you'd have to change `KafkaConfig.java`, not just
> the properties file** — editing the property alone would silently do nothing, since Spring Boot's
> autoconfigured `KafkaProperties` binding is bypassed entirely by these custom `@Bean` definitions.

---

## Part 10 — Running the Project

### 10.1 Running everything locally, step by step

```powershell
# 1. Start the broker + console
docker compose -f docker-compose.cdc.yml up -d

# 2. Wait for health
docker compose -f docker-compose.cdc.yml ps    # look for "healthy" on redpanda

# 3. Create the topic
docker exec xms-cdc-redpanda rpk topic create eos_dev.eos.employee_details --brokers localhost:29092

# 4. Start the Spring Boot app (in a separate terminal)
./mvnw spring-boot:run
```

### 10.2 Running individual components

- **Just the broker, without the app** — useful for producing test messages ahead of time and
  inspecting them later: `docker compose -f docker-compose.cdc.yml up -d redpanda redpanda-console`.
- **Just the unit tests for the consumer**, without any broker at all (these are pure Mockito tests,
  no real Kafka needed):
  ```powershell
  ./mvnw test "-Dtest=EmployeeCdcConsumerTest"
  ./mvnw test "-Dtest=CdcFailureLogServiceImplTest"
  ```

### 10.3 Testing CDC — producing a message

Via Redpanda Console (easiest): open http://localhost:8090 → **Topics** →
`eos_dev.eos.employee_details` → **Produce Message** → paste a JSON body (see §5.3) as the value,
use the `employee_uuid` as the key → Produce.

Via `rpk` from PowerShell:
```powershell
$body = @'
{"employee_uuid":"3f1b2c4d-0000-0000-0000-000000000001","employee_id":"5100101","first_name":"Asha","employment_status":"Active","__op":"c","__deleted":"false"}
'@
$body | docker exec -i xms-cdc-redpanda rpk topic produce eos_dev.eos.employee_details --brokers localhost:29092 --key 3f1b2c4d-0000-0000-0000-000000000001
```

### 10.4 Consuming messages manually (outside of the app)

To watch raw messages arrive on the topic, independent of our Spring app entirely — useful for
confirming *what Debezium (or your hand-crafted test message) actually put on the topic*, before
even worrying about whether our consumer processed it correctly:

```powershell
docker exec xms-cdc-redpanda rpk topic consume eos_dev.eos.employee_details --brokers localhost:29092
```

This prints every message currently on the topic (and streams new ones), showing key, value, offset,
and partition — a completely independent viewpoint from our application's logs.

### 10.5 Verifying the complete pipeline

1. Produce a "create" event (Scenario A from §5.3).
2. Check the app's console log for `Upserted employee cache row: ...`.
3. Query `SELECT * FROM employee_cache WHERE employee_uuid = '...';` — row should exist.
4. Produce an "update" event with the same key, different `employment_status`.
5. Confirm the *same* row updated (not a duplicate row) and `synced_at` moved forward.
6. Produce a malformed payload (Scenario E).
7. Confirm the app still acknowledges (no crash, no stuck consumer) and
   `SELECT * FROM cdc_failure_log ORDER BY created_at DESC;` shows a new `PARSE_FAILED` row.
8. (Optional, takes up to 10 minutes) Wait for `CdcRetryScheduler` to fire and watch its log line;
   since the malformed payload can never successfully parse, expect it to move toward `EXHAUSTED`
   after 3 attempts — this is the *expected*, correct behaviour for truly unfixable garbage input.

---

## Part 11 — Debugging & Monitoring

### 11.1 Debugging CDC issues — a decision tree

```
Is the message visible on the Kafka topic at all?
 (check via Redpanda Console, or `rpk topic consume ...`)
        │
        ├── NO  → the problem is upstream of our app:
        │         - Is the producer (Debezium, or your manual `rpk produce`) actually succeeding?
        │         - Right topic name? (check for typos in the eos_dev prefix)
        │         - Right broker address?
        │
        └── YES → the message IS on the topic. Is our consumer group's lag draining?
                  (Redpanda Console shows per-group lag per partition)
                        │
                        ├── Lag stuck, not decreasing → app isn't running, isn't connected,
                        │    or @KafkaListener never got wired (check @EnableKafka is present)
                        │
                        └── Lag draining (message WAS consumed) → check application logs for
                             "Upserted employee cache row" / a CdcFailureLog error line
                                  │
                                  ├── Success logged → check EmployeeCache table directly
                                  │
                                  └── Failure logged → SELECT * FROM cdc_failure_log
                                       ORDER BY created_at DESC; — read failureType + errorMessage
```

### 11.2 Inspecting Kafka topics and viewing messages

- **Redpanda Console** (http://localhost:8090 locally) — browse topics, inspect individual messages
  (key, value, headers, offset, partition, timestamp), see consumer group lag, and produce test
  messages by hand. This is the single most useful tool for this pipeline during development.
- **`rpk` CLI** (already used throughout this guide) — scriptable, good for quick checks or
  automation:
  ```powershell
  docker exec xms-cdc-redpanda rpk topic list --brokers localhost:29092
  docker exec xms-cdc-redpanda rpk topic describe eos_dev.eos.employee_details --brokers localhost:29092
  docker exec xms-cdc-redpanda rpk group describe xms-employee-consumer --brokers localhost:29092
  ```

### 11.3 Tracing an event's full journey

Every `CdcFailureLog` row stores `kafkaPartition` and `kafkaOffset` — the exact coordinates of the
original message — plus the full `rawPayload`. So even long after the fact, you can answer "what
exactly did Kafka deliver, and where was it in the stream?" without needing to still have that
message available on the (possibly already-rotated) topic.

### 11.4 Monitoring system health

What to actually watch, in priority order:

1. **Consumer group lag** for `xms-employee-consumer` — the single best signal that the consumer is
   keeping up. Growing lag means either the consumer is down, or messages are arriving faster than
   they're being processed.
2. **`cdc_failure_log` row count by status**, especially `EXHAUSTED` — these are events that will
   **never** self-heal via the scheduled retry; they represent silent data drift (some employee's
   cached data is now permanently stale until someone manually investigates) if left unaddressed.
   ```sql
   SELECT status, COUNT(*) FROM cdc_failure_log GROUP BY status;
   ```
3. **Application logs** for `CdcRetryScheduler`'s own summary line, logged every run:
   `Scheduled CDC failure retry completed: CdcRetryResponse[attempted=..., succeeded=..., failed=...]`.
4. **Kafka Connect's connector status** (production only) —
   `GET http://<connect-host>:8083/connectors/eos-employee-connector-dev/status` — confirms Debezium
   itself is actually running and not stuck in a `FAILED` task state.

### 11.5 Identifying where a failure occurred

Because every failure path in `EmployeeCdcConsumer` tags a specific `failureType`
(`PARSE_FAILED`/`VALIDATION_FAILED`/`UPSERT_FAILED`/`DELETE_FAILED`), you can immediately narrow down
*which stage* broke without reading a single stack trace first:

| `failureType` | What it tells you |
|---|---|
| `PARSE_FAILED` | The raw text wasn't valid JSON, or didn't match `EmployeeCdcEvent`'s shape at all — check the producer side (or your hand-crafted test payload) |
| `VALIDATION_FAILED` | It parsed fine, but had no usable `employee_uuid` — check what the source event actually looked like in `rawPayload` |
| `UPSERT_FAILED` | Parsing and validation were fine; the database write itself threw — check `errorMessage` for the underlying DB exception |
| `DELETE_FAILED` | Same as above, but for a delete-branch failure |

---

## Part 12 — Learning Roadmap

Recommended order, each level assuming you've genuinely internalized (not just skimmed) the one
before it.

### Level 1 — Beginner: concepts, no code yet
- [ ] Explain, in your own words, what CDC is and why polling/dual-writes are worse alternatives (Part 1).
- [ ] Explain broker, topic, partition, producer, consumer, consumer group, offset — from memory,
      using the analogies in Part 2, not the technical definitions.
- [ ] Explain why "reading a message doesn't delete it" is the single biggest thing that makes Kafka
      different from a task queue.
- **You're ready for Level 2 when**: you can draw the high-level diagram in §4.1 from memory, labeling
  every arrow with what actually flows across it.

### Level 2 — Intermediate: this project's local pipeline
- [ ] Actually run the local stack (Part 10.1) and walk through all five scenarios in `docs/cdc/README.md`.
- [ ] Read `EmployeeCdcConsumer.java` top to bottom and narrate out loud what happens at each `if`
      branch, matching it against §7.4.
- [ ] Deliberately break something (produce malformed JSON, stop the app mid-processing) and observe
      the recovery behaviour firsthand rather than just reading about it.
- **You're ready for Level 3 when**: you can explain, without looking anything up, why the app still
  acknowledges a message even when processing it fails, and why that's *correct*, not a bug.

### Level 3 — Advanced: reliability, ordering, and production
- [ ] Explain exactly why keying messages by `employee_uuid` is what guarantees per-employee
      ordering, independent of however many partitions the topic has.
- [ ] Explain the full failure-and-retry lifecycle end to end: Kafka's own "at least once" +
      manual-ack layer, *and* the separate application-level `CdcFailureLog`/`CdcRetryScheduler`
      layer — and why both exist rather than just one.
- [ ] Read `docs/cdc/RUNBOOK.md` and be able to explain what changes (and what doesn't) between local
      dev and production, without re-reading Part 6.
- [ ] Understand the two Jackson-version and circular-dependency "gotchas" (§7.4, §7.7) well enough
      to explain *why* each workaround exists, not just that it does.
- **You're ready to call yourself confident when**: you can explain this entire pipeline, unprompted,
  to another developer — starting from "why does EMS need employee data at all" and ending at "and
  that's why `CdcRetryService` is a separate class from `CdcFailureLogService`" — without needing to
  open this document.

---

## Appendix A — Common Mistakes

- **Assuming Kafka guarantees topic-wide ordering.** It only guarantees ordering *within a
  partition*. Ordering across an entire topic (with multiple partitions) is not a thing Kafka
  promises — you get per-key ordering via the message key, not per-topic ordering.
- **Treating "acknowledged" as "successfully processed."** In this pipeline, a message is
  acknowledged after either success *or* a recorded failure — acknowledgment means "Kafka doesn't
  need to redeliver this," not "this definitely worked." Check `CdcFailureLog`, not just whether the
  offset advanced.
- **Assuming a consumer restart replays everything from scratch.** Only true the *first* time a
  consumer group ever runs (thanks to `auto-offset-reset=earliest` with no prior committed offset).
  After that, a restart resumes from the last committed offset, not from the beginning.
- **Forgetting `@EnableKafka`.** As documented directly in this project's `KafkaConfig`: omitting it
  causes `@KafkaListener` methods to be silently never wired up — no error, no log, just a consumer
  that never runs.
- **Writing a non-idempotent consumer.** Because Kafka's manual-ack model is "at least once," a
  consumer that isn't safe to run twice on the same message (e.g., one that always does a raw
  `INSERT` instead of an upsert) will eventually double-process something after a crash-and-restart.
- **Assuming `@Lob` means "unlimited length" in every database.** As this project's own code
  discovered the hard way: `@Lob` alone can map to a tiny `TINYTEXT` in MySQL unless you specify
  `columnDefinition` explicitly.
- **Confusing a true Kafka tombstone (null value) with a Debezium rewrite-mode delete (a value with
  `__deleted="true"`).** They require different handling, and this project's consumer explicitly
  distinguishes them.

---

## Appendix B — Interview Questions

Test yourself — try to answer before checking which part of this guide covers it.

1. What problem does CDC solve that simple polling cannot? *(Part 1.2)*
2. Why does this project use log-based CDC (via Debezium reading MySQL's binlog) instead of a
   trigger-based approach? *(Part 1.3–1.4)*
3. What's the difference between a Kafka **topic** and a **partition**? Why does that distinction
   matter for message ordering? *(Part 2.3)*
4. If a topic has 3 partitions and 1 consumer in a consumer group, what happens? What if you add 2
   more consumers to that same group? *(Part 2.3, Consumer Group)*
5. What does "at least once" delivery mean, and why does that require the consumer logic itself to
   be idempotent? Give the concrete example from this project. *(Part 2.5)*
6. Why is `message.key.columns` set to `employee_uuid` in the Debezium connector config, and what
   would break if messages for the same employee weren't consistently keyed? *(Part 2.3, Partition)*
7. Explain the difference between a true Kafka tombstone and a Debezium rewrite-mode delete. Why
   does `EmployeeCdcConsumer` handle them differently? *(Part 7.4)*
8. Why is `CdcRetryService` a separate class from `CdcFailureLogService`, instead of just adding a
   retry method to the failure-log service directly? *(Part 4.5, Part 7.7)*
9. What is the practical difference in this codebase between "Kafka's own retry/redelivery
   mechanism" and "the `CdcFailureLog` + `CdcRetryScheduler` mechanism"? Why does this project need
   both? *(Part 6.5)*
10. Why does `auto-offset-reset=earliest` combined with Debezium's `snapshot.mode: initial` mean the
    very first run of this consumer fully populates `EmployeeCache` with no separate backfill job?
    *(Part 2.3, Offset)*
11. What real, specific bugs in the sibling LMS implementation does this project's
    `EmployeeCdcConsumer` deliberately avoid, and how? *(Part 7.4, Part 6.5)*
12. Why does `@Lob` alone not guarantee enough column size in MySQL, and how was that discovered in
    this project? *(Part 7.5)*
13. What actually changes, environment-variable-wise, between running this pipeline locally and in
    production? What stays byte-for-byte identical? *(Part 6.1)*
14. Why is `@EnableKafka` written explicitly in `KafkaConfig` instead of relying on autoconfiguration,
    and what's the specific failure mode if it were missing? *(Part 7.1)*
15. Why must `CdcRetryScheduler` never depend on `CurrentUserService` or a JWT-derived security
    context? *(Part 7.8)*

---

## Appendix C — Glossary

| Term | Meaning |
|---|---|
| **CDC (Change Data Capture)** | Detecting and streaming database changes as events, instead of only ever querying current state |
| **Binlog** | MySQL's internal, append-only log of every row-level change, originally built for replication — what Debezium reads |
| **Debezium** | An open-source CDC tool that reads a database's change log (e.g., MySQL's binlog) and publishes each change as a Kafka message |
| **Kafka Connect** | The runtime that hosts and manages connectors like Debezium's MySQL connector |
| **Broker** | A single Kafka (or Kafka-protocol-compatible) server that stores and serves messages |
| **Redpanda** | A Kafka-protocol-compatible broker implementation; used as the actual broker in this project, both locally and in production |
| **Topic** | A named, ordered stream of messages |
| **Partition** | A topic's physical subdivision; Kafka guarantees ordering only within one partition |
| **Producer** | Anything that publishes messages to a topic (here: Debezium) |
| **Consumer** | Anything that reads messages from a topic (here: `EmployeeCdcConsumer`) |
| **Consumer group** | A named set of consumers sharing the work of reading a topic; each partition is read by exactly one member at a time |
| **Offset** | A message's position within a partition; also, a consumer group's "bookmark" of how far it has confirmed processing |
| **Replication (Kafka)** | Copying a partition's data across multiple brokers for durability |
| **Idempotency** | The property that processing the same input multiple times produces the same result as processing it once — required for safe "at least once" delivery |
| **SMT (Single Message Transform)** | A small, configurable transformation Debezium/Kafka Connect applies to each message before publishing (here: `ExtractNewRecordState`, which flattens the payload) |
| **Snapshot mode (`initial`)** | Debezium publishing a synthetic "create" event for every existing row on first startup, before switching to live streaming |
| **Tombstone** | A Kafka message with a `null` value, conventionally meaning "this key's data is gone" |
| **Rewrite-mode delete** | Debezium's alternative delete representation: a message with a real value carrying `__deleted="true"`, instead of (or in addition to) a null-value tombstone |
| **Dead-letter record** | A durable record of a message that could not be processed, so it can be inspected/retried later instead of being silently lost — this project's `CdcFailureLog` |
| **Manual acknowledgment** | Committing a consumer's offset explicitly in application code, rather than automatically as soon as a message is handed to it |
