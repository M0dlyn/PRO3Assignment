package dk.via.pro3.system;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.*;
import dk.via.pro3.AnimalRegistrationService.ApiException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Common transport only; all mutations remain in database transactions. */
abstract class HttpApi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers=Executors.newFixedThreadPool(8);
    private final String token;
    HttpApi(String host,int port,String token) throws IOException {
        this.token=token;
        server=HttpServer.create(new InetSocketAddress(host,port),0); server.setExecutor(workers);
        server.createContext("/",exchange -> {
            try {
                if (!token.isEmpty() && !authorized(exchange)) throw new ApiException(401,"Bearer token required");
                route(exchange);
            } catch (ApiException e) { send(exchange,e.status(),Map.of("error",e.getMessage())); }
            catch (SQLException e) {
                Logger.getLogger(getClass().getName()).log(Level.WARNING,"Database operation failed",e);
                send(exchange,503,Map.of("error","Database unavailable"));
            } catch (RuntimeException e) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE,"Request failed",e);
                send(exchange,500,Map.of("error","Request failed"));
            } finally { exchange.close(); }
        });
    }
    private boolean authorized(HttpExchange e) {
        String header=e.getRequestHeaders().getFirst("Authorization");
        return header!=null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8));
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() {
        server.stop(0); workers.shutdownNow();
        try { workers.awaitTermination(5,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    protected abstract void route(HttpExchange exchange) throws IOException,SQLException;
    static JsonObject body(HttpExchange exchange) throws IOException {
        String type=exchange.getRequestHeaders().getFirst("Content-Type");
        if (type==null || !type.split(";",2)[0].strip().equalsIgnoreCase("application/json")) throw new ApiException(415,"Use application/json");
        byte[] bytes=exchange.getRequestBody().readNBytes(131073);
        if (bytes.length>131072) throw new ApiException(413,"Body exceeds 128 KiB");
        return Json.object(new String(bytes,StandardCharsets.UTF_8));
    }
    static void send(HttpExchange exchange,int status,Object value) throws IOException {
        byte[] bytes=Json.CODEC.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
    }
    static void method(HttpExchange e,String... allowed) {
        if (!Arrays.asList(allowed).contains(e.getRequestMethod())) {
            e.getResponseHeaders().set("Allow",String.join(", ",allowed)); throw new ApiException(405,"Method not allowed");
        }
    }
    static Map<String,String> query(HttpExchange e,String... allowed) {
        var result=new HashMap<String,String>(); String raw=e.getRequestURI().getRawQuery();
        if (raw==null || raw.isEmpty()) return result;
        for (String entry:raw.split("&",-1)) {
            String[] fields=entry.split("=",2); String key=decode(fields[0]);
            if (fields.length!=2 || !Arrays.asList(allowed).contains(key) || result.putIfAbsent(key,decode(fields[1]))!=null)
                throw Json.bad("Invalid or repeated query parameter");
        }
        return result;
    }
    static String decode(String input) {
        try { return URLDecoder.decode(input,StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw Json.bad("Invalid URL encoding"); }
    }
    static String segment(String input) { return decode(input.replace("+","%2B")); }
}
