package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JpxBusinessCalendarTest {
    private final JpxBusinessCalendar calendar = JpxBusinessCalendar.defaultCalendar();

    @Test
    void settlesTwoBusinessDaysLaterOnOrdinaryWeekdays() {
        assertThat(calendar.settlementDate(LocalDate.of(2026, 11, 17), 2)).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void skipsWeekends() {
        // Thursday -> Monday
        assertThat(calendar.settlementDate(LocalDate.of(2026, 11, 26), 2)).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void skipsNationalHolidays() {
        // Friday 2026-11-20 -> Monday 11-23 is Labor Thanksgiving Day -> Wednesday 11-25
        assertThat(calendar.settlementDate(LocalDate.of(2026, 11, 20), 2)).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void skipsGoldenWeek() {
        // Thursday 2026-04-30 -> Friday 05-01, then 05-04..05-06 are holidays -> Thursday 05-07
        assertThat(calendar.settlementDate(LocalDate.of(2026, 4, 30), 2)).isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    void skipsTheSilverWeekBridgeHoliday() {
        // Friday 2026-09-18 -> 09-21, 09-22, 09-23 are holidays -> Friday 09-25
        assertThat(calendar.settlementDate(LocalDate.of(2026, 9, 18), 2)).isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void skipsTheYearEndMarketHolidays() {
        // Wednesday 2026-12-30 -> 12-31 to 01-03 closed -> Monday 2027-01-04, Tuesday 01-05
        assertThat(calendar.settlementDate(LocalDate.of(2026, 12, 30), 2)).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(calendar.isBusinessDay(LocalDate.of(2028, 12, 31))).isFalse();
    }

    @Test
    void booksNonBusinessDayExecutionsToTheNextBusinessDayInTokyo() {
        // 2026-11-22T00:30Z is Sunday 09:30 JST -> next business day is Tuesday 11-24 (11-23 is a holiday)
        assertThat(calendar.tradeDate(Instant.parse("2026-11-22T00:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 24));
        // 2026-11-18T23:30Z is already Thursday 11-19 in Tokyo
        assertThat(calendar.tradeDate(Instant.parse("2026-11-18T23:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void acceptsAConfiguredHolidayList() {
        var custom = new JpxBusinessCalendar(List.of(LocalDate.of(2026, 11, 18)));
        assertThat(custom.settlementDate(LocalDate.of(2026, 11, 17), 2)).isEqualTo(LocalDate.of(2026, 11, 20));
    }
}
