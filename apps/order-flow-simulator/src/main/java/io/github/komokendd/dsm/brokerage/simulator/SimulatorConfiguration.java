package io.github.komokendd.dsm.brokerage.simulator;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
class SimulatorConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrderFlowGenerator orderFlowGenerator(SimulatorProperties properties) {
        return new OrderFlowGenerator(properties.trafficSeed(), properties.switchTradeRatio());
    }

    @Bean
    OrderSubmitter orderSubmitter(RestClient.Builder builder, SimulatorProperties properties) {
        return new OrderSubmitter(client(builder, properties.orderServiceUrl()));
    }

    @Bean
    ExchangeGatewayClient exchangeGatewayClient(RestClient.Builder builder, SimulatorProperties properties) {
        return new ExchangeGatewayClient(client(builder, properties.exchangeGatewayUrl()));
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
