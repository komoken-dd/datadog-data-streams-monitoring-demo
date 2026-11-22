package io.github.komokendd.dsm.brokerage.simulator;

/** Converts an orders-per-second rate into a whole number of orders due at each scheduler tick. */
final class EmissionLane {
    private double credit;
    private long lastNanos = -1;

    synchronized int due(long nowNanos, int perSecond) {
        if (perSecond <= 0) {
            credit = 0;
            lastNanos = -1;
            return 0;
        }
        if (lastNanos < 0) {
            lastNanos = nowNanos;
            credit = 1;
        } else {
            credit += perSecond * (nowNanos - lastNanos) / 1_000_000_000.0;
            lastNanos = nowNanos;
        }
        // Do not burst after a stall (GC, slow tick): at most one second's worth is carried.
        credit = Math.min(credit, perSecond);
        int due = (int) Math.floor(credit);
        credit -= due;
        return due;
    }
}
