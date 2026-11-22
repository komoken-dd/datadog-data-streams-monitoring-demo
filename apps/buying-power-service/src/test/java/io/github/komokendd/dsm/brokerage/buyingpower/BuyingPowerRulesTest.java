package io.github.komokendd.dsm.brokerage.buyingpower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.buyingpower.ReservationState.Status;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BuyingPowerRulesTest {
    @Test
    void limitBuyReservesLimitPriceTimesQuantity() {
        var plan = ReservationService.ReservationPlan.of(request("BUY", "LIMIT", 300, new BigDecimal("2810.0")));

        assertThat(plan.reservedAmountJpy()).isEqualByComparingTo("843000");
        assertThat(plan.reservedAmountJpy().scale()).isZero();
        assertThat(plan.reservedQuantity()).isZero();
    }

    @Test
    void marketBuyReservesAtDailyUpperLimit() {
        // D101 base 2800 + limit width 500 = 3300.
        var plan = ReservationService.ReservationPlan.of(request("BUY", "MARKET", 200, null));

        assertThat(plan.reservedAmountJpy()).isEqualByComparingTo("660000");
    }

    @Test
    void sellReservesSharesNotCash() {
        var plan = ReservationService.ReservationPlan.of(request("SELL", "MARKET", 500, null));

        assertThat(plan.reservedAmountJpy()).isEqualByComparingTo("0");
        assertThat(plan.reservedQuantity()).isEqualTo(500);
        assertThat(plan.toReservation().status()).isEqualTo(Status.RESERVED);
    }

    @Test
    void invalidReservationRequestsAreRejected() {
        assertThatThrownBy(() -> ReservationService.ReservationPlan.of(request("HOLD", "LIMIT", 100, BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationService.ReservationPlan.of(request("BUY", "LIMIT", 100, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationService.ReservationPlan.of(request("BUY", "MARKET", 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialBuyFillsReleaseProportionallyAndSumExactlyToTheReservation() {
        // A reservation that does not divide evenly: 1,000,001 yen over 300 shares in fills of 100.
        var reservation = buyReservation(300, "1000001");
        BigDecimal released = Jpy.of(0);

        var first = BuyingPowerRules.fill(Side.BUY, reservation, 100, 200, Jpy.of(280_000));
        assertThat(first.releasedAmountJpy()).isEqualByComparingTo("333333");
        assertThat(first.reservationStatus()).isEqualTo(Status.RESERVED);
        reservation = applied(reservation, first);
        released = released.add(first.releasedAmountJpy());

        var second = BuyingPowerRules.fill(Side.BUY, reservation, 100, 100, Jpy.of(280_000));
        assertThat(second.releasedAmountJpy()).isEqualByComparingTo("333333");
        reservation = applied(reservation, second);
        released = released.add(second.releasedAmountJpy());

        var last = BuyingPowerRules.fill(Side.BUY, reservation, 100, 0, Jpy.of(280_000));
        assertThat(last.releasedAmountJpy()).isEqualByComparingTo("333335");
        assertThat(last.reservationStatus()).isEqualTo(Status.CONSUMED);
        released = released.add(last.releasedAmountJpy());

        assertThat(released).isEqualByComparingTo("1000001");
    }

    @Test
    void buyFillDebitsExecutionAmountAndAddsSellableShares() {
        var delta = BuyingPowerRules.fill(Side.BUY, buyReservation(200, "562000"), 200, 0, Jpy.of(560_000));

        assertThat(delta.cashDeltaJpy()).isEqualByComparingTo("-560000");
        assertThat(delta.reservedDeltaJpy()).isEqualByComparingTo("-562000");
        assertThat(delta.sellableQuantityDelta()).isEqualTo(200);
        assertThat(delta.sellableReservedDelta()).isZero();
    }

    @Test
    void sellFillCreditsProceedsImmediatelyAndRemovesShares() {
        var reservation = sellReservation(300);

        var partial = BuyingPowerRules.fill(Side.SELL, reservation, 100, 200, Jpy.of(1_250_000));
        assertThat(partial.cashDeltaJpy()).isEqualByComparingTo("1250000");
        assertThat(partial.reservedDeltaJpy()).isEqualByComparingTo("0");
        assertThat(partial.sellableQuantityDelta()).isEqualTo(-100);
        assertThat(partial.sellableReservedDelta()).isEqualTo(-100);
        assertThat(partial.releasedQuantity()).isEqualTo(100);

        var last = BuyingPowerRules.fill(Side.SELL, applied(reservation, partial), 200, 0, Jpy.of(2_500_000));
        assertThat(last.sellableReservedDelta()).isEqualTo(-200);
        assertThat(last.reservationStatus()).isEqualTo(Status.CONSUMED);
    }

    @Test
    void expiryReleasesTheRemainingCashAfterPartialFill() {
        var reservation = buyReservation(300, "990000");
        var fill = BuyingPowerRules.fill(Side.BUY, reservation, 100, 200, Jpy.of(320_000));
        reservation = applied(reservation, fill);

        var expiry = BuyingPowerRules.releaseRemaining(reservation);

        assertThat(fill.releasedAmountJpy()).isEqualByComparingTo("330000");
        assertThat(expiry.releasedAmountJpy()).isEqualByComparingTo("660000");
        assertThat(expiry.reservedDeltaJpy()).isEqualByComparingTo("-660000");
        assertThat(expiry.cashDeltaJpy()).isEqualByComparingTo("0");
        assertThat(expiry.reservationStatus()).isEqualTo(Status.RELEASED);
    }

    @Test
    void expiryReleasesTheRemainingSharesOfASellOrder() {
        var expiry = BuyingPowerRules.releaseRemaining(sellReservation(1_000));

        assertThat(expiry.sellableReservedDelta()).isEqualTo(-1_000);
        assertThat(expiry.sellableQuantityDelta()).isZero();
        assertThat(expiry.reservationStatus()).isEqualTo(Status.RELEASED);
    }

    @Test
    void closedReservationIsNotReleasedTwice() {
        var released = applied(buyReservation(100, "280000"),
                BuyingPowerRules.releaseRemaining(buyReservation(100, "280000")));

        var again = BuyingPowerRules.releaseRemaining(released);
        var lateFill = BuyingPowerRules.fill(Side.BUY, released, 100, 0, Jpy.of(280_000));

        assertThat(again.reservedDeltaJpy()).isEqualByComparingTo("0");
        assertThat(again.reservationStatus()).isEqualTo(Status.RELEASED);
        assertThat(lateFill.reservedDeltaJpy()).isEqualByComparingTo("0");
        assertThat(lateFill.cashDeltaJpy()).isEqualByComparingTo("-280000");
        assertThat(lateFill.reservationStatus()).isEqualTo(Status.RELEASED);
    }

    @Test
    void fillWithoutReservationStillMovesCash() {
        var delta = BuyingPowerRules.fill(Side.SELL, null, 100, 0, Jpy.of(980_000));

        assertThat(delta.cashDeltaJpy()).isEqualByComparingTo("980000");
        assertThat(delta.sellableReservedDelta()).isZero();
        assertThat(delta.reservationStatus()).isNull();
    }

    static ReservationState buyReservation(long quantity, String amount) {
        return new ReservationState("o-1", "A00001", "D101", Side.BUY, "LIMIT", quantity,
                new BigDecimal(amount), Jpy.of(0), 0, 0, Status.RESERVED);
    }

    static ReservationState sellReservation(long quantity) {
        return new ReservationState("o-2", "A00001", "D105", Side.SELL, "MARKET", quantity,
                Jpy.of(0), Jpy.of(0), quantity, 0, Status.RESERVED);
    }

    private static ReservationState applied(ReservationState state, LedgerDelta delta) {
        return new ReservationState(state.orderId(), state.accountId(), state.symbol(), state.side(),
                state.orderType(), state.quantity(), state.reservedAmountJpy(),
                state.releasedAmountJpy().add(delta.releasedAmountJpy()), state.reservedQuantity(),
                state.releasedQuantity() + delta.releasedQuantity(), delta.reservationStatus());
    }

    private static BuyingPowerReservation.Request request(String side, String orderType, long quantity, BigDecimal limit) {
        return new BuyingPowerReservation.Request("A00001", "o-1", "D101", side, orderType, quantity, limit);
    }
}
