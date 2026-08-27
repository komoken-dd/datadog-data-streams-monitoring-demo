package io.github.komokendd.dsm.fx.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureRouterTest {
    private final FailureRouter router = new FailureRouter();

    @Test
    void routesAcrossTwoRetriesThenDeadLetter() {
        assertThat(router.route(0)).isEqualTo(FailureRoute.RETRY_5_SECONDS);
        assertThat(router.route(1)).isEqualTo(FailureRoute.RETRY_30_SECONDS);
        assertThat(router.route(2)).isEqualTo(FailureRoute.DEAD_LETTER);
    }
}
