package dk.via.pro3.system;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

final class Sql {
    private Sql() {}
    static int update(Connection c, String sql, Object... parameters) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            bind(statement, parameters); return statement.executeUpdate();
        }
    }
    static JsonArray rows(Connection c, String sql, Object... parameters) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            bind(statement, parameters);
            try (var rows = statement.executeQuery()) {
                var result = new JsonArray(); var metadata = rows.getMetaData();
                while (rows.next()) {
                    var row = new JsonObject();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String[] words = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
                        String key = words[0];
                        for (int j = 1; j < words.length; j++) key += Character.toUpperCase(words[j].charAt(0)) + words[j].substring(1);
                        Object value = rows.getObject(i);
                        if (value == null) row.add(key, JsonNull.INSTANCE);
                        else if (value instanceof Number n) row.addProperty(key, n);
                        else if (value instanceof Boolean b) row.addProperty(key, b);
                        else if (value instanceof Clob clob) row.addProperty(key, clob.getSubString(1, (int) clob.length()));
                        else row.addProperty(key, value.toString());
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }
    static JsonObject one(Connection c, String sql, Object... parameters) throws SQLException {
        var rows = rows(c, sql, parameters);
        if (rows.isEmpty()) throw new dk.via.pro3.AnimalRegistrationService.ApiException(404, "Referenced record not available locally");
        return rows.get(0).getAsJsonObject();
    }
    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
    }
}
