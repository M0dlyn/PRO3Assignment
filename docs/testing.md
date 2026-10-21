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
