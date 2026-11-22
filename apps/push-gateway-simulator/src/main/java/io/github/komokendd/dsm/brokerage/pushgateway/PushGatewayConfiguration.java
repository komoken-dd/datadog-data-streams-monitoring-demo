package io.github.komokendd.dsm.brokerage.pushgateway;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PushGatewayConfiguration {
    @Bean
    PushGatewayService pushGatewayService(
            MeterRegistry metrics,
            @Value("${demo.push.dedupe-capacity}") int dedupeCapacity,
            @Value("${demo.push.outage-response-delay}") Duration outageResponseDelay) {
        return new PushGatewayService(
                new DeliveredNotifications(dedupeCapacity),
                metrics,
                Clock.systemUTC(),
                Thread::sleep,
                outageResponseDelay);
    }
}
