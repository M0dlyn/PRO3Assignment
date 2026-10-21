# PRO3 Assignment — Parts 2 and 3

Java 21 services for slaughterhouse animal registration and traceability, backed by a persistent H2 SQL database. Part 3 registers and reads animals through REST; Part 2 retrieves product origins and affected products through gRPC.

## Build and run

Prerequisites: **JDK 21** and **Maven 3.9+**. The first build downloads dependencies from Maven Central. No separate database server or Docker is needed.

```sh
mvn clean verify
java -jar target/traceability-1.0.0.jar --demo
```

One process starts **REST at http://127.0.0.1:8080/api/animals** and **gRPC at 127.0.0.1:9090**. Records persist in `data/traceability.mv.db`, relative to the working directory. Stop with Ctrl+C. Restart from the same directory to retain the database. Do not launch two application processes against the same file.

`--demo` loads synthetic sample data only if the animal table is empty. Omit it to start an empty database or use existing records. Existing Part 2 databases are upgraded without resetting data; their missing farm values remain null.

## Part 3 REST API

| Request | Purpose |
| --- | --- |
| `POST /api/animals` | Register arrival date, weight, registration number and origin (farm) |
| `GET /api/animals/{registrationNumber}` | Read one animal |
| `GET /api/animals?arrivalDate=2026-10-21` | Read all animals arriving on a date |
| `GET /api/animals?origin=North%20Farm` | Read all animals from a farm |
| `GET /api/animals` | Read all animals; filters can also be combined |

```sh
curl -i -X POST http://127.0.0.1:8080/api/animals \
  -H 'Content-Type: application/json' \
  -d '{"registrationNumber":"DK1001","arrivalDate":"2026-10-21","arrivalWeightKg":120.125,"origin":"North Farm","species":"pig"}'

curl http://127.0.0.1:8080/api/animals/DK1001
```

A successful registration returns `201 Created`, a `Location` header and the stored details. Duplicate IDs return `409 Conflict`. Collections return sorted JSON arrays, including an empty array for no matches. New registrations are immediately visible to gRPC because both APIs use the same database.

See the [REST guide](docs/rest-api.md) for validation, error statuses, and curl examples. Import the [OpenAPI contract](docs/openapi.json) into Postman. No graphical UI is required or included.

## Part 2 gRPC API

Contract: [`src/main/proto/traceability.proto`](src/main/proto/traceability.proto). Maven generates Java messages and client/server stubs from this file. Both methods are unary RPCs; replies are distinct and sorted by identifier.

| RPC | Request | Response |
| --- | --- | --- |
| `traceability.v1.TraceabilityService/GetAnimalsForProduct` | `product_id` | `animals`: registration number, species, arrival weight and date |
| `traceability.v1.TraceabilityService/GetProductsForAnimal` | `registration_number` | `products`: product ID, type and packed date |

Identifiers are case-sensitive, 1–100 characters, and must have no surrounding whitespace. We treat the Part 1 `Animal.animalId` as its registration number. Dates use `YYYY-MM-DD`; weights are decimal strings in kilograms.

| Situation | gRPC status |
| --- | --- |
| Known entity with matches | `OK` with results |
| Known entity without matches | `OK` with an empty list |
| Unknown animal/product | `NOT_FOUND` |
| Blank, padded or too-long identifier | `INVALID_ARGUMENT` |
| SQL query failure | `UNAVAILABLE` |
| Unexpected server failure | `INTERNAL` |

See [manual testing](docs/testing.md) for Postman and grpcurl instructions.

## Traceability semantics

The Part 1 model records the **tray batches used by a product**, rather than the exact individual parts picked. Consequently, the service returns **all possible contributors and affected products**:

`Animal → Part → TrayBatch ← ProductTrayBatch → Product`

If animals A001 and A002 contributed to B001, every product using B001 is potentially affected by either animal. This is intentional conservative recall behavior. A tray can be reused: T001 holds B001 on one day and B003 later. Joining by **batch ID**, never tray ID, prevents the later batch from contaminating the earlier batch's recall results.

A batch represents one filling/use of a tray. Its historical membership must remain fixed once used for packing; parts must not be deleted from traceability history when physically consumed. Future station writers must enforce this rule. The Part 2 API is read-only.

## Architecture and tests

- [Updated C1, C2 and C3 diagrams](docs/architecture.md), with explicit implemented/planned boundaries.
- [Database model and upgrade behavior](docs/database.md).
- [JUnit coverage and manual test guide](docs/testing.md).
- [Original Part 1 diagrams](docs/part1/) and [Part 2 architecture snapshot](docs/architecture-part2.md).

`mvn clean verify` runs actual HTTP and gRPC calls against real SQL databases. Tests cover lookups, validation, duplicates (including concurrent requests), legacy schema upgrades, persistence across restart, and REST/gRPC interoperability. GitHub Actions runs the same build.

Part 3 implements animal registration only. Cutting/packing writes, station-local offline storage, outboxes, broker and event ingestion remain planned. REST registration requires a reachable server. This local demo uses plaintext HTTP/gRPC and loopback listeners; externally deployed services would need authentication and TLS.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:h2:file:./data/traceability` | Shared H2 JDBC URL |
| `REST_HOST` | `127.0.0.1` | REST bind address |
| `REST_PORT` | `8080` | REST port |
| `GRPC_HOST` | `127.0.0.1` | gRPC bind address |
| `GRPC_PORT` | `9090` | gRPC port |
| `GRPC_TARGET` | `127.0.0.1:9090` | Demo client's target |

## gRPC demonstration

With the application running, use another terminal:

```sh
java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient product P001
# A001, A002 (one per line)
java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient animal A001
# P001, P002 (one per line)
```

## Project layout

```text
src/main/proto/                    Part 2 gRPC contract
src/main/java/dk/via/pro3/          REST/gRPC adapters, services, repositories, bootstrap and demo client
src/main/resources/schema.sql      Original relational schema
src/main/resources/migration-v2.sql  Additive Part 3 origin migration and indexes
src/main/resources/demo.sql        Synthetic sample data
src/test/java/dk/via/pro3/          HTTP/gRPC integration tests
docs/                            C4 views, API contract, database and test guides
.github/workflows/ci.yml           Java 21 CI build
```

Technical references: [gRPC Java](https://grpc.io/docs/languages/java/basics/), [H2](https://h2database.com/html/features.html), [JDK HTTP server](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html).
