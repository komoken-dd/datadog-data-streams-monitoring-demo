package io.github.komokendd.dsm.brokerage.notification;

import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Short push texts, e.g. "D105 SELL 300 @ 9,500 executed". */
final class NotificationMessages {
    private NotificationMessages() {
    }

    static String format(NotificationRequest request) {
        String head = request.getSymbol() + " " + request.getSide() + " " + quantity(request.getQuantity());
        return switch (request.getNotificationType()) {
            case NotificationRequests.EXECUTION -> head + " @ " + price(request.getPrice()) + " executed";
            case NotificationRequests.EXPIRY -> head + " expired unfilled";
            default -> head + " " + request.getNotificationType().toLowerCase(Locale.ROOT);
        };
    }

    private static String quantity(long quantity) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).format(quantity);
    }

    private static String price(BigDecimal price) {
        if (price == null) {
            return "market";
        }
        return new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US)).format(price);
    }
}
