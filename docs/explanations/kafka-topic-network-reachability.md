# Topic Name — What It Actually Is, and How Everything Connects

You said you didn't get how everything is working — that's completely fair, we covered a lot of
ground across several different (and sometimes contradictory) explanations. This document starts
from zero, uses only what we've now *confirmed to be true* by actually reading the real
infrastructure code (`intranet-devops`), and builds up slowly. No assumptions this time — every
claim below points at a real file.

---

## TL;DR — read this paragraph first, then everything else will click

A **topic name** is just a label — like the name written on a folder. Knowing the folder's name
doesn't help you if you're not in the building that has the filing cabinet. Right now, the "filing
cabinet" (Kafka) lives **inside a private Kubernetes cluster network**, and it is only reachable by
things running *inside* that same cluster. Your EMS app, running on your laptop, is **outside** that
building entirely. LMS gets its data because LMS is a pod running *inside* that same cluster — not
because it knows some magic address you don't. That's the whole confusion, resolved in one sentence.
Everything below just explains that sentence in full detail.

---

## 1. What is a "topic name," really?

Forget Kafka for a second. Imagine a company mailroom with hundreds of numbered pigeonholes.
Pigeonhole `#42` is labeled **"Employee Updates."** That label — **"Employee Updates"** — is like a
Kafka **topic name**. It's just a name written on a slot. Anyone who walks up to that pigeonhole and
knows the name can put mail in it or take mail out of it.

**But notice something important**: knowing the name "Employee Updates" is useless if you're not
physically standing in the mailroom. You need to first **get into the building**, find the
mailroom, and *then* the label tells you which slot to use.

That's the entire distinction we kept circling back to:
- **Topic name** = which slot (a label).
- **Network access to the broker** = being allowed into the building at all.

You need **both**. Having just one is not enough. This document is mostly about the second one,
because that's the part that was actually missing.

---

## 2. The exact topic name in our real system — broken into pieces

We found the *real*, currently-running connector configuration in
`intranet-devops/k8s/infra/debezium/connector-job.yaml`. It registers a Debezium connector with:

```json
"topic.prefix": "eos_test",
"database.include.list": "eos",
"table.include.list": "eos.employee_details"
```

Debezium builds the topic name automatically from these three pieces, using the pattern
`<topic.prefix>.<database>.<table>`:

```
   eos_test    .    eos    .   employee_details
   └────┬────┘    └──┬──┘   └───────┬────────┘
   the connector's    the MySQL      the MySQL
   own "namespace"    schema/         table
   (so two different  database        name
   connectors don't    name
   collide)
```

So the real, full topic name today is:

```
eos_test.eos.employee_details
```

That's it. That's *all* a topic name is — a generated label, built from three config values.
Nothing about it tells you *where* the broker lives or *how* to reach it.

---

## 3. Why "topic name" alone was never going to be enough

Think back to the mailroom analogy. If someone tells you "your package is in pigeonhole
`eos_test.eos.employee_details`," your very next question has to be **"okay, but where is the
mailroom, and am I allowed inside?"** That's a completely separate question from the label itself.

In Kafka terms, "where is the mailroom, and am I allowed inside" translates to:
1. What is the broker's network address? (this is called `bootstrap.servers` in every Kafka client)
2. Can my machine actually route a network connection to that address at all?
3. Does the broker require a login (authentication) before it lets me in?

**All three of those are completely independent of the topic name.** You could have the perfect
topic name and still get nowhere, if the answer to #2 is "no."

---

## 4. The real infrastructure — what we found by actually reading `intranet-devops`

This is the part that changes everything. Here's exactly what's real, with file paths so you can
verify it yourself:

### Everything lives inside ONE Kubernetes cluster

```
intranet-devops/k8s/namespaces/namespaces.yaml  defines 3 namespaces:
  - backend   (application services: eos, lms, tms, pms, rms, ums)
  - infra     (kafka, debezium, kafka-ui, redis, monitoring)
  - argocd    (the deployment tool itself)
```

A **namespace** is just a way of grouping things inside the *same* cluster — like different floors
of the *same* office building. Different floors, same building, same front door, same internal
hallways connecting them.

### Kafka is on the "infra" floor, and its door only opens from inside the building

`intranet-devops/k8s/infra/kafka/kafka.yaml`:

```yaml
kind: Service
metadata:
  name: kafka-service
  namespace: infra
spec:
  type: ClusterIP        # ← this is the whole story right here
```

`ClusterIP` is Kubernetes' most restrictive service type. It means: **this address only exists, and
only works, for things already running inside the cluster.** There is no public door. No one on the
public internet — not you, not anyone — can dial this address directly. It's not a security setting
someone forgot to open; it's the default, deliberate choice for anything that shouldn't be public.

Even the broker's own self-reported address confirms this:

```yaml
- name: KAFKA_ADVERTISED_LISTENERS
  value: "PLAINTEXT://kafka-service.infra.svc.cluster.local:9092"
```

That hostname, `kafka-service.infra.svc.cluster.local`, is **Kubernetes-internal DNS**. It's
automatically created and only resolvable *by things running inside the cluster*. Your laptop's
internet connection has never heard of it and never will, no matter what you type — it's not a
"real" internet domain name at all, the same way "the third drawer on the left" only means anything
if you're already standing in front of the right filing cabinet.

### Debezium lives on the same floor, and does the actual watching-and-publishing

`intranet-devops/k8s/infra/debezium/debezium.yaml` — this is the piece that:
1. Connects to EOS's real database (AWS RDS MySQL) using credentials pulled from AWS Secrets
   Manager (`debezium-external-secret.yaml`).
2. Watches for row changes (the binlog — MySQL's built-in changelog).
3. Publishes each change as a message into Kafka, onto the topic name we decoded in §2.

It reaches Kafka the exact same way anything else "inside the building" does:
```yaml
- name: BOOTSTRAP_SERVERS
  value: "kafka-service.infra.svc.cluster.local:9092"
```
Same internal-only address. Debezium is also just a tenant of this building, one floor over.

### LMS lives on a different floor of the *same* building

`intranet-devops/k8s/backend/lms/deployment.yaml` — LMS is a `Deployment` running in the `backend`
namespace. Different floor (namespace) from Kafka's `infra` floor — but **the same building** (the
same Kubernetes cluster). Kubernetes lets anything on any floor reach anything on any other floor
using that same internal DNS naming, across namespace boundaries, without ever leaving the building.

**This is the entire answer to "how does LMS get its data."** LMS isn't doing anything clever. It's
not tunneling, not using a VPN, not calling some public API. It's simply *also inside the building*,
so the internal-only address works perfectly fine for it — the same way two employees on different
floors of one office can walk to each other's desks without ever stepping outside.

### Nothing Kafka-related has a public front door

`intranet-devops/k8s/ingress/ingress.yaml` is the **only** file in this entire repo that defines a
public-facing door (an "Ingress" = the front entrance of the building, the only place the public
internet is allowed to walk in). It lists exactly six paths:

```
/ums   /tms   /lms   /pms   /rms   /ems
```

Every single one of those is an **application HTTP API** (like "check my leave balance" or "submit
an expense report"). **Kafka, Debezium, and even Kafka-UI (the tool built specifically so a human
can browse Kafka topics) are not on this list at all.** There is currently no front door for any of
them — not because it was forgotten, but because internal infrastructure like a message broker
normally has no business being reachable from the public internet.

---

## 5. Side-by-side: LMS vs. your local EMS, right now

```
                         ONE Kubernetes cluster ("the building")
   ┌───────────────────────────────────────────────────────────────────┐
   │  floor: infra                                                       │
   │  ┌─────────┐   ┌───────────┐   ┌───────────┐                        │
   │  │  kafka   │◄──┤ debezium   │   │ kafka-ui   │   all ClusterIP —      │
   │  └────┬────┘   └───────────┘   └───────────┘   internal-only doors   │
   │       │  internal-only hallway (kafka-service.infra.svc.cluster.local)│
   ├───────┼───────────────────────────────────────────────────────────────┤
   │  floor: backend                                                       │
   │       │                                                              │
   │  ┌────▼────┐   ┌──────────┐                                          │
   │  │   eos    │   │   lms     │  ← LMS is INSIDE the building,          │
   │  └─────────┘   └──────────┘     just a different floor. It can walk   │
   │                                  the internal hallway straight to     │
   │                                  Kafka's door.                        │
   └───────────────────────────────────────────────────────────────────────┘
                              │
                    (the ONLY public door)
                    /ums /tms /lms /pms /rms /ems
                              │
                              ▼
                    ┌───────────────────┐
                    │   Your laptop       │   ← standing OUTSIDE the
                    │   running EMS        │      building entirely.
                    │   locally             │      Knowing Kafka's topic
                    │                       │      name changes nothing —
                    └───────────────────────┘      you're not even in the
                                                     building.
```

---

## 6. Full step-by-step: one real change, start to finish

Let's say an HR admin updates an employee's status in EOS.

1. **The write happens** in AWS RDS MySQL (EOS's real database).
2. **MySQL logs it** in its binlog — a built-in change-log every MySQL server keeps.
3. **Debezium** (running on the `infra` floor) is continuously watching that binlog. It sees the
   change and builds a message out of it.
4. **Debezium publishes** that message to Kafka, onto the topic `eos_test.eos.employee_details` —
   using the internal address `kafka-service.infra.svc.cluster.local:9092`, because Debezium is
   *inside the building*.
5. **Kafka stores the message**, in order, on that topic. It just sits there, waiting.
6. **LMS**, also inside the building (different floor), connects to that same internal address,
   subscribes to that topic name, and reads the message. It can do this because it never had to
   leave the building at all.
7. **Your local EMS**, sitting outside the building on your laptop, has no way to get to step 6.
   Even if you typed the exact topic name in perfectly, you'd still be standing outside a door that
   doesn't have a public handle.

---

## 7. So what do I actually need to do?

Three real options, in order of "how correct/permanent" they are:

| Option | In plain words | Effort |
|---|---|---|
| **A. Move EMS into the building** | Deploy EMS itself as a pod inside this same Kubernetes cluster (in the `backend` namespace, right next to `lms`/`eos`). Then it reaches Kafka's internal address exactly like LMS does — no tricks needed. This is the real, "production-correct" answer. | Needs a deployment file for EMS + someone with cluster access to apply it |
| **B. Borrow a temporary side door (local dev only)** | `kubectl port-forward` can temporarily punch a private, personal tunnel from your laptop into that one Kafka pod. Combined with editing your laptop's hosts file so `kafka-service.infra.svc.cluster.local` points at that tunnel, this can work *just* for local testing. Only works because there's a single Kafka broker today. | Needs someone to grant you `kubectl` access to the cluster first — a separate permissions question |
| **C. Ask if the VPN reaches inside the building** | `intranet-devops/docs/vpn-justification.md` proposes a VPN — but as written, it's scoped to gating the public website, not confirmed to route into the cluster's internal network. Worth asking the infra team directly rather than assuming. | Needs a direct answer from whoever owns this infrastructure |

**Option A is what "the same way LMS is getting data" actually means.** Options B and C are ways to
peek through a side window for local development — useful for testing, but not how the real system
is designed to work long-term.

---

## 8. Quick glossary — every name we've used, in one place

| Term | What it means here |
|---|---|
| **Topic name** | A label on a Kafka message stream — in our case `eos_test.eos.employee_details`, built from `topic.prefix` + database name + table name |
| **Broker** | The actual Kafka server that stores messages — here, a single pod named `kafka` in the `infra` namespace |
| **ClusterIP** | A Kubernetes service type meaning "only reachable from inside the cluster" — no public door |
| **`*.svc.cluster.local`** | Kubernetes' own internal DNS suffix — only resolvable by things running inside the cluster, never from the public internet |
| **Namespace** | A way of grouping things inside one cluster — like different floors of one building, not separate buildings |
| **Debezium** | The tool that watches MySQL's binlog and publishes each change as a Kafka message |
| **Ingress** | The one public front door into the whole system — and Kafka/Debezium/Kafka-UI are not on its list of rooms |
| **`kubectl port-forward`** | A way to temporarily tunnel from your own machine into one specific pod — for development, not production |

---

**If one thing should stick from this whole document**: a topic name is a label, not an address. You
were never missing information about the label — you were missing a path into the building the
label lives in. LMS has that path because it lives inside the same building. Right now, EMS doesn't.
