package io.github.komokendd.dsm.brokerage.order;

import java.math.BigDecimal;
import java.time.Instant;

sealed interface OrderResult {
    int httpStatus();

    Object body();

    record Accepted(AcceptedBody body) implements OrderResult {
        @Override
        public int httpStatus() {
            return 202;
        }
    }

    record Pending(PendingBody body) implements OrderResult {
        @Override
        public int httpStatus() { return 503; }
    }

    record PendingBody(String orderId, String eventId, String code, String status, String statusUrl, String message) { }

    record Failed(int httpStatus, String code, String message) implements OrderResult {
        @Override
        public Object body() {
            return new ErrorBody(code, message);
        }
    }

    record AcceptedBody(String orderId, String eventId, String status, BigDecimal reservedAmountJpy, Instant acceptedAt) {
    }

    record ErrorBody(String code, String message) {
    }
}
