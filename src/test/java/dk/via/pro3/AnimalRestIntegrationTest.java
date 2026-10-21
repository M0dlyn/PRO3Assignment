package dk.via.pro3;

import com.google.gson.*;
import dk.via.pro3.proto.AnimalRequest;
import dk.via.pro3.proto.TraceabilityServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests, SQL persistence, and compatibility with the Part 2 gRPC API. */
class AnimalRestIntegrationTest {
    private DataSource source;
    private AnimalRestServer server;
    private HttpClient client;
    private String base;
    private static final String NEW_ANIMAL = """
            {"registrationNumber":"NEW001","arrivalDate":"2026-10-21",
             "arrivalWeightKg":120.125,"origin":"Øster Farm"}
            """;

    @BeforeEach void start() throws Exception {
        source = Database.dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Database.initialize(source);
        Database.seedDemo(source);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        startHttp();
    }

    private void startHttp() throws Exception {
        server = new AnimalRestServer(source, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach void stop() throws Exception {
        server.close();
        client.close();
        sql("SHUTDOWN");
    }

    private void sql(String command) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private HttpResponse<String> request(String method, String path, String body, String type) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (type != null) builder.header("Content-Type", type);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return request("POST", "/api/animals", body, "application/json");
    }
    private HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, null); }
    private static JsonObject object(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    private static List<String> ids(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        var ids = new ArrayList<String>();
        JsonParser.parseString(response.body()).getAsJsonArray().forEach(
                item -> ids.add(item.getAsJsonObject().get("registrationNumber").getAsString()));
        return ids;
    }

    @Test void registersAndRetrievesAllRequiredDetails() throws Exception {
        var created = post(NEW_ANIMAL);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("/api/animals/NEW001", created.headers().firstValue("Location").orElseThrow());
        assertTrue(created.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var found = get(created.headers().firstValue("Location").orElseThrow());
        assertEquals(200, found.statusCode());
        assertEquals(object(created), object(found));
        var animal = object(found);
        assertEquals("NEW001", animal.get("registrationNumber").getAsString());
        assertEquals("2026-10-21", animal.get("arrivalDate").getAsString());
        assertEquals("120.125", animal.get("arrivalWeightKg").getAsString());
        assertEquals("Øster Farm", animal.get("origin").getAsString());
        assertEquals("unspecified", animal.get("species").getAsString());
    }

    @Test void looksUpByArrivalDate() throws Exception {
        assertEquals(List.of("A001", "A002"), ids(get("/api/animals?arrivalDate=2026-10-01")));
        assertEquals(List.of("A003"), ids(get("/api/animals?arrivalDate=2026-10-02")));
    }

    @Test void looksUpByExactOriginAndCombinedFilters() throws Exception {
        assertEquals(List.of("A001", "A003"), ids(get("/api/animals?origin=North%20Farm")));
        assertEquals(List.of("A003"), ids(get("/api/animals?origin=North+Farm&arrivalDate=2026-10-02")));
        assertEquals(List.of(), ids(get("/api/animals?origin=north%20farm")));
        assertEquals(201, post(NEW_ANIMAL).statusCode());
        assertEquals(List.of("NEW001"), ids(get("/api/animals?origin=%C3%98ster%20Farm")));
    }

    @Test void returnsSortedCollectionAndEmptyResults() throws Exception {
        assertEquals(List.of("A001", "A002", "A003", "A004"), ids(get("/api/animals")));
        assertEquals(List.of(), ids(get("/api/animals?arrivalDate=2020-01-01")));
        assertEquals(List.of(), ids(get("/api/animals?origin=Missing")));
    }

    @Test void missingAnimalReturnsJson404() throws Exception {
        var response = get("/api/animals/missing");
        assertEquals(404, response.statusCode());
        assertEquals("Animal not found", object(response).get("error").getAsString());
    }

    @Test void duplicateRegistrationReturnsConflictAndPreservesOriginal() throws Exception {
        assertEquals(201, post(NEW_ANIMAL).statusCode());
        assertEquals(409, post(NEW_ANIMAL.replace("Øster Farm", "Changed Farm")).statusCode());
        assertEquals("Øster Farm", object(get("/api/animals/NEW001")).get("origin").getAsString());
    }

    @Test void concurrentDuplicateRegistrationsCreateOneAnimal() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/animals"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NEW_ANIMAL)).build();
        var first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var statuses = new ArrayList<>(List.of(first.get(10, TimeUnit.SECONDS).statusCode(),
                second.get(10, TimeUnit.SECONDS).statusCode()));
        Collections.sort(statuses);
        assertEquals(List.of(201, 409), statuses);
        assertEquals(List.of("NEW001"), ids(get("/api/animals?arrivalDate=2026-10-21")));
    }

    @Test void rejectsMissingBlankAndInvalidFieldsWithoutWriting() throws Exception {
        var template = JsonParser.parseString(NEW_ANIMAL).getAsJsonObject();
        for (String key : List.of("registrationNumber", "arrivalDate", "arrivalWeightKg", "origin")) {
            var body = template.deepCopy(); body.remove(key);
            assertEquals(400, post(body.toString()).statusCode(), key);
        }
        for (String key : List.of("registrationNumber", "origin", "species")) {
            for (String value : List.of("", " ", " padded ", "x".repeat(201), "line\nbreak")) {
                var body = template.deepCopy(); body.addProperty(key, value);
                assertEquals(400, post(body.toString()).statusCode(), key + ": " + value);
            }
        }
        for (String value : List.of("2026-02-29", "2026-13-01", "21-10-2026", "0000-01-01")) {
            var body = template.deepCopy(); body.addProperty("arrivalDate", value);
            assertEquals(400, post(body.toString()).statusCode(), value);
        }
        for (String value : List.of("0", "-1", "1000000000", "0.0001", "1e999", "1e-999")) {
            assertEquals(400, post(NEW_ANIMAL.replace("120.125", value)).statusCode(), value);
        }
        assertEquals(404, get("/api/animals/NEW001").statusCode());
    }

    @Test void rejectsMalformedJsonWrongTypesUnknownAndDuplicateFields() throws Exception {
        for (String body : List.of("", "null", "[]", "{", "{} {}", "{unquoted:true}",
                NEW_ANIMAL.replace("120.125", "\"120.125\""),
                NEW_ANIMAL.replace("120.125", "null"),
                NEW_ANIMAL.replace("\"NEW001\"", "123"),
                NEW_ANIMAL.replace("\"NEW001\"", "false"),
                NEW_ANIMAL.replace("\"NEW001\"", "{}"),
                NEW_ANIMAL.replace("\"origin\"", "\"farm\""),
                NEW_ANIMAL.replace("{", "{\"origin\":\"Other\","))) {
            assertEquals(400, post(body).statusCode(), body);
        }
    }

    @Test void rejectsInvalidAndDuplicateQueryParameters() throws Exception {
        for (String query : List.of("arrivalDate=2026-02-30", "arrivalDate=", "origin=",
                "origin=%20", "farm=North", "origin=North&origin=South", "arrivalDate=2026-10-01&arrivalDate=2026-10-02")) {
            assertEquals(400, get("/api/animals?" + query).statusCode(), query);
        }
        assertEquals(400, get("/api/animals/A001?origin=North").statusCode());
    }

    @Test void enforcesRoutesMethodsMediaTypeAndBodyLimit() throws Exception {
        assertEquals(404, get("/api/animalsXYZ").statusCode());
        assertEquals(404, get("/api/animals/A001/extra").statusCode());
        var method = request("DELETE", "/api/animals/A001", null, null);
        assertEquals(405, method.statusCode());
        assertEquals("GET", method.headers().firstValue("Allow").orElseThrow());
        assertEquals(405, request("POST", "/api/animals/A001", NEW_ANIMAL, "application/json").statusCode());
        assertEquals(415, request("POST", "/api/animals", NEW_ANIMAL, "text/plain").statusCode());
        assertEquals(415, request("POST", "/api/animals", NEW_ANIMAL, null).statusCode());
        assertEquals(413, post(" ".repeat(16385)).statusCode());
    }

    @Test void encodedIdentifiersRoundTripAndSqlInputStaysData() throws Exception {
        var created = post(NEW_ANIMAL.replace("NEW001", "DK/42 +x"));
        assertEquals(201, created.statusCode());
        var found = get(created.headers().firstValue("Location").orElseThrow());
        assertEquals(200, found.statusCode());
        assertEquals("DK/42 +x", object(found).get("registrationNumber").getAsString());
        assertEquals(List.of(), ids(get("/api/animals?origin=%27%20OR%20%271%27%3D%271")));
        assertEquals(404, get("/api/animals/%27%20OR%20%271%27%3D%271").statusCode());
    }

    @Test void newRegistrationIsImmediatelyKnownToGrpc() throws Exception {
        assertEquals(201, post(NEW_ANIMAL).statusCode());
        var grpc = TraceabilityServer.create(source, "127.0.0.1", 0).start();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.getPort()).usePlaintext().build();
        try {
            var result = TraceabilityServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getProductsForAnimal(AnimalRequest.newBuilder().setRegistrationNumber("NEW001").build());
            assertEquals(0, result.getProductsCount());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            grpc.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test void persistsRegistrationAcrossDatabaseAndServerRestart(@TempDir Path directory) throws Exception {
        server.close(); sql("SHUTDOWN");
        String url = "jdbc:h2:file:" + directory.resolve("animals");
        source = Database.dataSource(url);
        Database.initialize(source); startHttp();
        assertEquals(201, post(NEW_ANIMAL).statusCode());
        server.close(); sql("SHUTDOWN");
        source = Database.dataSource(url);
        Database.initialize(source); startHttp();
        var result = get("/api/animals/NEW001");
        assertEquals(200, result.statusCode());
        assertEquals("Øster Farm", object(result).get("origin").getAsString());
    }

    @Test void upgradesExistingPart2SchemaWithoutInventingOrigin(@TempDir Path directory) throws Exception {
        server.close(); sql("SHUTDOWN");
        source = Database.dataSource("jdbc:h2:file:" + directory.resolve("legacy"));
        sql("CREATE TABLE animal (registration_number VARCHAR(100) PRIMARY KEY, species VARCHAR(100) NOT NULL, "
                + "arrival_weight_kg DECIMAL(12,3) NOT NULL, arrival_date DATE NOT NULL)");
        sql("INSERT INTO animal VALUES ('OLD001', 'pig', 100.000, DATE '2026-10-01')");
        Database.initialize(source);
        Database.initialize(source); // Repeat the migration safely.
        Database.seedDemo(source); // Existing records must not be overwritten by demonstration data.
        startHttp();
        var legacy = get("/api/animals/OLD001");
        assertEquals(200, legacy.statusCode());
        assertTrue(object(legacy).get("origin").isJsonNull());
        assertEquals("100.000", object(legacy).get("arrivalWeightKg").getAsString());
        assertEquals(201, post(NEW_ANIMAL).statusCode());
        assertEquals(List.of("NEW001", "OLD001"), ids(get("/api/animals")));
    }

    @Test void databaseFailureReturns503WithoutSqlDetails() throws Exception {
        sql("ALTER TABLE animal RENAME TO unavailable_animal");
        var result = get("/api/animals");
        assertEquals(503, result.statusCode());
        assertEquals("Animal database unavailable", object(result).get("error").getAsString());
    }
}
