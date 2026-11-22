package io.github.komokendd.dsm.brokerage.tradereport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.JpxBusinessCalendar;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeReportCalculatorTest {
    private static final BigDecimal AMOUNT = new BigDecimal("1250000");
    private final TradeReportCalculator defaultTerms =
            new TradeReportCalculator(JpxBusinessCalendar.defaultCalendar(), 2, BigDecimal.ZERO);

    @Test
    void settlesTwoBusinessDaysAfterTradeDate() {
        var terms = defaultTerms.terms(Side.BUY, AMOUNT, jst(2026, 11, 17, 10, 0)); // Tuesday

        assertThat(terms.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(terms.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void skipsWeekendAndNationalHoliday() {
        // Friday 2026-11-20; Monday 11-23 is Labor Thanksgiving Day.
        var terms = defaultTerms.terms(Side.SELL, AMOUNT, jst(2026, 11, 20, 9, 0));

        assertThat(terms.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(terms.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void skipsWeekendOnly() {
        // Friday 2026-11-13 settles on Tuesday 11-17.
        assertThat(defaultTerms.terms(Side.BUY, AMOUNT, jst(2026, 11, 13, 14, 30)).settlementDate())
                .isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test
    void skipsYearEndMarketHolidays() {
        // Tuesday 2026-12-29 -> Wed 12-30 (T+1), market closed 12-31 to 1-3 -> Mon 2027-01-04 (T+2).
        assertThat(defaultTerms.terms(Side.BUY, AMOUNT, jst(2026, 12, 29, 10, 0)).settlementDate())
                .isEqualTo(LocalDate.of(2027, 1, 4));
        // Wednesday 2026-12-30 (大納会) -> Mon 01-04 (T+1) -> Tue 01-05 (T+2).
        assertThat(defaultTerms.terms(Side.BUY, AMOUNT, jst(2026, 12, 30, 15, 0)).settlementDate())
                .isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void tradeDateIsTheTokyoBusinessDate() {
        // 2026-11-18T16:30Z is 01:30 on Thursday 11-19 in Tokyo.
        var terms = defaultTerms.terms(Side.BUY, AMOUNT, Instant.parse("2026-11-18T16:30:00Z"));

        assertThat(terms.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(terms.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    @Test
    void executionOnNonBusinessDayBooksToNextBusinessDay() {
        // Saturday 2026-11-21 -> trade date Tuesday 11-24 (Monday is a holiday) -> settles Thursday 11-26.
        var terms = defaultTerms.terms(Side.BUY, AMOUNT, jst(2026, 11, 21, 11, 0));

        assertThat(terms.tradeDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(terms.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 26));
    }

    @Test
    void usesConfiguredHolidaysAndCycle() {
        var market = new MarketProperties(3, List.of(LocalDate.of(2026, 11, 25)), BigDecimal.ZERO);
        var calculator = new TradeReportCalculator(
                TradeReportConfiguration.calendar(market), market.settlementCycleDays(), market.commissionJpy());

        // Friday 11-20: Mon 23 (not a configured holiday) T+1, Tue 24 T+2, Wed 25 holiday, Thu 26 T+3.
        assertThat(calculator.terms(Side.BUY, AMOUNT, jst(2026, 11, 20, 9, 0)).settlementDate())
                .isEqualTo(LocalDate.of(2026, 11, 26));
    }

    @Test
    void zeroCommissionSettlesAtExecutionAmount() {
        var terms = defaultTerms.terms(Side.BUY, AMOUNT, jst(2026, 11, 17, 10, 0));

        assertThat(terms.commissionJpy()).isEqualByComparingTo("0");
        assertThat(terms.settlementAmountJpy()).isEqualByComparingTo(AMOUNT);
        assertThat(terms.settlementAmountJpy().scale()).isZero();
    }

    @Test
    void commissionIsAddedForBuyAndDeductedForSell() {
        var calculator = new TradeReportCalculator(JpxBusinessCalendar.defaultCalendar(), 2, new BigDecimal("99"));
        Instant at = jst(2026, 11, 17, 10, 0);

        assertThat(calculator.terms(Side.BUY, AMOUNT, at).settlementAmountJpy()).isEqualByComparingTo("1250099");
        assertThat(calculator.terms(Side.SELL, AMOUNT, at).settlementAmountJpy()).isEqualByComparingTo("1249901");
    }

    private static Instant jst(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(JpxBusinessCalendar.TOKYO).toInstant();
    }
}
