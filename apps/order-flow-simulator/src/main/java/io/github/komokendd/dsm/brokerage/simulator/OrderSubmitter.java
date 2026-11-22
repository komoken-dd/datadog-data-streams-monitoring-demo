package io.github.komokendd.dsm.brokerage.simulator;

import java.util.Map;
import org.springframework.web.client.RestClient;

/** Submits orders to order-service. Never throws: rejects and outages are expected demo outcomes. */
public class OrderSubmitter {
    private final RestClient client;

    public OrderSubmitter(RestClient client) {
        this.client = client;
    }

    public SubmitResult submit(OrderRequest request) {
        try {
            return client.post()
                    .uri("/orders")
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        String code = null;
                        if (response.getStatusCode().isError()) {
                            try {
                                code = errorCode(response.bodyTo(Map.class));
                            } catch (RuntimeException unreadableBody) {
                                code = null;
                            }
                        }
                        return new SubmitResult(status, code, null);
                    });
        } catch (RuntimeException exception) {
            return new SubmitResult(-1, null, exception.getMessage());
        }
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
    public record SubmitResult(int status, String code, String error) {
        public boolean accepted() {
            return status >= 200 && status < 300;
        }
    }
}
