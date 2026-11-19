package dk.via.pro3.system;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import dk.via.pro3.AnimalRegistrationService.ApiException;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/** Small persistent append-only fan-out broker with durable offsets and idempotent publishing. */
public final class BrokerServer extends HttpApi {
    private final DataSource source;
    private Connection keepAlive;
    public BrokerServer(DataSource source,String host,int port,String token) throws Exception {
        super(host,port,required(token)); this.source=source;
        try (var c=source.getConnection()) {
            Sql.update(c,"CREATE TABLE IF NOT EXISTS broker_state (id INTEGER PRIMARY KEY, last_sequence BIGINT NOT NULL)");
            Sql.update(c,"CREATE TABLE IF NOT EXISTS event_log (sequence_number BIGINT PRIMARY KEY,event_id VARCHAR(36) UNIQUE NOT NULL,event_json CLOB NOT NULL)");
            if (Sql.rows(c,"SELECT id FROM broker_state WHERE id=1").isEmpty()) Sql.update(c,"INSERT INTO broker_state VALUES (1,0)");
        } catch(Exception e) { close(); throw e; }
        keepAlive=source.getConnection();
    }
    @Override public void close() {
        super.close();
        if (keepAlive!=null) try { keepAlive.close(); } catch(SQLException ignored) { }
    }
    private static String required(String token) {
        if (token==null || token.isBlank()) throw new IllegalArgumentException("BROKER_TOKEN is required"); return token;
    }
    public long publish(Event event) throws SQLException {
        event.validate();
        try (var c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                long last=Sql.one(c,"SELECT last_sequence FROM broker_state WHERE id=1 FOR UPDATE").get("lastSequence").getAsLong();
                var existing=Sql.rows(c,"SELECT sequence_number,event_json FROM event_log WHERE event_id=?",event.eventId());
                if (!existing.isEmpty()) {
                    var old=existing.get(0).getAsJsonObject();
                    if (!Json.object(old.get("eventJson").getAsString()).equals(Json.object(event.json()))) throw Json.conflict("Event ID reused with different payload");
                    c.commit(); return old.get("sequenceNumber").getAsLong();
                }
                long next=last+1;
                Sql.update(c,"INSERT INTO event_log VALUES (?,?,?)",next,event.eventId(),event.json());
                Sql.update(c,"UPDATE broker_state SET last_sequence=? WHERE id=1",next);
                c.commit(); return next;
            } catch(SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
    @Override protected void route(HttpExchange e) throws IOException,SQLException {
        String path=e.getRequestURI().getPath();
        if (path.equals("/events")) {
            method(e,"GET","POST");
            if (e.getRequestMethod().equals("POST")) {
                query(e); long offset=publish(Event.parse(body(e).toString())); send(e,200,Map.of("sequence",offset));
            } else {
                var query=query(e,"after"); long after;
                try { after=Long.parseLong(query.getOrDefault("after","0")); }
                catch(NumberFormatException ex) { throw Json.bad("after must be a nonnegative integer"); }
                if (after<0) throw Json.bad("after must be a nonnegative integer");
                try (var c=source.getConnection()) {
                    var result=new JsonArray();
                    for (var entry:Sql.rows(c,"SELECT sequence_number,event_json FROM event_log WHERE sequence_number>? ORDER BY sequence_number FETCH FIRST 200 ROWS ONLY",after)) {
                        var row=entry.getAsJsonObject(); var item=new JsonObject();
                        item.add("sequence",row.get("sequenceNumber")); item.add("event",Json.object(row.get("eventJson").getAsString())); result.add(item);
                    }
                    send(e,200,result);
                }
            }
        } else if (path.equals("/status")) {
            method(e,"GET"); query(e);
            try (var c=source.getConnection()) { send(e,200,Sql.one(c,"SELECT last_sequence FROM broker_state WHERE id=1")); }
        } else throw new ApiException(404,"Resource not found");
    }
}
