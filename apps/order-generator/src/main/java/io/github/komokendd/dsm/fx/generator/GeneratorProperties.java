package io.github.komokendd.dsm.fx.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.generator")
public record GeneratorProperties(
        String orderServiceUrl,
        int baselinePerSecond,
        int surgePerSecond,
        long surgeDurationSeconds,
        long trafficSeed) {
    private static final long DEFAULT_TRAFFIC_SEED = 20260819L;

    public GeneratorProperties {
        if (orderServiceUrl == null || orderServiceUrl.isBlank()) {
            orderServiceUrl = "http://order-service:8080";
        }
        if (baselinePerSecond <= 0) {
            baselinePerSecond = 2;
        }
        if (surgePerSecond <= baselinePerSecond) {
            surgePerSecond = 20;
        }
        if (surgeDurationSeconds <= 0) {
            surgeDurationSeconds = 90;
        }
        if (trafficSeed == 0) {
            trafficSeed = DEFAULT_TRAFFIC_SEED;
        }
    }
}
