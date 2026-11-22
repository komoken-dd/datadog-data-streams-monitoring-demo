package io.github.komokendd.dsm.brokerage.pushgateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeliveredNotificationsTest {
    @Test
    void recognisesRepeatedNotificationIds() {
        var delivered = new DeliveredNotifications(10);

        assertThat(delivered.markDelivered("n-1")).isTrue();
        assertThat(delivered.markDelivered("n-1")).isFalse();
        assertThat(delivered.markDelivered("n-2")).isTrue();
    }

    @Test
    void evictsTheLeastRecentlySeenIdOnceTheWindowIsFull() {
        var delivered = new DeliveredNotifications(2);
        delivered.markDelivered("n-1");
        delivered.markDelivered("n-2");
        delivered.markDelivered("n-1");

        delivered.markDelivered("n-3");

        assertThat(delivered.size()).isEqualTo(2);
        assertThat(delivered.markDelivered("n-1")).isFalse();
        assertThat(delivered.markDelivered("n-2")).isTrue();
    }
}
