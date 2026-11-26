package io.github.komokendd.dsm.brokerage.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Listener state participates in readiness only, never in dependency-driven liveness. */
@AutoConfiguration
@ConditionalOnProperty(name = "demo.health.kafka-listeners", havingValue = "true")
public class KafkaListenerHealthConfiguration {
    @Bean
    HealthIndicator brokerageKafka(ObjectProvider<KafkaListenerEndpointRegistry> registries) {
        return () -> {
            var registry = registries.getIfAvailable();
            if (registry == null || registry.getAllListenerContainers().isEmpty()) {
                return Health.down().withDetail("reason", "listeners_not_initialized").build();
            }
            for (var container : registry.getAllListenerContainers()) {
                if (!container.isRunning() || container.isContainerPaused()
                        || container.getAssignedPartitions() == null || container.getAssignedPartitions().isEmpty()) {
                    return Health.down().withDetail("reason", "listener_not_processing")
                            .withDetail("listener", container.getListenerId()).build();
                }
            }
            return Health.up().build();
        };
    }
}
