package io.github.komokendd.dsm.brokerage.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.order.OrderValidator.InvalidOrderException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderValidatorTest {
    private final OrderValidator validator = new OrderValidator();

    @Test
    void acceptsLimitOrderOnTickWithinDailyLimit() {
        var order = validator.validate(limit("D101", "BUY", 200, "2810"));

        assertThat(order.side()).isEqualTo(Side.BUY);
        assertThat(order.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(order.limitPrice()).isEqualByComparingTo("2810");
        assertThat(order.limitPrice().scale()).isEqualTo(1);
        assertThat(order.quantity()).isEqualTo(200);
    }

    @Test
    void acceptsMarketOrderWithoutLimitPriceAndNormalisesCase() {
        var order = validator.validate(new CreateOrderRequest("A00001", "D105", "sell", "market", 100L, null, " "));

        assertThat(order.side()).isEqualTo(Side.SELL);
        assertThat(order.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.limitPrice()).isNull();
        assertThat(order.demoScenarioId()).isNull();
    }

    @Test
    void rejectsUnknownSymbol() {
        assertInvalid(limit("9999", "BUY", 100, "1000"), "unknown symbol");
    }

    @Test
    void rejectsBadSideAndOrderType() {
        assertInvalid(limit("D101", "HOLD", 100, "2800"), "side");
        assertInvalid(new CreateOrderRequest("A00001", "D101", "BUY", "STOP", 100L, null, null), "orderType");
    }

    @Test
    void rejectsMissingAccount() {
        assertInvalid(new CreateOrderRequest(" ", "D101", "BUY", "MARKET", 100L, null, null), "accountId");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100, 150, 1_000_100})
    void rejectsQuantityOutsideTradingUnitsOrMaximum(long quantity) {
        assertThatThrownBy(() -> validator.validate(limit("D101", "BUY", quantity, "2800")))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void acceptsMaximumQuantity() {
        assertThat(validator.validate(limit("D103", "BUY", 1_000_000, "980")).quantity()).isEqualTo(1_000_000);
    }

    @Test
    void rejectsLimitPriceOffTick() {
        // D105 trades in the 10-yen tick band.
        assertInvalid(limit("D105", "BUY", 100, "12505"), "tick");
        assertInvalid(limit("D101", "BUY", 100, "2800.5"), "tick");
    }

    @Test
    void rejectsLimitPriceOutsideDailyLimit() {
        // D101 base 2800, limit width 500.
        assertInvalid(limit("D101", "BUY", 100, "3301"), "daily price limit");
        assertInvalid(limit("D101", "SELL", 100, "2299"), "daily price limit");
        assertThat(validator.validate(limit("D101", "SELL", 100, "2300")).limitPrice()).isEqualByComparingTo("2300");
        assertThat(validator.validate(limit("D101", "BUY", 100, "3300")).limitPrice()).isEqualByComparingTo("3300");
    }

    @Test
    void limitOrderRequiresPositiveLimitPrice() {
        assertInvalid(new CreateOrderRequest("A00001", "D101", "BUY", "LIMIT", 100L, null, null), "requires limitPrice");
        assertInvalid(limit("D101", "BUY", 100, "0"), "positive");
    }

    @Test
    void marketOrderMustNotCarryLimitPrice() {
        assertInvalid(new CreateOrderRequest("A00001", "D101", "BUY", "MARKET", 100L, new BigDecimal("2800"), null),
                "must not carry");
    }

    private void assertInvalid(CreateOrderRequest request, String messagePart) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining(messagePart);
    }

    static CreateOrderRequest limit(String symbol, String side, long quantity, String price) {
        return new CreateOrderRequest("A00001", symbol, side, "LIMIT", quantity, new BigDecimal(price), "test");
    }
}
