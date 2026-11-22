package io.github.komokendd.dsm.brokerage.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Rejected;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.ReservationOutcome;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Reserved;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Unavailable;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderIntakeServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:05Z");

    private final FakeBuyingPower buyingPower = new FakeBuyingPower();
    private final FakePublisher publisher = new FakePublisher();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final OrderIntakeService service =
            new OrderIntakeService(buyingPower, publisher, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reservedOrderIsPublishedKeyedByAccountAndAnswered202() {
        buyingPower.outcome = new Reserved(new BigDecimal("562000"), new BigDecimal("29438000"));

        var result = service.submit(OrderValidatorTest.limit("D101", "BUY", 200, "2810"));

        assertThat(result.httpStatus()).isEqualTo(202);
        var body = (OrderResult.AcceptedBody) result.body();
        assertThat(body.status()).isEqualTo("ACCEPTED");
        assertThat(body.reservedAmountJpy()).isEqualByComparingTo("562000");
        assertThat(body.acceptedAt()).isEqualTo(NOW);

        assertThat(buyingPower.requests).singleElement().satisfies(request -> {
            assertThat(request.orderId()).isEqualTo(body.orderId());
            assertThat(request.side()).isEqualTo("BUY");
            assertThat(request.orderType()).isEqualTo("LIMIT");
            assertThat(request.limitPrice()).isEqualByComparingTo("2810");
        });
        OrderAcceptedEvent event = publisher.published.getFirst();
        assertThat(event.getAccountId()).isEqualTo("A00001");
        assertThat(event.getOrderId()).isEqualTo(body.orderId());
        assertThat(event.getEventId()).isEqualTo(body.eventId());
        assertThat(event.getEventType()).isEqualTo("ORDER_ACCEPTED");
        assertThat(event.getReservedAmountJpy().scale()).isZero();
        assertThat(event.getLimitPrice().scale()).isEqualTo(1);
        assertThat(event.getDemoScenarioId()).isEqualTo("test");
        assertThat(metrics.get(MetricNames.ORDERS_ACCEPTED)
                        .tags("symbol", "D101", "side", "BUY", "order_type", "LIMIT")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(buyingPower.released).isEmpty();
    }

    @Test
    void insufficientBuyingPowerIs422WithReason() {
        buyingPower.outcome = new Rejected(BuyingPowerReservation.INSUFFICIENT_BUYING_POWER);

        var result = service.submit(new CreateOrderRequest("A00451", "D103", "BUY", "MARKET", 100L, null, "switch-trade"));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((OrderResult.ErrorBody) result.body()).code()).isEqualTo("INSUFFICIENT_BUYING_POWER");
        assertThat(publisher.published).isEmpty();
        assertThat(rejected("D103", "BUY", "INSUFFICIENT_BUYING_POWER")).isEqualTo(1);
    }

    @Test
    void insufficientSellableQuantityIs422() {
        buyingPower.outcome = new Rejected(BuyingPowerReservation.INSUFFICIENT_SELLABLE_QUANTITY);

        var result = service.submit(OrderValidatorTest.limit("D105", "SELL", 100, "12500"));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(rejected("D105", "SELL", "INSUFFICIENT_SELLABLE_QUANTITY")).isEqualTo(1);
    }

    @Test
    void unavailableBuyingPowerIs503() {
        buyingPower.outcome = new Unavailable("connect timed out");

        var result = service.submit(OrderValidatorTest.limit("D101", "BUY", 100, "2800"));

        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(((OrderResult.ErrorBody) result.body()).code()).isEqualTo("BUYING_POWER_UNAVAILABLE");
        assertThat(publisher.published).isEmpty();
        assertThat(rejected("D101", "BUY", "BUYING_POWER_UNAVAILABLE")).isEqualTo(1);
    }

    @Test
    void publishFailureReleasesReservationAndIs503() {
        buyingPower.outcome = new Reserved(new BigDecimal("280000"), new BigDecimal("0"));
        publisher.fail = true;

        var result = service.submit(OrderValidatorTest.limit("D101", "BUY", 100, "2800"));

        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(((OrderResult.ErrorBody) result.body()).code()).isEqualTo("ORDER_PUBLISH_FAILED");
        assertThat(buyingPower.released).containsExactly(buyingPower.requests.getFirst().orderId());
        assertThat(rejected("D101", "BUY", "ORDER_PUBLISH_FAILED")).isEqualTo(1);
        assertThat(metrics.find(MetricNames.ORDERS_ACCEPTED).counter()).isNull();
    }

    @Test
    void invalidOrderIs400WithoutCallingBuyingPowerAndUsesBoundedTags() {
        var result = service.submit(OrderValidatorTest.limit("ZZZZ", "HOLD", 100, "100"));

        assertThat(result.httpStatus()).isEqualTo(400);
        assertThat(((OrderResult.ErrorBody) result.body()).code()).isEqualTo("INVALID_ORDER");
        assertThat(buyingPower.requests).isEmpty();
        assertThat(rejected("UNKNOWN", "UNKNOWN", "INVALID_ORDER")).isEqualTo(1);
    }

    @Test
    void controllerMapsResultToHttpStatus() {
        buyingPower.outcome = new Rejected(BuyingPowerReservation.UNKNOWN_ACCOUNT);
        var controller = new OrderController(service);

        var response = controller.create(OrderValidatorTest.limit("D101", "BUY", 100, "2800"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isEqualTo(new OrderResult.ErrorBody("UNKNOWN_ACCOUNT", "unknown account"));
        assertThat(controller.unreadable(null).getStatusCode().value()).isEqualTo(400);
    }

    private double rejected(String symbol, String side, String reason) {
        return metrics.get(MetricNames.ORDERS_REJECTED)
                .tags("symbol", symbol, "side", side, "reason", reason)
                .counter()
                .count();
    }

    private static final class FakeBuyingPower implements BuyingPowerClient {
        ReservationOutcome outcome;
        final List<BuyingPowerReservation.Request> requests = new ArrayList<>();
        final List<String> released = new ArrayList<>();

        @Override
        public ReservationOutcome reserve(BuyingPowerReservation.Request request) {
            requests.add(request);
            return outcome;
        }

        @Override
        public void release(String orderId) {
            released.add(orderId);
        }
    }

    private static final class FakePublisher implements OrderEventPublisher {
        boolean fail;
        final List<OrderAcceptedEvent> published = new ArrayList<>();

        @Override
        public void publish(OrderAcceptedEvent event) {
            if (fail) {
                throw new IllegalStateException("broker unavailable");
            }
            published.add(event);
        }
    }
}
