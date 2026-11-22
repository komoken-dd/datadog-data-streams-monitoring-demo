package io.github.komokendd.dsm.brokerage.tradereport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Market conventions for trade reports.
 *
 * @param settlementCycleDays JPX business days from trade date to settlement (T+2 since 2019)
 * @param holidays            market holidays; empty means the built-in JPX calendar
 * @param commissionJpy       commission per fill; zero mirrors zero-commission online brokers
 */
@ConfigurationProperties("demo.market")
record MarketProperties(
        @DefaultValue("2") int settlementCycleDays,
        @DefaultValue @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> holidays,
        @DefaultValue("0") BigDecimal commissionJpy) {
    MarketProperties {
        if (settlementCycleDays < 0) {
            throw new IllegalArgumentException("demo.market.settlement-cycle-days must not be negative");
        }
        if (commissionJpy.signum() < 0) {
            throw new IllegalArgumentException("demo.market.commission-jpy must not be negative");
        }
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
    }
}
