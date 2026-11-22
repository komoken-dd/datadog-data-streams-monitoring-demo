package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

class SimulatorControllerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
    private final SimulatorProperties properties = SimulatorProperties.defaults();
    private final SimulatorControl control = new SimulatorControl(new SimpleMeterRegistry(), properties, clock);
    private final ExchangeGatewayClient exchange = mock(ExchangeGatewayClient.class);
    private final SimulatorController controller = new SimulatorController(control, exchange, properties);

    @Test
    void openingRushStartsThePreOpenOnTheExchangeFirst() {
        var response = controller.openingRush();

        verify(exchange).startOpeningAuction(60);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.OPENING_RUSH);
    }

    @Test
    void sellOffStartsTheExchangeSellOffFirst() {
        var response = controller.sellOff();

        verify(exchange).startSellOff(120);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.SELL_OFF);
    }

    @Test
    void exchangeFailureReturnsBadGatewayAndKeepsTheMode() {
        doThrow(new ResourceAccessException("connection refused")).when(exchange).startOpeningAuction(60);
        doThrow(new ResourceAccessException("connection refused")).when(exchange).startSellOff(120);

        var rush = controller.openingRush();
        var sellOff = controller.sellOff();

        assertThat(rush.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(sellOff.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(rush.getBody()).isInstanceOfSatisfying(SimulatorController.ScenarioError.class,
                error -> assertThat(error.code()).isEqualTo(SimulatorController.EXCHANGE_UNAVAILABLE));
        assertThat(control.current().mode()).isEqualTo(SimulatorMode.BASELINE);
    }
}
