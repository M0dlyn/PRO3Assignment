# PRO3 Assignment — Complete system (Part 4)

Three independent station services implement receiving, cutting and packing. Each uses its own persistent H2 database and transactional outbox, so available local work continues when the network or broker is down. A durable message broker distributes updates to the other stations and the central gRPC recall service. No GUI is required.

## Build and run

Requires **JDK 21+**, **Maven 3.9+**, and **Python 3** for the optional demonstration. The first Maven build needs internet access. No Docker or external database/message server installation is needed: the broker is included.

```sh
mvn clean verify
bash scripts/run-system.sh
```

The launcher runs five separate Java processes and keeps running until Ctrl+C. Each has its own database file under `data/<role>/store.mv.db`; logs are under `data/logs/`. Ctrl+C stops only the processes started by this launcher. Restarting it keeps all registrations, inventory, messages and synchronization offsets.

| Process | Interface | Owns |
| --- | --- | --- |
| Receiving | HTTP `127.0.0.1:8081` | Animal registration and arrival/farm lookups |
| Cutting | HTTP `127.0.0.1:8082` | Trays, batches and weighed parts linked to animals |
| Packing | HTTP `127.0.0.1:8083` | Product recipes, packing and stock consumption |
| Message broker | HTTP `127.0.0.1:8070` | Persistent event log and delivery/replay |
| Central | gRPC `127.0.0.1:9090`; HTTP `127.0.0.1:8084` | Animal/product traceability and synchronization status |

In another terminal:

```sh
python3 scripts/demo.py
```

The demonstration registers two animals, fills and closes leg/loin batches, creates a mixed-part product and a two-leg product, and waits for the central records. It prints identifiers and an authenticated gRPC recall command. Each run uses fresh IDs and preserves previous data.

The launcher supplies local demonstration credentials `local-demo-broker` and `local-demo-recall`; override `BROKER_TOKEN` and `RECALL_TOKEN` in your environment to use your own values. All listeners default to loopback. These demo credentials and plaintext connections are for the local assignment, not public deployment.

## Implemented workflow

1. **Receiving:** `POST /api/animals` stores registration number, arrival date, weight, farm and optional species. GET by ID, date or farm remains available locally.
2. **Cutting:** create physical trays with maximum capacity; open a single-type batch; register each weighed part with its source animal. Parts cannot exceed tray capacity or the animal's recorded arrival weight. Close the batch to freeze its historical membership.
3. **Packing:** create a product type with required quantities per part type, then pack a product from selected part IDs in closed batches. Selection must exactly match the recipe. Parts can only be consumed once, including under concurrent requests. Source batches are recorded automatically.
4. **Synchronization:** every successful command commits its domain records and outgoing event atomically. Background workers publish/retry and consume the durable broker log. Inbox records prevent duplicate effects. Tray reuse is allowed after all parts in previous batches have been packed and that update is visible to cutting.
5. **Recall:** the central gRPC API retrieves all possible animals in a product or all potentially affected products for an animal. It uses immutable batch history, preserving the conservative recall semantics from Part 1.

See [the complete API guide](docs/system-api.md), [OpenAPI contract](docs/system-openapi.json), and [step-by-step demonstration](scripts/demo.py).

## Offline operation and consistency

Receiving can register new animals offline. Cutting can use animals already cached locally. Packing can use closed batches already received locally. Commands never call the broker or another station before saving. Outboxes survive restart and resume automatically when the broker returns.

A record that has **never reached a station** cannot be used there during an outage: it returns 404 instead of inventing its details. Stations expose `GET /api/status` with pending events, replay position, last successful synchronization and last error. Central status requires the recall token:

```sh
curl -H 'Authorization: Bearer local-demo-recall' http://127.0.0.1:8084/api/status
```

A caught-up central replica can still be missing unpublished events from an offline station. Recall results are therefore based on received records and must not be treated as proof that no additional affected products exist. See [reliability and operating guide](docs/operations.md) for outage demonstrations and recovery behavior.

## Documentation and tests

- [C1, C2 and C3 architecture](docs/architecture.md).
- [Database, domain mapping and inventory rules](docs/database.md).
- [Full-system HTTP API and example payloads](docs/system-api.md).
- [Delivery, outages, configuration and limitations](docs/operations.md).
- [Test coverage and requirement mapping](docs/testing.md).
- Original [Part 1 diagrams](docs/part1/), [Part 2 architecture](docs/architecture-part2.md), and [Part 3 architecture](docs/architecture-part3.md) are retained.

`mvn clean verify` runs the original REST/gRPC tests plus multi-database integration tests for the complete workflow, broker outages, restarts, durable outboxes, duplicate delivery, transaction rollback, stock constraints, authentication and concurrent packing. GitHub Actions runs the same build.

## Individual processes

Instead of the launcher, run each role in its own terminal:

```sh
export BROKER_TOKEN=local-demo-broker
export RECALL_TOKEN=local-demo-recall
java -cp target/traceability-1.0.0.jar dk.via.pro3.system.SystemMain broker
# Other terminals, with the same environment:
# ... SystemMain receiving
# ... SystemMain cutting
# ... SystemMain packing
# ... SystemMain central
```

Only one active process per role is supported. Do not share database paths between roles. The central process requires `RECALL_TOKEN`, and every process requires `BROKER_TOKEN` when started directly. The launcher sets the demonstration defaults.

## Parts 2 and 3 compatibility

The original protobuf contract and Part 3 animal representation remain compatible. The previous combined application is still available as a standalone legacy demonstration:

```sh
java -jar target/traceability-1.0.0.jar --demo
```

That mode uses the old `data/traceability` database, REST port 8080 and gRPC port 9090; it does **not** participate in Part 4 synchronization. Do not run it alongside the full-system central process on the same gRPC port. Existing legacy data is preserved but is not automatically split into station databases: Part 4 starts fresh role-specific stores with complete event history. See [legacy REST documentation](docs/rest-api.md) for that mode.
