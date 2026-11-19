# Testing and assessment guide — Parts 2 and 3

## Automated tests

```sh
mvn clean verify
```

JUnit 5 tests start a real Netty gRPC server on an ephemeral loopback TCP port and call it using generated protobuf stubs. Each test has a fresh H2 database loaded from SQL. No mocked repository or hardcoded server response is used. A separate test reopens a file-backed database and restarts the gRPC server without reseeding.

| Requirement / edge case | Test |
| --- | --- |
| Animal registration numbers for a product, all batches, no duplicates | `productReturnsAllPotentialAnimalsOnceAcrossMultipleBatches` |
| All products involving an animal, no duplicates | `animalReturnsAllPotentialProductsOnce` |
| Physical tray reuse does not mix historical batches | `reusedPhysicalTrayDoesNotConnectSeparateBatches` |
| Known animal with no products | `knownAnimalWithoutProductsReturnsEmptySuccess` |
| Known product with no projected links | `knownProductWithoutProjectedLinksReturnsEmptySuccess` |
| Missing entities | `unknownEntitiesReturnNotFound` |
| Invalid inputs in both RPCs | `invalidIdentifiersReturnInvalidArgumentInBothMethods` |
| Case sensitivity and SQL injection-shaped input | `identifiersAreCaseSensitiveAndSqlIsParameterized` |
| Changes in SQL data appear through gRPC | `readsReflectDatabaseChangesInsteadOfHardcodedResponses` |
| Foreign keys, part type, weights and duplicate links | `schemaRejectsWrongPartTypeAndBrokenTraceabilityReferences` |
| Repeatable bootstrap without duplicate seed data | `initializationAndDemoCanBeRepeatedWithoutDuplicatingData` |
| File persistence across server/database restart | `dataSurvivesClosingDatabaseAndRestartingServer` |
| Database failure maps to a safe public error | `databaseFailureBecomesUnavailableWithoutExposingSql` |

The database-failure test intentionally drops a table; a server-side warning stack trace is expected in that test. The client receives only `UNAVAILABLE: Traceability database unavailable`. Reports are generated in `target/surefire-reports/`. GitHub Actions runs the same clean build on Java 21.

## Postman

1. Run the server with `--demo` as shown in the README.
2. Create a **gRPC** request to `127.0.0.1:9090` with TLS disabled.
3. Import `src/main/proto/traceability.proto` (or use server reflection if supported by your client).
4. Select `TraceabilityService / GetAnimalsForProduct`.
5. Send `{"productId":"P001"}`. Expect registration numbers A001 and A002.
6. Select `GetProductsForAnimal` and send `{"registrationNumber":"A001"}`. Expect products P001 and P002.
7. Query A003 and expect P003 only; T001 was reused with a different batch.
8. Query A004 and expect an empty products list (protobuf JSON may omit empty repeated fields).
9. Query an unknown identifier and expect `NOT_FOUND`; send an empty identifier and expect `INVALID_ARGUMENT`.

## Optional grpcurl

With grpcurl installed, the checked-in contract can be supplied explicitly:

```sh
grpcurl -plaintext -import-path src/main/proto -proto traceability.proto \
  -d '{"productId":"P001"}' 127.0.0.1:9090 \
  traceability.v1.TraceabilityService/GetAnimalsForProduct

grpcurl -plaintext -import-path src/main/proto -proto traceability.proto \
  -d '{"registrationNumber":"A001"}' 127.0.0.1:9090 \
  traceability.v1.TraceabilityService/GetProductsForAnimal
```

The Java client bundled in the executable jar provides the same demonstration without installing Postman or grpcurl.

## Part 2 deliverables

| Assignment item | Delivered artifact |
| --- | --- |
| Service retrieving animal and product information | Two unary RPCs and generated Java API |
| All animal registration numbers involved in a product | `GetAnimalsForProduct` |
| All products involving an animal | `GetProductsForAnimal` |
| Information comes from a database | JDBC joins over persistent H2 tables; SQL resources and persistence test |
| Implemented in gRPC | `.proto`, Netty server, generated stubs and real client |
| Tested | JUnit suite plus manual client instructions |
| Updated C1, C2 and C3 | `docs/architecture.md` |

The broader station registration and offline synchronization system is outside the Part 2 implementation scope. Conservative results follow the original tray-batch domain model; they identify potential, rather than proven individual-part, involvement.

## Recorded local verification

Verified on 2026-10-08 with Amazon Corretto Java 21 on macOS ARM64:

- `mvn clean verify`: **BUILD SUCCESS**; 13 tests, 0 failures, 0 errors, 0 skipped.
- Packaged executable jar starts a gRPC server and creates its disk database.
- Packaged Java client: P001 → A001, A002; A001 → P001, P002; A003 → P003; A004 → no matching products.

Maven used an isolated local dependency cache during verification. Postman/grpcurl instructions are provided for manual reproduction; those clients were not used for this recorded run. GitHub Actions reports its own result separately.

## Part 3 automated coverage

`AnimalRestIntegrationTest` uses Java HttpClient over real loopback HTTP connections and a fresh H2 database per test. Tests cover:

- POST/GET round trip of all required fields, exact weight, UTF-8 farm, default species and Location.
- Date, farm and combined filtering; sorted lists and empty matches.
- Missing animals and duplicate IDs without overwriting records, including concurrent submissions.
- Missing/invalid fields, impossible dates, weight bounds, wrong types, malformed/duplicate/unknown JSON fields.
- Unknown/repeated query parameters, invalid routes/methods, content type and body size.
- URL-encoded identifiers and SQL-injection-shaped input treated as data.
- Immediate visibility of a REST registration through the existing gRPC service.
- Disk persistence after restarting both database and HTTP server.
- Upgrade of a real legacy Part 2 schema, retaining records with null origin and allowing new registrations.
- JSON 503 response on database failure, without leaking SQL details.

The original 13 Part 2 tests remain in the suite. Intentional database failure tests emit expected server-side warning logs. See `target/surefire-reports/` for detailed results.

## Part 3 manual testing and requirements

Use [REST curl examples](rest-api.md) or import [openapi.json](openapi.json) into Postman. Start the executable jar, POST a registration, follow its Location, then query by arrivalDate and origin. Repeat the POST to see 409, request a nonexistent animal to see 404, and submit an invalid weight/date to see 400. Restart from the same directory and GET the registered animal to verify persistence.

| Part 3 requirement | Implementation |
| --- | --- |
| RESTful registration service | POST /api/animals; JSON, 201 and Location |
| Store date, weight, number and farm | Persistent animal row, origin migration, validated fields |
| Read a specific animal | GET /api/animals/{registrationNumber} |
| Read animals arriving on a date | GET /api/animals?arrivalDate=YYYY-MM-DD |
| Read animals of an origin | GET /api/animals?origin=... |
| Tested | HTTP/JDBC integration suite, including migration and persistence |
| Updated C1/C2/C3 | docs/architecture.md |

## Recorded Part 3 verification — 2026-10-21

- Clean Maven verify: **29 tests passed** (16 REST + 13 existing gRPC), 0 failures/errors/skips.
- Executable jar: HTTP registration returned 201 and a usable Location; ID, date, origin and combined filters returned the saved record.
- The packaged gRPC client immediately recognized the REST-registered animal.
- Stopping/restarting the application without demo seeding preserved all registration details.
- Verification used Java 21 on macOS ARM64 and an isolated Maven dependency cache. Postman import/examples are supplied for manual use; Postman was not used for these checks.

## Part 4 complete-system tests

`SystemIntegrationTest` starts a real HTTP broker, independent receiving/cutting/packing/central databases, HTTP station APIs and an authenticated gRPC server. No mocked message broker or shared station database substitutes for the communication path.

| Scenario | Evidence |
| --- | --- |
| Complete receiving → cutting → packing → central flow | Mixed-animal batches, multi-type recipe, multiple products, both recall directions |
| Reused physical tray | Later batch traces only to its own animal/product |
| Broker outages | Receiving/cutting continue using local data; packing works offline after receiving a closed batch |
| Restart and recovery | Broker restarts with its retained log; station restarts retain outbox; all pending updates eventually reach central |
| Durable local registration | Real file database shutdown/reopen retains domain record and unsent event |
| Lost acknowledgement / duplicate event | Broker reaccepts same ID once; projections do not duplicate; changed payload for same ID rejected |
| Bad projection or log gap | Transaction rolls back and cursor stays at last valid event |
| Cutting invariants | Capacity boundary, type mismatch, unknown animal, animal total weight, closed/empty batches, premature tray reuse |
| Packing invariants | Exact recipe, distinct known parts, closed batches, packing date, no reused stock |
| Concurrent packing | Two requests for the same part produce exactly one success and one conflict |
| Transaction rollback | Invalid recipe leaves no partial recipe, requirement rows or outgoing event |
| Ownership and validation | Wrong station rejected; receiving filters retained; bad JSON/route/method rejected |
| Authentication | Broker and central HTTP reject unauthenticated clients; gRPC requires a valid recall bearer token |
| Background delivery | Scheduled pollers deliver without explicit synchronization calls from operator requests |

Run `mvn clean verify` for all parts. Original tests still cover their legacy modes and contracts. Part 4 tests use ephemeral ports; the standalone launcher uses the documented default ports. Tests intentionally induce outages and database failures, so expected warning logs may appear.

### Manual whole-system assessment

1. Build and launch `scripts/run-system.sh`.
2. Run `python3 scripts/demo.py`; inspect the resulting product, inventory and central status.
3. Use its printed authenticated Java gRPC command to retrieve affected products.
4. For separate-process outage demonstrations, follow [operations.md](operations.md), stopping only the broker and keeping station APIs running.
5. Stop/restart all processes with the same data paths and verify stored products and recalls still exist.

### Part 4 requirement mapping

| Requirement | Delivered implementation |
| --- | --- |
| Three subsystems | Independent receiving, cutting and packing processes, role-owned commands and persistent local stores |
| Receiving | Animal registration plus ID/date/origin reads |
| Cutting | Part weighing/source links, typed/capacity-limited trays, immutable batch closure and safe reuse |
| Packing | Product recipes, quantity/type checks, unique stock allocation and recorded source trays/batches |
| External recall | Authenticated gRPC service over central SQL projection, both traceability directions |
| Independent operation under outages | Transactional local outboxes, durable broker, replay/deduplication and persisted consumer offsets |
| Updated documentation | README, API contract, domain/database design, operating guide and test coverage |
| Updated C1/C2/C3 | docs/architecture.md including station, broker and central component views |
| Test everything without GUI | Real HTTP/gRPC integration tests, executable demo and outage walkthrough |

### Recorded Part 4 verification — 2026-11-18

- Clean Maven verify under Amazon Corretto **Java 21**: **42 tests passed**, 0 failures, 0 errors, 0 skipped (13 full-system + 16 Part 3 REST + 13 Part 2 gRPC). The suite also passed under the local Maven default JDK 25.
- Packaged launcher started all five independent Java processes using isolated persistent database files.
- `scripts/demo.py` completed receiving → cutting → packing → central synchronization using HTTP requests.
- Authenticated packaged Java gRPC client returned both expected affected products for the shared-batch animal.
- Stopping and restarting the complete packaged system preserved the product and its part/batch references.
- Shell syntax and Python compilation checks passed for the launcher/demo; OpenAPI JSON references and current documentation file links were checked.

The packaged demonstration ran on macOS ARM64 with Java 21. Maven used an isolated dependency cache. Postman instructions/contracts are provided but Postman itself was not used for this verification. GitHub Actions will run after you commit and push.
