package io.github.komokendd.dsm.brokerage.simulator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Read-only scenario preparation and execution correlation. No balance reset API is used. */
class ScenarioAccountClient {
    private final RestClient buyingPower;
    private final RestClient orderState;

    ScenarioAccountClient(RestClient buyingPower, RestClient orderState) {
        this.buyingPower = buyingPower;
        this.orderState = orderState;
    }

    Optional<Account> account(String accountId) {
        return get(buyingPower, "/internal/buying-power/accounts/{id}", accountId, Account.class);
    }

    Optional<OrderProgress> order(String orderId) {
        return get(orderState, "/internal/orders/{id}", orderId, OrderProgress.class);
    }

    private static <T> Optional<T> get(RestClient client, String path, String id, Class<T> type) {
        return client.get().uri(path, id).exchange((request, response) -> {
            if (response.getStatusCode().value() == 404) return Optional.empty();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RestClientException("Scenario inquiry returned " + response.getStatusCode().value());
            }
            return Optional.ofNullable(response.bodyTo(type));
        });
    }

    record Account(String accountId, BigDecimal buyingPowerJpy, BigDecimal reservedAmountJpy, List<Position> sellable) {
        boolean busy() {
            return reservedAmountJpy == null || reservedAmountJpy.signum() != 0 || sellable == null
                    || sellable.stream().anyMatch(p -> p.reservedQuantity() != 0);
        }
    }

    record Position(String symbol, long quantity, long reservedQuantity, long sellableQuantity) { }

    record OrderProgress(String status, long quantity, long cumulativeQuantity, List<Execution> executions) {
        boolean fullyObservedFill() {
            return "FILLED".equals(status) && executions != null && cumulativeQuantity == quantity
                    && executions.stream().mapToLong(Execution::quantity).sum() == quantity;
        }

        boolean terminal() {
            return fullyObservedFill() || "EXPIRED".equals(status);
        }

        BigDecimal proceeds() {
            return executions.stream().map(e -> e.price().multiply(BigDecimal.valueOf(e.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    record Execution(long quantity, BigDecimal price) { }
}
