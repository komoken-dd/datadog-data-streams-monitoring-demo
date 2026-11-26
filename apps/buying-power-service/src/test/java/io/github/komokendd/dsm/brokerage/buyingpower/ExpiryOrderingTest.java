package io.github.komokendd.dsm.brokerage.buyingpower;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpiryOrderingTest {
    @Test
    void earlyExpiryReleasesOnlyExpiredCashWhileUnreflectedFillRemainsHeld() {
        var reservation = BuyingPowerRulesTest.buyReservation(1000, "1000000");
        var expiry = BuyingPowerRules.expire(reservation, 500);
        assertThat(expiry.releasedAmountJpy()).isEqualByComparingTo("500000");
        assertThat(expiry.reservationStatus()).isEqualTo(ReservationState.Status.RESERVED);
        reservation = apply(reservation, expiry);
        BigDecimal cash = Jpy.of(1_000_000);
        BigDecimal reserved = Jpy.of(1_000_000).add(expiry.reservedDeltaJpy());
        // Only the genuinely expired half can fund another order.
        BigDecimal newOrder = cash.subtract(reserved);
        assertThat(newOrder).isEqualByComparingTo("500000");
        reserved = reserved.add(newOrder);
        var fill = BuyingPowerRules.fill(Side.BUY, reservation, 500, 500, Jpy.of(500_000));
        assertThat(cash.add(fill.cashDeltaJpy()).subtract(reserved.add(fill.reservedDeltaJpy())))
                .isEqualByComparingTo("0");
        assertThat(fill.reservationStatus()).isEqualTo(ReservationState.Status.RELEASED);
    }

    @Test
    void earlyExpiryKeepsSoldButUnreflectedSharesReserved() {
        var reservation = BuyingPowerRulesTest.sellReservation(1000);
        var expiry = BuyingPowerRules.expire(reservation, 500);
        assertThat(expiry.releasedQuantity()).isEqualTo(500);
        reservation = apply(reservation, expiry);
        var fill = BuyingPowerRules.fill(Side.SELL, reservation, 500, 500, Jpy.of(5_000_000));
        assertThat(fill.sellableReservedDelta()).isEqualTo(-500);
        assertThat(fill.sellableQuantityDelta()).isEqualTo(-500);
        assertThat(fill.reservationStatus()).isEqualTo(ReservationState.Status.RELEASED);
    }

    @Test
    void everyAllowedExpiryPositionConvergesAcrossMultiplePartialFillsAndRounding() {
        for (List<String> sequence : List.of(List.of("expiry","fill","fill"),
                List.of("fill","expiry","fill"), List.of("fill","fill","expiry"))) {
            var state = BuyingPowerRulesTest.buyReservation(300, "1000000");
            BigDecimal cashDelta = Jpy.of(0);
            BigDecimal reservedDelta = Jpy.of(0);
            for (String step : sequence) {
                var delta = step.equals("expiry") ? BuyingPowerRules.expire(state, 100)
                        : BuyingPowerRules.fill(Side.BUY, state, 100, 100, Jpy.of(280_000));
                cashDelta = cashDelta.add(delta.cashDeltaJpy());
                reservedDelta = reservedDelta.add(delta.reservedDeltaJpy());
                state = apply(state, delta);
            }
            assertThat(cashDelta).as(sequence.toString()).isEqualByComparingTo("-560000");
            assertThat(reservedDelta).as(sequence.toString()).isEqualByComparingTo("-1000000");
            assertThat(state.reflectedQuantity()).isEqualTo(200);
            assertThat(state.expiredQuantity()).isEqualTo(100);
            assertThat(state.status()).isEqualTo(ReservationState.Status.RELEASED);
        }
    }

    static ReservationState apply(ReservationState state, LedgerDelta delta) {
        return new ReservationState(state.orderId(), state.accountId(), state.symbol(), state.side(), state.orderType(),
                state.quantity(), state.reservedAmountJpy(), state.releasedAmountJpy().add(delta.releasedAmountJpy()),
                state.reservedQuantity(), state.releasedQuantity() + delta.releasedQuantity(), delta.reservationStatus(),
                state.reflectedQuantity() + delta.reflectedQuantityDelta(),
                delta.expiredQuantity() == null ? state.expiredQuantity() : delta.expiredQuantity());
    }
}
