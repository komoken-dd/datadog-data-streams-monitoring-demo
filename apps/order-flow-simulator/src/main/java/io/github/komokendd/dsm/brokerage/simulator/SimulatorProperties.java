package io.github.komokendd.dsm.brokerage.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.simulator")
public record SimulatorProperties(
        String orderServiceUrl,
        String exchangeGatewayUrl,
        int baselinePerSecond,
        int openingRushPerSecond,
        long openingRushSeconds,
        int sellOffPerSecond,
        long sellOffSeconds,
        long trafficSeed,
        double switchTradeRatio,
        long switchTradeBuyDelayMs,
        int maxInFlight) {
    private static final long DEFAULT_TRAFFIC_SEED = 20261122L;

    public SimulatorProperties {
        if (orderServiceUrl == null || orderServiceUrl.isBlank()) {
            orderServiceUrl = "http://order-service:8080";
        }
        if (exchangeGatewayUrl == null || exchangeGatewayUrl.isBlank()) {
            exchangeGatewayUrl = "http://exchange-gateway-simulator:8080";
        }
        if (baselinePerSecond < 0) {
            baselinePerSecond = 3;
        }
        if (openingRushPerSecond <= 0) {
            openingRushPerSecond = 25;
        }
        if (openingRushSeconds <= 0) {
            openingRushSeconds = 60;
        }
        if (sellOffPerSecond <= 0) {
            sellOffPerSecond = 25;
        }
        if (sellOffSeconds <= 0) {
            sellOffSeconds = 120;
        }
        if (trafficSeed == 0) {
            trafficSeed = DEFAULT_TRAFFIC_SEED;
        }
        if (switchTradeRatio < 0 || switchTradeRatio > 1) {
            switchTradeRatio = 0.10;
        }
        if (switchTradeBuyDelayMs <= 0) {
            switchTradeBuyDelayMs = 3_000;
        }
        if (maxInFlight <= 0) {
            maxInFlight = 200;
        }
    }

    static SimulatorProperties defaults() {
        return new SimulatorProperties(null, null, 3, 25, 60, 25, 120, 0, 0.10, 3_000, 200);
    }
}
