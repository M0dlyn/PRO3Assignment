package dk.via.pro3.system;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;

/** Retries durable outbox deliveries and replays broker events without blocking operators. */
public final class SyncWorker implements AutoCloseable {
    private final StationStore store;
    private final String broker,token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private volatile String lastError="Not synchronized yet",lastSuccess=null;
    private volatile long brokerSequence=-1;
    public SyncWorker(StationStore store,String broker,String token) {
        this.store=store; this.broker=broker.replaceAll("/+$",""); this.token=token;
    }
    public void start() { scheduler.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS); }
    public synchronized boolean tick() {
        try {
            for (var pending:store.pending()) {
                request("POST","/events",pending.json());
                store.delivered(pending.sequence()); // Only after durable broker acknowledgement.
            }
            String response=request("GET","/events?after="+store.cursor(),null);
            for (var value:JsonParser.parseString(response).getAsJsonArray()) {
                var item=value.getAsJsonObject();
                store.accept(item.get("sequence").getAsLong(),Event.parse(item.get("event").toString()));
            }
            brokerSequence=Json.object(request("GET","/status",null)).get("lastSequence").getAsLong();
            lastError=null; lastSuccess=Instant.now().toString(); return true;
        } catch(Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            lastError=e.getClass().getSimpleName()+": "+e.getMessage(); return false;
        }
    }
    private String request(String method,String path,String json) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(broker+path)).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+token);
        if (json!=null) request.header("Content-Type","application/json");
        var response=client.send(request.method(method,json==null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IllegalStateException("Broker HTTP "+response.statusCode());
        return response.body();
    }
    public JsonObject status() throws java.sql.SQLException {
        var status=store.status(); status.addProperty("lastSyncError",lastError); status.addProperty("lastSuccessfulSync",lastSuccess);
        status.addProperty("lastObservedBrokerSequence",brokerSequence);
        status.addProperty("caughtUpToObservedBroker",lastError==null && store.cursor()==brokerSequence);
        return status;
    }
    @Override public void close() {
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(5,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        client.close();
    }
}
