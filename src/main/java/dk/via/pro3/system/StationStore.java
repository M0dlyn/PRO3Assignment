package dk.via.pro3.system;

import com.google.gson.*;
import dk.via.pro3.*;
import dk.via.pro3.AnimalRegistrationService.ApiException;
import org.h2.tools.RunScript;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Each process owns one database: domain records, outbox, inbox and replay cursor. */
public final class StationStore implements AutoCloseable {
    public record Pending(long sequence, String json) {}
    private final DataSource source;
    private final String role;
    private final Connection keepAlive;
    public StationStore(DataSource source, String role) throws Exception {
        if (!Set.of("receiving","cutting","packing","central").contains(role)) throw new IllegalArgumentException("Unknown station role");
        this.source=source; this.role=role;
        Database.initialize(source);
        try (var c=source.getConnection(); var reader=new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/system.sql")),StandardCharsets.UTF_8)) {
            RunScript.execute(c,reader);
            var state=Sql.rows(c,"SELECT node_role FROM node_state WHERE id=1");
            if (state.isEmpty()) {
                if (!Sql.rows(c,"SELECT registration_number FROM animal FETCH FIRST 1 ROW ONLY").isEmpty()
                        || !Sql.rows(c,"SELECT tray_id FROM tray FETCH FIRST 1 ROW ONLY").isEmpty()
                        || !Sql.rows(c,"SELECT name FROM product_type FETCH FIRST 1 ROW ONLY").isEmpty())
                    throw new IllegalArgumentException("Part 4 needs a fresh role-specific database; legacy data has no event history");
                Sql.update(c,"INSERT INTO node_state (id,node_role) VALUES (1,?)",role);
            } else if (!state.get(0).getAsJsonObject().get("nodeRole").getAsString().equals(role))
                throw new IllegalArgumentException("Database belongs to another role");
        }
        keepAlive=source.getConnection();
    }
    @Override public void close() throws SQLException { keepAlive.close(); }
    public DataSource dataSource() { return source; }
    public String role() { return role; }

    public Event command(String type, JsonObject payload) throws SQLException {
        if (!role.equals(Event.OWNERS.get(type))) throw new ApiException(403,"Operation belongs to another station");
        var input=payload.deepCopy();
        if (type.equals("BATCH_OPENED")) {
            Json.fields(input,"batchId","trayId","partType");
            input.addProperty("createdAt",LocalDateTime.now(ZoneOffset.UTC).toString());
        }
        Event event=Event.create(role,type,input);
        transaction(c -> {
            Domain.apply(c,event);
            Sql.update(c,"INSERT INTO inbox VALUES (?,?)",event.eventId(),event.json());
            Sql.update(c,"INSERT INTO outbox (event_id,event_json) VALUES (?,?)",event.eventId(),event.json());
        });
        return event;
    }
    public void accept(long sequence, Event event) throws SQLException {
        event.validate();
        transaction(c -> {
            long cursor=Sql.one(c,"SELECT cursor_value FROM node_state WHERE id=1").get("cursorValue").getAsLong();
            if (sequence<=cursor) return;
            if (sequence!=cursor+1) throw Json.conflict("Broker log gap; replay paused");
            var seen=Sql.rows(c,"SELECT event_json FROM inbox WHERE event_id=?",event.eventId());
            if (seen.isEmpty()) {
                Domain.apply(c,event);
                Sql.update(c,"INSERT INTO inbox VALUES (?,?)",event.eventId(),event.json());
            } else if (!Json.object(seen.get(0).getAsJsonObject().get("eventJson").getAsString()).equals(Json.object(event.json())))
                throw Json.conflict("Reused event ID with different content");
            Sql.update(c,"UPDATE node_state SET cursor_value=? WHERE id=1",sequence);
        });
    }
    public List<Pending> pending() throws SQLException {
        try (var c=source.getConnection()) {
            var result=new ArrayList<Pending>();
            for (var value:Sql.rows(c,"SELECT sequence_number,event_json FROM outbox WHERE delivered=FALSE ORDER BY sequence_number FETCH FIRST 200 ROWS ONLY")) {
                var row=value.getAsJsonObject(); result.add(new Pending(row.get("sequenceNumber").getAsLong(),row.get("eventJson").getAsString()));
            }
            return result;
        }
    }
    public void delivered(long sequence) throws SQLException {
        try (var c=source.getConnection()) { Sql.update(c,"UPDATE outbox SET delivered=TRUE WHERE sequence_number=?",sequence); }
    }
    public long cursor() throws SQLException {
        try (var c=source.getConnection()) { return Sql.one(c,"SELECT cursor_value FROM node_state WHERE id=1").get("cursorValue").getAsLong(); }
    }
    public JsonObject status() throws SQLException {
        try (var c=source.getConnection()) {
            var result=Sql.one(c,"SELECT node_role,cursor_value FROM node_state WHERE id=1");
            result.addProperty("pendingOutbox",Sql.one(c,"SELECT COUNT(*) AS total FROM outbox WHERE delivered=FALSE").get("total").getAsLong());
            return result;
        }
    }
    public JsonArray list(String resource) throws SQLException {
        String sql=switch(resource) {
            case "trays" -> "SELECT * FROM tray ORDER BY tray_id";
            case "batches" -> """
                    SELECT b.*,COALESCE((SELECT SUM(weight_kg) FROM part p WHERE p.batch_id=b.batch_id),0) AS total_weight_kg,
                    (SELECT COUNT(*) FROM part p LEFT JOIN product_part used ON used.part_id=p.part_id
                     WHERE p.batch_id=b.batch_id AND used.part_id IS NULL) AS available_parts
                    FROM tray_batch b ORDER BY batch_id
                    """;
            case "parts" -> "SELECT p.*,used.product_id FROM part p LEFT JOIN product_part used ON used.part_id=p.part_id ORDER BY p.part_id";
            case "product-types" -> "SELECT name FROM product_type ORDER BY name";
            case "products" -> "SELECT * FROM product ORDER BY product_id";
            default -> throw new ApiException(404,"Unknown resource");
        };
        try (var c=source.getConnection()) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var rows=Sql.rows(c,sql);
            for (var value:rows) {
                var row=value.getAsJsonObject();
                if (resource.equals("product-types")) row.add("requirements",Sql.rows(c,"SELECT part_type,quantity FROM product_requirement WHERE product_type=? ORDER BY part_type",row.get("name").getAsString()));
                if (resource.equals("products")) {
                    row.add("parts",Sql.rows(c,"SELECT part_id FROM product_part WHERE product_id=? ORDER BY part_id",row.get("productId").getAsString()));
                    row.add("batches",Sql.rows(c,"SELECT batch_id FROM product_tray_batch WHERE product_id=? ORDER BY batch_id",row.get("productId").getAsString()));
                }
            }
            c.commit(); return rows;
        }
    }
    private void transaction(Work work) throws SQLException {
        try (var c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                // One transaction lock for operator commands and incoming projections on this node.
                Sql.one(c,"SELECT id FROM node_state WHERE id=1 FOR UPDATE");
                work.run(c); c.commit();
            } catch (SQLException | RuntimeException exception) {
                c.rollback();
                if (exception instanceof SQLException sql && "23505".equals(sql.getSQLState()))
                    throw Json.conflict("Identifier already exists or part already allocated");
                throw exception;
            }
        }
    }
    @FunctionalInterface private interface Work { void run(Connection c) throws SQLException; }
}
