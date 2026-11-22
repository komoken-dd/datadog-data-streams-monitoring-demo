package io.github.komokendd.dsm.brokerage.pushgateway;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in for an external mobile push provider with a demo-controlled outage switch. */
final class PushGatewayService {
    enum Mode {
        NORMAL,
        OUTAGE
    }

    enum Outcome {
        DELIVERED("delivered"),
        DUPLICATE("duplicate"),
        UNAVAILABLE("unavailable");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    record PushRequest(
            String notificationId,
            String accountId,
            String orderId,
            String notificationType,
            String message) {
    }

    record Status(Mode mode, Instant lastActionAt, long delivered, long duplicates, long rejected) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PushGatewayService.class);

    private final DeliveredNotifications deliveredNotifications;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration outageResponseDelay;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
    private final AtomicReference<Instant> lastActionAt;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    PushGatewayService(
            DeliveredNotifications deliveredNotifications,
            MeterRegistry metrics,
            Clock clock,
            Sleeper sleeper,
            Duration outageResponseDelay) {
        this.deliveredNotifications = deliveredNotifications;
        this.metrics = metrics;
        this.clock = clock;
        this.sleeper = sleeper;
        this.outageResponseDelay = outageResponseDelay;
        this.lastActionAt = new AtomicReference<>(clock.instant());
    }

    Outcome push(PushRequest request) throws InterruptedException {
        Outcome outcome;
        if (mode.get() == Mode.OUTAGE) {
            // A degraded provider answers slowly before refusing, which is what makes outages costly.
            sleeper.sleep(outageResponseDelay);
            rejected.incrementAndGet();
            outcome = Outcome.UNAVAILABLE;
        } else if (deliveredNotifications.markDelivered(request.notificationId())) {
            delivered.incrementAndGet();
            outcome = Outcome.DELIVERED;
        } else {
            duplicates.incrementAndGet();
            outcome = Outcome.DUPLICATE;
        }
        Counter.builder(MetricNames.PUSH_REQUESTS)
                .tag("result", outcome.tag)
                .tag("notification_type", bounded(request.notificationType()))
                .register(metrics)
                .increment();
        try (var ignored = EventLogContext.open(
                        request.accountId(), request.orderId(), null, "PUSH_" + outcome.name(), null, null)
                .attribute("notification_id", request.notificationId())
                .attribute("notification_type", request.notificationType())) {
            switch (outcome) {
                case DELIVERED -> LOG.info("Push notification sent to device: {}", request.message());
                case DUPLICATE -> LOG.info("Duplicate push suppressed by notification_id");
                case UNAVAILABLE -> LOG.warn("Push provider outage, request rejected with 503");
            }
        }
        return outcome;
    }

    Status outage() {
        mode.set(Mode.OUTAGE);
        lastActionAt.set(clock.instant());
        LOG.warn("Push provider outage started");
        return status();
    }

    Status recover() {
        mode.set(Mode.NORMAL);
        lastActionAt.set(clock.instant());
        LOG.info("Push provider recovered");
        return status();
    }

    Status status() {
        return new Status(mode.get(), lastActionAt.get(), delivered.get(), duplicates.get(), rejected.get());
    }

    private static String bounded(String notificationType) {
        return "EXECUTION".equals(notificationType) || "EXPIRY".equals(notificationType)
                ? notificationType
                : "OTHER";
    }
}
