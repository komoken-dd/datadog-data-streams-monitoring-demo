package io.github.komokendd.dsm.brokerage.notification;

/** Two delayed retries, then the dead-letter topic. */
final class FailureRouter {
    FailureRoute route(int completedRetries) {
        return switch (completedRetries) {
            case 0 -> FailureRoute.RETRY_5_SECONDS;
            case 1 -> FailureRoute.RETRY_30_SECONDS;
            default -> FailureRoute.DEAD_LETTER;
        };
    }
}
