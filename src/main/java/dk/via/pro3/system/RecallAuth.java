package dk.via.pro3.system;

import io.grpc.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protects the full-system central gRPC endpoint, including reflection. */
public final class RecallAuth implements ServerInterceptor {
    private static final Metadata.Key<String> AUTH=Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER);
    private final String expected;
    public RecallAuth(String token) {
        if (token==null || token.isBlank()) throw new IllegalArgumentException("RECALL_TOKEN is required");
        expected="Bearer "+token;
    }
    @Override public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT,RespT> call,Metadata headers,ServerCallHandler<ReqT,RespT> next) {
        String actual=headers.get(AUTH);
        if (actual==null || !MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("Valid recall bearer token required"),new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call,headers);
    }
}
