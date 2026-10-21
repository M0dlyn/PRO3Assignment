# PRO3 Assignment — Part 2

A Java 21 gRPC service for slaughterhouse traceability, backed by a persistent H2 SQL database. It retrieves all animal registration numbers potentially involved in a product, and all products potentially involving an animal.

## Run

Prerequisites: **JDK 21** and **Maven 3.9+**. The first build requires internet access to Maven Central. No separate database server, Docker, or IDE plugin is needed.

```sh
mvn clean verify
java -jar target/traceability-1.0.0.jar --demo
```

The server listens on `127.0.0.1:9090`. The database is saved under `data/traceability.mv.db`, relative to the working directory. `--demo` loads synthetic sample data only if the animal table is empty. Start without `--demo` to use existing records (or create an empty database). Stop with Ctrl+C. Do not start two processes against the same database file.

In a second terminal, from the repository directory:

```sh
java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient product P001
# A001
# A002

java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient animal A001
# P001
# P002

java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient animal A004
# No matching products
```

## API

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

## Architecture and scope

- [Updated C1, C2 and C3 diagrams](docs/architecture.md) — rendered by GitHub from Mermaid source.
- [Database model, constraints and query design](docs/database.md).
- [Tests and requirement coverage](docs/testing.md).
- [Original Part 1 diagrams](docs/part1/).

Part 2 implements the central query application and its database. The three station applications, local stores, broker and asynchronous ingestion remain architectural context from Part 1; they are **not implemented here**. SQL fixtures populate the database for this part. No responses are hardcoded in the service.

Stations are designed to continue local work during network outages and later synchronize through the broker. The central query service only knows data already received: an empty response is not a guarantee that no unsynchronized products exist. The future ingestion path must preserve batch history and handle duplicate/out-of-order events before this can be a production recall system.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:h2:file:./data/traceability` | H2 JDBC URL |
| `GRPC_HOST` | `127.0.0.1` | Server bind address |
| `GRPC_PORT` | `9090` | Server port |
| `GRPC_TARGET` | `127.0.0.1:9090` | Demo client's server target |

This coursework demo uses plaintext gRPC and a local embedded database. External authenticated access in the architectural model is a deployment requirement, not an implemented feature. Keep the default loopback binding; external deployment needs TLS and authorization. Reflection is enabled for API exploration.

## Project layout

```text
src/main/proto/                  gRPC contract
src/main/java/dk/via/pro3/        Server, service, repository, database, demo client
src/main/resources/schema.sql    Relational schema
src/main/resources/demo.sql      Synthetic traceability fixture
src/test/java/dk/via/pro3/        JUnit integration tests over real TCP gRPC
docs/                            C4 diagrams, database design, test guide, Part 1 images
.github/workflows/ci.yml          Java 21 build and test workflow
```

Technical references: [gRPC Java tutorial](https://grpc.io/docs/languages/java/basics/), [H2 documentation](https://h2database.com/html/features.html).
