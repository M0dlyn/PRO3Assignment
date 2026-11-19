package dk.via.pro3.system;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dk.via.pro3.AnimalRegistrationService.ApiException;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Strict JSON and shared domain input validation. */
public final class Json {
    public static final Gson CODEC = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private Json() {}
    public static JsonObject object(String body) {
        try (var reader = new JsonReader(new StringReader(body))) {
            reader.setStrictness(Strictness.STRICT);
            var value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw bad("Expected one JSON object");
            return value.getAsJsonObject();
        } catch (IOException | IllegalStateException | NumberFormatException e) { throw bad("Invalid JSON"); }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 20) throw bad("JSON nesting too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var obj = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (obj.has(key)) throw bad("Duplicate field: " + key);
                    obj.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield obj;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw bad("Invalid JSON value");
        };
    }
    public static void fields(JsonObject value, String... allowed) {
        var names = Set.of(allowed);
        for (String key : value.keySet()) if (!names.contains(key)) throw bad("Unknown field: " + key);
    }
    public static String text(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(key + " must be a string");
        String result = value.getAsString();
        if (result.isBlank() || result.length() > 100 || !result.equals(result.strip())
                || result.codePoints().anyMatch(Character::isISOControl)) throw bad(key + " must contain 1–100 unpadded characters");
        return result;
    }
    public static BigDecimal weight(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(key + " must be a number");
        BigDecimal number = value.getAsBigDecimal();
        if (number.signum() <= 0 || number.compareTo(new BigDecimal("999999999.999")) > 0
                || number.stripTrailingZeros().scale() > 3) throw bad(key + " must be positive, fit DECIMAL(12,3), and have at most 3 decimal places");
        return number.setScale(3);
    }
    public static int quantity(JsonObject object, String key) {
        var value = object.get(key);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(key + " must be an integer");
            int result = value.getAsBigDecimal().intValueExact();
            if (result < 1 || result > 500) throw bad(key + " must be 1–500");
            return result;
        } catch (ArithmeticException e) { throw bad(key + " must be 1–500"); }
    }
    public static String date(JsonObject object, String key) {
        String value = text(object, key);
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || LocalDate.parse(value).getYear() < 1) throw bad("Invalid " + key);
        } catch (DateTimeException e) { throw bad("Invalid " + key); }
        return value;
    }
    public static JsonArray array(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()
                || value.getAsJsonArray().size() > 500) throw bad(key + " must contain 1–500 entries");
        return value.getAsJsonArray();
    }
    public static ApiException bad(String message) { return new ApiException(400, message); }
    public static ApiException conflict(String message) { return new ApiException(409, message); }
}
