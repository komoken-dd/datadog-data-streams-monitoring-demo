package io.github.komokendd.dsm.brokerage.notification;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP client for push-gateway-simulator. Timeouts are set on the RestClient request factory. */
final class PushGatewayClient implements PushGateway {
    private final RestClient restClient;

    PushGatewayClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PushResponse push(PushMessage message) {
        try {
            PushResponse response = restClient.post()
                    .uri("/push")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(PushResponse.class);
            return response == null ? new PushResponse(message.notificationId(), false) : response;
        } catch (RestClientException failure) {
            throw new PushDeliveryException(classify(failure), failure.getMessage(), failure);
        }
    }

    static String classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return PushDeliveryException.TIMEOUT;
            }
        }
        // 5xx responses and refused or reset connections: the provider is not taking requests.
        return PushDeliveryException.UNAVAILABLE;
    }
}
