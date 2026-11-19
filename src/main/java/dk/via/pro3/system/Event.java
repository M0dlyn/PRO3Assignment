package dk.via.pro3.system;

import com.google.gson.JsonObject;
import java.util.*;

/** Immutable logical event; the JSON string is retained unchanged for retry checks. */
public record Event(String eventId, int version, String source, String type, JsonObject payload) {
    public static final Map<String, String> OWNERS = Map.of(
            "ANIMAL_REGISTERED", "receiving", "TRAY_CREATED", "cutting", "BATCH_OPENED", "cutting",
            "PART_REGISTERED", "cutting", "BATCH_CLOSED", "cutting", "RECIPE_CREATED", "packing", "PRODUCT_PACKED", "packing");
    public static Event create(String source, String type, JsonObject payload) {
        var event = new Event(UUID.randomUUID().toString(), 1, source, type, payload.deepCopy());
        event.validate(); return event;
    }
    public void validate() {
        if (eventId == null || !eventId.matches("[0-9a-fA-F-]{36}")) throw Json.bad("Invalid event ID");
        try { UUID.fromString(eventId); } catch (IllegalArgumentException e) { throw Json.bad("Invalid event ID"); }
        if (version != 1 || source == null || type == null || !source.equals(OWNERS.get(type)) || payload == null)
            throw Json.bad("Invalid event version, owner or payload");
    }
    public String json() { return Json.CODEC.toJson(this); }
    public static Event parse(String raw) {
        var object = Json.object(raw);
        Json.fields(object, "eventId", "version", "source", "type", "payload");
        if (!object.has("payload") || !object.get("payload").isJsonObject()) throw Json.bad("Missing event payload");
        var event = new Event(Json.text(object, "eventId"), Json.quantity(object, "version"),
                Json.text(object, "source"), Json.text(object, "type"), object.getAsJsonObject("payload"));
        event.validate(); return event;
    }
}
