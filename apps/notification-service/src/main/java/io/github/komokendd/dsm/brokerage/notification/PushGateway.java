package io.github.komokendd.dsm.brokerage.notification;

/** External push provider. Implementations throw {@link PushDeliveryException} on retryable failures. */
interface PushGateway {
    PushResponse push(PushMessage message);

    record PushMessage(
            String notificationId,
            String accountId,
            String orderId,
            String notificationType,
            String message) {
    }

    record PushResponse(String notificationId, boolean duplicate) {
    }
}
