# Testing and assessment guide

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
