package io.github.komokendd.dsm.brokerage.tradereport;

import io.github.komokendd.dsm.brokerage.common.JpxBusinessCalendar;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** Trade date, settlement date and settlement amount for one fill. */
final class TradeReportCalculator {
    private final JpxBusinessCalendar calendar;
    private final int settlementCycleDays;
    private final BigDecimal commissionJpy;

    TradeReportCalculator(JpxBusinessCalendar calendar, int settlementCycleDays, BigDecimal commissionJpy) {
        this.calendar = calendar;
        this.settlementCycleDays = settlementCycleDays;
        this.commissionJpy = commissionJpy.setScale(0, RoundingMode.HALF_UP);
    }

    Terms terms(Side side, BigDecimal executionAmountJpy, Instant executedAt) {
        LocalDate tradeDate = calendar.tradeDate(executedAt);
        LocalDate settlementDate = calendar.settlementDate(tradeDate, settlementCycleDays);
        BigDecimal amount = executionAmountJpy.setScale(0, RoundingMode.HALF_UP);
        // The buyer pays amount plus commission; the seller receives amount minus commission.
        BigDecimal settlement = side == Side.BUY ? amount.add(commissionJpy) : amount.subtract(commissionJpy);
        return new Terms(tradeDate, settlementDate, amount, commissionJpy, settlement);
    }

    record Terms(
            LocalDate tradeDate,
            LocalDate settlementDate,
            BigDecimal executionAmountJpy,
            BigDecimal commissionJpy,
            BigDecimal settlementAmountJpy) {
    }
}
