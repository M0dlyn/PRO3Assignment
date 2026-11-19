package dk.via.pro3.system;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import dk.via.pro3.*;
import dk.via.pro3.AnimalRegistrationService.ApiException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

/** Same read API on every local replica; only the owning station may perform each command. */
public final class StationServer extends HttpApi {
    private final StationStore store;
    private final SyncWorker sync;
    private final AnimalRegistrationService animals;
    public StationServer(StationStore store,SyncWorker sync,String host,int port,String token) throws IOException {
        super(host,port,token); this.store=store; this.sync=sync;
        animals=new AnimalRegistrationService(new AnimalRepository(store.dataSource()));
    }
    @Override protected void route(HttpExchange e) throws IOException,SQLException {
        String raw=e.getRequestURI().getRawPath(); String[] path=raw.split("/",-1);
        if (raw.equals("/api/status")) { method(e,"GET"); query(e); send(e,200,sync.status()); return; }
        if (path.length<3 || !path[1].equals("api") || !Set.of("animals","trays","batches","parts","product-types","products").contains(path[2]))
            throw new ApiException(404,"Resource not found");
        String resource=path[2];
        if (path.length==5 && resource.equals("batches") && path[4].equals("close") && !path[3].isEmpty()) {
            method(e,"POST"); query(e); var payload=body(e); Json.fields(payload);
            payload.addProperty("batchId",segment(path[3])); store.command("BATCH_CLOSED",payload);
            send(e,200,item(resource,segment(path[3]))); return;
        }
        if (path.length==4 && !path[3].isEmpty()) {
            method(e,"GET"); query(e); String id=segment(path[3]);
            send(e,200,resource.equals("animals") ? animals.find(id) : item(resource,id)); return;
        }
        if (path.length!=3) throw new ApiException(404,"Resource not found");
        method(e,"GET","POST");
        if (e.getRequestMethod().equals("GET")) {
            var q=query(e,resource.equals("animals") ? new String[]{"arrivalDate","origin"} : new String[]{});
            send(e,200,resource.equals("animals") ? animals.list(q.get("arrivalDate"),q.get("origin")) : store.list(resource));
        } else {
            query(e); var payload=body(e);
            String type=switch(resource) {
                case "animals" -> "ANIMAL_REGISTERED"; case "trays" -> "TRAY_CREATED"; case "batches" -> "BATCH_OPENED";
                case "parts" -> "PART_REGISTERED"; case "product-types" -> "RECIPE_CREATED"; case "products" -> "PRODUCT_PACKED";
                default -> throw new ApiException(404,"Resource not found");
            };
            store.command(type,payload); String id=Json.text(payload,idField(resource));
            e.getResponseHeaders().set("Location","/api/"+resource+"/"+URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20"));
            send(e,201,resource.equals("animals") ? animals.find(id) : item(resource,id));
        }
    }
    private JsonObject item(String resource,String id) throws SQLException {
        for (var row:store.list(resource)) if (row.getAsJsonObject().get(idField(resource)).getAsString().equals(id)) return row.getAsJsonObject();
        throw new ApiException(404,"Record not available locally");
    }
    private static String idField(String resource) {
        return switch(resource) {
            case "animals" -> "registrationNumber"; case "trays" -> "trayId"; case "batches" -> "batchId";
            case "parts" -> "partId"; case "product-types" -> "name"; case "products" -> "productId";
            default -> throw Json.bad("Unknown resource");
        };
    }
}
