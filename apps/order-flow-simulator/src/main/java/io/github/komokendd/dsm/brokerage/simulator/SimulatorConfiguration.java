package io.github.komokendd.dsm.brokerage.simulator;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    ScenarioAccountClient scenarioAccountClient(RestClient.Builder builder,
            @Value("${BUYING_POWER_SERVICE_URL:http://buying-power-service:8080}") String buyingPowerUrl,
            @Value("${ORDER_STATE_SERVICE_URL:http://order-state-service:8080}") String stateUrl) {
        return new ScenarioAccountClient(client(builder, buyingPowerUrl), client(builder, stateUrl));
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
