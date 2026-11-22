package io.github.komokendd.dsm.brokerage.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderSnapshotTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-20T00:00:01Z");

    @Test
    void anyArrivalOrderConvergesToFilled() {
        List<OrderSnapshot> events = List.of(
                event(0, OrderStatus.ACCEPTED, "S-1"),
                event(100, OrderStatus.PARTIALLY_FILLED, null),
                event(300, OrderStatus.FILLED, null));

        for (List<OrderSnapshot> order : permutations(events)) {
            OrderSnapshot state = replay(order);
            assertThat(state.status()).as("arrival order %s", order).isEqualTo(OrderStatus.FILLED);
            assertThat(state.cumulativeQuantity()).isEqualTo(300);
            assertThat(state.unfilledQuantity()).isZero();
            assertThat(state.demoScenarioId()).isEqualTo("S-1");
        }
    }

    @Test
    void executionBeforeAcceptedCreatesTheOrder() {
        OrderSnapshot state = OrderSnapshot.merge(null, event(100, OrderStatus.PARTIALLY_FILLED, null));
        state = OrderSnapshot.merge(state, event(0, OrderStatus.ACCEPTED, "S-1"));

        assertThat(state.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(state.cumulativeQuantity()).isEqualTo(100);
        assertThat(state.acceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(state.demoScenarioId()).isEqualTo("S-1");
    }

    @Test
    void partialFillThenExpiryStaysExpiredEvenWhenAStaleFillArrivesLate() {
        OrderSnapshot state = replay(List.of(
                event(0, OrderStatus.ACCEPTED, null),
                event(200, OrderStatus.EXPIRED, null),
                event(100, OrderStatus.PARTIALLY_FILLED, null),
                event(200, OrderStatus.PARTIALLY_FILLED, null)));

        assertThat(state.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(state.cumulativeQuantity()).isEqualTo(200);
    }

    @Test
    void cumulativeQuantityOnlyGrows() {
        OrderSnapshot state = replay(List.of(
                event(200, OrderStatus.PARTIALLY_FILLED, null),
                event(100, OrderStatus.PARTIALLY_FILLED, null)));

        assertThat(state.cumulativeQuantity()).isEqualTo(200);
        assertThat(state.unfilledQuantity()).isEqualTo(100);
    }

    @Test
    void filledIsNotOverwrittenByExpiry() {
        OrderSnapshot state = replay(List.of(
                event(300, OrderStatus.FILLED, null),
                event(300, OrderStatus.EXPIRED, null)));

        assertThat(state.status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void duplicateEventIsANoOp() {
        OrderSnapshot first = OrderSnapshot.merge(null, event(100, OrderStatus.PARTIALLY_FILLED, null));

        assertThat(OrderSnapshot.merge(first, event(100, OrderStatus.PARTIALLY_FILLED, null))).isEqualTo(first);
    }

    private static OrderSnapshot event(long cumulative, OrderStatus status, String scenario) {
        return new OrderSnapshot("O-1", "A00001", "D105", "SELL", "LIMIT", 300, cumulative, status,
                ACCEPTED_AT, scenario);
    }

    private static OrderSnapshot replay(List<OrderSnapshot> events) {
        OrderSnapshot state = null;
        for (OrderSnapshot event : events) {
            state = OrderSnapshot.merge(state, event);
        }
        return state;
    }

    static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
