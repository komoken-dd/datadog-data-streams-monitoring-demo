package io.github.komokendd.dsm.brokerage.pushgateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PushGatewayServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    private SimpleMeterRegistry registry;
    private List<Duration> sleeps;
    private PushGatewayService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sleeps = new ArrayList<>();
        service = new PushGatewayService(
                new DeliveredNotifications(100),
                registry,
                Clock.fixed(NOW, ZoneOffset.UTC),
                sleeps::add,
                Duration.ofMillis(800));
    }

    @Test
    void deliversOnceAndSuppressesRedeliveryOfTheSameNotification() throws Exception {
        assertThat(service.push(request("n-1"))).isEqualTo(PushGatewayService.Outcome.DELIVERED);
        assertThat(service.push(request("n-1"))).isEqualTo(PushGatewayService.Outcome.DUPLICATE);
        assertThat(service.push(request("n-2"))).isEqualTo(PushGatewayService.Outcome.DELIVERED);

        var status = service.status();
        assertThat(status.mode()).isEqualTo(PushGatewayService.Mode.NORMAL);
        assertThat(status.delivered()).isEqualTo(2);
        assertThat(status.duplicates()).isEqualTo(1);
        assertThat(status.rejected()).isZero();
        assertThat(registry.get(MetricNames.PUSH_REQUESTS).tag("result", "delivered").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(MetricNames.PUSH_REQUESTS).tag("result", "duplicate").counter().count())
                .isEqualTo(1.0);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void outageAnswersSlowlyAndDoesNotRecordTheNotificationAsDelivered() throws Exception {
        service.outage();

        assertThat(service.push(request("n-1"))).isEqualTo(PushGatewayService.Outcome.UNAVAILABLE);

        assertThat(sleeps).containsExactly(Duration.ofMillis(800));
        assertThat(service.status().rejected()).isEqualTo(1);
        assertThat(registry.get(MetricNames.PUSH_REQUESTS).tag("result", "unavailable").counter().count())
                .isEqualTo(1.0);

        var recovered = service.recover();
        assertThat(recovered.mode()).isEqualTo(PushGatewayService.Mode.NORMAL);
        assertThat(recovered.lastActionAt()).isEqualTo(NOW);
        assertThat(service.push(request("n-1"))).isEqualTo(PushGatewayService.Outcome.DELIVERED);
    }

    @Test
    void controllerMapsOutcomesToTheHttpContract() throws Exception {
        var controller = new PushController(service);
        var body = new PushController.PushBody("n-1", "A0001", "order-1", "EXECUTION", "D105 SELL 300 @ 9,500 executed");

        var first = controller.push(body);
        var second = controller.push(body);
        service.outage();
        var rejected = controller.push(new PushController.PushBody("n-2", "A0001", "order-1", "EXPIRY", "x"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isEqualTo(new PushController.PushAccepted("n-1", false));
        assertThat(second.getBody()).isEqualTo(new PushController.PushAccepted("n-1", true));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static PushGatewayService.PushRequest request(String notificationId) {
        return new PushGatewayService.PushRequest(
                notificationId, "A0001", "order-1", "EXECUTION", "D105 SELL 300 @ 9,500 executed");
    }
}
