package io.github.komokendd.dsm.brokerage.common;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * JPX business-day calendar: weekends, Japanese national holidays, and the
 * year-end/new-year market holidays (Dec 31 to Jan 3) are non-business days.
 *
 * <p>The holiday list is configuration. The built-in default covers 2026 and 2027;
 * outside that range only weekends and the year-end holidays are applied.
 */
public final class JpxBusinessCalendar {
    public static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    static final List<LocalDate> DEFAULT_HOLIDAYS = List.of(
            // 2026
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 2, 11),
            LocalDate.of(2026, 2, 23), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 29),
            LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 5),
            LocalDate.of(2026, 5, 6), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 11),
            LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 23),
            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 23),
            // 2027
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 11), LocalDate.of(2027, 2, 11),
            LocalDate.of(2027, 2, 23), LocalDate.of(2027, 3, 22), LocalDate.of(2027, 4, 29),
            LocalDate.of(2027, 5, 3), LocalDate.of(2027, 5, 4), LocalDate.of(2027, 5, 5),
            LocalDate.of(2027, 7, 19), LocalDate.of(2027, 8, 11), LocalDate.of(2027, 9, 20),
            LocalDate.of(2027, 9, 23), LocalDate.of(2027, 10, 11), LocalDate.of(2027, 11, 3),
            LocalDate.of(2027, 11, 23));
    private static final Set<MonthDay> YEAR_END_HOLIDAYS = Set.of(
            MonthDay.of(12, 31), MonthDay.of(1, 1), MonthDay.of(1, 2), MonthDay.of(1, 3));

    private final Set<LocalDate> holidays;

    public JpxBusinessCalendar(Collection<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public static JpxBusinessCalendar defaultCalendar() {
        return new JpxBusinessCalendar(DEFAULT_HOLIDAYS);
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY
                && day != DayOfWeek.SUNDAY
                && !YEAR_END_HOLIDAYS.contains(MonthDay.from(date))
                && !holidays.contains(date);
    }

    public LocalDate nextOrSameBusinessDay(LocalDate date) {
        LocalDate current = date;
        while (!isBusinessDay(current)) {
            current = current.plusDays(1);
        }
        return current;
    }

    public LocalDate plusBusinessDays(LocalDate date, int businessDays) {
        if (businessDays < 0) {
            throw new IllegalArgumentException("businessDays must not be negative");
        }
        LocalDate current = date;
        int remaining = businessDays;
        while (remaining > 0) {
            current = current.plusDays(1);
            if (isBusinessDay(current)) {
                remaining--;
            }
        }
        return current;
    }

    /**
     * Trade date of an execution. The demo trades continuously, so an execution on a
     * non-business day is booked to the next business day (a deliberate simplification).
     */
    public LocalDate tradeDate(Instant executedAt) {
        return nextOrSameBusinessDay(LocalDate.ofInstant(executedAt, TOKYO));
    }

    public LocalDate settlementDate(LocalDate tradeDate, int settlementCycleDays) {
        return plusBusinessDays(tradeDate, settlementCycleDays);
    }
}
