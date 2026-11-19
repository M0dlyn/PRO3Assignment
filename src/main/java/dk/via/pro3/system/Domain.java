package dk.via.pro3.system;

import com.google.gson.*;
import dk.via.pro3.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Business rules executed within the station transaction and again by projections. */
final class Domain {
    private Domain() {}
    static void apply(Connection c, Event event) throws SQLException {
        var p = event.payload();
        switch (event.type()) {
            case "ANIMAL_REGISTERED" -> {
                var animal = AnimalRegistrationService.validate(AnimalRestServer.parse(p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                Sql.update(c, "INSERT INTO animal (registration_number,species,arrival_weight_kg,arrival_date,origin) VALUES (?,?,?,?,?)",
                        animal.registrationNumber(), animal.species(), animal.arrivalWeightKg(), java.sql.Date.valueOf(animal.arrivalDate()), animal.origin());
            }
            case "TRAY_CREATED" -> {
                Json.fields(p, "trayId", "maximumWeightKg");
                Sql.update(c, "INSERT INTO tray VALUES (?,?)", Json.text(p,"trayId"), Json.weight(p,"maximumWeightKg"));
            }
            case "BATCH_OPENED" -> {
                Json.fields(p, "batchId", "trayId", "partType", "createdAt");
                String tray = Json.text(p,"trayId"), type = Json.text(p,"partType");
                Sql.one(c, "SELECT tray_id FROM tray WHERE tray_id=?", tray);
                if (!Sql.rows(c, "SELECT batch_id FROM tray_batch WHERE tray_id=? AND closed=FALSE", tray).isEmpty())
                    throw Json.conflict("Tray already has an open batch");
                if (!Sql.rows(c, """
                        SELECT p.part_id FROM part p JOIN tray_batch b ON b.batch_id=p.batch_id
                        LEFT JOIN product_part used ON used.part_id=p.part_id WHERE b.tray_id=? AND used.part_id IS NULL
                        """, tray).isEmpty()) throw Json.conflict("Tray still contains unpacked parts; synchronize packing updates before reuse");
                ensureType(c, type);
                LocalDateTime created;
                try { created = LocalDateTime.parse(Json.text(p,"createdAt")); }
                catch (DateTimeException e) { throw Json.bad("Invalid createdAt"); }
                Sql.update(c, "INSERT INTO tray_batch (batch_id,tray_id,part_type,created_at,closed) VALUES (?,?,?,?,FALSE)",
                        Json.text(p,"batchId"), tray, type, Timestamp.valueOf(created));
            }
            case "PART_REGISTERED" -> {
                Json.fields(p,"partId","animalRegistrationNumber","batchId","partType","weightKg");
                String animal = Json.text(p,"animalRegistrationNumber"), batch = Json.text(p,"batchId"), type = Json.text(p,"partType");
                BigDecimal weight = Json.weight(p,"weightKg");
                var b = Sql.one(c, "SELECT b.closed,b.part_type,t.maximum_weight_kg FROM tray_batch b JOIN tray t ON t.tray_id=b.tray_id WHERE b.batch_id=?", batch);
                if (b.get("closed").getAsBoolean()) throw Json.conflict("Batch is closed; historical membership is immutable");
                if (!b.get("partType").getAsString().equals(type)) throw Json.conflict("Part type does not match batch");
                BigDecimal total = Sql.one(c, "SELECT COALESCE(SUM(weight_kg),0) AS weight FROM part WHERE batch_id=?", batch).get("weight").getAsBigDecimal();
                if (total.add(weight).compareTo(b.get("maximumWeightKg").getAsBigDecimal()) > 0) throw Json.conflict("Tray weight capacity exceeded");
                BigDecimal arrival = Sql.one(c,"SELECT arrival_weight_kg FROM animal WHERE registration_number=?",animal).get("arrivalWeightKg").getAsBigDecimal();
                BigDecimal cut = Sql.one(c,"SELECT COALESCE(SUM(weight_kg),0) AS weight FROM part WHERE animal_registration_number=?",animal).get("weight").getAsBigDecimal();
                if (cut.add(weight).compareTo(arrival) > 0) throw Json.conflict("Registered parts exceed animal arrival weight");
                Sql.update(c,"INSERT INTO part VALUES (?,?,?,?,?)",Json.text(p,"partId"),animal,batch,type,weight);
            }
            case "BATCH_CLOSED" -> {
                Json.fields(p,"batchId"); String batch = Json.text(p,"batchId");
                var b = Sql.one(c,"SELECT closed FROM tray_batch WHERE batch_id=?",batch);
                if (b.get("closed").getAsBoolean()) throw Json.conflict("Batch already closed");
                if (Sql.rows(c,"SELECT part_id FROM part WHERE batch_id=?",batch).isEmpty()) throw Json.conflict("Cannot close an empty batch");
                Sql.update(c,"UPDATE tray_batch SET closed=TRUE WHERE batch_id=?",batch);
            }
            case "RECIPE_CREATED" -> {
                Json.fields(p,"name","requirements"); String name=Json.text(p,"name");
                var requirements = Json.array(p,"requirements");
                var seen = new HashSet<String>(); int total = 0;
                Sql.update(c,"INSERT INTO product_type VALUES (?)",name);
                for (var entry : requirements) {
                    if (!entry.isJsonObject()) throw Json.bad("Each requirement must be an object");
                    var r=entry.getAsJsonObject(); Json.fields(r,"partType","quantity");
                    String type=Json.text(r,"partType"); int count=Json.quantity(r,"quantity");
                    if (!seen.add(type)) throw Json.bad("Duplicate part type in recipe");
                    total+=count; if (total>500) throw Json.bad("A recipe may require at most 500 parts");
                    ensureType(c,type);
                    Sql.update(c,"INSERT INTO product_requirement VALUES (?,?,?)",name,type,count);
                }
            }
            case "PRODUCT_PACKED" -> {
                Json.fields(p,"productId","productType","packedDate","partIds");
                String id=Json.text(p,"productId"), type=Json.text(p,"productType"), date=Json.date(p,"packedDate");
                Sql.one(c,"SELECT name FROM product_type WHERE name=?",type);
                var expected=new TreeMap<String,Integer>();
                for (var row : Sql.rows(c,"SELECT part_type,quantity FROM product_requirement WHERE product_type=?",type)) {
                    var r=row.getAsJsonObject(); expected.put(r.get("partType").getAsString(),r.get("quantity").getAsInt());
                }
                if (expected.isEmpty()) throw Json.conflict("Product type has no recipe");
                var actual=new TreeMap<String,Integer>(); var parts=new LinkedHashSet<String>(); var batches=new TreeSet<String>();
                for (var value : Json.array(p,"partIds")) {
                    var wrapper=new JsonObject(); wrapper.add("partId",value); String part=Json.text(wrapper,"partId");
                    if (!parts.add(part)) throw Json.bad("Duplicate part in product");
                    var row=Sql.one(c,"""
                            SELECT p.part_type,p.batch_id,b.closed,b.created_at FROM part p
                            JOIN tray_batch b ON b.batch_id=p.batch_id WHERE p.part_id=?
                            """,part);
                    if (!row.get("closed").getAsBoolean()) throw Json.conflict("Only closed batches can be packed");
                    if (LocalDate.parse(date).isBefore(Timestamp.valueOf(row.get("createdAt").getAsString()).toLocalDateTime().toLocalDate()))
                        throw Json.conflict("Packed date precedes batch creation");
                    if (!Sql.rows(c,"SELECT part_id FROM product_part WHERE part_id=?",part).isEmpty()) throw Json.conflict("Part already packed");
                    actual.merge(row.get("partType").getAsString(),1,Integer::sum); batches.add(row.get("batchId").getAsString());
                }
                if (!expected.equals(actual)) throw Json.conflict("Selected parts do not match the product recipe");
                Sql.update(c,"INSERT INTO product VALUES (?,?,?)",id,type,java.sql.Date.valueOf(date));
                for (String part : parts) Sql.update(c,"INSERT INTO product_part VALUES (?,?)",part,id);
                for (String batch : batches) Sql.update(c,"INSERT INTO product_tray_batch VALUES (?,?)",id,batch);
            }
            default -> throw Json.bad("Unsupported event");
        }
    }
    private static void ensureType(Connection c,String type) throws SQLException {
        if (Sql.rows(c,"SELECT name FROM part_type WHERE name=?",type).isEmpty()) Sql.update(c,"INSERT INTO part_type VALUES (?)",type);
    }
}
