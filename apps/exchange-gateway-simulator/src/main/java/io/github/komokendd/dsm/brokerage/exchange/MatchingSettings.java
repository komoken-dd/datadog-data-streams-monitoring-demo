package io.github.komokendd.dsm.brokerage.exchange;

import java.time.Duration;

/**
 * Tuning knobs of the matching engine.
 *
 * @param fullFillProbability       chance that a multi-lot order fills completely in a continuous-session tick
 * @param randomWalkProbability     chance per tick that a quiet symbol moves by one tick
 * @param sellPressureSharesPerTick pending sell shares that push a sell-off price down by one tick per matching tick
 * @param maxSellOffTicksPerStep    cap of the per-tick sell-off price step
 * @param maxOpeningTicksFromBase   opening auction price is base price +/- up to this many ticks
 * @param maxWorkingAge             safety expiry for working orders (reason SESSION_CLOSE)
 */
public record MatchingSettings(
        double fullFillProbability,
        double randomWalkProbability,
        long sellPressureSharesPerTick,
        int maxSellOffTicksPerStep,
        int maxOpeningTicksFromBase,
        Duration maxWorkingAge) {
    public static final MatchingSettings DEFAULTS =
            new MatchingSettings(0.6, 0.15, 4_000, 2, 3, Duration.ofMinutes(10));
}
