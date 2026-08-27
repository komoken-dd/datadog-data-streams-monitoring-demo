package io.github.komokendd.dsm.fx.execution;

import io.github.komokendd.dsm.fx.common.DemoScenarios;
import io.github.komokendd.dsm.fx.common.EventIds;
import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.KafkaHeaders;
import io.github.komokendd.dsm.fx.common.MetricNames;
import io.github.komokendd.dsm.fx.common.SyntheticFeeCalculator;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
final class ExecutionProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionProcessor.class);
    private static final String FAILURE_CATEGORY = "DEMO_EXECUTION_TIMEOUT";

    private final KafkaTemplate<String, Object> kafka;
    private final MeterRegistry metrics;
    private final ExecutionControl control;
    private final FailureRouter failureRouter = new FailureRouter();

    ExecutionProcessor(KafkaTemplate<String, Object> kafka, MeterRegistry metrics, ExecutionControl control) {
        this.kafka = kafka;
        this.metrics = metrics;
        this.control = control;
    }

    @KafkaListener(
            topics = Topics.ORDERS_APPROVED,
            groupId = "execution-adapter-v1",
            containerFactory = "approvedKafkaListenerFactory")
    void onApproved(ConsumerRecord<String, OrderApprovedEvent> record) {
        process(record, 0);
    }

    @KafkaListener(
            topics = Topics.EXECUTION_RETRY_5S,
            groupId = "execution-adapter-retry-5s-v1",
            containerFactory = "approvedKafkaListenerFactory")
    void onFiveSecondRetry(ConsumerRecord<String, OrderApprovedEvent> record) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(5));
        process(record, headerInt(record.headers(), KafkaHeaders.RETRY_COUNT, 1));
    }

    @KafkaListener(
            topics = Topics.EXECUTION_RETRY_30S,
            groupId = "execution-adapter-retry-30s-v1",
            containerFactory = "approvedKafkaListenerFactory")
    void onThirtySecondRetry(ConsumerRecord<String, OrderApprovedEvent> record) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(30));
        process(record, headerInt(record.headers(), KafkaHeaders.RETRY_COUNT, 2));
    }

    private void process(ConsumerRecord<String, OrderApprovedEvent> record, int completedRetries) {
        OrderApprovedEvent order = record.value();
        boolean redriven = headerBoolean(record.headers(), KafkaHeaders.REDRIVEN);
        try (var context = EventLogContext.open(
                        order.getOrderId(),
                        order.getEventId(),
                        order.getEventType(),
                        order.getCurrencyPair(),
                        order.getDemoScenarioId())
                .retry(completedRetries)) {
            sleep(control.delayMillis());
            if (shouldRouteToFailure(order.getDemoScenarioId(), redriven)) {
                routeFailure(record, completedRetries);
                return;
            }
            execute(order);
        }
    }

    static boolean shouldRouteToFailure(String scenarioId, boolean redriven) {
        return !redriven
                && DemoScenarios.isProcessingFailure(scenarioId);
    }

    private void routeFailure(ConsumerRecord<String, OrderApprovedEvent> record, int completedRetries) {
        FailureRoute route = failureRouter.route(completedRetries);
        int newRetryCount = completedRetries + 1;
        String destination = switch (route) {
            case RETRY_5_SECONDS -> Topics.EXECUTION_RETRY_5S;
            case RETRY_30_SECONDS -> Topics.EXECUTION_RETRY_30S;
            case DEAD_LETTER -> Topics.EXECUTION_DLT;
        };
        Headers headers = KafkaHeaders.copyDemoMetadata(record.headers());
        replaceHeader(headers, KafkaHeaders.RETRY_COUNT, Integer.toString(newRetryCount));
        replaceHeader(headers, KafkaHeaders.FAILURE_CATEGORY, FAILURE_CATEGORY);
        replaceHeader(headers, KafkaHeaders.FAILURE_TIMESTAMP, Instant.now().toString());
        replaceHeader(headers, KafkaHeaders.ORIGINATING_TOPIC, Topics.ORDERS_APPROVED);
        kafka.send(new ProducerRecord<>(destination, null, record.key(), record.value(), headers)).join();
        Counter.builder(MetricNames.RETRY_ATTEMPTS)
                .tag("currency_pair", record.value().getCurrencyPair())
                .tag("failure_category", FAILURE_CATEGORY)
                .register(metrics)
                .increment();
        if (route == FailureRoute.DEAD_LETTER) {
            Counter.builder(MetricNames.DLT_EVENTS)
                    .tag("currency_pair", record.value().getCurrencyPair())
                    .tag("failure_category", FAILURE_CATEGORY)
                    .register(metrics)
                    .increment();
        }
        LOG.warn("Synthetic execution failure routed to {}", destination);
    }

    private void execute(OrderApprovedEvent order) {
        Instant executedAt = Instant.now();
        String eventId = EventIds.derived(order.getEventId(), "TRADE_EXECUTED");
        var syntheticFeeUsd = SyntheticFeeCalculator.feeUsd(order.getCurrencyPair(), order.getNotionalUsd());
        var trade = TradeExecutedEvent.newBuilder()
                .setEventId(eventId)
                .setOrderId(order.getOrderId())
                .setEventType("TRADE_EXECUTED")
                .setEventVersion(1)
                .setOccurredAt(executedAt)
                .setCurrencyPair(order.getCurrencyPair())
                .setDemoScenarioId(order.getDemoScenarioId())
                .setExecutionId(EventIds.derived(order.getEventId(), "EXECUTION"))
                .setSide(order.getSide())
                .setQuantity(order.getQuantity())
                .setExecutionPrice(order.getLimitPrice())
                .setNotionalUsd(order.getNotionalUsd())
                .setSubmittedAt(order.getSubmittedAt())
                .setExecutedAt(executedAt)
                .build();
        kafka.send(Topics.TRADES_EXECUTED, order.getOrderId(), trade).join();
        Counter.builder(MetricNames.EXECUTIONS_COMPLETED)
                .tag("currency_pair", order.getCurrencyPair())
                .tag("side", order.getSide())
                .register(metrics)
                .increment();
        DistributionSummary.builder(MetricNames.EXECUTIONS_NOTIONAL)
                .baseUnit("USD")
                .publishPercentileHistogram()
                .tag("currency_pair", order.getCurrencyPair())
                .tag("side", order.getSide())
                .register(metrics)
                .record(order.getNotionalUsd().doubleValue());
        DistributionSummary.builder(MetricNames.EXECUTIONS_SYNTHETIC_FEE)
                .baseUnit("USD")
                .publishPercentileHistogram()
                .tag("currency_pair", order.getCurrencyPair())
                .tag("side", order.getSide())
                .register(metrics)
                .record(syntheticFeeUsd.doubleValue());
        DistributionSummary.builder(MetricNames.ORDER_TO_EXECUTION)
                .baseUnit("milliseconds")
                .publishPercentileHistogram()
                .tag("currency_pair", order.getCurrencyPair())
                .register(metrics)
                .record(Duration.between(order.getSubmittedAt(), executedAt).toMillis());
        LOG.info("Synthetic execution completed, execution_event_id={}, synthetic_fee_usd={}",
                eventId, syntheticFeeUsd);
    }

    private static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execution delay interrupted", exception);
        }
    }

    private static void replaceHeader(Headers headers, String name, String value) {
        headers.remove(name);
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static int headerInt(Headers headers, String name, int fallback) {
        var header = headers.lastHeader(name);
        return header == null ? fallback : Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
    }

    private static boolean headerBoolean(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header != null && Boolean.parseBoolean(new String(header.value(), StandardCharsets.UTF_8));
    }
}
