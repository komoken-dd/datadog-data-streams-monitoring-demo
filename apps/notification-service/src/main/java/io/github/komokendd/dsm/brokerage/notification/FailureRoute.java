package io.github.komokendd.dsm.brokerage.notification;

import io.github.komokendd.dsm.brokerage.common.Topics;

enum FailureRoute {
    RETRY_5_SECONDS(Topics.NOTIFICATIONS_RETRY_5S),
    RETRY_30_SECONDS(Topics.NOTIFICATIONS_RETRY_30S),
    DEAD_LETTER(Topics.NOTIFICATIONS_DLT);

    private final String topic;

    FailureRoute(String topic) {
        this.topic = topic;
    }

    String topic() {
        return topic;
    }
}
