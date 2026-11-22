package io.github.komokendd.dsm.brokerage.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.Topics;
import org.junit.jupiter.api.Test;

class FailureRouterTest {
    private final FailureRouter router = new FailureRouter();

    @Test
    void routesAcrossTwoRetriesThenDeadLetter() {
        assertThat(router.route(0)).isEqualTo(FailureRoute.RETRY_5_SECONDS);
        assertThat(router.route(1)).isEqualTo(FailureRoute.RETRY_30_SECONDS);
        assertThat(router.route(2)).isEqualTo(FailureRoute.DEAD_LETTER);
        assertThat(router.route(7)).isEqualTo(FailureRoute.DEAD_LETTER);
    }

    @Test
    void mapsRoutesToTheContractTopics() {
        assertThat(FailureRoute.RETRY_5_SECONDS.topic()).isEqualTo(Topics.NOTIFICATIONS_RETRY_5S);
        assertThat(FailureRoute.RETRY_30_SECONDS.topic()).isEqualTo(Topics.NOTIFICATIONS_RETRY_30S);
        assertThat(FailureRoute.DEAD_LETTER.topic()).isEqualTo(Topics.NOTIFICATIONS_DLT);
    }
}
