package io.github.komokendd.dsm.brokerage.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/internal/demo")
class SimulatorController {
    static final String EXCHANGE_UNAVAILABLE = "EXCHANGE_UNAVAILABLE";
    private static final Logger LOG = LoggerFactory.getLogger(SimulatorController.class);

    private final SimulatorControl control;
    private final ExchangeGatewayClient exchange;
    private final SimulatorProperties properties;

    SimulatorController(SimulatorControl control, ExchangeGatewayClient exchange, SimulatorProperties properties) {
        this.control = control;
        this.exchange = exchange;
        this.properties = properties;
    }

    @GetMapping("/status")
    SimulatorControl.SimulatorStatus status() {
        return control.status();
    }

    @PostMapping("/baseline")
    SimulatorControl.SimulatorStatus baseline() {
        return control.baseline();
    }

    @PostMapping("/stop")
    SimulatorControl.SimulatorStatus stop() {
        return control.stop();
    }

    /** Puts the exchange into pre-open first; the rush lasts exactly as long as the pre-open window. */
    @PostMapping("/opening-rush")
    ResponseEntity<?> openingRush() {
        try {
            exchange.startOpeningAuction(properties.openingRushSeconds());
        } catch (RestClientException exception) {
            return exchangeUnavailable("opening auction", exception);
        }
        SimulatorControl.SimulatorStatus status = control.openingRush();
        LOG.info("Opening rush started: {} orders/s for {} s ({})",
                properties.openingRushPerSecond(), properties.openingRushSeconds(), status.demoScenarioId());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/sell-off")
    ResponseEntity<?> sellOff() {
        try {
            exchange.startSellOff(properties.sellOffSeconds());
        } catch (RestClientException exception) {
            return exchangeUnavailable("sell-off", exception);
        }
        SimulatorControl.SimulatorStatus status = control.sellOff();
        LOG.info("Sell-off started: +{} sell orders/s for {} s ({})",
                properties.sellOffPerSecond(), properties.sellOffSeconds(), status.demoScenarioId());
        return ResponseEntity.ok(status);
    }

    private ResponseEntity<ScenarioError> exchangeUnavailable(String operation, RestClientException exception) {
        LOG.warn("Exchange {} control failed; simulator mode unchanged: {}", operation, exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ScenarioError(
                EXCHANGE_UNAVAILABLE,
                "exchange-gateway-simulator " + operation + " call failed: " + exception.getMessage(),
                control.status()));
    }

    record ScenarioError(String code, String message, SimulatorControl.SimulatorStatus status) {
    }
}
