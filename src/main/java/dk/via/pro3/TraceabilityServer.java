package dk.via.pro3;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class TraceabilityServer {
    private TraceabilityServer() {}

    public static Server create(DataSource source, String host, int port) {
        return NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
                .addService(new TraceabilityService(new TraceabilityRepository(source)))
                .addService(ProtoReflectionServiceV1.newInstance())
                .build();
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.stream(args).anyMatch(arg -> !arg.equals("--demo"))) {
            throw new IllegalArgumentException("Usage: java -jar target/traceability-1.0.0.jar [--demo]");
        }
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:h2:file:./data/traceability");
        String host = System.getenv().getOrDefault("GRPC_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "9090"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("GRPC_PORT must be 1–65535");
        var source = Database.dataSource(url);
        Database.initialize(source);
        if (Arrays.asList(args).contains("--demo")) Database.seedDemo(source);
        var server = create(source, host, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow();
            } catch (InterruptedException exception) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
        System.out.printf("Traceability gRPC listening on %s:%d%n", host, server.getPort());
        server.awaitTermination();
    }
}
