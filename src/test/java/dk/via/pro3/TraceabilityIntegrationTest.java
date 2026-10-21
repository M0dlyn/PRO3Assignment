package dk.via.pro3;

import dk.via.pro3.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Uses generated stubs, a real TCP gRPC server, and SQL data (no mocked repository). */
class TraceabilityIntegrationTest {
    private DataSource source;
    private Server server;
    private ManagedChannel channel;
    private TraceabilityServiceGrpc.TraceabilityServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        source = Database.dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Database.initialize(source);
        Database.seedDemo(source);
        connect(source);
    }

    private void connect(DataSource database) throws Exception {
        server = TraceabilityServer.create(database, "127.0.0.1", 0).start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        stub = TraceabilityServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private void disconnect() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        disconnect();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private AnimalsResponse animals(String id) {
        return stub.getAnimalsForProduct(ProductRequest.newBuilder().setProductId(id).build());
    }

    private ProductsResponse products(String id) {
        return stub.getProductsForAnimal(AnimalRequest.newBuilder().setRegistrationNumber(id).build());
    }

    private static List<String> animalIds(AnimalsResponse response) {
        return response.getAnimalsList().stream().map(Animal::getRegistrationNumber).toList();
    }

    private static List<String> productIds(ProductsResponse response) {
        return response.getProductsList().stream().map(Product::getProductId).toList();
    }

    private void sql(String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void productReturnsAllPotentialAnimalsOnceAcrossMultipleBatches() {
        var result = animals("P001");
        assertEquals(List.of("A001", "A002"), animalIds(result));
        var first = result.getAnimals(0);
        assertEquals("pig", first.getSpecies());
        assertEquals("120.000", first.getArrivalWeightKg());
        assertEquals("2026-10-01", first.getArrivalDate());
    }

    @Test
    void animalReturnsAllPotentialProductsOnce() {
        var result = products("A001");
        assertEquals(List.of("P001", "P002"), productIds(result));
        assertEquals("mixed-pack", result.getProducts(0).getProductType());
        assertEquals("2026-10-01", result.getProducts(0).getPackedDate());
        assertEquals(List.of("P001", "P002"), productIds(products("A002")));
    }

    @Test
    void reusedPhysicalTrayDoesNotConnectSeparateBatches() {
        assertEquals(List.of("A003"), animalIds(animals("P003")));
        assertEquals(List.of("P003"), productIds(products("A003")));
        assertFalse(productIds(products("A001")).contains("P003"));
    }

    @Test
    void knownAnimalWithoutProductsReturnsEmptySuccess() {
        assertEquals(0, products("A004").getProductsCount());
    }

    @Test
    void knownProductWithoutProjectedLinksReturnsEmptySuccess() throws Exception {
        sql("INSERT INTO product VALUES ('P004', 'leg-pack', DATE '2026-10-03')");
        assertEquals(0, animals("P004").getAnimalsCount());
    }

    @Test
    void unknownEntitiesReturnNotFound() {
        assertCode(Status.Code.NOT_FOUND, () -> animals("missing"));
        assertCode(Status.Code.NOT_FOUND, () -> products("missing"));
    }

    @Test
    void invalidIdentifiersReturnInvalidArgumentInBothMethods() {
        for (String id : List.of("", "   ", " A001", "A001 ", "a".repeat(101))) {
            assertCode(Status.Code.INVALID_ARGUMENT, () -> animals(id));
            assertCode(Status.Code.INVALID_ARGUMENT, () -> products(id));
        }
    }

    @Test
    void identifiersAreCaseSensitiveAndSqlIsParameterized() {
        for (String id : List.of("a001", "' OR '1'='1")) {
            assertCode(Status.Code.NOT_FOUND, () -> products(id));
        }
        assertCode(Status.Code.NOT_FOUND, () -> animals("' OR '1'='1"));
        assertEquals(List.of("A001", "A002"), animalIds(animals("P001")));
    }

    @Test
    void readsReflectDatabaseChangesInsteadOfHardcodedResponses() throws Exception {
        sql("INSERT INTO product VALUES ('P999', 'leg-pack', DATE '2026-10-03')");
        sql("INSERT INTO product_tray_batch VALUES ('P999', 'B001')");
        assertEquals(List.of("P001", "P002", "P999"), productIds(products("A002")));
        assertEquals(List.of("A001", "A002"), animalIds(animals("P999")));
    }

    @Test
    void schemaRejectsWrongPartTypeAndBrokenTraceabilityReferences() {
        assertThrows(SQLException.class, () -> sql(
                "INSERT INTO part VALUES ('BAD', 'A001', 'B001', 'loin', 1.000)"));
        assertThrows(SQLException.class, () -> sql(
                "INSERT INTO part VALUES ('BAD', 'unknown', 'B001', 'leg', 1.000)"));
        assertThrows(SQLException.class, () -> sql(
                "INSERT INTO product_tray_batch VALUES ('P001', 'unknown')"));
        assertThrows(SQLException.class, () -> sql(
                "INSERT INTO part VALUES ('BAD', 'A001', 'B001', 'leg', -1.000)"));
        assertThrows(SQLException.class, () -> sql(
                "INSERT INTO product_tray_batch VALUES ('P001', 'B001')"));
    }

    @Test
    void initializationAndDemoCanBeRepeatedWithoutDuplicatingData() throws Exception {
        Database.initialize(source);
        Database.seedDemo(source);
        assertEquals(List.of("A001", "A002"), animalIds(animals("P001")));
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM part")) {
            rows.next();
            assertEquals(6, rows.getInt(1));
        }
    }

    @Test
    void dataSurvivesClosingDatabaseAndRestartingServer(@TempDir Path directory) throws Exception {
        disconnect();
        var persistent = Database.dataSource("jdbc:h2:file:" + directory.resolve("traceability"));
        Database.initialize(persistent);
        Database.seedDemo(persistent);
        connect(persistent);
        assertEquals(List.of("P001", "P002"), productIds(products("A001")));
        disconnect();
        try (var connection = persistent.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        var reopened = Database.dataSource("jdbc:h2:file:" + directory.resolve("traceability"));
        Database.initialize(reopened); // No seed on restart.
        connect(reopened);
        assertEquals(List.of("A001", "A002"), animalIds(animals("P001")));
        assertEquals(List.of("P003"), productIds(products("A003")));
    }

    @Test
    void databaseFailureBecomesUnavailableWithoutExposingSql() throws Exception {
        sql("DROP TABLE product_tray_batch");
        var error = assertThrows(StatusRuntimeException.class, () -> animals("P001"));
        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        assertEquals("Traceability database unavailable", error.getStatus().getDescription());
    }

    private static void assertCode(Status.Code expected, Runnable call) {
        var error = assertThrows(StatusRuntimeException.class, call::run);
        assertEquals(expected, error.getStatus().getCode());
    }
}
