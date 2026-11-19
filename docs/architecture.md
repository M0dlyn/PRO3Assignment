# C4 architecture — completed system, Part 4

The three station services now implement the full operational workflow. Each has its own persistent local database and outgoing queue. Indirect event delivery connects them through a durable broker; the central application serves external recall queries over gRPC. HTTP clients replace a GUI for assessment.

## C1 — System context

```mermaid
flowchart LR
    receiving["Receiving operator<br/>Weighs and registers animals and farm origins"]
    cutting["Cutting operator<br/>Weighs parts; fills and closes tray batches"]
    packing["Packing operator<br/>Defines recipes and packs products"]
    recall["Authorized external recall user"]
    system["Slaughterhouse System<br/>Receiving, cutting, packing and traceability"]
    receiving <-->|Registers and looks up animals| system
    cutting <-->|Registers parts, trays and batch completion| system
    packing <-->|Registers products and views available parts| system
    recall <-->|Requests animal origins or affected products| system
```

## C2 — Containers and communication

```mermaid
flowchart TB
    ro[Receiving operator / HTTP client]
    co[Cutting operator / HTTP client]
    po[Packing operator / HTTP client]
    recall[External gRPC recall client]
    subgraph system[Slaughterhouse System]
        receiving["Receiving service<br/>Java HTTP JSON :8081"]
        cutting["Cutting service<br/>Java HTTP JSON :8082"]
        packing["Packing service<br/>Java HTTP JSON :8083"]
        rdb[("Receiving H2 file<br/>Local records, inbox, outbox, cursor")]
        cdb[("Cutting H2 file<br/>Local records, inbox, outbox, cursor")]
        pdb[("Packing H2 file<br/>Local records, inbox, outbox, cursor")]
        broker["Durable message broker<br/>Java HTTP :8070<br/>Append, deduplicate and replay"]
        log[("Broker H2 file<br/>Append-only event log")]
        central["Central application<br/>gRPC :9090<br/>Read-only HTTP/status :8084"]
        db[("Central H2 file<br/>Traceability projection, inbox and cursor")]
    end
    ro <-->|Direct HTTP requests and replies| receiving
    co <-->|Direct HTTP requests and replies| cutting
    po <-->|Direct HTTP requests and replies| packing
    receiving <-->|Local JDBC transactions| rdb
    cutting <-->|Local JDBC transactions| cdb
    packing <-->|Local JDBC transactions| pdb
    receiving -.->|Indirect events from durable outbox| broker
    cutting -.->|Indirect events from durable outbox| broker
    packing -.->|Indirect events from durable outbox| broker
    broker -.->|Ordered replay over polling HTTP| receiving
    broker -.->|Ordered replay over polling HTTP| cutting
    broker -.->|Ordered replay over polling HTTP| packing
    broker -.->|Ordered replay over polling HTTP| central
    broker <-->|Durable JDBC append and reads| log
    central <-->|Local JDBC reads and projection writes| db
    recall <-->|Direct authenticated gRPC| central
```

There are five independent JVM processes and five private database files. H2 stores are logical data containers embedded in their owning process, not separately networked database servers. No station command calls another station or central service. Each station keeps a full local projection of the event stream in this small system; only the owner of an operation can write new business events.

The custom broker provides durable fan-out over HTTP. Operator REST calls are direct; business updates are indirect because producers enqueue events without waiting for consumers. Broker acknowledgement confirms durable storage, not completion at every replica.

## C3 — Station components

The same station executable is configured with a receiving, cutting or packing role. The role determines allowed commands; its database persists the role to prevent accidental reuse.

```mermaid
flowchart TB
    operator[Operator HTTP client]
    broker[Durable broker]
    db[(Private local H2 database)]
    subgraph station[Receiving / cutting / packing JVM]
        main["SystemMain<br/>Role, configuration and lifecycle"]
        http["StationServer / HttpApi<br/>Routing, JSON, status and optional bearer auth"]
        store["StationStore<br/>Atomic commands and projections<br/>Inbox/outbox/cursor transactions"]
        domain["Domain<br/>Registration, tray/batch rules<br/>Recipe and inventory validation"]
        animals["AnimalRegistrationService / AnimalRepository<br/>Existing validation and animal reads"]
        sync["SyncWorker<br/>Timed publisher and replay consumer<br/>Retry and lag diagnostics"]
    end
    operator <-->|Direct REST| http
    main --> http
    main --> sync
    main --> store
    http -->|Owner-checked command or local read| store
    http -->|Animal validation and lookup| animals
    store -->|Within transaction| domain
    store <-->|Records and durable event state| db
    domain <-->|Prepared SQL| db
    animals <-->|Local reads| db
    sync <-->|Pending events and atomic replay| store
    sync -.->|Publish and poll; retries on failure| broker
```

| Station role | Domain commands | Key invariants |
| --- | --- | --- |
| Receiving | Register animal | Unique registration number; valid date, farm and positive decimal weight |
| Cutting | Create tray, open batch, register part, close batch | One part type; capacity; source animal; no late batch changes; tray empty before reuse |
| Packing | Define recipe, pack product | Exact required quantities/types; only closed batches; each part allocated at most once |

The local node-state row lock serializes commands and projection transactions. A successful command applies domain changes, records its inbox identity and adds its outgoing event in one commit. A rejected operation rolls everything back. Read endpoints return local data immediately, regardless of broker reachability.

## C3 — Broker components

```mermaid
flowchart LR
    producers[Station SyncWorkers]
    consumers[Station and central SyncWorkers]
    subgraph broker[Broker JVM]
        main[SystemMain]
        http["HttpApi<br/>Bearer authentication and HTTP routing"]
        service["BrokerServer<br/>Envelope validation<br/>Idempotent append and ordered replay"]
        log[("H2 event_log + broker_state<br/>Unique event ID and committed sequence")]
    end
    main --> http
    producers -->|POST event| http
    consumers -->|GET events after cursor| http
    http --> service
    service <-->|Commit-order lock and SQL transactions| log
```

The broker does not interpret inventory or call consumers synchronously. An identical retry returns the original sequence; a changed event with the same ID is rejected. The append lock prevents visible offset gaps caused by concurrent commits. The entire log is retained for replay.

## C3 — Central components

```mermaid
flowchart TB
    client[External recall client]
    monitor[HTTP read/status client]
    broker[Durable broker]
    db[(Central H2 projection)]
    subgraph central[Central JVM]
        main[SystemMain]
        auth["RecallAuth<br/>gRPC bearer-token interceptor"]
        grpc["TraceabilityService<br/>Part 2 protobuf contract"]
        repo["TraceabilityRepository<br/>Conservative batch-level SQL joins"]
        http["StationServer in central role<br/>Authenticated reads; rejects commands"]
        sync["SyncWorker<br/>Consumes broker events"]
        store["StationStore + Domain<br/>Transactional projection and inbox/cursor"]
    end
    main --> auth
    main --> http
    main --> sync
    client <-->|Authenticated gRPC| auth
    auth --> grpc
    grpc --> repo
    repo <-->|Local SQL queries| db
    monitor <-->|Authenticated HTTP| http
    http --> store
    http -->|Lag/error status| sync
    sync -.->|Replay requests| broker
    sync --> store
    store <-->|Atomic projection writes| db
```

The original traceability contract is unchanged. Detailed product-part allocations now protect inventory, while recalls still join through source batches as in the Part 1 model. Reusing a tray never connects new batches to old products.

## Consistency and scope

Stations can operate offline with locally known dependencies, and persist outgoing changes until the broker returns. A newly arrived animal cannot be used at cutting before that registration reaches cutting; similarly packing must first receive a closed batch. There is no invented fallback data.

Synchronization status distinguishes queued writes, local replay position and the last observed broker position. It does not claim global recall completeness while any station may hold unpublished events. The implementation supports one writer process per role, multiple concurrent operator requests, and independent restarts. It includes bearer protection for internal broker access and external recall; default transport is local plaintext. Deployment/security and operational boundaries are documented in [operations.md](operations.md).

Earlier snapshots: [Part 2](architecture-part2.md), [Part 3](architecture-part3.md), and [original Part 1](part1/).
