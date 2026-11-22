package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test helpers: order factory and a tiny in-memory book that applies engine updates. */
final class Orders {
    private Orders() {
    }

    static WorkingOrder market(String id, String symbol, Side side, long quantity, Instant acceptedAt) {
        return order(id, symbol, side, OrderType.MARKET, quantity, null, acceptedAt);
    }

    static WorkingOrder limit(String id, String symbol, Side side, long quantity, BigDecimal limitPrice, Instant acceptedAt) {
        return order(id, symbol, side, OrderType.LIMIT, quantity, Jpy.price(limitPrice), acceptedAt);
    }

    static WorkingOrder order(
            String id, String symbol, Side side, OrderType type, long quantity, BigDecimal limitPrice, Instant acceptedAt) {
        BigDecimal reserved = side == Side.BUY
                ? BuyingPowerReservation.buyReservationAmount(Instruments.require(symbol), type, limitPrice, quantity)
                : Jpy.of(0);
        return new WorkingOrder(
                id, "evt-" + id, "A00001", symbol, side, type, quantity, limitPrice, reserved, null,
                0, 1, WorkingOrderStatus.WORKING, acceptedAt, null);
    }

    static final class Book {
        private final Map<String, WorkingOrder> working = new LinkedHashMap<>();
        final List<TickResult.Fill> fills = new ArrayList<>();
        final List<TickResult.Expiry> expiries = new ArrayList<>();

        void add(WorkingOrder order) {
            working.put(order.orderId(), order);
        }

        int size() {
            return working.size();
        }

        List<WorkingOrder> orders() {
            return List.copyOf(working.values());
        }

        TickResult tick(MatchingEngine engine, Instant now) {
            TickResult result = engine.tick(orders(), now);
            for (TickResult.OrderUpdate update : result.updates()) {
                WorkingOrder updated = working.get(update.orderId()).apply(update);
                if (updated.status() == WorkingOrderStatus.WORKING) {
                    working.put(updated.orderId(), updated);
                } else {
                    working.remove(updated.orderId());
                }
            }
            fills.addAll(result.fills());
            expiries.addAll(result.expiries());
            return result;
        }
    }
}
