package io.github.komokendd.dsm.fx.common;

public final class DemoScenarios {
    public static final String PROCESSING_FAILURE_PREFIX = "processing-failure-";

    private DemoScenarios() {
    }

    public static boolean isProcessingFailure(String scenarioId) {
        return scenarioId != null && scenarioId.startsWith(PROCESSING_FAILURE_PREFIX);
    }
}
