package io.github.komokendd.dsm.brokerage.order;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class OrderConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BuyingPowerClient buyingPowerClient(
            RestClient.Builder builder,
            @Value("${demo.buying-power.url}") String baseUrl,
            @Value("${demo.buying-power.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${demo.buying-power.read-timeout:2s}") Duration readTimeout) {
        // Short timeouts: a slow 余力 check must fail the order fast rather than queue requests.
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new HttpBuyingPowerClient(builder.baseUrl(baseUrl).requestFactory(requestFactory).build());
    }

    @Bean
    OrderIntakeService orderIntakeService(
            BuyingPowerClient buyingPowerClient, MeterRegistry metrics, Clock clock,
            OrderSubmissionStore submissions, io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox outbox,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        return new OrderIntakeService(buyingPowerClient, metrics, clock, submissions, outbox, transactions);
    }
}
