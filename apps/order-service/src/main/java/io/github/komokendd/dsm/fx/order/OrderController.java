package io.github.komokendd.dsm.fx.order;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.MetricNames;
import io.github.komokendd.dsm.fx.common.Money;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
final class OrderController {
    private static final Logger LOG = LoggerFactory.getLogger(OrderController.class);
    private static final int AVRO_DECIMAL_PRECISION = 19;
    private static final int AVRO_DECIMAL_SCALE = 2;
    private static final Set<String> PAIRS = Set.of("USD/JPY", "EUR/USD", "GBP/USD");
    private static final Set<String> SIDES = Set.of("BUY", "SELL");

    private final KafkaTemplate<String, OrderReceivedEvent> kafka;
    private final MeterRegistry metrics;

    OrderController(KafkaTemplate<String, OrderReceivedEvent> kafka, MeterRegistry metrics) {
        this.kafka = kafka;
        this.metrics = metrics;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    OrderAcceptedResponse create(@Valid @RequestBody CreateOrderRequest request) throws Exception {
        if (!PAIRS.contains(request.currencyPair())) {
            throw new IllegalArgumentException("currencyPair must be one of " + PAIRS);
        }
        String side = request.side().toUpperCase();
        if (!SIDES.contains(side)) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }

        Instant now = Instant.now();
        String orderId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        BigDecimal notionalUsd = Money.usdEquivalent(request.currencyPair(), request.quantity());
        if (notionalUsd.precision() > AVRO_DECIMAL_PRECISION
                || notionalUsd.scale() > AVRO_DECIMAL_SCALE) {
            throw new IllegalArgumentException(
                    "quantity produces a USD-equivalent notional outside the supported range");
        }
        var event = OrderReceivedEvent.newBuilder()
                .setEventId(eventId)
                .setOrderId(orderId)
                .setEventType("ORDER_RECEIVED")
                .setEventVersion(1)
                .setOccurredAt(now)
                .setCurrencyPair(request.currencyPair())
                .setDemoScenarioId(request.demoScenarioId())
                .setSide(side)
                .setQuantity(request.quantity())
                .setLimitPrice(request.limitPrice())
                .setNotionalUsd(notionalUsd)
                .setSubmittedAt(now)
                .build();

        kafka.send(Topics.ORDERS_RECEIVED, orderId, event).get(5, TimeUnit.SECONDS);
        Counter.builder(MetricNames.ORDERS_RECEIVED)
                .tag("currency_pair", request.currencyPair())
                .tag("side", side)
                .register(metrics)
                .increment();
        try (var ignored = EventLogContext.open(
                orderId, eventId, "ORDER_RECEIVED", request.currencyPair(), request.demoScenarioId())) {
            LOG.info("Synthetic FX order accepted");
        }
        return new OrderAcceptedResponse(orderId, eventId, "RECEIVED", now);
    }

    record OrderAcceptedResponse(String orderId, String eventId, String status, Instant acceptedAt) {
    }
}
