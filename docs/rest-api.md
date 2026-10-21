# Part 3 — Animal registration REST API

Base URL: `http://127.0.0.1:8080`. All replies use JSON. Import [openapi.json](openapi.json) into Postman for the API contract, or use the curl requests below. No graphical UI is required.

## Endpoints

| Method and path | Result |
| --- | --- |
| `POST /api/animals` | Register an animal; `201 Created`, full record and relative `Location` URL |
| `GET /api/animals/{registrationNumber}` | Read one animal; `200` or `404` |
| `GET /api/animals?arrivalDate=2026-10-21` | Read all animals arriving on that date |
| `GET /api/animals?origin=North%20Farm` | Read all animals from exactly that farm |
| `GET /api/animals?arrivalDate=2026-10-21&origin=North%20Farm` | Intersection of both filters |
| `GET /api/animals` | Read all animals |

Collections are JSON arrays, sorted by registration number; no matches returns `200` and `[]`. Identifiers and origin matching are case-sensitive. URL-encode path identifiers and query values. Trailing-slash routes are not accepted. Repeated or unknown query parameters are rejected to avoid silently ignoring a filter typo.

## Registration body

```json
{
  "registrationNumber": "DK1001",
  "arrivalDate": "2026-10-21",
  "arrivalWeightKg": 120.125,
  "origin": "North Farm",
  "species": "pig"
}
```

| Field | Rules |
| --- | --- |
| `registrationNumber` | Required string, 1–100 characters; unique |
| `arrivalDate` | Required valid calendar date, `YYYY-MM-DD`, year 0001–9999 |
| `arrivalWeightKg` | Required JSON number; greater than zero, at most 999999999.999, at most three decimal places (trailing zeros ignored) |
| `origin` | Required farm name/identifier, string of 1–200 characters |
| `species` | Optional string of 1–100 characters; defaults to `unspecified` for compatibility with the Part 2 model |

Text must be nonblank with no surrounding whitespace or control characters. Unknown/duplicate JSON fields, null values, incorrect types and malformed JSON are rejected. Body limit: 16 KiB. Send UTF-8 JSON with `Content-Type: application/json`. Decimal values are stored without binary floating-point rounding.

Records migrated from Part 2 may have `origin: null`, because no farm was recorded then. New REST registrations always require a farm. Existing data is never replaced by the sample data or migration.

## Demonstration

From the repository directory:

```sh
mvn clean verify
java -jar target/traceability-1.0.0.jar --demo
```

In another terminal:

```sh
curl -i -X POST http://127.0.0.1:8080/api/animals \
  -H 'Content-Type: application/json' \
  -d '{"registrationNumber":"DK1001","arrivalDate":"2026-10-21","arrivalWeightKg":120.125,"origin":"North Farm","species":"pig"}'

curl -i http://127.0.0.1:8080/api/animals/DK1001

curl -G http://127.0.0.1:8080/api/animals \
  --data-urlencode 'arrivalDate=2026-10-21'

curl -G http://127.0.0.1:8080/api/animals \
  --data-urlencode 'origin=North Farm'
```

The first POST returns `201`; repeating it returns `409`, preserving the original record. Stop/restart the server and GET the same identifier to check disk persistence. The same working directory and `DB_URL` must be used on restart.

Fresh demo data: North Farm has A001 and A003; South Farm has A002; West Farm has A004. A001 and A002 arrived on 2026-10-01. An existing Part 2 database is not reseeded, so its records keep unknown origins.

## Error responses

```json
{"error":"Animal registration number already exists"}
```

| Status | Meaning |
| --- | --- |
| `400 Bad Request` | Invalid fields, malformed JSON, or invalid/unknown/repeated query parameters |
| `404 Not Found` | Unknown animal or route |
| `405 Method Not Allowed` | Unsupported method; response includes `Allow` |
| `409 Conflict` | Registration number already exists |
| `413 Content Too Large` | Body exceeds 16 KiB |
| `415 Unsupported Media Type` | Registration was not sent as application/json |
| `503 Service Unavailable` | SQL operation failed; SQL details are not sent to the client |
| `500 Internal Server Error` | Unexpected application error |

No update/delete endpoints are implemented: registration history and Part 2 traceability references are preserved. The API has no authentication in this local coursework demonstration.
