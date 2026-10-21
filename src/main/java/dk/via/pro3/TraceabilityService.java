package dk.via.pro3;

import dk.via.pro3.proto.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Validates requests, delegates SQL, and maps records/errors to the public contract. */
public final class TraceabilityService extends TraceabilityServiceGrpc.TraceabilityServiceImplBase {
    private static final Logger LOG = Logger.getLogger(TraceabilityService.class.getName());
    private final TraceabilityRepository repository;

    public TraceabilityService(TraceabilityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void getAnimalsForProduct(ProductRequest request, StreamObserver<AnimalsResponse> observer) {
        respond(observer, () -> {
            String id = validId(request.getProductId());
            var records = repository.animalsForProduct(id).orElseThrow(() ->
                    Status.NOT_FOUND.withDescription("Product not found").asRuntimeException());
            var response = AnimalsResponse.newBuilder();
            for (var animal : records) {
                response.addAnimals(Animal.newBuilder()
                        .setRegistrationNumber(animal.registrationNumber())
                        .setSpecies(animal.species())
                        .setArrivalWeightKg(animal.arrivalWeightKg().toPlainString())
                        .setArrivalDate(animal.arrivalDate().toString()));
            }
            return response.build();
        });
    }

    @Override
    public void getProductsForAnimal(AnimalRequest request, StreamObserver<ProductsResponse> observer) {
        respond(observer, () -> {
            String id = validId(request.getRegistrationNumber());
            var records = repository.productsForAnimal(id).orElseThrow(() ->
                    Status.NOT_FOUND.withDescription("Animal not found").asRuntimeException());
            var response = ProductsResponse.newBuilder();
            for (var product : records) {
                response.addProducts(Product.newBuilder()
                        .setProductId(product.productId())
                        .setProductType(product.productType())
                        .setPackedDate(product.packedDate().toString()));
            }
            return response.build();
        });
    }

    private static String validId(String value) {
        if (value.isBlank() || value.length() > 100 || !value.equals(value.strip())) {
            throw Status.INVALID_ARGUMENT.withDescription(
                    "Identifier must contain 1–100 characters without surrounding whitespace")
                    .asRuntimeException();
        }
        return value;
    }

    private static <T> void respond(StreamObserver<T> observer, SqlOperation<T> operation) {
        final T response;
        try {
            response = operation.run();
        } catch (StatusRuntimeException exception) {
            observer.onError(exception);
            return;
        } catch (SQLException exception) {
            LOG.log(Level.WARNING, "Traceability database query failed", exception);
            observer.onError(Status.UNAVAILABLE.withDescription("Traceability database unavailable")
                    .asRuntimeException());
            return;
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "Unexpected traceability service failure", exception);
            observer.onError(Status.INTERNAL.withDescription("Traceability request failed")
                    .asRuntimeException());
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }

    @FunctionalInterface
    private interface SqlOperation<T> { T run() throws SQLException; }
}
