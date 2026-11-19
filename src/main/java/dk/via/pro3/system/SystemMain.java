package dk.via.pro3.system;

import dk.via.pro3.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

/** Run one of five independently restartable processes using the same executable artifact. */
public final class SystemMain {
    private SystemMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length!=1 || !Set.of("broker","receiving","cutting","packing","central").contains(args[0]))
            throw new IllegalArgumentException("Usage: SystemMain broker|receiving|cutting|packing|central");
        String role=args[0],host=env("HTTP_HOST","127.0.0.1");
        String brokerToken=required("BROKER_TOKEN");
        String recallToken=role.equals("central") ? required("RECALL_TOKEN") : "";
        int port=port("HTTP_PORT",switch(role) { case "broker" -> 8070; case "receiving" -> 8081;
            case "cutting" -> 8082; case "packing" -> 8083; default -> 8084; });
        var source=Database.dataSource(env("DB_URL","jdbc:h2:file:./data/"+role+"/store"));
        var resources=new ArrayList<AutoCloseable>();
        try {
            if (role.equals("broker")) {
                var server=new BrokerServer(source,host,port,brokerToken); resources.add(server); server.start();
            } else {
                var store=new StationStore(source,role); resources.add(store);
                var sync=new SyncWorker(store,env("BROKER_URL","http://127.0.0.1:8070"),brokerToken);
                var server=new StationServer(store,sync,host,port,role.equals("central") ? recallToken : env("STATION_TOKEN",""));
                resources.add(server); resources.add(sync); server.start(); sync.start();
                if (role.equals("central")) {
                    var grpc=NettyServerBuilder.forAddress(new InetSocketAddress(env("GRPC_HOST","127.0.0.1"),port("GRPC_PORT",9090)))
                            .intercept(new RecallAuth(recallToken))
                            .addService(new TraceabilityService(new TraceabilityRepository(source)))
                            .addService(ProtoReflectionServiceV1.newInstance()).build().start();
                    resources.add(() -> { grpc.shutdownNow(); grpc.awaitTermination(5,TimeUnit.SECONDS); });
                    System.out.println("Authenticated recall gRPC listening on "+grpc.getPort());
                }
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> close(resources)));
            System.out.printf("%s listening on http://%s:%d%n",role,host,port);
            new CountDownLatch(1).await();
        } catch(Exception e) { close(resources); throw e; }
    }
    private static void close(List<AutoCloseable> resources) {
        for (int i=resources.size()-1;i>=0;i--) {
            try { resources.get(i).close(); } catch(Exception ignored) { }
        }
    }
    private static String env(String name,String fallback) { return System.getenv().getOrDefault(name,fallback); }
    private static String required(String name) {
        String value=env(name,""); if (value.isBlank()) throw new IllegalArgumentException(name+" must be set"); return value;
    }
    private static int port(String name,int fallback) {
        int value=Integer.parseInt(env(name,Integer.toString(fallback)));
        if (value<1 || value>65535) throw new IllegalArgumentException(name+" must be 1–65535"); return value;
    }
}
