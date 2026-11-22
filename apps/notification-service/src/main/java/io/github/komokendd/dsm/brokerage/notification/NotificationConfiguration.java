package io.github.komokendd.dsm.brokerage.notification;

import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.web.client.RestClient;

@Configuration
class NotificationConfiguration {
    private static final int MAIN_LISTENER_CONCURRENCY = 3;

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ExecutionReportEvent> executionListenerFactory(
            KafkaProperties properties) {
        var factory = KafkaFactory.listenerFactory(properties, ExecutionReportEvent.class);
        factory.setConcurrency(MAIN_LISTENER_CONCURRENCY);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderExpiredEvent> expiryListenerFactory(
            KafkaProperties properties) {
        var factory = KafkaFactory.listenerFactory(properties, OrderExpiredEvent.class);
        factory.setConcurrency(MAIN_LISTENER_CONCURRENCY);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, NotificationRequest> notificationRequestListenerFactory(
            KafkaProperties properties) {
        var factory = KafkaFactory.listenerFactory(properties, NotificationRequest.class);
        factory.setConcurrency(MAIN_LISTENER_CONCURRENCY);
        return factory;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RetryWait retryWait(Clock clock) {
        return new RetryWait(clock, Thread::sleep);
    }

    @Bean
    PushGateway pushGateway(
            RestClient.Builder builder,
            @Value("${demo.notification.push-gateway-url}") String baseUrl,
            @Value("${demo.notification.connect-timeout}") Duration connectTimeout,
            @Value("${demo.notification.read-timeout}") Duration readTimeout) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return new PushGatewayClient(builder.baseUrl(baseUrl).requestFactory(requestFactory).build());
    }
}
