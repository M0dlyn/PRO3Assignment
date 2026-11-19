package dk.via.pro3;

import dk.via.pro3.proto.*;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;

/** Small real gRPC client for a reproducible demonstration without extra tools. */
public final class TraceabilityClient {
    private TraceabilityClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("product") || args[0].equals("animal"))) {
            throw new IllegalArgumentException("Usage: TraceabilityClient product|animal IDENTIFIER");
        }
        var channel = ManagedChannelBuilder.forTarget(
                System.getenv().getOrDefault("GRPC_TARGET", "127.0.0.1:9090")).usePlaintext().build();
        try {
            var stub = TraceabilityServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            String token = System.getenv("RECALL_TOKEN");
            if (token != null && !token.isBlank()) {
                var headers = new io.grpc.Metadata();
                headers.put(io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
                stub = stub.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
            }
            if (args[0].equals("product")) {
                var response = stub.getAnimalsForProduct(ProductRequest.newBuilder().setProductId(args[1]).build());
                response.getAnimalsList().forEach(animal -> System.out.println(animal.getRegistrationNumber()));
                if (response.getAnimalsCount() == 0) System.out.println("No matching animals");
            } else {
                var response = stub.getProductsForAnimal(AnimalRequest.newBuilder().setRegistrationNumber(args[1]).build());
                response.getProductsList().forEach(product -> System.out.println(product.getProductId()));
                if (response.getProductsCount() == 0) System.out.println("No matching products");
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
