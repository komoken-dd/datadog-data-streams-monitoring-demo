package io.github.komokendd.dsm.fx.state;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.KafkaHeaders;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import io.github.komokendd.dsm.fx.schema.OrderRejectedEvent;
import io.github.komokendd.dsm.fx.schema.SettlementInstructedEvent;
import io.github.komokendd.dsm.fx.schema.TradeConfirmedEvent;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class OrderStateProjection {
    private static final Logger LOG = LoggerFactory.getLogger(OrderStateProjection.class);
    private final JdbcTemplate jdbc;

    OrderStateProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.ORDERS_RECEIVED, groupId = "order-state-service-v1", containerFactory = "stateReceivedFactory")
    void received(OrderReceivedEvent event) {
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), OrderStatus.RECEIVED, "received");
    }

    @KafkaListener(topics = Topics.ORDERS_APPROVED, groupId = "order-state-service-v1", containerFactory = "stateApprovedFactory")
    void approved(ConsumerRecord<String, OrderApprovedEvent> record) {
        OrderApprovedEvent event = record.value();
        OrderStatus status = headerBoolean(record, KafkaHeaders.REDRIVEN)
                ? OrderStatus.REDRIVEN
                : OrderStatus.PENDING_EXECUTION;
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), status,
                status == OrderStatus.REDRIVEN ? "redriven" : "approved");
    }

    @KafkaListener(topics = Topics.ORDERS_REJECTED, groupId = "order-state-service-v1", containerFactory = "stateRejectedFactory")
    void rejected(OrderRejectedEvent event) {
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), OrderStatus.REJECTED, "rejected");
    }

    @KafkaListener(topics = Topics.EXECUTION_RETRY_5S, groupId = "order-state-service-v1", containerFactory = "stateApprovedFactory")
    void retryFive(ConsumerRecord<String, OrderApprovedEvent> record) {
        retry(record);
    }

    @KafkaListener(topics = Topics.EXECUTION_RETRY_30S, groupId = "order-state-service-v1", containerFactory = "stateApprovedFactory")
    void retryThirty(ConsumerRecord<String, OrderApprovedEvent> record) {
        retry(record);
    }

    @KafkaListener(topics = Topics.EXECUTION_DLT, groupId = "order-state-service-v1", containerFactory = "stateApprovedFactory")
    void deadLetter(ConsumerRecord<String, OrderApprovedEvent> record) {
        var event = record.value();
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), OrderStatus.DEAD_LETTER,
                "dead-letter-" + retryCount(record));
    }

    @KafkaListener(topics = Topics.TRADES_EXECUTED, groupId = "order-state-service-v1", containerFactory = "stateExecutedFactory")
    void executed(TradeExecutedEvent event) {
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), OrderStatus.EXECUTED, "executed");
    }

    @KafkaListener(topics = Topics.TRADES_CONFIRMED, groupId = "order-state-service-v1", containerFactory = "stateConfirmedFactory")
    void confirmed(TradeConfirmedEvent event) {
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), OrderStatus.CONFIRMED, "confirmed");
    }

    @KafkaListener(topics = Topics.SETTLEMENTS_INSTRUCTED, groupId = "order-state-service-v1", containerFactory = "stateSettlementFactory")
    void settled(SettlementInstructedEvent event) {
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(),
                OrderStatus.SETTLEMENT_INSTRUCTED, "settled");
    }

    private void retry(ConsumerRecord<String, OrderApprovedEvent> record) {
        var event = record.value();
        transition(event.getEventId(), event.getOrderId(), event.getCurrencyPair(), event.getSide(),
                event.getNotionalUsd(), event.getSubmittedAt(), event.getDemoScenarioId(), OrderStatus.RETRYING,
                "retry-" + retryCount(record));
    }

    void transition(
            String eventId,
            String orderId,
            String currencyPair,
            String side,
            BigDecimal notionalUsd,
            Instant submittedAt,
            String scenarioId,
            OrderStatus next,
            String transitionKey) {
        int claimed = jdbc.update("""
                insert into order_state_service.processed_transitions (event_id, transition_key, processed_at)
                values (?, ?, now()) on conflict do nothing
                """, eventId, transitionKey);
        if (claimed == 0) {
            return;
        }
        jdbc.update("""
                insert into order_state_service.orders
                    (order_id, status, status_rank, currency_pair, side, notional_usd, submitted_at, updated_at, demo_scenario_id)
                values (?, ?, ?, ?, ?, ?, ?, now(), ?)
                on conflict (order_id) do update set
                    status = excluded.status,
                    status_rank = excluded.status_rank,
                    updated_at = now(),
                    demo_scenario_id = coalesce(excluded.demo_scenario_id, order_state_service.orders.demo_scenario_id)
                where excluded.status_rank >= order_state_service.orders.status_rank
                  and order_state_service.orders.status not in ('REJECTED', 'SETTLEMENT_INSTRUCTED')
                """, orderId, next.name(), next.rank(), currencyPair, side, notionalUsd,
                Timestamp.from(submittedAt), scenarioId);
        try (var ignored = EventLogContext.open(orderId, eventId, next.name(), currencyPair, scenarioId)) {
            LOG.info("Order state projection transitioned to {}", next);
        }
    }

    private static int retryCount(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(KafkaHeaders.RETRY_COUNT);
        return header == null ? 0 : Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
    }

    private static boolean headerBoolean(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header != null && Boolean.parseBoolean(new String(header.value(), StandardCharsets.UTF_8));
    }
}
