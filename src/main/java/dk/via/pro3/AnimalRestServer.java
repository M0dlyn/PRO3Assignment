package dk.via.pro3;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dk.via.pro3.AnimalRegistrationService.ApiException;

import javax.sql.DataSource;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** REST adapter using the JDK HTTP server; independent of the gRPC transport. */
public final class  AnimalRestServer implements AutoCloseable {
    private static final String ROOT = "/api/animals";
    private static final int MAX_BODY = 16 * 1024;
    private static final Gson JSON = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private static final Logger LOG = Logger.getLogger(AnimalRestServer.class.getName());
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final AnimalRegistrationService service;

    public AnimalRestServer(DataSource source, String host, int port) throws IOException {
        service = new AnimalRegistrationService(new AnimalRepository(source));
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            dispatch(exchange);
        } catch (ApiException exception) {
            send(exchange, exception.status(), Map.of("error", exception.getMessage()));
        } catch (SQLException exception) {
            LOG.log(Level.WARNING, "Animal database operation failed", exception);
            send(exchange, 503, Map.of("error", "Animal database unavailable"));
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "Unexpected animal REST failure", exception);
            send(exchange, 500, Map.of("error", "Animal request failed"));
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getRawPath();
        boolean collection = path.equals(ROOT);
        boolean item = path.startsWith(ROOT + "/") && path.length() > ROOT.length() + 1
                && !path.substring(ROOT.length() + 1).contains("/");
        if (!collection && !item) throw new ApiException(404, "Resource not found");
        String method = exchange.getRequestMethod();
        if (!(method.equals("GET") || (collection && method.equals("POST")))) {
            exchange.getResponseHeaders().set("Allow", collection ? "GET, POST" : "GET");
            throw new ApiException(405, "Method not allowed");
        }
        var query = query(exchange.getRequestURI().getRawQuery());
        if (item) {
            if (!query.isEmpty()) throw new ApiException(400, "Item lookup does not accept query parameters");
            // Form decoding treats '+' as a space; a path segment must preserve literal '+'.
            String id = decode(path.substring(ROOT.length() + 1).replace("+", "%2B"));
            send(exchange, 200, service.find(id));
        } else if (method.equals("GET")) {
            send(exchange, 200, service.list(query.get("arrivalDate"), query.get("origin")));
        } else {
            if (!query.isEmpty()) throw new ApiException(400, "Registration does not accept query parameters");
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                throw new ApiException(415, "Content-Type must be application/json");
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) throw new ApiException(413, "Request body exceeds 16 KiB");
            var animal = service.register(parse(body));
            String id = URLEncoder.encode(animal.registrationNumber(), StandardCharsets.UTF_8).replace("+", "%20");
            exchange.getResponseHeaders().set("Location", ROOT + "/" + id);
            send(exchange, 201, animal);
        }
    }

    // A flat request parser rejects unknown/duplicate fields and wrong JSON types without coercion.
    public static AnimalRegistration parse(byte[] body) {
        try (var reader = new JsonReader(new StringReader(new String(body, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            var strings = new HashMap<String, String>();
            var seen = new HashSet<String>();
            BigDecimal weight = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key)) throw new ApiException(400, "Duplicate JSON field: " + key);
                if (key.equals("arrivalWeightKg")) {
                    if (reader.peek() != JsonToken.NUMBER) throw new ApiException(400, "arrivalWeightKg must be a JSON number");
                    weight = new BigDecimal(reader.nextString());
                } else if (Set.of("registrationNumber", "arrivalDate", "origin", "species").contains(key)) {
                    if (reader.peek() != JsonToken.STRING) throw new ApiException(400, key + " must be a JSON string");
                    strings.put(key, reader.nextString());
                } else {
                    throw new ApiException(400, "Unknown JSON field: " + key);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new ApiException(400, "Expected one JSON object");
            return new AnimalRegistration(strings.get("registrationNumber"), strings.get("arrivalDate"),
                    weight, strings.get("origin"), strings.get("species"));
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            throw new ApiException(400, "Malformed JSON request");
        }
    }

    private static Map<String, String> query(String raw) {
        var result = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) return result;
        for (String entry : raw.split("&", -1)) {
            String[] pair = entry.split("=", 2);
            String key = decode(pair[0]);
            if (!Set.of("arrivalDate", "origin").contains(key) || pair.length != 2
                    || result.putIfAbsent(key, decode(pair[1])) != null) {
                throw new ApiException(400, "Use each of arrivalDate and origin at most once");
            }
        }
        return result;
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException exception) { throw new ApiException(400, "Invalid URL encoding"); }
    }

    private static void send(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = JSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
