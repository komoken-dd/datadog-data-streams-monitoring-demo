package io.github.komokendd.dsm.brokerage.tradereport;

import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.JpxBusinessCalendar;
import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
@EnableConfigurationProperties(MarketProperties.class)
class TradeReportConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ExecutionReportEvent> executionListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, ExecutionReportEvent.class);
    }

    @Bean
    IdempotentEventStore tradeReportEventStore(JdbcTemplate jdbc) {
        return new IdempotentEventStore(jdbc, "trade_report_service");
    }

    @Bean
    JpxBusinessCalendar jpxBusinessCalendar(MarketProperties market) {
        return calendar(market);
    }

    @Bean
    TradeReportCalculator tradeReportCalculator(JpxBusinessCalendar calendar, MarketProperties market) {
        return new TradeReportCalculator(calendar, market.settlementCycleDays(), market.commissionJpy());
    }

    static JpxBusinessCalendar calendar(MarketProperties market) {
        return market.holidays().isEmpty()
                ? JpxBusinessCalendar.defaultCalendar()
                : new JpxBusinessCalendar(market.holidays());
    }
}
