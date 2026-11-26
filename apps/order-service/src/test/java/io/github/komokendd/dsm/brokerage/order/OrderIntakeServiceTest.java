package io.github.komokendd.dsm.brokerage.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OrderIntakeServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:05Z");
    private final FakeBuyingPower buyingPower = new FakeBuyingPower();
    private final MemoryStore store = new MemoryStore();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final TransactionTemplate transactions = directTransactions();
    private final OrderIntakeService service = serviceAt(NOW);

    private OrderIntakeService serviceAt(Instant now) {
        return new OrderIntakeService(buyingPower, metrics, Clock.fixed(now, ZoneOffset.UTC),
                store, outbox, transactions);
    }

    @Test
    void acceptedOrderIsDurablyStagedAndAnswered202() {
        buyingPower.outcome = new BuyingPowerClient.Reserved(new BigDecimal("562000"), BigDecimal.ZERO);
        var result = service.submit(OrderValidatorTest.limit("D101", "BUY", 200, "2810"), "customer-order-1");
        assertThat(result.httpStatus()).isEqualTo(202);
        var body = (OrderResult.AcceptedBody) result.body();
        assertThat(body.status()).isEqualTo("ACCEPTED");
        assertThat(body.reservedAmountJpy()).isEqualByComparingTo("562000");
        assertThat(outbox.events).singleElement().satisfies(event -> {
            assertThat(event.getOrderId()).isEqualTo(body.orderId());
            assertThat(event.getEventId()).isEqualTo(body.eventId());
            assertThat(event.getAccountId()).isEqualTo("A00001");
        });
        assertThat(store.rows.get(body.orderId()).state()).isEqualTo("ACCEPTED");
        assertThat(buyingPower.cancellations).isEmpty();
    }

    @Test
    void retryingSameKeyReturnsOriginalAcceptanceWithoutAnotherReservationOrEvent() {
        buyingPower.outcome = new BuyingPowerClient.Reserved(new BigDecimal("280000"), BigDecimal.ZERO);
        var request = OrderValidatorTest.limit("D101", "BUY", 100, "2800");
        var first = service.submit(request, "same-key");
        var repeated = serviceAt(NOW.plusSeconds(5)).submit(request, "same-key");
        assertThat(repeated.body()).isEqualTo(first.body());
        assertThat(buyingPower.requests).hasSize(1);
        assertThat(outbox.events).hasSize(1);
    }

    @Test
    void sameKeyForAnotherOrderIs409() {
        buyingPower.outcome = new BuyingPowerClient.Reserved(new BigDecimal("280000"), BigDecimal.ZERO);
        service.submit(OrderValidatorTest.limit("D101", "BUY", 100, "2800"), "same-key");
        var conflict = service.submit(OrderValidatorTest.limit("D101", "BUY", 200, "2800"), "same-key");
        assertThat(conflict.httpStatus()).isEqualTo(409);
        assertThat(buyingPower.requests).hasSize(1);
    }

    @Test
    void timedOutReservationRemainsQueryableAndIsReconciledWithoutReservingAgain() {
        buyingPower.outcome = new BuyingPowerClient.Unavailable("response timed out after remote commit");
        var pending = service.submit(OrderValidatorTest.limit("D101", "BUY", 100, "2800"), "timeout-key");
        assertThat(pending.httpStatus()).isEqualTo(503);
        var body = (OrderResult.PendingBody) pending.body();
        assertThat(body.status()).isEqualTo("UNKNOWN");
        assertThat(body.statusUrl()).endsWith(body.orderId());
        assertThat(service.status(body.orderId())).isPresent();
        buyingPower.lookupOutcome = new BuyingPowerClient.Reserved(new BigDecimal("280000"), BigDecimal.ZERO);
        var recovered = serviceAt(NOW.plusSeconds(2)).advance(body.orderId(), false);
        assertThat(recovered.httpStatus()).isEqualTo(202);
        assertThat(buyingPower.requests).hasSize(1);
        assertThat(outbox.events).hasSize(1);
        assertThat(buyingPower.cancellations).isEmpty();
    }

    @Test
    void unconfirmedCancellationRemainsDurableUntilFencedCancellationSucceeds() {
        buyingPower.outcome = new BuyingPowerClient.Unavailable("timeout");
        var pending = (OrderResult.PendingBody) service.submit(
                OrderValidatorTest.limit("D101", "BUY", 100, "2800"), "cancel-key").body();
        buyingPower.cancellationConfirmed = false;
        assertThat(serviceAt(NOW.plusSeconds(31)).advance(pending.orderId(), false))
                .isInstanceOf(OrderResult.Pending.class);
        assertThat(store.rows.get(pending.orderId()).pending()).isTrue();
        buyingPower.cancellationConfirmed = true;
        assertThat(serviceAt(NOW.plusSeconds(32)).advance(pending.orderId(), false).httpStatus()).isEqualTo(409);
        assertThat(store.rows.get(pending.orderId()).state()).isEqualTo("CANCELLED");
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void publicationIsNeverAttemptedOnRequestThreadAndAcceptedReservationIsNeverCompensated() {
        buyingPower.outcome = new BuyingPowerClient.Reserved(new BigDecimal("280000"), BigDecimal.ZERO);
        var result = service.submit(OrderValidatorTest.limit("D101", "BUY", 100, "2800"), "outbox-key");
        var body = (OrderResult.AcceptedBody) result.body();
        // Even after its original reservation deadline, an already accepted durable
        // submission must be relayed as the same order, never cancelled/replaced.
        assertThat(serviceAt(NOW.plusSeconds(60)).advance(body.orderId(), false).body()).isEqualTo(body);
        assertThat(buyingPower.cancellations).isEmpty();
        assertThat(outbox.events).hasSize(1);
    }

    @Test
    void rejectionIsDefinitiveAndInvalidOrdersNeverTouchReservation() {
        buyingPower.outcome = new BuyingPowerClient.Rejected(BuyingPowerReservation.INSUFFICIENT_BUYING_POWER);
        assertThat(service.submit(OrderValidatorTest.limit("D101", "BUY", 100, "2800")).httpStatus()).isEqualTo(422);
        int calls = buyingPower.requests.size();
        assertThat(service.submit(OrderValidatorTest.limit("ZZZZ", "HOLD", 100, "100")).httpStatus()).isEqualTo(400);
        assertThat(buyingPower.requests).hasSize(calls);
        assertThat(outbox.events).isEmpty();
    }

    private static TransactionTemplate directTransactions() {
        var template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        return template;
    }

    private static final class FakeBuyingPower implements BuyingPowerClient {
        ReservationOutcome outcome;
        ReservationOutcome lookupOutcome = new Missing();
        boolean cancellationConfirmed;
        final List<BuyingPowerReservation.Request> requests = new ArrayList<>();
        final List<String> cancellations = new ArrayList<>();
        public ReservationOutcome reserve(BuyingPowerReservation.Request request) { requests.add(request); return outcome; }
        public ReservationOutcome lookup(String orderId) { return lookupOutcome; }
        public boolean cancel(String orderId) { cancellations.add(orderId); return cancellationConfirmed; }
    }

    private static final class MemoryStore extends OrderSubmissionStore {
        final Map<String, Submission> rows = new HashMap<>();
        MemoryStore() { super(null); }
        @Override Submission createOrLoad(String key, String hash, OrderAcceptedEvent event, Instant deadline) {
            var existing = rows.get(event.getOrderId());
            if (existing != null) {
                if (!existing.requestHash().equals(hash)) throw new ConflictingRequestException();
                return existing;
            }
            var row = new Submission(event.getOrderId(), hash, "RESERVING", null, deadline, event);
            rows.put(row.orderId(), row); return row;
        }
        @Override Optional<Submission> lock(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override Optional<Submission> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override void transition(String id, String state, String reason) {
            var old = rows.get(id); rows.put(id, new Submission(id, old.requestHash(), state, reason, old.deadline(), old.event()));
        }
    }

    private static final class RecordingOutbox extends JdbcEventOutbox {
        final List<OrderAcceptedEvent> events = new ArrayList<>();
        RecordingOutbox() { super(null, "order_service"); }
        @Override public void append(String id, String topic, String key, SpecificRecord event) {
            assertThat(topic).isEqualTo(Topics.ORDERS_ACCEPTED);
            assertThat(key).isEqualTo(((OrderAcceptedEvent) event).getAccountId());
            events.add((OrderAcceptedEvent) event);
        }
        @Override public void lockWriter(String name) { }
    }
}
