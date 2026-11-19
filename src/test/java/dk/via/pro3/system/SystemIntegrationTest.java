package dk.via.pro3.system;

import com.google.gson.*;
import dk.via.pro3.*;
import dk.via.pro3.proto.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real independent databases, HTTP broker/stations and authenticated gRPC recall. */
class SystemIntegrationTest {
    @TempDir Path directory;
    private DataSource brokerData;
    private BrokerServer broker;
    private int brokerPort;
    private final Map<String,StationStore> stores=new LinkedHashMap<>();
    private final Map<String,SyncWorker> workers=new LinkedHashMap<>();
    private final Map<String,StationServer> servers=new LinkedHashMap<>();
    private HttpClient client;
    private static final String TOKEN="test-broker", RECALL="test-recall";

    @BeforeEach void start() throws Exception {
        brokerData=Database.dataSource("jdbc:h2:file:"+directory.resolve("broker"));
        broker=new BrokerServer(brokerData,"127.0.0.1",0,TOKEN); broker.start(); brokerPort=broker.port();
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for (String role:List.of("receiving","cutting","packing","central")) {
            var store=new StationStore(Database.dataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"),role);
            stores.put(role,store); openNode(role,store);
        }
    }
    private void openNode(String role,StationStore store) throws Exception {
        var worker=new SyncWorker(store,"http://127.0.0.1:"+brokerPort,TOKEN); workers.put(role,worker);
        var server=new StationServer(store,worker,"127.0.0.1",0,role.equals("central")?RECALL:"");
        servers.put(role,server); server.start();
    }
    @AfterEach void stop() throws Exception {
        for (var s:servers.values()) s.close();
        for (var w:workers.values()) w.close();
        if (broker!=null) broker.close();
        if (client!=null) client.close();
        for (var s:stores.values()) { s.close(); shutdown(s.dataSource()); }
        if (brokerData!=null) shutdown(brokerData);
    }
    private static void shutdown(DataSource source) throws Exception {
        try (var c=source.getConnection();var statement=c.createStatement()) { statement.execute("SHUTDOWN"); }
    }
    private HttpResponse<String> http(String role,String method,String path,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+servers.get(role).port()+path)).timeout(Duration.ofSeconds(5));
        if (role.equals("central")) request.header("Authorization","Bearer "+RECALL);
        if (body!=null) request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonObject post(String role,String path,String body) throws Exception {
        var response=http(role,"POST","/api/"+path,body);
        assertTrue(response.statusCode()==200 || response.statusCode()==201,response.statusCode()+": "+response.body());
        return Json.object(response.body());
    }
    private void sync() {
        for (int iteration=0;iteration<3;iteration++) for (var worker:workers.values())
            assertTrue(worker.tick(),() -> { try { return worker.status().toString(); } catch(Exception e) { return e.toString(); } });
    }
    private void animal(String id) throws Exception {
        post("receiving","animals","{\"registrationNumber\":\""+id+"\",\"arrivalDate\":\"2026-11-19\",\"arrivalWeightKg\":120,\"origin\":\"Farm One\",\"species\":\"pig\"}");
    }
    private void trayBatch(String tray,String batch,String type,int capacity) throws Exception {
        post("cutting","trays","{\"trayId\":\""+tray+"\",\"maximumWeightKg\":"+capacity+"}");
        openBatch(tray,batch,type);
    }
    private void openBatch(String tray,String batch,String type) throws Exception {
        post("cutting","batches","{\"trayId\":\""+tray+"\",\"batchId\":\""+batch+"\",\"partType\":\""+type+"\"}");
    }
    private String partBody(String id,String animal,String batch,String type,int weight) {
        return "{\"partId\":\""+id+"\",\"animalRegistrationNumber\":\""+animal+"\",\"batchId\":\""+batch+"\",\"partType\":\""+type+"\",\"weightKg\":"+weight+"}";
    }
    private void part(String id,String animal,String batch,String type,int weight) throws Exception {
        post("cutting","parts",partBody(id,animal,batch,type,weight));
    }
    private void closeBatch(String id) throws Exception { post("cutting","batches/"+id+"/close","{}"); }
    private void recipe(String name,String type,int quantity) throws Exception {
        post("packing","product-types","{\"name\":\""+name+"\",\"requirements\":[{\"partType\":\""+type+"\",\"quantity\":"+quantity+"}]}");
    }
    private String product(String id,String type,String... parts) {
        var result=new JsonObject(); result.addProperty("productId",id); result.addProperty("productType",type);
        result.addProperty("packedDate","2099-01-01"); result.add("partIds",Json.CODEC.toJsonTree(parts)); return result.toString();
    }

    @Test void fullWorkflowPreservesConservativeRecallAndSeparatesReusedTrays() throws Exception {
        animal("A1"); animal("A2"); animal("A3"); sync();
        trayBatch("T1","B1","leg",30); trayBatch("T2","B2","loin",10);
        part("L1","A1","B1","leg",8); part("L2","A2","B1","leg",9); part("L3","A1","B1","leg",8);
        part("LOIN","A1","B2","loin",5); closeBatch("B1"); closeBatch("B2"); sync();
        post("packing","product-types","{\"name\":\"mixed\",\"requirements\":[{\"partType\":\"leg\",\"quantity\":1},{\"partType\":\"loin\",\"quantity\":1}]}");
        recipe("legs","leg",2); recipe("single","leg",1);
        post("packing","products",product("P1","mixed","L2","LOIN"));
        post("packing","products",product("P2","legs","L1","L3")); sync();
        openBatch("T1","B3","leg"); part("L4","A3","B3","leg",8); closeBatch("B3"); sync();
        post("packing","products",product("P3","single","L4")); sync();
        var repository=new TraceabilityRepository(stores.get("central").dataSource());
        assertEquals(List.of("A1","A2"),repository.animalsForProduct("P1").orElseThrow().stream().map(TraceabilityRepository.AnimalRecord::registrationNumber).toList());
        assertEquals(List.of("P1","P2"),repository.productsForAnimal("A2").orElseThrow().stream().map(TraceabilityRepository.ProductRecord::productId).toList());
        assertEquals(List.of("P3"),repository.productsForAnimal("A3").orElseThrow().stream().map(TraceabilityRepository.ProductRecord::productId).toList());
        var grpc=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).intercept(new RecallAuth(RECALL))
                .addService(new TraceabilityService(repository)).build().start();
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",grpc.getPort()).usePlaintext().build();
        try {
            var stub=TraceabilityServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
            var denied=assertThrows(StatusRuntimeException.class,() -> stub.getAnimalsForProduct(ProductRequest.newBuilder().setProductId("P1").build()));
            assertEquals(Status.Code.UNAUTHENTICATED,denied.getStatus().getCode());
            var headers=new Metadata(); headers.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer "+RECALL);
            var authenticated=stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            assertEquals(2,authenticated.getAnimalsForProduct(ProductRequest.newBuilder().setProductId("P1").build()).getAnimalsCount());
            assertEquals(2,authenticated.getProductsForAnimal(AnimalRequest.newBuilder().setRegistrationNumber("A2").build()).getProductsCount());
        } finally { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); grpc.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
    }

    @Test void stationsContinueDuringBrokerOutagesAndRecoverAfterRestarts() throws Exception {
        animal("A1"); sync();
        broker.close(); broker=null;
        animal("OFFLINE");
        trayBatch("T1","B1","leg",20); part("L1","A1","B1","leg",8); closeBatch("B1");
        assertFalse(workers.get("receiving").tick()); assertFalse(workers.get("cutting").tick());
        assertTrue(stores.get("cutting").status().get("pendingOutbox").getAsLong()>0);
        assertFalse(workers.get("central").tick());
        assertFalse(workers.get("central").status().get("caughtUpToObservedBroker").getAsBoolean());
        servers.get("cutting").close(); workers.get("cutting").close();
        stores.get("cutting").close();
        var restored=new StationStore(stores.get("cutting").dataSource(),"cutting"); stores.put("cutting",restored); openNode("cutting",restored);
        broker=new BrokerServer(brokerData,"127.0.0.1",brokerPort,TOKEN); broker.start(); sync();
        assertEquals(200,http("central","GET","/api/animals/OFFLINE",null).statusCode());
        broker.close(); broker=null;
        recipe("single","leg",1); post("packing","products",product("P1","single","L1"));
        assertFalse(workers.get("packing").tick());
        assertEquals(404,http("central","GET","/api/products/P1",null).statusCode());
        broker=new BrokerServer(brokerData,"127.0.0.1",brokerPort,TOKEN); broker.start(); sync();
        assertEquals(200,http("central","GET","/api/products/P1",null).statusCode());
        for (var worker:workers.values()) assertTrue(worker.status().get("caughtUpToObservedBroker").getAsBoolean());
        for (var store:stores.values()) assertEquals(0,store.status().get("pendingOutbox").getAsLong());
    }

    @Test void registrationAndOutboxSurviveFileDatabaseShutdown() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("durable-receiving");
        var source=Database.dataSource(url); var node=new StationStore(source,"receiving");
        node.command("ANIMAL_REGISTERED",Json.object("{\"registrationNumber\":\"DURABLE\",\"arrivalDate\":\"2026-11-19\",\"arrivalWeightKg\":100,\"origin\":\"Farm\"}"));
        node.close(); shutdown(source);
        var restored=new StationStore(Database.dataSource(url),"receiving");
        assertEquals(1,restored.pending().size());
        try (var worker=new SyncWorker(restored,"http://127.0.0.1:"+brokerPort,TOKEN)) { assertTrue(worker.tick()); }
        sync(); assertEquals(200,http("central","GET","/api/animals/DURABLE",null).statusCode());
        restored.close(); shutdown(restored.dataSource());
    }

    @Test void duplicateDeliveriesAndLostAcknowledgementsHaveOneEffect() throws Exception {
        animal("A1");
        var pending=stores.get("receiving").pending().get(0); Event event=Event.parse(pending.json());
        long first=broker.publish(event); // Simulate durable publish followed by lost acknowledgement.
        assertEquals(first,broker.publish(event));
        sync();
        assertEquals(1,JsonParser.parseString(http("central","GET","/api/animals",null).body()).getAsJsonArray().size());
        assertEquals(0,stores.get("receiving").pending().size());
        var changed=event.payload().deepCopy(); changed.addProperty("origin","Another Farm");
        assertThrows(dk.via.pro3.AnimalRegistrationService.ApiException.class,() -> broker.publish(new Event(event.eventId(),1,event.source(),event.type(),changed)));
        broker.close(); broker=new BrokerServer(brokerData,"127.0.0.1",brokerPort,TOKEN); broker.start();
        assertEquals(first,broker.publish(event));
        stores.get("central").accept(first,event); // Repeated cursor is safe.
        assertEquals(first,stores.get("central").cursor());
    }

    @Test void projectionFailureKeepsCursorAndRecordAtomic() throws Exception {
        Event invalid=Event.create("cutting","TRAY_CREATED",Json.object("{\"trayId\":\"BAD\",\"maximumWeightKg\":-1}"));
        assertThrows(dk.via.pro3.AnimalRegistrationService.ApiException.class,() -> stores.get("central").accept(1,invalid));
        assertEquals(0,stores.get("central").cursor()); assertTrue(stores.get("central").list("trays").isEmpty());
        Event valid=Event.create("cutting","TRAY_CREATED",Json.object("{\"trayId\":\"T1\",\"maximumWeightKg\":10}"));
        assertThrows(dk.via.pro3.AnimalRegistrationService.ApiException.class,() -> stores.get("central").accept(2,valid));
        stores.get("central").accept(1,valid); assertEquals(1,stores.get("central").cursor());
    }

    @Test void capacityPartTypeAndAnimalWeightAreEnforcedAtomically() throws Exception {
        animal("A1"); sync(); trayBatch("T1","B1","leg",10); part("L1","A1","B1","leg",8);
        long before=stores.get("cutting").status().get("pendingOutbox").getAsLong();
        assertEquals(409,http("cutting","POST","/api/parts",partBody("OVER","A1","B1","leg",3)).statusCode());
        assertEquals(409,http("cutting","POST","/api/parts",partBody("WRONG","A1","B1","loin",1)).statusCode());
        assertEquals(404,http("cutting","POST","/api/parts",partBody("UNKNOWN","MISSING","B1","leg",1)).statusCode());
        assertEquals(before,stores.get("cutting").status().get("pendingOutbox").getAsLong());
        part("L2","A1","B1","leg",2); // Exact capacity permitted.
        trayBatch("T2","B2","loin",200);
        assertEquals(409,http("cutting","POST","/api/parts",partBody("HEAVY","A1","B2","loin",111)).statusCode());
    }

    @Test void closureAndTrayReusePreserveHistoricalMembership() throws Exception {
        animal("A1"); sync(); trayBatch("T1","B1","leg",10);
        assertEquals(409,http("cutting","POST","/api/batches/B1/close","{}").statusCode());
        assertEquals(409,http("cutting","POST","/api/batches","{\"trayId\":\"T1\",\"batchId\":\"B2\",\"partType\":\"leg\"}").statusCode());
        part("L1","A1","B1","leg",8); closeBatch("B1");
        assertEquals(409,http("cutting","POST","/api/parts",partBody("L2","A1","B1","leg",1)).statusCode());
        assertEquals(409,http("cutting","POST","/api/batches/B1/close","{}").statusCode());
        assertEquals(409,http("cutting","POST","/api/batches","{\"trayId\":\"T1\",\"batchId\":\"B2\",\"partType\":\"leg\"}").statusCode());
    }

    @Test void packingRequiresExactRecipeClosedBatchesAndAvailableDistinctParts() throws Exception {
        animal("A1"); sync(); trayBatch("T1","B1","leg",30); part("L1","A1","B1","leg",8); part("L2","A1","B1","leg",8); sync();
        recipe("pair","leg",2); recipe("single","leg",1);
        assertEquals(409,http("packing","POST","/api/products",product("OPEN","single","L1")).statusCode());
        closeBatch("B1"); sync();
        assertEquals(409,http("packing","POST","/api/products",product("MISSING","pair","L1")).statusCode());
        assertEquals(400,http("packing","POST","/api/products",product("DUP","pair","L1","L1")).statusCode());
        assertEquals(404,http("packing","POST","/api/products",product("UNKNOWN","single","no-part")).statusCode());
        assertEquals(409,http("packing","POST","/api/products",product("EARLY","single","L1").replace("2099-01-01","2000-01-01")).statusCode());
        post("packing","products",product("P1","pair","L1","L2"));
        assertEquals(409,http("packing","POST","/api/products",product("REUSE","single","L1")).statusCode());
        assertEquals(1,stores.get("packing").list("products").size());
    }

    @Test void concurrentPackingCannotConsumeTheSamePartTwice() throws Exception {
        animal("A1"); sync(); trayBatch("T1","B1","leg",10); part("L1","A1","B1","leg",8); closeBatch("B1"); sync(); recipe("single","leg",1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> http("packing","POST","/api/products",product("P1","single","L1")).statusCode());
            var second=executor.submit(() -> http("packing","POST","/api/products",product("P2","single","L1")).statusCode());
            var codes=new ArrayList<>(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))); Collections.sort(codes);
            assertEquals(List.of(201,409),codes); assertEquals(1,stores.get("packing").list("products").size());
        }
    }

    @Test void recipeFailureRollsBackItsPartialRowsAndOutbox() throws Exception {
        long before=stores.get("packing").status().get("pendingOutbox").getAsLong();
        assertEquals(400,http("packing","POST","/api/product-types","{\"name\":\"bad\",\"requirements\":[{\"partType\":\"leg\",\"quantity\":1},{\"partType\":\"leg\",\"quantity\":1}]}").statusCode());
        assertEquals(400,http("packing","POST","/api/product-types","{\"name\":\"empty\",\"requirements\":[]}").statusCode());
        assertTrue(stores.get("packing").list("product-types").isEmpty());
        assertEquals(before,stores.get("packing").status().get("pendingOutbox").getAsLong());
    }

    @Test void ownershipValidationAndReceivingQueriesRemainAvailableLocally() throws Exception {
        animal("A1");
        assertEquals(1,JsonParser.parseString(http("receiving","GET","/api/animals?arrivalDate=2026-11-19&origin=Farm%20One",null).body()).getAsJsonArray().size());
        assertEquals(403,http("packing","POST","/api/trays","{\"trayId\":\"T1\",\"maximumWeightKg\":10}").statusCode());
        assertEquals(403,http("central","POST","/api/animals","{}").statusCode());
        assertEquals(400,http("cutting","POST","/api/trays","{\"trayId\":\"T\",\"maximumWeightKg\":0}").statusCode());
        assertEquals(400,http("cutting","POST","/api/trays","{\"trayId\":\"T\",\"trayId\":\"U\",\"maximumWeightKg\":10}").statusCode());
        assertEquals(400,http("receiving","GET","/api/animals?origin=x&origin=y",null).statusCode());
        assertEquals(405,http("receiving","DELETE","/api/animals/A1",null).statusCode());
        assertEquals(404,http("cutting","GET","/api/trays/T/more",null).statusCode());
        assertThrows(IllegalArgumentException.class,() -> new StationStore(stores.get("cutting").dataSource(),"packing"));
    }

    @Test void brokerAndCentralHttpRejectUnauthenticatedRequests() throws Exception {
        for (int port:List.of(brokerPort,servers.get("central").port())) {
            String path=port==brokerPort?"/events":"/api/status";
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build();
            assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertThrows(dk.via.pro3.AnimalRegistrationService.ApiException.class,
                () -> Event.create("receiving","PRODUCT_PACKED",new JsonObject()));
    }

    @Test void offlineCommandsDoNotCallBrokerAndAutomaticWorkerEventuallyDelivers() throws Exception {
        animal("A1");
        assertEquals(404,http("central","GET","/api/animals/A1",null).statusCode());
        for (var worker:workers.values()) worker.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (http("central","GET","/api/animals/A1",null).statusCode()!=200) {
            assertTrue(System.nanoTime()<deadline,"Automatic background delivery timed out"); Thread.sleep(50);
        }
    }
}
