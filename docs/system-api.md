# Full-system API — Part 4

All station routes begin with `/api`. Receiving listens on 8081, cutting on 8082, packing on 8083, and the central read replica on 8084. The same GET routes read each node's local records. A mutation must be sent to its owner, otherwise it returns 403. No GUI is needed.

HTTP request/response bodies use UTF-8 JSON. POST success returns 201 and Location, except batch closure returns 200. Collections return ordered arrays; no matches returns `[]`. Identifiers are case-sensitive, 1–100 nonblank characters without surrounding whitespace/control characters. URL-encode identifiers and filter values. Unknown and duplicate JSON fields are rejected. The request body limit is 128 KiB.

[Import the full-system OpenAPI contract](system-openapi.json) into Postman. The historical `openapi.json` describes the legacy Part 3 mode.

## Receiving — port 8081

```http
POST /api/animals
Content-Type: application/json

{"registrationNumber":"A1","arrivalDate":"2026-11-19","arrivalWeightKg":120,"origin":"North Farm","species":"pig"}
```

Origin is required, 1–200 characters. Species is optional and defaults to `unspecified`. Date is a real calendar date in YYYY-MM-DD format. Positive weights use at most three decimal places and fit DECIMAL(12,3). Registration numbers are unique. Duplicate registration returns 409 without overwriting the original or creating an extra outgoing event.

```text
GET /api/animals/A1
GET /api/animals?arrivalDate=2026-11-19
GET /api/animals?origin=North%20Farm
GET /api/animals?arrivalDate=2026-11-19&origin=North%20Farm
GET /api/animals
```

Filters combine with AND and exact case-sensitive farm matching, as in Part 3. The 201 response means **saved locally and queued**, not received by all other nodes.

## Cutting — port 8082

Create a reusable tray and start one filling of that tray:

```http
POST /api/trays
Content-Type: application/json

{"trayId":"T1","maximumWeightKg":30}
```

```http
POST /api/batches
Content-Type: application/json

{"batchId":"B1","trayId":"T1","partType":"leg"}
```

The server assigns `createdAt` in UTC. A tray can have only one open batch, and every part in a previous batch must have been packed before the tray can be reused. The cutting replica must have received that packing event. Batch IDs are never reused, even when a physical tray is reused.

```http
POST /api/parts
Content-Type: application/json

{"partId":"CUT1","animalRegistrationNumber":"A1","batchId":"B1","partType":"leg","weightKg":8.5}
```

The animal must be known locally; each part references exactly one animal and batch. Part type must equal the batch's type. Total batch weight cannot exceed the tray capacity; the total weight of all registered parts from an animal cannot exceed its arrival weight. All parts remain in traceability history after packing.

When the batch is ready to move to packing:

```http
POST /api/batches/B1/close
Content-Type: application/json

{}
```

An empty batch cannot be closed. A closed batch cannot receive more parts or be closed again. There is no reopen or delete operation. Packing only accepts closed batches, so its traceability membership is stable.

```text
GET /api/trays
GET /api/trays/T1
GET /api/batches
GET /api/batches/B1
GET /api/parts
GET /api/parts/CUT1
```

Batch responses include `closed`, historical `totalWeightKg`, and `availableParts`. Part responses include `productId` (null until packed). Historical batch weight does not decrease; available part count does.

## Packing — port 8083

Create an immutable product recipe:

```http
POST /api/product-types
Content-Type: application/json

{"name":"half-animal-example","requirements":[{"partType":"leg","quantity":2},{"partType":"loin","quantity":1}]}
```

A recipe contains at least one distinct part type and a positive integer quantity per type. The total is at most 500 parts. The example is illustrative; enter the actual expected parts for your chosen product. A package of identical parts uses one requirement, e.g. `{"name":"two-legs","requirements":[{"partType":"leg","quantity":2}]}`.

```http
POST /api/products
Content-Type: application/json

{"productId":"P1","productType":"half-animal-example","packedDate":"2026-11-19","partIds":["CUT1","CUT2","CUT3"]}
```

The selected IDs must exist locally in closed batches, be distinct and unconsumed, and exactly match the recipe's quantities and types. They may originate from different animals and different trays. The date cannot precede any source batch's creation date. Saving the product, consuming its parts, recording its source batches and creating its outgoing event are one transaction.

```text
GET /api/product-types
GET /api/product-types/two-legs
GET /api/products
GET /api/products/P1
```

Product results include selected parts and source batches. Only the packing node owns allocations; this prevents offline stations from spending the same inventory independently.

## Central recall — gRPC 9090

The Part 2 RPC names are unchanged:

- `traceability.v1.TraceabilityService/GetAnimalsForProduct` takes `product_id`.
- `traceability.v1.TraceabilityService/GetProductsForAnimal` takes `registration_number`.

Send metadata `authorization: Bearer <RECALL_TOKEN>`. The bundled client supports the token through the environment:

```sh
RECALL_TOKEN=local-demo-recall java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient animal A1
RECALL_TOKEN=local-demo-recall java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient product P1
```

In Postman, import `src/main/proto/traceability.proto`, target `127.0.0.1:9090`, disable TLS for the local demo, and add the authorization metadata. Reflection also requires authentication. Missing/incorrect credentials return UNAUTHENTICATED.

Recall remains **conservative through source batches**, consistent with the original assignment model. If B1 contained parts from A1 and A2, every product using B1 is potentially affected by either animal, even though actual selected part IDs are now also stored for inventory. Results are distinct and ordered; unknown IDs return NOT_FOUND and known IDs without links return an empty response.

The central HTTP API provides the same read endpoints plus status, protected by `Authorization: Bearer <RECALL_TOKEN>`. Central rejects all operator writes.

## Synchronization status — every node

```text
GET /api/status
```

Fields: `nodeRole`, `cursorValue`, `pendingOutbox`, `lastSyncError`, `lastSuccessfulSync`, `lastObservedBrokerSequence`, `caughtUpToObservedBroker`.

A successful request never triggers synchronization. The background poller normally runs every second; no operator request waits for the broker. `caughtUpToObservedBroker` only describes the broker log last observed, not unpublished updates at other stations.

## Errors

Errors use `{"error":"description"}`. Statuses: 400 invalid input; 401 missing/invalid token on protected endpoints; 403 wrong station for command; 404 unknown local record/route; 405 unsupported method with Allow header; 409 duplicate ID, capacity, inventory or lifecycle conflict; 413 body too large; 415 wrong Content-Type; 503 database unavailable; 500 unexpected failure. A missing upstream record may become available after synchronization; check the status before retrying.

Broker protocol, credentials and recovery behavior are documented in [operations.md](operations.md).
