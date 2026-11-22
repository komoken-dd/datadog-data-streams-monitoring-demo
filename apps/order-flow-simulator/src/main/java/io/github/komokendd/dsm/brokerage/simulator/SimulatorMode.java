package io.github.komokendd.dsm.brokerage.simulator;

public enum SimulatorMode {
    STOPPED(-1),
    BASELINE(0),
    OPENING_RUSH(1),
    SELL_OFF(2);

    private final int gaugeValue;

    SimulatorMode(int gaugeValue) {
        this.gaugeValue = gaugeValue;
    }

    public int gaugeValue() {
        return gaugeValue;
    }
}
