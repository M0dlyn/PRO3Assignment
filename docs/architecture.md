# Updated architecture — Part 2

These C4 views refine the supplied Part 1 architecture. Solid arrows labeled **direct** represent synchronous calls; arrows labeled **indirect** represent asynchronous messages. Boxes explicitly identify which components exist in Part 2. The station and broker design is retained so local work can continue through a network outage.

## C1 — System context

```mermaid
flowchart LR
    receiving["Receiving operator<br/>Registers and weighs animals"]
    cutting["Cutting operator<br/>Registers parts and tray batches"]
    packing["Packing operator<br/>Packs and registers products"]
    external["Authorized external recall user"]
    system["Slaughterhouse Traceability System<br/>Tracks animal origins and potentially affected products"]
    receiving -->|Registers animals| system
    cutting -->|Registers parts and batches| system
    packing -->|Registers products and source batches| system
    external -->|Requests product origins or animal recall results| system
    system -->|Returns animal registration numbers or affected products| external
```

The external actor represents the intended system context. Part 2 demonstrates that interface with a local gRPC client; externally authenticated deployment remains future work.

## C2 — Container view

```mermaid
flowchart TB
    ro[Receiving operator]
    co[Cutting operator]
    po[Packing operator]
    user[External recall user]
    client["Recall client<br/>Java demo client or Postman<br/>Implemented for local demonstration"]
    subgraph system[Slaughterhouse Traceability System]
        receiving["Receiving application<br/>Planned"]
        cutting["Cutting application<br/>Planned"]
        packing["Packing application<br/>Planned"]
        rdb[("Receiving local store + outbox<br/>Planned")]
        cdb[("Cutting local store + outbox<br/>Planned")]
        pdb[("Packing local store + outbox<br/>Planned")]
        broker["Message broker<br/>Planned; technology unspecified"]
        central["Central application<br/>Java 21, gRPC<br/>Query service implemented<br/>Event ingestion planned"]
        db[("Central traceability database<br/>H2 via JDBC<br/>Implemented; embedded, disk persisted")]
    end
    ro --> receiving
    co --> cutting
    po --> packing
    receiving <-->|Direct local reads/writes| rdb
    cutting <-->|Direct local reads/writes| cdb
    packing <-->|Direct local reads/writes| pdb
    receiving -.->|Indirect saved updates when connected| broker
    cutting -.->|Indirect saved updates when connected| broker
    packing -.->|Indirect saved updates when connected| broker
    broker -.->|Indirect event delivery; planned| central
    user --> client
    client <-->|Direct gRPC over HTTP/2| central
    central <-->|Direct JDBC SQL queries; schema/demo initialization| db
```

The embedded database is a logical data-store container, not a separately launched server. Stations save operational data and outgoing events locally before trying to synchronize. A future consumer projects the events into the central database. Queries do not call the stations or depend on the broker being online, but results may lag unsynchronized local activity. Part 2 uses explicit SQL demo initialization instead of implementing that event flow.

## C3 — Components of the central application

```mermaid
flowchart TB
    client["External gRPC client<br/>Generated blocking stub or Postman"]
    broker["Message broker<br/>Planned"]
    db[("H2 database<br/>Animals, parts, batches, products and links")]
    subgraph central[Central application — Java process]
        bootstrap["TraceabilityServer<br/>Configures bind address; starts/stops Netty gRPC"]
        reflection["gRPC reflection service<br/>Exposes protobuf descriptors"]
        service["TraceabilityService<br/>Validates identifiers; maps records and status codes"]
        repository["TraceabilityRepository<br/>Prepared SQL joins; deduplication; consistent read transaction"]
        database["Database<br/>Builds JDBC DataSource; initializes schema; optional demo seed"]
        ingest["Event consumer / projection writer<br/>Planned; not implemented"]
    end
    client <-->|Direct protobuf RPC| service
    client <-->|API discovery| reflection
    bootstrap -->|Registers| service
    bootstrap -->|Registers| reflection
    bootstrap -->|Initializes| database
    service -->|Calls query methods| repository
    repository <-->|JDBC SELECTs| db
    database -->|DDL and optional seed transaction| db
    broker -.->|Indirect event delivery| ingest
    ingest -.->|Future idempotent projection writes| db
```

Generated protobuf code forms the transport boundary. The repository returns Java records, keeping SQL independent of protobuf. `TraceabilityService` maps those records into response messages and translates failures to gRPC status codes. `TraceabilityClient` lives outside the central application and exercises the same public contract as other clients.

## Decisions and limits

1. **Unary gRPC methods** match the two Part 2 lookups; one `.proto` defines messages and RPCs.
2. **H2 and JDBC** provide a real persistent database without additional infrastructure for assessment.
3. **Batch-level traceability** preserves the original model and conservatively includes every possible source animal. Reusing a tray creates a new batch identity.
4. **Deterministic, distinct results** eliminate duplicates caused by multiple parts or source batches.
5. **Offline stations remain a system requirement.** Local stores/outboxes and the broker are architecture only in this submission. Delivery deduplication, ordering, retries and synchronization visibility belong to a future ingestion implementation.
6. **Local demonstration boundary.** Plaintext, reflection and a loopback listener simplify testing. TLS and authorization must protect the external recall interface before deployment. They are not claimed as implemented.
7. **Scale.** Unary replies return all matches; pagination/streaming and connection pooling would be needed for much larger datasets. This implementation targets the coursework dataset.
