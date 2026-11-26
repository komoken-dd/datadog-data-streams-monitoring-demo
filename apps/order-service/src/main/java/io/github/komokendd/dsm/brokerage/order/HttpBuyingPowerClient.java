package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import java.util.Set;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class HttpBuyingPowerClient implements BuyingPowerClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpBuyingPowerClient.class);
    static final String FALLBACK_REJECT_REASON = "BUYING_POWER_REJECTED";
    private static final Set<String> KNOWN_REASONS = Set.of(
            BuyingPowerReservation.INSUFFICIENT_BUYING_POWER,
            BuyingPowerReservation.INSUFFICIENT_SELLABLE_QUANTITY,
            BuyingPowerReservation.UNKNOWN_ACCOUNT, BuyingPowerReservation.RESERVATION_EXPIRED,
            BuyingPowerReservation.RESERVATION_CANCELLED);

    private final RestClient restClient;

    HttpBuyingPowerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ReservationOutcome reserve(BuyingPowerReservation.Request request) {
        try {
            return restClient.post()
                    .uri(BuyingPowerReservation.RESERVATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200) {
                            var body = response.bodyTo(BuyingPowerReservation.Response.class);
                            if (body == null || body.reservedAmountJpy() == null) {
                                return new Unavailable("empty reservation response");
                            }
                            return new Reserved(body.reservedAmountJpy(), body.buyingPowerJpy());
                        }
                        if (status == 422) {
                            var body = response.bodyTo(BuyingPowerReservation.Response.class);
                            String reason = body == null ? null : body.reason();
                            // Reasons become metric tags, so keep them to the known set.
                            return new Rejected(KNOWN_REASONS.contains(reason) ? reason : FALLBACK_REJECT_REASON);
                        }
                        return new Unavailable("buying-power-service responded " + status);
                    });
        } catch (RestClientException exception) {
            return new Unavailable(exception.getMessage());
        }
    }

    @Override
    public ReservationOutcome lookup(String orderId) {
        try {
            return restClient.get().uri(BuyingPowerReservation.RESERVATIONS_PATH + "/{orderId}", orderId)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) return new Missing();
                        if (response.getStatusCode().value() != 200) return new Unavailable("Reservation lookup failed");
                        var body = response.bodyTo(BuyingPowerReservation.Response.class);
                        if (body != null && BuyingPowerReservation.RESERVED.equals(body.status())
                                && body.reservedAmountJpy() != null) {
                            return new Reserved(body.reservedAmountJpy(), body.buyingPowerJpy());
                        }
                        return new Unavailable("Reservation is not open");
                    });
        } catch (RestClientException exception) {
            return new Unavailable("Reservation lookup unavailable");
        }
    }

    @Override
    public boolean cancel(String orderId) {
        try {
            restClient.post()
                    .uri(BuyingPowerReservation.RESERVATIONS_PATH + "/{orderId}/release", orderId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException exception) {
            LOG.error("Reservation cancellation unconfirmed; durable submission remains pending", exception);
            return false;
        }
    }
}
