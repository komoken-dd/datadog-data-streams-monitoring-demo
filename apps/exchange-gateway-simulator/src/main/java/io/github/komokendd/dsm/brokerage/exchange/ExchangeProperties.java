package io.github.komokendd.dsm.brokerage.exchange;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.exchange")
public record ExchangeProperties(
        long seed,
        long matchIntervalMs,
        int matchBatchSize,
        long maxWorkingAgeSeconds,
        double fullFillProbability,
        double randomWalkProbability,
        long sellPressureSharesPerTick,
        long completedRetentionMinutes) {
    private static final long DEFAULT_SEED = 20261122L;

    public ExchangeProperties {
        if (seed == 0) {
            seed = DEFAULT_SEED;
        }
        if (matchIntervalMs <= 0) {
            matchIntervalMs = 250;
        }
        if (matchBatchSize <= 0) {
            matchBatchSize = 5_000;
        }
        if (maxWorkingAgeSeconds <= 0) {
            maxWorkingAgeSeconds = 600;
        }
        if (fullFillProbability <= 0 || fullFillProbability > 1) {
            fullFillProbability = 0.6;
        }
        if (randomWalkProbability < 0 || randomWalkProbability > 1) {
            randomWalkProbability = 0.15;
        }
        if (sellPressureSharesPerTick <= 0) {
            sellPressureSharesPerTick = 1_500;
        }
        if (completedRetentionMinutes <= 0) {
            completedRetentionMinutes = 60;
        }
    }

    MatchingSettings matchingSettings() {
        return new MatchingSettings(
                fullFillProbability,
                randomWalkProbability,
                sellPressureSharesPerTick,
                MatchingSettings.DEFAULTS.maxSellOffTicksPerStep(),
                MatchingSettings.DEFAULTS.maxOpeningTicksFromBase(),
                Duration.ofSeconds(maxWorkingAgeSeconds));
    }
}
