package io.github.komokendd.dsm.brokerage.tradereport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MarketPropertiesTest {
    @Test
    void defaultsToTPlusTwoZeroCommissionAndBuiltInCalendar() {
        MarketProperties market = bind(Map.of());

        assertThat(market.settlementCycleDays()).isEqualTo(2);
        assertThat(market.commissionJpy()).isZero();
        assertThat(market.holidays()).isEmpty();
        // Built-in calendar knows 2026-11-23 (Labor Thanksgiving Day).
        assertThat(TradeReportConfiguration.calendar(market).isBusinessDay(LocalDate.of(2026, 11, 23))).isFalse();
    }

    @Test
    void bindsCommaSeparatedHolidaysFromEnvironmentStyleValue() {
        MarketProperties market = bind(Map.of(
                "demo.market.settlement-cycle-days", "3",
                "demo.market.commission-jpy", "55",
                "demo.market.holidays", "2026-11-25,2027-01-11"));

        assertThat(market.settlementCycleDays()).isEqualTo(3);
        assertThat(market.commissionJpy()).isEqualByComparingTo("55");
        assertThat(market.holidays()).containsExactly(LocalDate.of(2026, 11, 25), LocalDate.of(2027, 1, 11));
    }

    @Test
    void bindsIndexedHolidaysFromYamlList() {
        MarketProperties market = bind(Map.of(
                "demo.market.holidays[0]", "2026-11-25",
                "demo.market.holidays[1]", "2026-11-26"));

        assertThat(market.holidays()).containsExactly(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26));
        var calendar = TradeReportConfiguration.calendar(market);
        // A configured list replaces the built-in one entirely.
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 11, 23))).isTrue();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 11, 25))).isFalse();
    }

    private static MarketProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("demo.market", Bindable.of(MarketProperties.class));
    }
}
