package io.github.komokendd.dsm.brokerage.notification;

final class PushDeliveryException extends RuntimeException {
    static final String UNAVAILABLE = "PUSH_PROVIDER_UNAVAILABLE";
    static final String TIMEOUT = "PUSH_PROVIDER_TIMEOUT";

    private final String failureCategory;

    PushDeliveryException(String failureCategory, String message, Throwable cause) {
        super(message, cause);
        this.failureCategory = failureCategory;
    }

    String failureCategory() {
        return failureCategory;
    }
}
