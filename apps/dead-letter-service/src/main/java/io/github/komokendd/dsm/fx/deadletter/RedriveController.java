package io.github.komokendd.dsm.fx.deadletter;

import io.github.komokendd.dsm.fx.common.KafkaHeaders;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
class RedriveController {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, OrderApprovedEvent> kafka;

    RedriveController(JdbcTemplate jdbc, KafkaTemplate<String, OrderApprovedEvent> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @PostMapping("/redrive")
    @Transactional
    RedriveResult redrive() throws Exception {
        List<DeadLetterRow> rows = jdbc.query("""
                select * from dead_letter_service.dead_letters
                where resolved_at is null
                order by received_at for update
                """, RedriveController::map);
        for (DeadLetterRow row : rows) {
            String newEventId = UUID.randomUUID().toString();
            var event = OrderApprovedEvent.newBuilder()
                    .setEventId(newEventId)
                    .setOrderId(row.orderId())
                    .setEventType("ORDER_APPROVED")
                    .setEventVersion(1)
                    .setOccurredAt(Instant.now())
                    .setCurrencyPair(row.currencyPair())
                    .setDemoScenarioId(row.scenarioId())
                    .setSide(row.side())
                    .setQuantity(row.quantity())
                    .setLimitPrice(row.limitPrice())
                    .setNotionalUsd(row.notionalUsd())
                    .setSubmittedAt(row.submittedAt())
                    .setApprovedAt(row.approvedAt())
                    .build();
            var headers = new RecordHeaders()
                    .add(KafkaHeaders.REDRIVEN, "true".getBytes(StandardCharsets.UTF_8))
                    .add(KafkaHeaders.ORIGINATING_TOPIC, Topics.EXECUTION_DLT.getBytes(StandardCharsets.UTF_8));
            kafka.send(new ProducerRecord<>(Topics.ORDERS_APPROVED, null, row.orderId(), event, headers))
                    .get(5, TimeUnit.SECONDS);
            jdbc.update("""
                    update dead_letter_service.dead_letters
                    set resolved_at = now(), redrive_event_id = ? where event_id = ? and resolved_at is null
                    """, newEventId, row.eventId());
        }
        return new RedriveResult(rows.size(), Instant.now());
    }

    private static DeadLetterRow map(ResultSet rs, int rowNumber) throws SQLException {
        return new DeadLetterRow(
                rs.getString("event_id"), rs.getString("order_id"), rs.getString("currency_pair"),
                rs.getString("side"), rs.getBigDecimal("quantity"), rs.getBigDecimal("limit_price"),
                rs.getBigDecimal("notional_usd"), rs.getTimestamp("submitted_at").toInstant(),
                rs.getTimestamp("approved_at").toInstant(), rs.getString("demo_scenario_id"));
    }

    record RedriveResult(int redrivenCount, Instant completedAt) {
    }

    record DeadLetterRow(
            String eventId,
            String orderId,
            String currencyPair,
            String side,
            java.math.BigDecimal quantity,
            java.math.BigDecimal limitPrice,
            java.math.BigDecimal notionalUsd,
            Instant submittedAt,
            Instant approvedAt,
            String scenarioId) {
    }
}
