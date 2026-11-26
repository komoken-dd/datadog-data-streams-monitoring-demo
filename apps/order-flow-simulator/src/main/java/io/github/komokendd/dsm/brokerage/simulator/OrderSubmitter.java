package io.github.komokendd.dsm.brokerage.simulator;

import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import io.github.komokendd.dsm.brokerage.common.EventIds;
import org.springframework.web.client.RestClient;

/** Submits orders to order-service. Never throws: rejects and outages are expected demo outcomes. */
public class OrderSubmitter {
    private final RestClient client;

    public OrderSubmitter(RestClient client) {
        this.client = client;
    }

    public SubmitResult submit(OrderRequest request) {
        String key = UUID.randomUUID().toString();
        // The intake API derives its stable inquiry ID from account + idempotency key.
        String inquiryId = EventIds.derived(request.accountId() + ":" + key, "ORDER_ID");
        try {
            return client.post()
                    .uri("/orders")
                    .header("Idempotency-Key", key)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        String code = null;
                        String orderId = inquiryId;
                        try {
                            Map<?, ?> body = response.bodyTo(Map.class);
                            code = errorCode(body);
                            if (body != null && body.get("orderId") != null) orderId = body.get("orderId").toString();
                        } catch (RuntimeException unreadableBody) {
                            // Keep the stable inquiry ID even if the response or its body was lost.
                        }
                        return new SubmitResult(status, code, null, orderId);
                    });
        } catch (RuntimeException exception) {
            return new SubmitResult(-1, null, exception.getMessage(), inquiryId);
        }
    }

    Optional<String> status(String orderId) {
        return client.get().uri("/orders/{id}", orderId).exchange((request, response) -> {
            if (response.getStatusCode().value() == 404) return Optional.empty();
            if (response.getStatusCode().value() == 422) return Optional.of("REJECTED");
            if (response.getStatusCode().value() == 410) return Optional.of("CANCELLED");
            Map<?, ?> body = response.bodyTo(Map.class);
            if (response.getStatusCode().value() == 409 && "ORDER_CANCELLED".equals(errorCode(body))) {
                return Optional.of("CANCELLED");
            }
            return body == null || body.get("status") == null ? Optional.empty()
                    : Optional.of(body.get("status").toString());
        });
    }

    private static String errorCode(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object code = body.get("code");
        return code == null ? null : code.toString();
    }

    /**
     * @param status HTTP status, or -1 when order-service could not be reached
     * @param code   error code from the response body (for example INSUFFICIENT_BUYING_POWER)
     */
    public record SubmitResult(int status, String code, String error, String orderId) {
        public SubmitResult(int status, String code, String error) {
            this(status, code, error, null);
        }
        public boolean accepted() {
            return status >= 200 && status < 300;
        }
    }
}
