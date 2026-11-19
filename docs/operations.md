# Reliability and operation — Part 4

## Process configuration

Each `SystemMain` process accepts exactly one role: `broker`, `receiving`, `cutting`, `packing`, or `central`.

| Variable | Default | Applies to |
| --- | --- | --- |
| `DB_URL` | `jdbc:h2:file:./data/<role>/store` | Each process's private database |
| `HTTP_HOST` | `127.0.0.1` | All HTTP listeners |
| `HTTP_PORT` | Broker 8070, receiving 8081, cutting 8082, packing 8083, central 8084 | Selected process |
| `BROKER_URL` | `http://127.0.0.1:8070` | Stations and central |
| `BROKER_TOKEN` | Required for direct startup | Broker and its trusted peers |
| `RECALL_TOKEN` | Required for central startup | Central gRPC and HTTP reads/status |
| `STATION_TOKEN` | Empty (local operator API) | Optional bearer token on station HTTP endpoints |
| `GRPC_HOST` / `GRPC_PORT` | `127.0.0.1` / `9090` | Central only |
| `GRPC_TARGET` | `127.0.0.1:9090` | Existing Java client |

The launcher provides explicit local demo credentials and fixed default ports. For custom per-process ports/paths, launch the roles separately. `DB_URL` must not be set to the same path for every launcher child. Node metadata records its role and rejects a different role using that database. H2 file locking also prevents two processes opening the same embedded database simultaneously.

## Persistence and delivery

A command validates and changes the domain, inserts its own event in `inbox`, and appends the event to `outbox` within one transaction. The node's database row lock serializes command and projection transactions. Rejected requests roll back every change, including any partially created recipe/product and outgoing event. This also prevents concurrent overfilling and double allocation.

`SyncWorker` performs a bounded iteration:

1. Read up to 200 undelivered outgoing events in their commit order.
2. POST each event to the broker. Mark it delivered only after a successful durable acknowledgement.
3. GET up to 200 broker events after the node's persisted cursor and apply them in sequence.
4. Read the broker's latest sequence and update in-memory diagnostic status.

The worker repeats after one second. Each HTTP request has a three-second deadline and a two-second connection timeout. Network failures retain the outbox and cursor, report an error, and retry on the next iteration. Operator endpoints do not use this network client and remain available against local storage.

If the broker saves an event but its acknowledgement is lost, the publisher retries the same event ID. The broker returns the original sequence. If the event ID is reused with different content, it rejects it. Consumers commit domain changes, inbox deduplication and cursor advancement together. Replayed own events are recognized by inbox records and do not reapply their changes. A failed projection or sequence gap does **not** advance the cursor: replay pauses visibly rather than silently losing data.

Every broker append holds a database row lock until commit. This assigns offsets in commit order, so a consumer cannot observe a later offset before a lower uncommitted one and accidentally skip it. The log is append-only, persisted to disk, and retained indefinitely for this coursework implementation. A new empty central database can rebuild by replaying it from zero.

One connection is kept open for each process's database lifetime; individual operations still use their own short-lived connections. This avoids races from repeatedly closing/reopening a file database under concurrent HTTP requests. Stop synchronization and HTTP workers before closing that lifetime connection.

## Broker protocol

This project includes a small **single-node persistent HTTP message broker**, not a RabbitMQ/Kafka adapter. It implements the needed durable fan-out event log, replay and retry behavior without extra infrastructure. Domain communication is indirect through the broker even though its transport is ordinary HTTP.

All broker routes require `Authorization: Bearer <BROKER_TOKEN>`:

- `POST /events`: append an event, or acknowledge an identical retry. Returns `{"sequence":1}`.
- `GET /events?after=0`: up to 200 entries, ordered as `[{"sequence":1,"event":{...}}]`.
- `GET /status`: `{"lastSequence":1}`.

An event has `eventId` (UUID), `version` (1), `source` (receiving/cutting/packing), `type`, and `payload`. Types and their owners:

| Source | Events |
| --- | --- |
| receiving | ANIMAL_REGISTERED |
| cutting | TRAY_CREATED, BATCH_OPENED, PART_REGISTERED, BATCH_CLOSED |
| packing | RECIPE_CREATED, PRODUCT_PACKED |

The broker validates envelope version/type/owner and deduplicates IDs. The application stations are trusted publishers; domain validation happens at the writer and at each projection. The broker token is shared among these internal peers, not separate per-role cryptographic authorization. Central is a read-only application, not an event producer.

## Offline demonstration

Use separate terminals for the five direct role commands in the README so each process can be stopped independently.

1. Register an animal at receiving. Wait until GET at cutting returns it.
2. Stop only the broker terminal. Register another animal at receiving; create/fill/close a batch using the cached first animal at cutting. Both operations succeed.
3. Inspect each station's `/api/status`: pendingOutbox increases and the poller reports the failed connection. Local GET requests still work. Restart a station if desired; its domain and outbox data remain.
4. Restart the broker using the same database path. Pending counts drain automatically; the new records reach the other nodes.
5. Wait until packing has a closed batch, then stop the broker again. Create a recipe and pack from that cached batch. The product is saved locally; central does not yet know it.
6. Restart the broker. Wait for central to receive the product, then perform the authenticated gRPC recall.

The test suite automates these outages and restart scenarios, plus lost-acknowledgement replay and on-disk outbox recovery.

## Boundaries and recovery

- **Exactly one writer service per station role.** Multiple operator HTTP requests are safe; disconnected multi-master instances of the same role are not supported. Central/other stations replicate but do not allocate or register on behalf of an owner.
- **Cached dependencies are necessary.** A station cannot use an animal/batch it has never received. This is an information constraint; it returns 404. It can continue all other valid local work while disconnected.
- **Eventual recall completeness.** Check every station's pending queue and central's replay status. A central cursor matching the last observed broker sequence cannot prove an offline station has no unpublished products. The API reports received data, not a global completeness guarantee.
- **Immutable history.** No endpoint deletes parts, reopens batches, edits recipes or replaces registered entities. Every consumed part remains linked to its animal and historical batch. A reused physical tray gets a new batch ID.
- **Partial/corrupt data or protocol mismatch.** Replay stops at the failing event with `lastSyncError`. Correct the software/data issue and restart; do not manually advance the cursor to hide the failure. This demo has no dead-letter admin UI or automatic conflict resolution.
- **Backup together.** Preserve role databases and the broker log. Restart with the same paths. Losing the broker log while retaining advanced consumer cursors requires coordinated recovery from backups; resetting just the broker is not a supported recovery procedure. The launcher never deletes data.
- **Legacy migration.** Existing Parts 2/3 data is preserved in its old path. A nonempty legacy store cannot become a Part 4 station automatically because its events and inventory allocations are unknown. Use fresh role databases and register via the station APIs. Schema extension itself is additive.
- **Scale and deployment.** No log compaction, clustering, TLS, pagination of domain lists or horizontal writer scaling is implemented. The default local configuration is suitable for assessment. Public deployment needs TLS and appropriately managed credentials; shared broker credentials assume trusted internal peers.
