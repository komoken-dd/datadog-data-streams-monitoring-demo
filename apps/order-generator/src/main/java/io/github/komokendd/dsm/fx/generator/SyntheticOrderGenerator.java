package io.github.komokendd.dsm.fx.generator;

import java.math.BigDecimal;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
final class SyntheticOrderGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(SyntheticOrderGenerator.class);
    private static final BigDecimal FAILURE_SCENARIO_QUANTITY = new BigDecimal("100000.00");

    private final RestClient client;
    private final GeneratorControl control;
    private final GeneratorProperties properties;
    private final OrderTrafficProfile trafficProfile;
    private volatile long nextEmissionNanos;

    SyntheticOrderGenerator(RestClient client, GeneratorControl control, GeneratorProperties properties) {
        this.client = client;
        this.control = control;
        this.properties = properties;
        this.trafficProfile = new OrderTrafficProfile(properties.trafficSeed());
    }

    @Scheduled(fixedRate = 25)
    void tick() {
        int rate = control.requestsPerSecond();
        if (rate == 0) {
            nextEmissionNanos = 0;
            return;
        }
        long now = System.nanoTime();
        if (now < nextEmissionNanos) {
            return;
        }
        nextEmissionNanos = now + Duration.ofSeconds(1).toNanos() / rate;
        OrderTrafficProfile.OrderSample sample = trafficProfile.next();
        String pair = sample.currencyPair();
        String scenarioId = control.claimScenarioForNextOrder();
        BigDecimal quantity = scenarioId == null
                ? sample.quantity()
                : FAILURE_SCENARIO_QUANTITY;
        BigDecimal price = switch (pair) {
            case "USD/JPY" -> new BigDecimal("148.250000");
            case "EUR/USD" -> new BigDecimal("1.085000");
            case "GBP/USD" -> new BigDecimal("1.271000");
            default -> throw new IllegalStateException(pair);
        };
        var request = new CreateOrderRequest(pair, sample.side(), quantity, price, scenarioId);
        try {
            client.post().uri("/orders").body(request).retrieve().toBodilessEntity();
        } catch (RuntimeException exception) {
            LOG.warn("Synthetic order submission failed: {}", exception.getMessage());
        }
    }

    record CreateOrderRequest(
            String currencyPair,
            String side,
            BigDecimal quantity,
            BigDecimal limitPrice,
            String demoScenarioId) {
    }
}
