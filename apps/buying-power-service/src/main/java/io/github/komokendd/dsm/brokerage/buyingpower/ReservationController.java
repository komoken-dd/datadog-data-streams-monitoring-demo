package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ReservationController {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservations;
    private final AccountQueryService accounts;
    private final MeterRegistry metrics;

    ReservationController(ReservationService reservations, AccountQueryService accounts, MeterRegistry metrics) {
        this.reservations = reservations;
        this.accounts = accounts;
        this.metrics = metrics;
    }

    @PostMapping(BuyingPowerReservation.RESERVATIONS_PATH)
    ResponseEntity<BuyingPowerReservation.Response> reserve(@RequestBody BuyingPowerReservation.Request request) {
        BuyingPowerReservation.Response response;
        try {
            response = reservations.reserve(request);
        } catch (DuplicateKeyException exception) {
            // Lost a race with a concurrent request for the same order; that request's reservation stands.
            response = reservations.existing(request.orderId());
        }
        boolean reserved = BuyingPowerReservation.RESERVED.equals(response.status());
        String result = reserved ? BuyingPowerReservation.RESERVED : response.reason();
        Counter.builder(MetricNames.BUYING_POWER_RESERVATIONS)
                .tag("side", request.side().trim().toUpperCase(java.util.Locale.ROOT))
                .tag("result", result)
                .register(metrics)
                .increment();
        try (var ignored = EventLogContext.open(
                        request.accountId(), request.orderId(), null, "BUYING_POWER_RESERVATION", request.symbol(), null)
                .attribute("result", result)) {
            LOG.info("Buying-power reservation {}, reserved_amount_jpy={}, buying_power_jpy={}",
                    result, response.reservedAmountJpy(), response.buyingPowerJpy());
        }
        return ResponseEntity.status(reserved ? 200 : 422).body(response);
    }

    @GetMapping(BuyingPowerReservation.RESERVATIONS_PATH + "/{orderId}")
    ResponseEntity<BuyingPowerReservation.Response> reservation(@PathVariable("orderId") String orderId) {
        return ResponseEntity.of(reservations.find(orderId));
    }

    @PostMapping(BuyingPowerReservation.RESERVATIONS_PATH + "/{orderId}/release")
    ReservationService.ReleaseResponse release(@PathVariable("orderId") String orderId) {
        var response = reservations.release(orderId);
        try (var ignored = EventLogContext.open(null, orderId, null, "BUYING_POWER_RELEASE", null, null)) {
            LOG.info("Reservation released by compensation, status={}, released_amount_jpy={}, released_quantity={}",
                    response.status(), response.releasedAmountJpy(), response.releasedQuantity());
        }
        return response;
    }

    @GetMapping("/internal/buying-power/accounts/{accountId}")
    ResponseEntity<AccountQueryService.AccountView> account(@PathVariable("accountId") String accountId) {
        return ResponseEntity.of(accounts.find(accountId));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_RESERVATION", exception.getMessage()));
    }

    record ErrorBody(String code, String message) {
    }
}
