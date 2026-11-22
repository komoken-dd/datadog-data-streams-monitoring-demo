package io.github.komokendd.dsm.brokerage.pushgateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PushController {
    private final PushGatewayService service;

    PushController(PushGatewayService service) {
        this.service = service;
    }

    @PostMapping("/push")
    ResponseEntity<Object> push(@Valid @RequestBody PushBody body) throws InterruptedException {
        var outcome = service.push(new PushGatewayService.PushRequest(
                body.notificationId(), body.accountId(), body.orderId(), body.notificationType(), body.message()));
        if (outcome == PushGatewayService.Outcome.UNAVAILABLE) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorBody("PUSH_PROVIDER_UNAVAILABLE", "Push provider is temporarily unavailable"));
        }
        return ResponseEntity.ok(new PushAccepted(
                body.notificationId(), outcome == PushGatewayService.Outcome.DUPLICATE));
    }

    record PushBody(
            @NotBlank String notificationId,
            @NotBlank String accountId,
            @NotBlank String orderId,
            @NotBlank String notificationType,
            @NotBlank String message) {
    }

    record PushAccepted(String notificationId, boolean duplicate) {
    }

    record ErrorBody(String code, String message) {
    }
}
