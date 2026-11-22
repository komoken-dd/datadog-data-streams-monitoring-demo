package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.TickSizes;
import java.math.BigDecimal;

/** Order-entry checks a broker runs before touching buying power. */
final class OrderValidator {
    static final long MAX_QUANTITY = 1_000_000;

    ValidatedOrder validate(CreateOrderRequest request) {
        if (request == null) {
            throw new InvalidOrderException("request body is required");
        }
        if (request.accountId() == null || request.accountId().isBlank()) {
            throw new InvalidOrderException("accountId is required");
        }
        Instrument instrument = Instruments.find(request.symbol())
                .orElseThrow(() -> new InvalidOrderException("unknown symbol: " + request.symbol()));
        Side side = parseSide(request.side());
        OrderType orderType = parseOrderType(request.orderType());
        long quantity = validateQuantity(request.quantity());
        BigDecimal limitPrice = validateLimitPrice(instrument, orderType, request.limitPrice());
        return new ValidatedOrder(
                request.accountId().trim(),
                instrument,
                side,
                orderType,
                quantity,
                limitPrice,
                blankToNull(request.demoScenarioId()));
    }

    private static Side parseSide(String value) {
        try {
            return Side.parse(value);
        } catch (RuntimeException exception) {
            throw new InvalidOrderException("side must be BUY or SELL");
        }
    }

    private static OrderType parseOrderType(String value) {
        try {
            return OrderType.parse(value);
        } catch (RuntimeException exception) {
            throw new InvalidOrderException("orderType must be MARKET or LIMIT");
        }
    }

    private static long validateQuantity(Long quantity) {
        if (quantity == null || quantity <= 0) {
            throw new InvalidOrderException("quantity must be positive");
        }
        if (quantity % Instrument.TRADING_UNIT != 0) {
            throw new InvalidOrderException("quantity must be a multiple of the trading unit " + Instrument.TRADING_UNIT);
        }
        if (quantity > MAX_QUANTITY) {
            throw new InvalidOrderException("quantity must not exceed " + MAX_QUANTITY);
        }
        return quantity;
    }

    private static BigDecimal validateLimitPrice(Instrument instrument, OrderType orderType, BigDecimal limitPrice) {
        if (orderType == OrderType.MARKET) {
            if (limitPrice != null) {
                throw new InvalidOrderException("a MARKET order must not carry a limitPrice");
            }
            return null;
        }
        if (limitPrice == null) {
            throw new InvalidOrderException("a LIMIT order requires limitPrice");
        }
        if (limitPrice.signum() <= 0) {
            throw new InvalidOrderException("limitPrice must be positive");
        }
        if (!instrument.withinDailyLimit(limitPrice)) {
            throw new InvalidOrderException("limitPrice %s is outside the daily price limit %s-%s".formatted(
                    limitPrice.toPlainString(),
                    instrument.lowerLimitPrice().toPlainString(),
                    instrument.upperLimitPrice().toPlainString()));
        }
        if (!TickSizes.onTick(limitPrice)) {
            throw new InvalidOrderException("limitPrice %s is not on the tick size %s".formatted(
                    limitPrice.toPlainString(), TickSizes.tick(limitPrice).toPlainString()));
        }
        return Jpy.price(limitPrice);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record ValidatedOrder(
            String accountId,
            Instrument instrument,
            Side side,
            OrderType orderType,
            long quantity,
            BigDecimal limitPrice,
            String demoScenarioId) {
        String symbol() {
            return instrument.symbol();
        }
    }

    static final class InvalidOrderException extends RuntimeException {
        InvalidOrderException(String message) {
            super(message);
        }
    }
}
